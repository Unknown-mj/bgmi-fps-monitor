//! Per-session files: `<name>.csv` with one line per reading, and
//! `<name>.summary` (key=value lines) that is rewritten every few seconds,
//! so a summary survives even if the app is killed mid-match.

use crate::{games, Monitor, Reading};
use std::fs::{self, File};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

const HEADER: &str = "elapsed_s,status,fps,smooth_fps,target_fps,frames,skipped,stutters,long_pauses,\
                      heat_status,battery_c,cpu_c,gpu_c,skin_c";
/// Rewrite the summary every this many readings (10 × 0.5 s = 5 s).
const SUMMARY_EVERY: u32 = 10;

pub struct SessionLog {
    csv: BufWriter<File>,
    summary_path: PathBuf,
    start: Option<f64>,
    lines: u32,
}

impl SessionLog {
    pub fn create(csv_path: &Path) -> io::Result<Self> {
        let mut csv = BufWriter::new(File::create(csv_path)?);
        writeln!(csv, "{HEADER}")?;
        Ok(SessionLog {
            csv,
            summary_path: csv_path.with_extension("summary"),
            start: None,
            lines: 0,
        })
    }

    pub fn record(&mut self, now_secs: f64, reading: &Reading, monitor: &Monitor) -> io::Result<()> {
        let elapsed = now_secs - *self.start.get_or_insert(now_secs);
        writeln!(self.csv, "{}", csv_line(elapsed, reading, monitor))?;
        // Flush every line: if the app is killed, the log is still complete.
        self.csv.flush()?;
        self.lines += 1;
        if self.lines % SUMMARY_EVERY == 0 {
            self.write_summary(monitor)?;
        }
        Ok(())
    }

    pub fn write_summary(&self, monitor: &Monitor) -> io::Result<()> {
        fs::write(&self.summary_path, summary_text(monitor))
    }
}

/// `Some(x)` formatted with `decimals`, `None` as an empty field.
fn opt(value: Option<f64>, decimals: usize) -> String {
    value.map_or(String::new(), |v| format!("{v:.decimals$}"))
}

pub fn csv_line(elapsed: f64, reading: &Reading, monitor: &Monitor) -> String {
    let frames = match reading {
        Reading::NoGame => "no_game,,,,,,,".to_string(),
        Reading::First => "first,,,,,,,".to_string(),
        Reading::Window(w) => format!(
            "{},{:.1},{},{},{},{},{},{}",
            if w.reset { "reset" } else { "ok" },
            w.fps,
            opt(monitor.smoothed_fps(), 1),
            opt(w.target_fps, 0),
            w.frames,
            w.skipped,
            w.stutters,
            w.long_pauses,
        ),
    };
    // Latest heat reading (taken every few seconds), repeated on each line.
    let t = monitor.thermal.unwrap_or_default();
    format!(
        "{elapsed:.3},{frames},{},{},{},{},{}",
        t.status.map_or(String::new(), |s| s.to_string()),
        opt(t.battery_c, 1),
        opt(t.cpu_c, 1),
        opt(t.gpu_c, 1),
        opt(t.skin_c, 1),
    )
}

pub fn summary_text(monitor: &Monitor) -> String {
    let s = &monitor.session;
    let p = &monitor.thermal_peaks;
    let per_min = if s.seconds > 0.0 { s.stutters as f64 * 60.0 / s.seconds } else { 0.0 };
    format!(
        "game={}\ngameplay_seconds={:.1}\navg_fps={:.1}\ntarget_fps={}\nworst_1s_fps={}\nframes={}\nskipped={}\n\
         stutters={}\nstutters_per_min={:.1}\nlong_pauses={}\n\
         start_battery_c={}\nmax_battery_c={}\nmax_heat_status={}\nmax_cpu_c={}\nmax_gpu_c={}\nmax_skin_c={}\n",
        monitor.game().map_or("", |p| games::name_of(p).unwrap_or(p)),
        s.seconds,
        s.fps(),
        opt(monitor.target_fps, 0),
        opt(monitor.worst_fps, 1),
        s.frames,
        s.skipped,
        s.stutters,
        per_min,
        s.long_pauses,
        opt(p.start_battery_c, 1),
        opt(p.battery_c, 1),
        p.status.map_or(String::new(), |s| s.to_string()),
        opt(p.cpu_c, 1),
        opt(p.gpu_c, 1),
        opt(p.skin_c, 1),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Monitor;

    fn dump(total: u64, b33: u64) -> String {
        format!(
            "layerName = a SurfaceView[com.pubg.imobile/x](BLAST)\ntotalFrames = {total}\n\
             present2present histogram is as below:\n8ms={total} 33ms={b33}\n"
        )
    }

    const HEAT: &str = "Thermal Status: 1\nCurrent temperatures from HAL:\n\
                        \tTemperature{mValue=51.3, mType=0, mName=CPU1, mStatus=0}\n\
                        \tTemperature{mValue=44.6, mType=1, mName=GPU0, mStatus=0}\n\
                        \tTemperature{mValue=38.9, mType=3, mName=skin, mStatus=0}\n  temperature: 367\n";

    #[test]
    fn writes_csv_and_summary() {
        let dir = std::env::temp_dir().join(format!("fps-core-log-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let csv_path = dir.join("session.csv");

        let mut m = Monitor::new("com.pubg.imobile");
        let mut log = SessionLog::create(&csv_path).unwrap();
        for (i, (total, b33)) in [(0, 0), (60, 0), (120, 1)].into_iter().enumerate() {
            let now = 10.0 + i as f64 * 0.5;
            if i == 1 {
                m.feed_thermal(HEAT);
            }
            let r = m.feed(&dump(total, b33), now);
            log.record(now, &r, &m).unwrap();
        }
        log.write_summary(&m).unwrap();

        let csv = fs::read_to_string(&csv_path).unwrap();
        let lines: Vec<&str> = csv.lines().collect();
        assert_eq!(lines[0], HEADER);
        // No heat reading yet on the first line.
        assert_eq!(lines[1], "0.000,first,,,,,,,,,,,,");
        // Before a full second is known, the window's own rate gives the target.
        assert_eq!(lines[3], "1.000,ok,120.0,120.0,120,60,0,1,0,1,36.7,51.3,44.6,38.9");
        assert!(summary_text(&m).contains("target_fps=120\n"));

        let summary = fs::read_to_string(dir.join("session.summary")).unwrap();
        assert!(summary.contains("avg_fps=120.0\n"));
        assert!(summary.starts_with("game=BGMI\n"));
        assert!(summary.contains("worst_1s_fps=120.0\n"));
        assert!(summary.contains("stutters=1\n"));
        assert!(summary.contains("stutters_per_min=60.0\n"));
        assert!(summary.contains("start_battery_c=36.7\n"));
        assert!(summary.contains("max_battery_c=36.7\n"));
        assert!(summary.contains("max_heat_status=1\n"));
        assert!(summary.contains("max_gpu_c=44.6\n"));
        fs::remove_dir_all(&dir).unwrap();
    }
}
