---
layout: default
title: Real FPS counter and stutter monitor for Android
---

**FPS Monitor** is a free, open-source Android app that shows the **real frame rate (FPS), stutters and phone heat of BGMI and PUBG Mobile** while you play, in a small floating overlay.

[Download the latest APK](https://github.com/Unknown-mj/bgmi-fps-monitor/releases/latest){: .btn} [Source code on GitHub](https://github.com/Unknown-mj/bgmi-fps-monitor){: .btn}

## Why it's accurate

Most FPS meter apps count how often *their own overlay* refreshes, which mostly shows your **screen's refresh rate** (for example 120), not the game's FPS. FPS Monitor reads the frame statistics Android's display system (SurfaceFlinger) keeps for **the game's own surface**, so it shows the frames the game really put on screen:

- the game at 90 FPS on a 120 Hz screen shows **90**, not 120
- every hitch is counted as a **skipped frame** or **stutter**, judged against the game's target frame rate

## Features

- **Live FPS** twice a second, with the game's **target FPS** (30 / 60 / 90 / 120 / 144) detected automatically
- **Skipped frames, stutters, long pauses and worst 1-second FPS**
- **Phone heat**: battery, CPU, GPU and surface temperature, plus Android's heat status
- **Floating overlay**: one clean row or column of numbers, a switch per meter, black or transparent, touches pass through to the game
- **Session history** with match graphs
- **Every PUBG Mobile version** detected automatically: BGMI, PUBG Mobile (Global), KR, VN, TW, Game for Peace, Lite and Beta
- **Light and private**: about 0.5 MB, no internet permission, no data leaves the phone

## Requirements

- Android 14 or newer, 64-bit ARM phone
- [Shizuku](https://shizuku.rikka.app/) installed and running (no root needed)

## Safety

FPS Monitor is **unofficial** and **read-only**: it never opens, injects into, reads the memory of, or changes the game. It only reads frame statistics Android already keeps. Not affiliated with KRAFTON or Tencent. Use at your own risk.

## Learn more

The full guide, glossary of every number, build instructions and supported versions are in the [README on GitHub](https://github.com/Unknown-mj/bgmi-fps-monitor#readme). Licensed under the [MIT License](https://github.com/Unknown-mj/bgmi-fps-monitor/blob/main/LICENSE).
