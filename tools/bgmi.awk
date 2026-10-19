/^displayRefreshRate = / { pre = $0 "\n"; next }
/^(renderRate|uid) = / { pre = pre $0 "\n"; next }
/^layerName = / { inb = index($0, "SurfaceView[com.pubg.imobile/") > 0; if (inb) printf "%s%s\n", pre, $0; pre = ""; next }
inb && /^(totalFrames|droppedFrames) = / { print; next }
inb && /^present2present histogram/ { print; getline; print; next }
