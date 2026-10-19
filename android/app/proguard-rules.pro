# Rust (fps-jni) finds these by class and method name: keep both unrenamed.
-keep class dev.fpsmonitor.FpsNative { *; }

# ShellRunner calls Shizuku's private newProcess() by reflection, so the
# shrinker must not remove or rename it.
-keep class rikka.shizuku.Shizuku { *; }
