# Does FPS Monitor itself cause stutters? Take one BGMI-only reading at the
# start of gameplay and one at the end, from the PC, and let the Rust tool
# compare them. Run once with the app off ("baseline") and once with it on
# ("monitored").
#   powershell -ExecutionPolicy Bypass -File tools\ab-test.ps1 -Label baseline -Mark start
#   powershell -ExecutionPolicy Bypass -File tools\ab-test.ps1 -Label baseline -Mark stop
param(
    [Parameter(Mandatory)][ValidateSet("baseline", "monitored")][string]$Label,
    [Parameter(Mandatory)][ValidateSet("start", "stop")][string]$Mark
)
$ErrorActionPreference = "Stop"
$adb  = "$env:USERPROFILE\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$tool = Join-Path $root "fps-core\target\debug\fps-dump.exe"
$out  = Join-Path $root "samples\ab"
New-Item -ItemType Directory -Force $out | Out-Null
$awkOnPhone = "/data/local/tmp/fps_pubg.awk"

# Same on-phone filter as the app: only BGMI's lines leave the phone.
& $adb push (Join-Path $PSScriptRoot "pubg.awk") $awkOnPhone | Out-Null
$dump = & $adb exec-out "dumpsys SurfaceFlinger --timestats -dump -maxlayers 20 | awk -f $awkOnPhone"
& $adb shell rm $awkOnPhone
$stamp = [DateTime]::UtcNow
$dump | Out-File -Encoding utf8 "$out\$Label-$Mark.txt"
$stamp.ToString("o") | Out-File -Encoding utf8 "$out\$Label-$Mark.time"
"Saved $Label $Mark reading at $($stamp.ToLocalTime().ToString('HH:mm:ss'))"

if ($Mark -eq "stop") {
    $t0 = [DateTime]::Parse((Get-Content "$out\$Label-start.time")).ToUniversalTime()
    $secs = [math]::Round(($stamp - $t0).TotalSeconds, 2)
    & $tool "$out\$Label-start.txt" "$out\$Label-stop.txt" $secs
}
