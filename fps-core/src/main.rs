//! PC test tool.
//!
//!   fps-dump <dump.txt>                      show the game's totals
//!   fps-dump <before.txt> <after.txt> <secs> show FPS and stutters between two dumps
//!   fps-dump --awk                           print the on-phone filter (same as the app's)
//!   fps-dump --package-regex                 print every PUBG package as one regex
//!
//! Works with every PUBG version in fps-core/src/games.rs and picks the one
//! in the dumps automatically.

use fps_core::games::{self, Game, GAMES};
use fps_core::{compare, game_snapshot, parse_dump, snap_to_cap, GameSnapshot, LayerBlock, LONG_PAUSE_MS};
use std::process::exit;

fn read(path: &str) -> Vec<LayerBlock> {
    let text = std::fs::read_to_string(path).unwrap_or_else(|e| {
        eprintln!("Cannot read {path}: {e}");
        exit(1);
    });
    parse_dump(&text)
}

/// Every known PUBG version present in the dump.
fn present(blocks: &[LayerBlock]) -> Vec<(&'static Game, GameSnapshot)> {
    GAMES.iter().filter_map(|g| game_snapshot(blocks, g.package).map(|s| (g, s))).collect()
}

fn no_game(path: &str) -> ! {
    eprintln!("No PUBG game layer found in {path}");
    exit(1);
}

/// The game with the most frames in one dump.
fn load_one(path: &str) -> (&'static Game, GameSnapshot) {
    present(&read(path)).into_iter().max_by_key(|(_, s)| s.total_frames).unwrap_or_else(|| no_game(path))
}

/// The game that drew the most frames between two dumps (present in both).
fn load_pair(before: &str, after: &str) -> (&'static Game, GameSnapshot, GameSnapshot) {
    let a = present(&read(before));
    present(&read(after))
        .into_iter()
        .filter_map(|(g, b)| a.iter().find(|(ga, _)| ga.package == g.package).map(|(_, s)| (g, s.clone(), b)))
        .max_by_key(|(_, a, b)| b.total_frames.saturating_sub(a.total_frames))
        .unwrap_or_else(|| no_game(after))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.as_slice() {
        [flag] if flag == "--awk" => print!("{}", games::awk_filter()),
        [flag] if flag == "--package-regex" => println!("{}", games::package_regex()),
        [path] => {
            let (game, s) = load_one(path);
            println!("game:           {}", game.name);
            println!("blocks found:   {}", s.blocks);
            println!("uid:            {}", s.uid.map_or("?".into(), |u| u.to_string()));
            println!("totalFrames:    {}", s.total_frames);
            println!("droppedFrames:  {}", s.dropped_frames);
            println!("present2present (non-zero buckets):");
            for (ms, count) in s.present2present.iter().filter(|(_, &c)| c > 0) {
                println!("  {ms:>5} ms  {count}");
            }
        }
        [before, after, secs] => {
            let seconds: f64 = secs.parse().unwrap_or_else(|_| {
                eprintln!("<secs> must be a number, got {secs}");
                exit(1);
            });
            let (game, a, b) = load_pair(before, after);
            // Two dumps alone can't show a target rate; use the measured FPS.
            let raw = compare(&a, &b, seconds, None);
            let target = snap_to_cap(raw.fps);
            let w = compare(&a, &b, seconds, Some(target));
            println!("game:        {}", game.name);
            println!("frames:      {} in {:.2} s", w.frames, w.seconds);
            println!("FPS:         {:.1} (target {target:.0})", w.fps);
            println!("skipped:     {} (1 refresh longer than normal)", w.skipped);
            println!("stutters:    {} (2+ refreshes longer, under {LONG_PAUSE_MS} ms)", w.stutters);
            println!("long pauses: {} (gaps >= {LONG_PAUSE_MS} ms)", w.long_pauses);
            // Which screen refresh rate the frames were shown at.
            let mut by_refresh: std::collections::BTreeMap<u32, u64> = Default::default();
            for (key, block) in &b.per_block {
                let before = a.per_block.get(key).map_or(0, |s| s.total_frames);
                *by_refresh.entry(block.display_refresh_rate.unwrap_or(0)).or_default() +=
                    block.total_frames.saturating_sub(before);
            }
            let shares: Vec<String> = by_refresh
                .iter()
                .filter(|(_, &f)| f > 0)
                .map(|(hz, f)| format!("{hz} Hz {:.0}%", *f as f64 * 100.0 / w.frames.max(1) as f64))
                .collect();
            println!("screen:      {}", shares.join(", "));
            let minutes = w.seconds / 60.0;
            if minutes > 0.0 {
                println!(
                    "per minute:  {:.1} skipped, {:.1} stutters, {:.1} long pauses",
                    w.skipped as f64 / minutes,
                    w.stutters as f64 / minutes,
                    w.long_pauses as f64 / minutes,
                );
            }
            if w.reset {
                println!("WARNING: a counter went down (stats cleared or game restarted)");
            }
        }
        _ => {
            eprintln!("usage: fps-dump <dump.txt>");
            eprintln!("       fps-dump <before.txt> <after.txt> <seconds>");
            eprintln!("       fps-dump --awk | --package-regex");
            exit(2);
        }
    }
}
