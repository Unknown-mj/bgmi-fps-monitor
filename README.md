# FPS Monitor for BGMI and PUBG Mobile

A small Android app that shows **the real frame rate, stutters and phone heat of BGMI and other PUBG Mobile versions while you play**, entirely on the phone. It recognises the version you open automatically. The measuring and analysis are written in **Rust**; the Android side is a thin Kotlin layer.

> [!WARNING]
> - **Unofficial.** Not made, endorsed or supported by KRAFTON, Tencent, or any BGMI or PUBG Mobile publisher.
> - **Read-only.** It never opens, injects into, reads the memory of, or modifies the game. It only reads frame statistics that Android's display system (SurfaceFlinger) already keeps. No official KRAFTON policy on such tools was found, so **use it at your own risk**.
> - **Tested on one phone only** (Android 16, 120 Hz screen). It may not work, or may report wrong numbers, on other phones; see [Compatibility](#compatibility).
> - **Needs [Shizuku](https://shizuku.rikka.app/)**, which gives apps shell-level (ADB) access. Only grant it to apps you trust. This app uses it for four fixed, read-only commands and nothing else (see `ShellRunner.kt`).
> - **No ready-made APK.** You build and install it yourself; see [Build and install](#build-and-install).

## Features

- **Live FPS** twice a second, smoothed over the last second, with the game's **target rate** detected automatically (30 / 60 / 90 / 120 / 144…).
- **Skipped frames and stutters**, judged against the target rate and the screen's refresh rate, so a game running at 90 FPS on a 120 Hz screen isn't counted as stuttering.
- **Long pauses** (loading, menus) counted separately from stutters.
- **Worst 1-second FPS** of the session.
- **Phone heat**: Android heat status (None → Light → Moderate → Severe…), battery, CPU, GPU and surface temperatures, read every 5 seconds.
- **Floating overlay** on top of the game:
  - one clean row of numbers, for example `118  BAT 38°  CPU 54°`, or stacked in a column
  - a separate switch for each meter: FPS, stutter count, battery, surface, CPU and GPU temperature
  - FPS coloured by health; each temperature turns orange, then red, as that part heats up
  - sizes S, M or L, and a black or transparent background
  - optionally shown only while the game is drawing, and an optional red flash on each stutter
  - touches pass through to the game when locked
  - kept light: fixed-width text, no re-layouts, redraws only when a value changes
- **Live notification** with FPS, stutters and battery temperature.
- **Session history** with a **match graph**: FPS with a lowest–highest band, stutter markers and battery temperature.
- **Dark gamer-style UI** with four accent themes (Neon Cyan, Dragon Red, Toxic Green, Purple Pulse).
- **Light on the game**: graphs and animations are only drawn while the app itself is on screen; during play only the overlay and notification update.

## Glossary

| Term | Meaning |
|---|---|
| **FPS** (frames per second) | How many new pictures the game shows each second. Higher is smoother. |
| **Live FPS** | FPS averaged over the last 1 second, updated twice a second. This is the big number in the app and overlay. |
| **Target FPS** | The frame rate the game is aiming for (for example 90 or 120). The app works it out from the highest steady rate in the last 10 seconds, rounded to a common limit (30, 60, 90, 120, 144…). |
| **Avg FPS** | Average FPS over the session's gameplay time only. Time with the game in the background doesn't count. |
| **Worst 1s** | The lowest 1-second FPS while the game was drawing continuously: the worst dip you actually felt. |
| **Refresh rate (Hz)** | How many times per second the screen itself updates (for example 120 Hz). The game can never show more FPS than this. |
| **Frame gap** | Time between two frames appearing on screen. At 120 FPS on a 120 Hz screen it's about 8 ms. |
| **Skipped frame** | A frame that arrived **one screen refresh late**, so the previous picture stayed up twice as long (16 ms instead of 8 ms at 120 Hz). Mild, usually not noticed. |
| **Stutter** | A frame that arrived **two or more refreshes late**, but under 200 ms (for example 25–199 ms at 120 Hz). This is the hitch you can see. |
| **Long pause** | A gap of 200 ms or more: loading screens, menus, switching apps. Counted separately so it doesn't inflate stutters. |
| **Stutters/min** | Stutters divided by minutes of gameplay. Best for comparing sessions of different lengths. |
| **Gameplay time** | Time the game was actually drawing frames while monitoring was on. |
| **Heat status** | Android's own heat level: None → Light → **Moderate** → **Severe** → Critical → Emergency → Shutdown. From Moderate, phones often lower performance or screen speed (for example 120 Hz → 90 Hz). |
| **Battery / CPU / GPU / Surface °C** | Live temperature sensors. CPU and GPU show the hottest core. "Surface" (skin) is the outside of the phone. |
| **Session** | One monitoring run, from Start to Stop. Each gets a log and summary in the app's private storage. |
| **Match graph** | The session's FPS over time. The shaded band shows the lowest and highest FPS in each slice (so short dips stay visible), red dots are stutters, and the orange line is battery temperature. |
| **SurfaceFlinger / timestats** | Android's display system and its built-in frame statistics, which this app reads. |
| **Shizuku** | An app that lets other apps run ADB-level commands without root. Needed here to read the frame statistics. |

## How it works

1. When monitoring starts, the app clears and enables SurfaceFlinger's frame statistics (`dumpsys SurfaceFlinger --timestats`).
2. Every 0.5 s it reads the statistics through Shizuku. A filter on the phone keeps only the PUBG games' lines (about 1–4 KB).
3. The Rust core (`fps-core`) parses them and computes FPS from the frame counter, and skipped frames and stutters from the frame-gap histogram.
4. Results go to the screen, overlay and notification, and into a session log in the app's private storage.

Each game is found by its package name, never by its full layer name, which changes every session. If several versions are installed, the one currently drawing frames is measured; switching versions starts a fresh measurement so two games' numbers never mix.

## Supported versions

| Version | Android package | Status |
|---|---|---|
| BGMI (India) | `com.pubg.imobile` | **Tested on a real phone** |
| PUBG Mobile (Global) | `com.tencent.ig` | Package confirmed, not tested on a phone |
| PUBG Mobile (KR) | `com.pubg.krmobile` | Package confirmed, not tested on a phone |
| PUBG Mobile (VN) | `com.vng.pubgmobile` | Package confirmed, not tested on a phone |
| PUBG Mobile Lite | `com.tencent.iglite` | Package confirmed, not tested on a phone |
| PUBG Mobile (TW) | `com.rekoo.pubgm` | Package from older sources, not tested |
| Game for Peace (China) | `com.tencent.tmgp.pubgmhd` | Package from third-party listings, not tested |
| PUBG Mobile (Beta) | `com.tencent.igce` | Package from one unofficial source, not tested |

All versions use the same engine and the same Android display system, so they are read the same proven way as BGMI. A wrong package name is harmless: it simply never matches. The list lives in one place, `fps-core/src/games.rs`.

## Compatibility

| Requirement | Why |
|---|---|
| Android 14 or newer | Uses the "special use" foreground-service type |
| 64-bit ARM phone (arm64-v8a) | The Rust library is built only for arm64 |
| Shizuku installed and running | Frame statistics need shell-level access. Shizuku has to be restarted after every reboot (wireless debugging or a PC). |
| A supported PUBG version | See [Supported versions](#supported-versions) |

Phone makers can change how SurfaceFlinger reports statistics. The parser was verified on one Android 16 phone only.

## Download

A ready-made APK is attached to each version on the **[Releases](../../releases)** page. It isn't from the Play Store, so Android will ask you to allow installing from unknown sources, and Google Play Protect may offer to scan it. You still need [Shizuku](https://shizuku.rikka.app/) running.

## Build and install

The build scripts are **Windows (PowerShell)** only for now.

**You need:**
- [Rust](https://rustup.rs) with the Android target: `rustup target add aarch64-linux-android`
- Android SDK with platform 35 and **NDK 26.3.11579264**
- JDK 17 (for example Eclipse Temurin). Gradle 8.11.1 is downloaded by the included wrapper.
- `adb` (Android platform-tools) and a phone with USB debugging on

**Steps:**
1. Copy `fps-jni/.cargo/config.toml.example` to `fps-jni/.cargo/config.toml` and set your NDK path.
2. Create `android/local.properties` with your SDK path, for example `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.
3. Check the `adb` and `JAVA_HOME` paths at the top of `tools/install-app.ps1`.
4. Connect the phone and run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\install-app.ps1
   ```
   This builds the Rust library, copies it into the app, builds the app and installs it.

**Run the Rust tests** (no phone needed): `cd fps-core && cargo test`

**Release build** (not debuggable, shrunk, about 0.5 MB): put your signing details in `android/keystore.properties` (git-ignored; see the comment at the top of `android/app/build.gradle.kts`), then run `tools\build-release.ps1`. It also refuses to finish if the APK contains your Windows username or folder names. Without a keystore it signs with the debug key, which is only for testing on your own phone.

## Using the app

1. Start **Shizuku**.
2. Open **FPS Monitor** and tap **Start monitoring**. Allow the Shizuku and notification permissions when asked.
3. *(Optional)* In **Settings**, allow "display over other apps" and turn on the floating overlay. To move it, turn off Lock, drag it, then lock it again.
4. Play BGMI or any supported PUBG version. Live FPS shows in the overlay and the notification, with the version's name.
5. Tap **Stop monitoring** afterwards. The **Sessions** tab has the summary and graph for each match.

## PC tools (optional)

| Script | What it does |
|---|---|
| `tools/install-app.ps1` | Build everything (debug) and install on the phone |
| `tools/build-release.ps1` | Build the release APK and check it for personal strings |
| `tools/ab-auto.ps1` | Automatic on/off test during a match: turns monitoring on and off over adb and compares stutters, to check the app doesn't slow the game |
| `tools/ab-test.ps1`, `tools/live-test.ps1` | Older manual versions of the same tests |
| `tools/strip-dump.ps1` | Removes every app except the PUBG games from saved dumps |
| `fps-core` → `cargo run -- before.txt after.txt <seconds>` | Compare two saved dumps on the PC |

Starting and stopping from the PC is restricted to the adb shell (`android.permission.DUMP`), so other apps on the phone can't control the monitor.

## Project structure

```
fps-core/            Rust: all analysis, tested on a PC
  src/lib.rs           dump parser, FPS / skipped / stutter maths, target-rate detection, Monitor
  src/thermal.rs       heat parser (live sensor values only)
  src/log.rs           session CSV and summary files
  src/series.rs        shrinks a session log into a graph, keeping dips
  src/main.rs          PC command-line tool (fps-dump)
fps-jni/             Rust: JNI bridge used by the Android app
android/             Kotlin app (no AndroidX)
  app/src/main/java/dev/fpsmonitor/
    MainActivity.kt    Live / Sessions / Settings screens
    MonitorService.kt  foreground service that polls every 0.5 s
    ShellRunner.kt     the only commands the app can run (via Shizuku)
    FpsNative.kt       Rust bindings and the Reading data class
    Overlay.kt         floating overlay
    Widgets.kt         gauge, graph, stat tiles, heat bars
    Theme.kt           colours, accents, font
    Sessions.kt        session history files
  third_party/rajdhani/  font licence and notes
tools/               PowerShell build and test scripts, awk filter
```

## Privacy

- Everything stays on the phone. The app has **no internet permission** and sends nothing anywhere.
- The on-phone filter passes only the PUBG games' frame statistics to the app. Other apps' data in the system report is discarded on the phone.
- Session logs live in the app's private storage, are excluded from Android backup and phone-to-phone transfer, and are deleted when you uninstall it.
- `samples/` (raw dumps, which list every app on a phone) and build outputs are excluded from this repository.

## Credits and licence

- Font: [Rajdhani](https://github.com/google/fonts/tree/main/ofl/rajdhani) by Indian Type Foundry, SIL Open Font License 1.1 (subset; see `android/third_party/rajdhani/`).
- Uses [Shizuku](https://github.com/RikkaApps/Shizuku) (API library, Apache 2.0).
- This project's own code: [MIT License](LICENSE), © 2026 Unknown-mj.
