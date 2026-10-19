# Rewrites timestats dumps in place, keeping only the global header and the
# PUBG game layer blocks (same list as fps-core/src/games.rs). Other apps'
# screen names (banking, messages...) are removed.
#   powershell -ExecutionPolicy Bypass -File tools\strip-dump.ps1 <file-or-folder>...
param([Parameter(ValueFromRemainingArguments)][string[]]$Paths)
$ErrorActionPreference = "Stop"

# The package list comes from fps-dump (fps-core/src/games.rs), never a copy here.
$tool = Join-Path (Split-Path $PSScriptRoot -Parent) "fps-core\target\debug\fps-dump.exe"
if (-not (Test-Path $tool)) { throw "Build fps-dump first: cd fps-core; cargo build" }
$gamePattern = 'SurfaceView\[(' + (& $tool --package-regex) + ')/'

$files = $Paths | ForEach-Object { Get-ChildItem $_ -Recurse -File -Filter *.txt }
foreach ($f in $files) {
    $lines = [IO.File]::ReadAllLines($f.FullName)
    # A layer block starts at "displayRefreshRate", three lines above its "layerName".
    $starts = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith("layerName = ")) {
            $s = $i
            if ($i -ge 3 -and $lines[$i - 3].StartsWith("displayRefreshRate")) { $s = $i - 3 }
            $starts += [pscustomobject]@{ Start = $s; Name = $lines[$i] }
        }
    }
    if ($starts.Count -eq 0) { continue }
    $keep = [Collections.Generic.List[string]]::new()
    $keep.AddRange([string[]]$lines[0..($starts[0].Start - 1)])
    for ($b = 0; $b -lt $starts.Count; $b++) {
        $end = if ($b + 1 -lt $starts.Count) { $starts[$b + 1].Start - 1 } else { $lines.Count - 1 }
        if ($starts[$b].Name -match $gamePattern) {
            $keep.AddRange([string[]]$lines[$starts[$b].Start..$end])
        }
    }
    [IO.File]::WriteAllLines($f.FullName, $keep, [Text.UTF8Encoding]::new($false))
}
"Stripped $($files.Count) file(s)"
