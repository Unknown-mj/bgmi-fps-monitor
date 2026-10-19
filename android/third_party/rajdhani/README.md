Rajdhani Bold by Indian Type Foundry, from https://github.com/google/fonts/tree/main/ofl/rajdhani,
licensed under the SIL Open Font License 1.1 (OFL.txt). The OFL declares no Reserved Font Name.

`app/src/main/res/font/rajdhani_bold.ttf` is a subset (Basic Latin, °, ·, dashes, ellipsis,
arrows, squares/circles) made with fontTools to cut it from 400 KB to 20 KB:

    pyftsubset Rajdhani-Bold.ttf --unicodes="U+0020-007E,U+00B0,U+00B7,U+2013,U+2014,U+2026,U+2190-2193,U+25A0-25CF" --layout-features='*'
