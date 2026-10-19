//! JNI bridge for `dev.fpsmonitor.FpsNative`. Kotlin hands over the dump
//! text and a monotonic timestamp; Rust keeps the state, does the math and
//! writes the session log.

use fps_core::games::GAMES;
use fps_core::log::SessionLog;
use fps_core::series::session_series;
use fps_core::{Monitor, Reading};
use jni::objects::{JClass, JString};
use jni::sys::{jdoubleArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::path::Path;

// Status codes in slot 0 of the returned array. Keep in sync with FpsNative.kt.
const NO_GAME: f64 = 0.0;
const FIRST: f64 = 1.0;
const OK: f64 = 2.0;
const RESET: f64 = 3.0;
/// Stands in for "no value yet" in the returned array.
const NONE: f64 = -1.0;

struct State {
    monitor: Monitor,
    /// None if no log path was given or the file couldn't be created;
    /// monitoring still works without it.
    log: Option<SessionLog>,
}

/// `log_path` is the session's .csv file, or "" for no log.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_create<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    package: JString<'l>,
    log_path: JString<'l>,
) -> jlong {
    let Ok(package) = env.get_string(&package) else { return 0 };
    let package: String = package.into();
    let log_path: String = env.get_string(&log_path).map(Into::into).unwrap_or_default();
    let log = if log_path.is_empty() { None } else { SessionLog::create(Path::new(&log_path)).ok() };
    // "" = watch every known PUBG version and measure whichever is drawing.
    let monitor = if package.is_empty() { Monitor::for_known_games() } else { Monitor::new(&package) };
    Box::into_raw(Box::new(State { monitor, log })) as jlong
}

/// Returns [status, fps, frames, skipped, stutters, longPauses,
///          sessionFps, sessionSeconds, sessionFrames, sessionSkipped,
///          sessionStutters, sessionLongPauses, smoothedFps, worstFps,
///          logOk, targetFps, heatStatus, batteryC, cpuC, skinC,
///          maxHeatStatus, maxBatteryC, gpuC, gameIndex], or null on a JNI
/// error. gameIndex points into `knownGames()`.
/// Missing values are -1.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_feed<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dump: JString<'l>,
    now_nanos: jlong,
) -> jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: handle came from `create` and Kotlin only uses it on one thread
    // until `destroy`.
    let state = unsafe { &mut *(handle as *mut State) };
    let Ok(text) = env.get_string(&dump) else { return std::ptr::null_mut() };
    let text: String = text.into();

    let now = now_nanos as f64 / 1e9;
    let reading = state.monitor.feed(&text, now);
    let log_ok = match &mut state.log {
        Some(log) => {
            // A different game took over: close its summary, continue in a new file pair.
            let rotated = match state.monitor.take_finished() {
                Some(done) => log.start_part(&done).is_ok(),
                None => true,
            };
            rotated && log.record(now, &reading, &state.monitor).is_ok()
        }
        None => false,
    };

    let mut out = [0.0; 24];
    match &reading {
        Reading::NoGame => out[0] = NO_GAME,
        Reading::First => out[0] = FIRST,
        Reading::Window(w) => {
            out[0] = if w.reset { RESET } else { OK };
            out[1] = w.fps;
            out[2] = w.frames as f64;
            out[3] = w.skipped as f64;
            out[4] = w.stutters as f64;
            out[5] = w.long_pauses as f64;
        }
    }
    let m = &state.monitor;
    let s = &m.session;
    out[6] = s.fps();
    out[7] = s.seconds;
    out[8] = s.frames as f64;
    out[9] = s.skipped as f64;
    out[10] = s.stutters as f64;
    out[11] = s.long_pauses as f64;
    out[12] = m.smoothed_fps().unwrap_or(NONE);
    out[13] = m.worst_fps.unwrap_or(NONE);
    out[14] = if log_ok { 1.0 } else { 0.0 };
    out[15] = m.target_fps.unwrap_or(NONE);
    let t = m.thermal.unwrap_or_default();
    out[16] = t.status.map_or(NONE, f64::from);
    out[17] = t.battery_c.unwrap_or(NONE);
    out[18] = t.cpu_c.unwrap_or(NONE);
    out[19] = t.skin_c.unwrap_or(NONE);
    out[20] = m.thermal_peaks.status.map_or(NONE, f64::from);
    out[21] = m.thermal_peaks.battery_c.unwrap_or(NONE);
    out[22] = t.gpu_c.unwrap_or(NONE);
    out[23] = m.known_game_index().map_or(NONE, |i| i as f64);

    let Ok(array) = env.new_double_array(out.len() as i32) else { return std::ptr::null_mut() };
    if env.set_double_array_region(&array, 0, &out).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Feeds the filtered thermalservice + battery text. The values come back
/// in the next `feed` result and go into the session log.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_feedThermal<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: as in `feed`; called on the same thread.
    let state = unsafe { &mut *(handle as *mut State) };
    if let Ok(text) = env.get_string(&text) {
        let text: String = text.into();
        state.monitor.feed_thermal(&text);
    }
}

/// The PUBG versions the monitor recognises, one "package<TAB>name" per line,
/// in `gameIndex` order. The app builds its on-phone filter and names from it.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_knownGames<'l>(env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    let text: String = GAMES.iter().map(|g| format!("{}\t{}\n", g.package, g.name)).collect();
    env.new_string(text).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// The awk program the app runs on the phone to keep only the PUBG games'
/// lines (see `fps_core::games::awk_filter`). Never empty.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_gameFilter<'l>(env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    env.new_string(fps_core::games::awk_filter()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// Smoothed FPS for the last minute (oldest first), for the live graph.
/// Called on the polling thread, right after `feed`.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_history<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: as in `feed`; called on the same thread.
    let state = unsafe { &*(handle as *const State) };
    double_array(&env, &state.monitor.fps_trail())
}

/// Reads a session .csv and returns the match graph as a flat array of
/// POINT_STRIDE values per point: [t, fpsMin, fpsAvg, fpsMax, stutters,
/// batteryC, cpuC], missing values -1. Empty if the file can't be read.
/// Needs no monitor handle, so it is safe from any thread.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_sessionSeries<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    max_points: jint,
) -> jdoubleArray {
    let path: String = env.get_string(&path).map(Into::into).unwrap_or_default();
    let text = std::fs::read_to_string(&path).unwrap_or_default();
    let mut flat = Vec::new();
    for p in session_series(&text, max_points.max(1) as usize) {
        flat.extend_from_slice(&[
            p.t,
            p.fps_min,
            p.fps_avg,
            p.fps_max,
            p.stutters as f64,
            p.battery_c.unwrap_or(NONE),
            p.cpu_c.unwrap_or(NONE),
        ]);
    }
    double_array(&env, &flat)
}

fn double_array(env: &JNIEnv, values: &[f64]) -> jdoubleArray {
    let Ok(array) = env.new_double_array(values.len() as i32) else { return std::ptr::null_mut() };
    if env.set_double_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Writes the final summary and frees the session.
#[no_mangle]
pub extern "system" fn Java_dev_fpsmonitor_FpsNative_destroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: handle came from `create` and is destroyed exactly once.
    let state = unsafe { Box::from_raw(handle as *mut State) };
    if let Some(log) = &state.log {
        let _ = log.write_summary(&state.monitor);
    }
}
