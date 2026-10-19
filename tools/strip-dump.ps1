# Rewrites timestats dumps in place, keeping only the global header and the
# BGMI layer blocks. Other apps' screen names (banking, messages...) are removed.
#   powershell -ExecutionPolicy Bypass -File tools\strip-dump.ps1 <file-or-folder>...
param([Parameter(ValueFromRemainingArguments)][string[]]$Paths)

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
        if ($starts[$b].Name -match 'SurfaceView\[com\.pubg\.imobile/') {
            $keep.AddRange([string[]]$lines[$starts[$b].Start..$end])
        }
    }
    [IO.File]::WriteAllLines($f.FullName, $keep, [Text.UTF8Encoding]::new($false))
}
"Stripped $($files.Count) file(s)"
