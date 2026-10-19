//! Turns a session's `.csv` log (see `log.rs`) into a short series for the
//! match graph. Long matches have thousands of rows; the graph needs a few
//! hundred points. Each point keeps the lowest and highest FPS of its slice,
//! so a one-second drop still shows instead of being averaged away.

/// One slice of the match.
#[derive(Debug, Clone, PartialEq)]
pub struct Point {
    /// Seconds since the session started (start of the slice).
    pub t: f64,
    pub fps_min: f64,
    pub fps_avg: f64,
    pub fps_max: f64,
    pub stutters: u64,
    /// Last known battery temperature in the slice.
    pub battery_c: Option<f64>,
    /// Hottest CPU reading in the slice.
    pub cpu_c: Option<f64>,
    /// Hottest GPU reading in the slice.
    pub gpu_c: Option<f64>,
}

struct Row {
    t: f64,
    fps: f64,
    stutters: u64,
    battery_c: Option<f64>,
    cpu_c: Option<f64>,
    gpu_c: Option<f64>,
}

fn hottest(a: Option<f64>, b: Option<f64>) -> Option<f64> {
    match (a, b) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (a, b) => a.or(b),
    }
}

/// Reads columns by header name, so older logs without heat columns still work.
fn parse_rows(csv: &str) -> Vec<Row> {
    let mut lines = csv.lines();
    let Some(header) = lines.next() else { return Vec::new() };
    let cols: Vec<&str> = header.trim().split(',').collect();
    let col = |name: &str| cols.iter().position(|c| *c == name);
    let (Some(t_i), Some(status_i), Some(fps_i)) = (col("elapsed_s"), col("status"), col("fps")) else {
        return Vec::new();
    };
    let (stutters_i, battery_i, cpu_i, gpu_i) = (col("stutters"), col("battery_c"), col("cpu_c"), col("gpu_c"));

    lines
        .filter_map(|line| {
            let f: Vec<&str> = line.trim().split(',').collect();
            if f.get(status_i) != Some(&"ok") {
                return None;
            }
            let num = |i: Option<usize>| i.and_then(|i| f.get(i)).and_then(|v| v.parse::<f64>().ok());
            Some(Row {
                t: num(Some(t_i))?,
                fps: num(Some(fps_i))?,
                stutters: stutters_i.and_then(|i| f.get(i)).and_then(|v| v.parse().ok()).unwrap_or(0),
                battery_c: num(battery_i),
                cpu_c: num(cpu_i),
                gpu_c: num(gpu_i),
            })
        })
        .collect()
}

/// At most `max_points` points covering the whole session. Empty if the log
/// has no measured windows.
pub fn session_series(csv: &str, max_points: usize) -> Vec<Point> {
    let rows = parse_rows(csv);
    let (Some(first), Some(last)) = (rows.first(), rows.last()) else { return Vec::new() };
    let max_points = max_points.max(1);
    let (t0, span) = (first.t, (last.t - first.t).max(f64::EPSILON));

    let mut points: Vec<Point> = Vec::new();
    let mut sums: Vec<(f64, usize)> = Vec::new();
    let mut current: Option<usize> = None;
    for row in &rows {
        let bucket = if rows.len() <= max_points {
            points.len() // one point per row
        } else {
            (((row.t - t0) / span) * max_points as f64).floor().min((max_points - 1) as f64) as usize
        };
        if current != Some(bucket) {
            current = Some(bucket);
            points.push(Point {
                t: row.t,
                fps_min: row.fps,
                fps_avg: 0.0,
                fps_max: row.fps,
                stutters: 0,
                battery_c: None,
                cpu_c: None,
                gpu_c: None,
            });
            sums.push((0.0, 0));
        }
        let p = points.last_mut().unwrap();
        let s = sums.last_mut().unwrap();
        p.fps_min = p.fps_min.min(row.fps);
        p.fps_max = p.fps_max.max(row.fps);
        p.stutters += row.stutters;
        p.battery_c = row.battery_c.or(p.battery_c);
        p.cpu_c = hottest(p.cpu_c, row.cpu_c);
        p.gpu_c = hottest(p.gpu_c, row.gpu_c);
        s.0 += row.fps;
        s.1 += 1;
    }
    for (p, (sum, n)) in points.iter_mut().zip(sums) {
        p.fps_avg = sum / n as f64;
    }
    points
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEADER: &str = "elapsed_s,status,fps,smooth_fps,target_fps,frames,skipped,stutters,long_pauses,\
                          heat_status,battery_c,cpu_c,gpu_c,skin_c";

    fn csv(rows: impl Iterator<Item = String>) -> String {
        std::iter::once(HEADER.to_string()).chain(rows).collect::<Vec<_>>().join("\n")
    }

    fn ok(t: f64, fps: f64, stutters: u64, batt: &str, cpu: &str) -> String {
        format!("{t:.3},ok,{fps:.1},{fps:.1},120,60,0,{stutters},0,1,{batt},{cpu},,")
    }

    #[test]
    fn short_session_gives_one_point_per_reading() {
        let text = csv([ok(0.5, 118.0, 0, "36.0", "50.0"), ok(1.0, 120.0, 1, "", "")].into_iter());
        let s = session_series(&text, 200);
        assert_eq!(s.len(), 2);
        assert_eq!(s[1].fps_avg, 120.0);
        assert_eq!(s[1].stutters, 1);
        assert_eq!(s[0].battery_c, Some(36.0));
        assert_eq!(s[1].battery_c, None);
    }

    #[test]
    fn long_session_keeps_a_one_second_dip() {
        // 20 minutes at 0.5 s = 2400 readings, one dip to 40 FPS.
        let rows = (1..=2400).map(|i| {
            let fps = if i == 1234 { 40.0 } else { 120.0 };
            ok(i as f64 * 0.5, fps, u64::from(i == 1234), "38.0", "55.0")
        });
        let s = session_series(&csv(rows), 200);
        assert_eq!(s.len(), 200);
        assert_eq!(s.iter().map(|p| p.fps_min).fold(f64::MAX, f64::min), 40.0);
        assert_eq!(s.iter().filter(|p| p.fps_min < 100.0).count(), 1);
        assert_eq!(s.iter().map(|p| p.stutters).sum::<u64>(), 1);
        assert!(s.iter().all(|p| p.fps_max == 120.0));
    }

    #[test]
    fn skips_non_ok_rows_and_garbage() {
        let text = format!(
            "{HEADER}\n0.000,first,,,,,,,,,,,,\n0.5,no_game,,,,,,,,,,,,\nnot,a,row\n{}\n1.5,reset,10.0,,,,,,,,,,,",
            ok(1.0, 90.0, 0, "", "")
        );
        let s = session_series(&text, 200);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].fps_avg, 90.0);
    }

    #[test]
    fn older_logs_without_heat_columns_still_work() {
        let text = "elapsed_s,status,fps,smooth_fps,target_fps,frames,skipped,stutters,long_pauses\n\
                    0.5,ok,117.0,117.0,120,59,0,2,0";
        let s = session_series(text, 200);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].stutters, 2);
        assert_eq!(s[0].cpu_c, None);
    }

    #[test]
    fn gpu_keeps_the_hottest_reading_in_each_slice() {
        let row = |t: f64, gpu: &str| format!("{t:.3},ok,120.0,120.0,120,60,0,0,0,1,38.0,60.0,{gpu},");
        // 400 readings shrunk to 2 slices: the second half has one hot GPU reading.
        let rows = (1..=400).map(|i| row(i as f64 * 0.5, if i == 300 { "82.0" } else { "60.0" }));
        let s = session_series(&csv(rows), 2);
        assert_eq!(s.len(), 2);
        assert_eq!(s[0].gpu_c, Some(60.0));
        assert_eq!(s[1].gpu_c, Some(82.0));
    }

    #[test]
    fn logs_from_before_gpu_logging_have_no_gpu() {
        let text = "elapsed_s,status,fps,smooth_fps,target_fps,frames,skipped,stutters,long_pauses,\
                    heat_status,battery_c,cpu_c,skin_c\n\
                    0.5,ok,117.0,117.0,120,59,0,0,0,1,37.0,55.0,40.0";
        let s = session_series(text, 200);
        assert_eq!(s[0].cpu_c, Some(55.0));
        assert_eq!(s[0].gpu_c, None);
    }

    #[test]
    fn empty_or_headerless_input_gives_nothing() {
        assert!(session_series("", 200).is_empty());
        assert!(session_series(HEADER, 200).is_empty());
        assert!(session_series("just,some,text\n1,2,3", 200).is_empty());
    }
}
