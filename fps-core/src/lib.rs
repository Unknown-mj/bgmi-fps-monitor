//! Parses `dumpsys SurfaceFlinger --timestats -dump` output and turns two
//! readings into live FPS and stutter counts. Pure text in, numbers out:
//! no Android, no device access, so it can be tested on a PC.
//!
//! Layout of one layer block in the dump (verified on an Android 16 phone):
//!
//! ```text
//! displayRefreshRate = 120 fps      <- these three come BEFORE layerName
//! renderRate = 120 fps
//! uid = 10123
//! layerName = 73fbc89 SurfaceView[com.pubg.imobile/...](BLAST) ...#473
//! totalFrames = 157293
//! droppedFrames = 0
//! ...
//! present2present histogram is as below:
//! 0ms=0 1ms=0 ... 8ms=142411 ... 1000ms=14
//! ```
//!
//! The same layer appears once per (refresh rate, render rate) pair, so a
//! game's numbers are the SUM of all its blocks.

use std::collections::{BTreeMap, VecDeque};

pub mod log;
pub mod series;
pub mod thermal;

use thermal::{parse_thermal, Thermal, ThermalPeaks};

/// The smoothed live FPS covers about this many seconds of recent windows.
pub const SMOOTH_SECS: f64 = 1.0;
/// Points kept for the live FPS graph (120 × 0.5 s = the last minute).
pub const TRAIL_LEN: usize = 120;
/// The target frame rate is the highest common cap the game reached, as a
/// 1-second average, within this many seconds.
pub const TARGET_SECS: f64 = 10.0;
/// Frame-rate caps games commonly use; the target snaps to the nearest one.
pub const COMMON_CAPS: [f64; 9] = [30.0, 40.0, 45.0, 60.0, 72.0, 90.0, 120.0, 144.0, 165.0];
/// Gaps at or above this are counted as a long pause (menus, loading,
/// app in background) instead of a stutter. The last bucket is "1000ms",
/// which holds every gap of 1 s or more.
pub const LONG_PAUSE_MS: u32 = 200;
/// A window longer than this means measuring was interrupted (app frozen or
/// killed in the background), not a real reading: it is treated as a reset,
/// so the gap never gets merged into the session.
pub const MAX_WINDOW_SECS: f64 = 5.0;
/// Used when a block doesn't say its display refresh rate.
const DEFAULT_REFRESH_HZ: f64 = 120.0;

/// One layer block from the dump.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct LayerBlock {
    pub layer_name: String,
    pub uid: Option<u32>,
    pub display_refresh_rate: Option<u32>,
    pub render_rate: Option<u32>,
    pub total_frames: u64,
    pub dropped_frames: u64,
    /// `present2present` histogram as (bucket start in ms, count).
    pub present2present: Vec<(u32, u64)>,
}

/// Identifies one block across dumps: (layer name, display rate, render rate).
pub type BlockKey = (String, Option<u32>, Option<u32>);

/// One block's counters, kept separately because each block has its own
/// display refresh rate, which decides how its gaps are judged.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BlockStats {
    pub display_refresh_rate: Option<u32>,
    pub total_frames: u64,
    pub present2present: BTreeMap<u32, u64>,
}

/// All of one game's blocks, at one moment in time.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct GameSnapshot {
    pub blocks: usize,
    pub uid: Option<u32>,
    pub total_frames: u64,
    pub dropped_frames: u64,
    /// All blocks' histograms added together (for display).
    pub present2present: BTreeMap<u32, u64>,
    pub per_block: BTreeMap<BlockKey, BlockStats>,
}

/// What happened between two snapshots.
#[derive(Debug, Clone, PartialEq)]
pub struct Window {
    pub seconds: f64,
    pub frames: u64,
    pub fps: f64,
    /// Frame rate the gaps were judged against; None = display refresh rate.
    pub target_fps: Option<f64>,
    /// Gaps one screen refresh longer than normal at the target rate.
    pub skipped: u64,
    /// Gaps two or more refreshes longer than normal, under LONG_PAUSE_MS.
    pub stutters: u64,
    /// Gaps >= LONG_PAUSE_MS.
    pub long_pauses: u64,
    /// True if a counter went down (stats cleared, or game restarted).
    /// The numbers for this window are then not trustworthy.
    pub reset: bool,
}

/// Parse every layer block in a timestats dump.
pub fn parse_dump(text: &str) -> Vec<LayerBlock> {
    let mut blocks: Vec<LayerBlock> = Vec::new();
    // Fields seen before the next layerName line.
    let mut pending_refresh = None;
    let mut pending_render = None;
    let mut pending_uid = None;
    // Index into `blocks` of the layer we are inside, if any.
    let mut current: Option<usize> = None;
    // Set when the previous line was "present2present histogram is as below:".
    let mut want_p2p = false;

    for raw in text.lines() {
        let line = raw.trim_start_matches('\u{feff}').trim();

        if want_p2p {
            if line.is_empty() {
                continue;
            }
            want_p2p = false;
            if let Some(i) = current {
                blocks[i].present2present = parse_histogram(line);
            }
            continue;
        }

        if line.ends_with("histogram is as below:") {
            // Exact name: the global section has "presentToPresent", and
            // layers also have "present2presentDelta", which we don't want.
            want_p2p = current.is_some() && line.starts_with("present2present histogram");
            continue;
        }

        let Some((key, value)) = line.split_once(" = ") else {
            continue;
        };
        match key {
            "displayRefreshRate" => pending_refresh = parse_fps(value),
            "renderRate" => pending_render = parse_fps(value),
            "uid" => pending_uid = value.parse().ok(),
            "layerName" => {
                blocks.push(LayerBlock {
                    layer_name: value.to_string(),
                    uid: pending_uid.take(),
                    display_refresh_rate: pending_refresh.take(),
                    render_rate: pending_render.take(),
                    ..Default::default()
                });
                current = Some(blocks.len() - 1);
            }
            "totalFrames" => {
                if let Some(i) = current {
                    blocks[i].total_frames = value.parse().unwrap_or(0);
                }
            }
            "droppedFrames" => {
                if let Some(i) = current {
                    blocks[i].dropped_frames = value.parse().unwrap_or(0);
                }
            }
            _ => {}
        }
    }
    blocks
}

/// "120 fps" -> 120
fn parse_fps(value: &str) -> Option<u32> {
    value.split_whitespace().next()?.parse().ok()
}

/// "0ms=0 1ms=3 8ms=142411" -> [(0,0), (1,3), (8,142411)]
fn parse_histogram(line: &str) -> Vec<(u32, u64)> {
    line.split_whitespace()
        .filter_map(|tok| {
            let (bucket, count) = tok.split_once('=')?;
            let ms = bucket.strip_suffix("ms")?.parse().ok()?;
            Some((ms, count.parse().ok()?))
        })
        .collect()
}

/// Is this the game's own drawing surface? Match by package name, never by
/// the full layer name: the hex prefix and `#NNN` suffix change each session.
pub fn is_game_layer(layer_name: &str, package: &str) -> bool {
    layer_name.contains("SurfaceView[") && layer_name.contains(&format!("[{package}/"))
}

/// Collect every block that belongs to `package`'s game surface.
/// Returns None if the game has no blocks in this dump.
pub fn game_snapshot(blocks: &[LayerBlock], package: &str) -> Option<GameSnapshot> {
    let mut snap = GameSnapshot::default();
    for b in blocks.iter().filter(|b| is_game_layer(&b.layer_name, package)) {
        snap.blocks += 1;
        snap.uid = snap.uid.or(b.uid);
        snap.total_frames += b.total_frames;
        snap.dropped_frames += b.dropped_frames;
        let key = (b.layer_name.clone(), b.display_refresh_rate, b.render_rate);
        let stats = snap.per_block.entry(key).or_default();
        stats.display_refresh_rate = b.display_refresh_rate;
        stats.total_frames += b.total_frames;
        for &(ms, count) in &b.present2present {
            *snap.present2present.entry(ms).or_insert(0) += count;
            *stats.present2present.entry(ms).or_insert(0) += count;
        }
    }
    (snap.blocks > 0).then_some(snap)
}

/// How one gap between two shown frames is judged.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Gap {
    Normal,
    Skipped,
    Stutter,
    LongPause,
}

/// Judge a gap by how many screen refreshes it lasted, compared with how
/// many one frame normally lasts at the target rate. At 120 FPS on 120 Hz
/// that is 1; at 90 FPS on 120 Hz frames alternate 1 and 2, so 2 is normal;
/// at 60 FPS it is 2. One refresh more than normal is a skipped frame, two
/// or more is a stutter.
pub fn classify_gap(bucket_ms: u32, refresh_hz: f64, target_fps: Option<f64>) -> Gap {
    if bucket_ms >= LONG_PAUSE_MS {
        return Gap::LongPause;
    }
    let vsync_ms = 1000.0 / refresh_hz;
    let refreshes = ((bucket_ms as f64 / vsync_ms).round() as u32).max(1);
    let target = target_fps.unwrap_or(refresh_hz).min(refresh_hz);
    let normal = ((refresh_hz / target) - 0.05).ceil().max(1.0) as u32;
    if refreshes <= normal {
        Gap::Normal
    } else if refreshes == normal + 1 {
        Gap::Skipped
    } else {
        Gap::Stutter
    }
}

/// Nearest common frame-rate cap to `fps`.
pub fn snap_to_cap(fps: f64) -> f64 {
    let distance = |cap: f64| (cap / fps).ln().abs();
    COMMON_CAPS.into_iter().fold(COMMON_CAPS[0], |best, cap| if distance(cap) < distance(best) { cap } else { best })
}

/// Compare two snapshots taken `seconds` apart (use a monotonic clock).
/// Gaps are judged against `target_fps` (None = each block's refresh rate).
pub fn compare(prev: &GameSnapshot, now: &GameSnapshot, seconds: f64, target_fps: Option<f64>) -> Window {
    let mut reset = now.total_frames < prev.total_frames
        // A block that vanished also means counters went backwards.
        || prev.per_block.keys().any(|k| !now.per_block.contains_key(k));
    let frames = now.total_frames.saturating_sub(prev.total_frames);

    let mut skipped = 0;
    let mut stutters = 0;
    let mut long_pauses = 0;
    let empty = BlockStats::default();
    for (key, block) in &now.per_block {
        let before = prev.per_block.get(key).unwrap_or(&empty);
        let refresh = block.display_refresh_rate.map_or(DEFAULT_REFRESH_HZ, f64::from);
        for (&ms, &count) in &block.present2present {
            let was = before.present2present.get(&ms).copied().unwrap_or(0);
            if count < was {
                reset = true;
            }
            let added = count.saturating_sub(was);
            match classify_gap(ms, refresh, target_fps) {
                Gap::Normal => {}
                Gap::Skipped => skipped += added,
                Gap::Stutter => stutters += added,
                Gap::LongPause => long_pauses += added,
            }
        }
    }

    let fps = if seconds > 0.0 { frames as f64 / seconds } else { 0.0 };
    Window { seconds, frames, fps, target_fps, skipped, stutters, long_pauses, reset }
}

/// Running totals over a monitoring session. Only windows where the game
/// actually drew frames count, so time with BGMI in the background doesn't
/// drag the average down.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Session {
    pub seconds: f64,
    pub frames: u64,
    pub skipped: u64,
    pub stutters: u64,
    pub long_pauses: u64,
}

impl Session {
    pub fn fps(&self) -> f64 {
        if self.seconds > 0.0 { self.frames as f64 / self.seconds } else { 0.0 }
    }

    fn add(&mut self, w: &Window) {
        self.seconds += w.seconds;
        self.frames += w.frames;
        self.skipped += w.skipped;
        self.stutters += w.stutters;
        self.long_pauses += w.long_pauses;
    }
}

/// Result of feeding one dump to a [`Monitor`].
#[derive(Debug, Clone, PartialEq)]
pub enum Reading {
    /// The game has no layer in this dump (not running yet, or stats cleared).
    NoGame,
    /// First reading of the game: nothing to compare against yet.
    First,
    /// Change since the previous reading.
    Window(Window),
}

/// Keeps the previous snapshot so each new dump gives one [`Window`].
pub struct Monitor {
    package: String,
    prev: Option<(GameSnapshot, f64)>,
    /// Latest windows covering about SMOOTH_SECS, for the smoothed FPS.
    recent: VecDeque<Window>,
    /// (time, full-second FPS) over the last TARGET_SECS, for the target.
    history: VecDeque<(f64, f64)>,
    /// Frame rate the game is aiming for; None until a full second is seen.
    pub target_fps: Option<f64>,
    pub session: Session,
    /// Lowest smoothed FPS seen while the game was drawing continuously.
    pub worst_fps: Option<f64>,
    /// Latest phone heat reading, if one has been fed.
    pub thermal: Option<Thermal>,
    pub thermal_peaks: ThermalPeaks,
    /// Smoothed FPS per window, newest last, for the live graph.
    trail: VecDeque<f64>,
}

impl Monitor {
    pub fn new(package: &str) -> Self {
        Monitor {
            package: package.to_string(),
            prev: None,
            recent: VecDeque::new(),
            history: VecDeque::new(),
            target_fps: None,
            session: Session::default(),
            worst_fps: None,
            thermal: None,
            thermal_peaks: ThermalPeaks::default(),
            trail: VecDeque::with_capacity(TRAIL_LEN),
        }
    }

    /// Smoothed FPS for the last TRAIL_LEN windows, oldest first. Windows
    /// with the game in the background show as 0.
    pub fn fps_trail(&self) -> Vec<f64> {
        self.trail.iter().copied().collect()
    }

    /// Feed the filtered `dumpsys thermalservice` + `dumpsys battery` text.
    /// Heat changes slowly, so this is called every few seconds, not every poll.
    pub fn feed_thermal(&mut self, text: &str) -> Thermal {
        let t = parse_thermal(text);
        self.thermal = Some(t);
        self.thermal_peaks.add(&t);
        t
    }

    /// `now_secs` must come from a monotonic clock.
    pub fn feed(&mut self, dump: &str, now_secs: f64) -> Reading {
        let Some(snap) = game_snapshot(&parse_dump(dump), &self.package) else {
            self.prev = None;
            self.recent.clear();
            self.history.clear();
            self.target_fps = None;
            return Reading::NoGame;
        };
        let reading = match &self.prev {
            Some((prev, then)) => {
                // Judged against the target known so far, from earlier windows.
                // Before there is one, use this window's own rate, so a game at
                // 90 FPS isn't judged as a 120 FPS game in its first second.
                let secs = now_secs - then;
                let frames = snap.total_frames.saturating_sub(prev.total_frames);
                let target = self.target_fps.or_else(|| {
                    (secs > 0.0 && frames > 0).then(|| snap_to_cap(frames as f64 / secs))
                });
                let mut w = compare(prev, &snap, secs, target);
                if secs > MAX_WINDOW_SECS {
                    w.reset = true;
                }
                self.track(&w, now_secs);
                Reading::Window(w)
            }
            None => Reading::First,
        };
        self.prev = Some((snap, now_secs));
        reading
    }

    /// FPS over the last ~SMOOTH_SECS. Steadier than one 0.5 s window,
    /// where a single frame landing either side of a reading shows as ±2 FPS.
    pub fn smoothed_fps(&self) -> Option<f64> {
        let secs: f64 = self.recent.iter().map(|w| w.seconds).sum();
        let frames: u64 = self.recent.iter().map(|w| w.frames).sum();
        (secs > 0.0).then(|| frames as f64 / secs)
    }

    fn track(&mut self, w: &Window, now_secs: f64) {
        if w.reset {
            self.recent.clear();
            return;
        }
        if w.frames > 0 {
            self.session.add(w);
        }
        self.recent.push_back(w.clone());
        let mut secs: f64 = self.recent.iter().map(|w| w.seconds).sum();
        while self.recent.len() > 1 && secs - self.recent[0].seconds >= SMOOTH_SECS - 1e-9 {
            secs -= self.recent.pop_front().unwrap().seconds;
        }
        // Only a full second where the game drew in every window counts, so
        // menus and the game being in the background don't count.
        if self.trail.len() == TRAIL_LEN {
            self.trail.pop_front();
        }
        self.trail.push_back(self.smoothed_fps().unwrap_or(w.fps));

        let full = secs >= SMOOTH_SECS * 0.9 && self.recent.iter().all(|w| w.frames > 0);
        let Some(fps) = self.smoothed_fps().filter(|_| full) else { return };

        self.worst_fps = Some(self.worst_fps.map_or(fps, |worst| worst.min(fps)));

        self.history.push_back((now_secs, fps));
        while self.history.front().is_some_and(|&(t, _)| now_secs - t > TARGET_SECS) {
            self.history.pop_front();
        }
        let best = self.history.iter().map(|&(_, f)| f).fold(0.0, f64::max);
        self.target_fps = Some(snap_to_cap(best));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BGMI: &str = "com.pubg.imobile";

    /// Trimmed copy of the real dump's shape: global section, one BGMI block
    /// per refresh rate, plus an unrelated app's SurfaceView.
    const SAMPLE: &str = "\u{feff}SurfaceFlinger TimeStats:
Legacy stats are as follows:
totalFrames = 174059
presentToPresent histogram is as below:
0ms=0 8ms=999999
displayRefreshRate = 120 fps
renderRate = 120 fps
uid = 10123
layerName = 73fbc89 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) 01-01 12:00:00.000#473
packageName =
totalFrames = 157293
droppedFrames = 0
averageFPS = 111.879
present2present histogram is as below:
0ms=0 8ms=142411 16ms=14192 24ms=395 33ms=160 200ms=4 1000ms=14
present2presentDelta histogram is as below:
0ms=128401 8ms=27930 24ms=0 25ms=62

displayRefreshRate = 90 fps
renderRate = 90 fps
uid = 10123
layerName = 73fbc89 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) 01-01 12:00:00.000#473
totalFrames = 1692
droppedFrames = 2
present2present histogram is as below:
8ms=1 11ms=1685 22ms=5 24ms=1

displayRefreshRate = 120 fps
renderRate = 120 fps
uid = 10456
layerName = f470c28 SurfaceView[com.example.otherapp/x.MainActivity](BLAST) 01-01 12:05:00.000#672
totalFrames = 500
present2present histogram is as below:
8ms=500 24ms=77
";

    /// One BGMI block as the app receives it after the on-phone awk filter.
    fn block(refresh: u32, total: u64, buckets: &[(u32, u64)]) -> String {
        let hist: Vec<String> = buckets.iter().map(|(ms, c)| format!("{ms}ms={c}")).collect();
        format!(
            "displayRefreshRate = {refresh} fps\nrenderRate = {refresh} fps\nuid = 10123\n\
             layerName = 1a2b3c SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) 01-01 12:10:00.000#9\n\
             totalFrames = {total}\ndroppedFrames = 0\n\
             present2present histogram is as below:\n{}\n",
            hist.join(" ")
        )
    }

    fn filtered(total: u64, b8: u64, b33: u64) -> String {
        block(120, total, &[(8, b8), (16, 0), (33, b33), (1000, 0)])
    }

    #[test]
    fn reads_rates_and_uid_from_lines_before_layer_name() {
        let blocks = parse_dump(SAMPLE);
        assert_eq!(blocks.len(), 3);
        assert_eq!(blocks[0].uid, Some(10123));
        assert_eq!(blocks[0].display_refresh_rate, Some(120));
        assert_eq!(blocks[1].render_rate, Some(90));
        assert_eq!(blocks[2].uid, Some(10456));
    }

    #[test]
    fn ignores_global_section_and_delta_histogram() {
        let blocks = parse_dump(SAMPLE);
        assert_eq!(blocks[0].total_frames, 157293);
        // present2presentDelta (25ms=62) must not leak into present2present.
        assert!(!blocks[0].present2present.iter().any(|&(ms, _)| ms == 25));
        assert_eq!(blocks[0].present2present[1], (8, 142411));
    }

    #[test]
    fn sums_all_blocks_of_the_game_only() {
        let snap = game_snapshot(&parse_dump(SAMPLE), BGMI).unwrap();
        assert_eq!(snap.blocks, 2);
        assert_eq!(snap.per_block.len(), 2);
        assert_eq!(snap.uid, Some(10123));
        assert_eq!(snap.total_frames, 157293 + 1692);
        assert_eq!(snap.dropped_frames, 2);
        assert_eq!(snap.present2present[&8], 142412);
        assert_eq!(snap.present2present[&24], 396); // the other app's 77 not included
    }

    #[test]
    fn missing_game_gives_none() {
        assert!(game_snapshot(&parse_dump(SAMPLE), "com.example.none").is_none());
    }

    #[test]
    fn classify_gap_by_refreshes_at_target() {
        use Gap::*;
        // 120 FPS on 120 Hz: 1 refresh normal, 2 skipped, 3+ stutter.
        assert_eq!(classify_gap(8, 120.0, Some(120.0)), Normal);
        assert_eq!(classify_gap(16, 120.0, Some(120.0)), Skipped);
        assert_eq!(classify_gap(24, 120.0, Some(120.0)), Stutter);
        // No target yet: same as the display rate.
        assert_eq!(classify_gap(16, 120.0, None), Skipped);
        // 90 FPS on 120 Hz: 8/16 ms alternate normally; 25 skipped; 33 stutter.
        assert_eq!(classify_gap(16, 120.0, Some(90.0)), Normal);
        assert_eq!(classify_gap(25, 120.0, Some(90.0)), Skipped);
        assert_eq!(classify_gap(33, 120.0, Some(90.0)), Stutter);
        // 90 FPS on a 90 Hz display: 11 normal, 22 skipped.
        assert_eq!(classify_gap(11, 90.0, Some(90.0)), Normal);
        assert_eq!(classify_gap(22, 90.0, Some(90.0)), Skipped);
        // 60 FPS on 120 Hz: 16 normal, 24/25 skipped.
        assert_eq!(classify_gap(16, 120.0, Some(60.0)), Normal);
        assert_eq!(classify_gap(24, 120.0, Some(60.0)), Skipped);
        // Long pauses regardless of target.
        assert_eq!(classify_gap(200, 120.0, Some(60.0)), LongPause);
        assert_eq!(classify_gap(1000, 120.0, Some(120.0)), LongPause);
    }

    #[test]
    fn snaps_to_nearest_cap() {
        assert_eq!(snap_to_cap(123.1), 120.0);
        assert_eq!(snap_to_cap(117.0), 120.0);
        assert_eq!(snap_to_cap(91.5), 90.0);
        assert_eq!(snap_to_cap(59.0), 60.0);
        assert_eq!(snap_to_cap(140.0), 144.0);
    }

    #[test]
    fn fps_and_gaps_between_two_readings() {
        let prev = game_snapshot(&parse_dump(&block(120, 1000, &[(8, 990), (16, 0), (33, 0), (1000, 0)])), BGMI).unwrap();
        let now = game_snapshot(&parse_dump(&block(120, 1056, &[(8, 1039), (16, 4), (33, 2), (1000, 1)])), BGMI).unwrap();

        let w = compare(&prev, &now, 0.5, Some(120.0));
        assert_eq!(w.frames, 56);
        assert!((w.fps - 112.0).abs() < 1e-9);
        assert_eq!(w.skipped, 4);
        assert_eq!(w.stutters, 2);
        assert_eq!(w.long_pauses, 1);
        assert!(!w.reset);
    }

    #[test]
    fn counter_going_down_is_flagged_as_reset() {
        let prev = game_snapshot(&parse_dump(SAMPLE), BGMI).unwrap();
        let now = GameSnapshot { total_frames: 10, ..prev.clone() };
        let w = compare(&prev, &now, 0.5, None);
        assert!(w.reset);
        assert_eq!(w.frames, 0);
    }

    #[test]
    fn monitor_tracks_windows_and_session() {
        let mut m = Monitor::new(BGMI);
        assert_eq!(m.feed("SurfaceFlinger TimeStats:\n", 0.0), Reading::NoGame);
        assert_eq!(m.feed(&filtered(1000, 990, 0), 1.0), Reading::First);

        let Reading::Window(w) = m.feed(&filtered(1060, 1049, 1), 1.5) else { panic!() };
        assert_eq!(w.frames, 60);
        assert!((w.fps - 120.0).abs() < 1e-9);
        assert_eq!(w.stutters, 1);

        // Game in background: no new frames, so session time doesn't grow.
        m.feed(&filtered(1060, 1049, 1), 2.0);
        m.feed(&filtered(1118, 1107, 1), 2.5);
        assert_eq!(m.session.frames, 118);
        assert!((m.session.seconds - 1.0).abs() < 1e-9);
        assert_eq!(m.session.stutters, 1);
    }

    #[test]
    fn monitor_skips_reset_windows_in_session() {
        let mut m = Monitor::new(BGMI);
        m.feed(&filtered(5000, 4990, 0), 0.0);
        let Reading::Window(w) = m.feed(&filtered(30, 29, 0), 0.5) else { panic!() };
        assert!(w.reset);
        assert_eq!(m.session, Session::default());
        // Next window counts normally again.
        m.feed(&filtered(90, 89, 0), 1.0);
        assert_eq!(m.session.frames, 60);
    }

    #[test]
    fn long_gap_is_a_reset_not_part_of_the_session() {
        let mut m = Monitor::new(BGMI);
        m.feed(&filtered(0, 0, 0), 0.0);
        m.feed(&filtered(60, 60, 0), 0.5);
        // App frozen for 90 s in the background, then one late reading.
        let Reading::Window(w) = m.feed(&filtered(10_860, 10_800, 28), 90.5) else { panic!() };
        assert!(w.reset);
        assert_eq!(m.session.frames, 60);
        assert_eq!(m.session.stutters, 0);
        // Measuring carries on normally from the new baseline.
        m.feed(&filtered(10_920, 10_860, 28), 91.0);
        assert_eq!(m.session.frames, 120);
    }

    #[test]
    fn fps_trail_keeps_the_last_minute() {
        let mut m = Monitor::new(BGMI);
        m.feed(&filtered(0, 0, 0), 0.0);
        for i in 1..=(TRAIL_LEN as u64 + 10) {
            m.feed(&filtered(i * 60, i * 60, 0), i as f64 * 0.5);
        }
        let trail = m.fps_trail();
        assert_eq!(trail.len(), TRAIL_LEN);
        assert!(trail.iter().all(|&f| (f - 120.0).abs() < 1e-9));
    }

    #[test]
    fn smoothed_fps_covers_last_second_and_tracks_worst() {
        let mut m = Monitor::new(BGMI);
        m.feed(&filtered(0, 0, 0), 0.0);
        m.feed(&filtered(62, 62, 0), 0.5); // 124 FPS window
        m.feed(&filtered(120, 120, 0), 1.0); // 116 FPS window
        assert!((m.smoothed_fps().unwrap() - 120.0).abs() < 1e-9);
        assert!((m.worst_fps.unwrap() - 120.0).abs() < 1e-9);

        m.feed(&filtered(170, 170, 0), 1.5); // 100 FPS window: oldest one drops out
        assert!((m.smoothed_fps().unwrap() - 108.0).abs() < 1e-9);
        assert!((m.worst_fps.unwrap() - 108.0).abs() < 1e-9);

        // Game in the background: no frames, worst FPS must not become 0.
        m.feed(&filtered(170, 170, 0), 2.0);
        m.feed(&filtered(170, 170, 0), 2.5);
        assert!((m.worst_fps.unwrap() - 108.0).abs() < 1e-9);
    }

    /// Feeds `secs` of a steady cadence on 120 Hz: every 0.5 s, `n8` frames
    /// 8 ms apart and `n16` frames 16 ms apart. Returns the last window.
    fn run_cadence(m: &mut Monitor, start: &mut (f64, u64, u64, u64), secs: f64, n8: u64, n16: u64) -> Window {
        let mut last = None;
        let steps = (secs / 0.5) as usize;
        for _ in 0..steps {
            let (t, total, b8, b16) = start;
            *t += 0.5;
            *total += n8 + n16;
            *b8 += n8;
            *b16 += n16;
            let dump = block(120, *total, &[(8, *b8), (16, *b16), (25, 0), (33, 0)]);
            if let Reading::Window(w) = m.feed(&dump, *t) {
                last = Some(w);
            }
        }
        last.unwrap()
    }

    #[test]
    fn ninety_fps_on_120hz_is_not_counted_as_skipping() {
        let mut m = Monitor::new(BGMI);
        let mut state = (0.0, 0, 0, 0);
        m.feed(&block(120, 0, &[(8, 0), (16, 0)]), 0.0);
        // 90 FPS = 45 frames per 0.5 s: 30 at 8 ms + 15 at 16 ms.
        let w = run_cadence(&mut m, &mut state, 5.0, 30, 15);
        assert_eq!(m.target_fps, Some(90.0));
        assert_eq!(w.skipped, 0);
        assert_eq!(w.stutters, 0);
    }

    #[test]
    fn first_window_at_90_fps_is_not_counted_as_skipping() {
        let mut m = Monitor::new(BGMI);
        m.feed(&block(120, 0, &[(8, 0), (16, 0)]), 0.0);
        // 1 s polling, 90 FPS: 60 frames at 8 ms + 30 at 16 ms.
        let Reading::Window(w) = m.feed(&block(120, 90, &[(8, 60), (16, 30)]), 1.0) else { panic!() };
        assert_eq!(w.target_fps, Some(90.0));
        assert_eq!(w.skipped, 0);
    }

    #[test]
    fn dropping_from_120_to_90_counts_skips() {
        let mut m = Monitor::new(BGMI);
        let mut state = (0.0, 0, 0, 0);
        m.feed(&block(120, 0, &[(8, 0), (16, 0)]), 0.0);
        run_cadence(&mut m, &mut state, 3.0, 60, 0); // steady 120 FPS
        assert_eq!(m.target_fps, Some(120.0));
        // Heavy fight: drops to 90 FPS. The target stays 120 (within 10 s),
        // so the 16 ms gaps are real skipped frames.
        let w = run_cadence(&mut m, &mut state, 2.0, 30, 15);
        assert_eq!(m.target_fps, Some(120.0));
        assert_eq!(w.skipped, 15);
    }

    #[test]
    fn real_dump_if_present() {
        // samples/ is not committed; skip quietly when it's missing.
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../samples/dump1.txt");
        let Ok(text) = std::fs::read_to_string(path) else { return };
        // Only checks the shape, so no values from a real phone live in the code.
        let snap = game_snapshot(&parse_dump(&text), BGMI).unwrap();
        assert!(snap.blocks >= 1);
        assert!(snap.uid.is_some());
        assert!(snap.total_frames > 0);
        assert!(!snap.present2present.is_empty());
    }
}
