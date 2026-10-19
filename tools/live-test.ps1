# Live test from the PC: polls timestats like the app will, then prints
# FPS and stutters per window. Run while a BGMI match is in progress.
#   powershell -ExecutionPolicy Bypass -File tools\live-test.ps1 [-Count 20] [-IntervalMs 500]
param([int]$Count = 20, [int]$IntervalMs = 500)

$adb  = "$env:USERPROFILE\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$tool = Join-Path $root "fps-core\target\debug\fps-dump.exe"
$out  = Join-Path $root ("samples\live-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force $out | Out-Null

& $adb shell dumpsys SurfaceFlinger --timestats -enable | Out-Null

"== Cost of one dump on the phone (3 runs)"
1..3 | ForEach-Object { & $adb shell "(time dumpsys SurfaceFlinger --timestats -dump > /dev/null) 2>&1" }

"== Taking $Count dumps, every $IntervalMs ms"
$sw = [Diagnostics.Stopwatch]::StartNew()
$times = @()
for ($i = 0; $i -lt $Count; $i++) {
    $start = $sw.Elapsed.TotalSeconds
    # Out-File -Encoding utf8: plain '>' in Windows PowerShell writes UTF-16.
    & $adb exec-out "dumpsys SurfaceFlinger --timestats -dump" | Out-File -Encoding utf8 "$out\d$i.txt"
    $times += $start
    $wait = $IntervalMs - [int](($sw.Elapsed.TotalSeconds - $start) * 1000)
    if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
}

"== Per window"
for ($i = 1; $i -lt $Count; $i++) {
    $secs = [math]::Round($times[$i] - $times[$i - 1], 3)
    $r = & $tool "$out\d$($i-1).txt" "$out\d$i.txt" $secs
    "{0,2}: {1}" -f $i, (($r | Select-Object -Skip 1) -join " | ")
}

"== Whole run"
$secs = [math]::Round($times[$Count - 1] - $times[0], 3)
& $tool "$out\d0.txt" "$out\d$($Count-1).txt" $secs
"Dumps saved in $out"
