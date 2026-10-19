# Builds the Rust library and the Android app, then installs it on the phone.
#   powershell -ExecutionPolicy Bypass -File tools\install-app.ps1
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
$adb  = "$env:USERPROFILE\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent

Push-Location (Join-Path $root "fps-jni")
try {
    & cargo build --release --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw "Rust build failed" }
    $libs = Join-Path $root "android\app\src\main\jniLibs\arm64-v8a"
    New-Item -ItemType Directory -Force $libs | Out-Null
    Copy-Item "target\aarch64-linux-android\release\libfps_jni.so" $libs -Force
} finally {
    Pop-Location
}

Push-Location (Join-Path $root "android")
try {
    & .\gradlew.bat --console=plain assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Android build failed" }
    & $adb install -r "$env:USERPROFILE\.fps-monitor-build\app\outputs\apk\debug\app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw "Install failed" }
    "Installed. Open 'FPS Monitor' on the phone."
} finally {
    Pop-Location
}
