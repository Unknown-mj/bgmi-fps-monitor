# Keeps only the PUBG games' layer blocks from `dumpsys SurfaceFlinger --timestats -dump`.
# Same filter the app builds from fps-core/src/games.rs; keep the list in sync.
/^displayRefreshRate = / { pre = $0 "\n"; next }
/^(renderRate|uid) = / { pre = pre $0 "\n"; next }
/^layerName = / { inb = ($0 ~ /SurfaceView\[(com\.pubg\.imobile|com\.tencent\.ig|com\.pubg\.krmobile|com\.vng\.pubgmobile|com\.rekoo\.pubgm|com\.tencent\.tmgp\.pubgmhd|com\.tencent\.iglite|com\.tencent\.igce)\//); if (inb) printf "%s%s\n", pre, $0; pre = ""; next }
inb && /^(totalFrames|droppedFrames) = / { print; next }
inb && /^present2present histogram/ { print; getline; print; next }
