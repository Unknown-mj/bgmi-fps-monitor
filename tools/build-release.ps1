# Builds the release APK (not debuggable, shrunk) and checks it for personal
# strings before you publish it.
#   powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
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
    & .\gradlew.bat --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Android build failed" }
} finally {
    Pop-Location
}

$apk = "$env:USERPROFILE\.fps-monitor-build\app\outputs\apk\release\app-release.apk"
if (-not (Test-Path (Join-Path $root "android\keystore.properties"))) {
    Write-Warning "No android\keystore.properties: this APK is signed with the DEBUG key. Test only, do not publish."
}

# Fail if the APK contains the Windows username or other personal markers.
$bytes = [IO.File]::ReadAllBytes($apk)
$text = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
$markers = @($env:USERNAME, "OneDrive", "Desktop\") | Where-Object { $_ }
$found = $markers | Where-Object { $text.Contains($_) }
if ($found) { throw "APK contains personal strings: $($found -join ', ')" }
"Release APK OK: $apk"
