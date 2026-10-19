//! The PUBG Mobile versions the monitor recognises. This is the only list:
//! the app builds its on-phone filter and display names from it.
//!
//! Packages other than BGMI come from store listings and install guides, not
//! from a test device. A wrong entry is harmless: it simply never matches.

/// One game version: its Android package and the name shown in the app.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Game {
    pub package: &'static str,
    pub name: &'static str,
}

pub const GAMES: &[Game] = &[
    Game { package: "com.pubg.imobile", name: "BGMI" },
    Game { package: "com.tencent.ig", name: "PUBG Mobile" },
    Game { package: "com.pubg.krmobile", name: "PUBG Mobile (KR)" },
    Game { package: "com.vng.pubgmobile", name: "PUBG Mobile (VN)" },
    Game { package: "com.rekoo.pubgm", name: "PUBG Mobile (TW)" },
    Game { package: "com.tencent.tmgp.pubgmhd", name: "Game for Peace" },
    Game { package: "com.tencent.iglite", name: "PUBG Mobile Lite" },
    Game { package: "com.tencent.igce", name: "PUBG Mobile (Beta)" },
];

/// Display name for a package, or None if it isn't a known PUBG version.
pub fn name_of(package: &str) -> Option<&'static str> {
    GAMES.iter().find(|g| g.package == package).map(|g| g.name)
}

/// "com\.pubg\.imobile|com\.tencent\.ig|…": every package as one regex alternation.
pub fn package_regex() -> String {
    GAMES.iter().map(|g| g.package.replace('.', "\\.")).collect::<Vec<_>>().join("|")
}

/// The awk program run on the phone after `dumpsys SurfaceFlinger --timestats -dump`.
/// It keeps only the PUBG games' layer blocks: the rate/uid lines just before
/// each layerName, then totalFrames, droppedFrames and the present2present
/// histogram. Everything about other apps stays on the phone. The trailing
/// "/" in the match keeps com.tencent.ig apart from com.tencent.iglite.
/// Used by the app (through JNI) and the PC tools (`fps-dump --awk`).
pub fn awk_filter() -> String {
    format!(
        concat!(
            "/^displayRefreshRate = / {{ pre = $0 \"\\n\"; next }}\n",
            "/^(renderRate|uid) = / {{ pre = pre $0 \"\\n\"; next }}\n",
            "/^layerName = / {{ inb = ($0 ~ /SurfaceView\\[({})\\//); ",
            "if (inb) printf \"%s%s\\n\", pre, $0; pre = \"\"; next }}\n",
            "inb && /^(totalFrames|droppedFrames) = / {{ print; next }}\n",
            "inb && /^present2present histogram/ {{ print; getline; print; next }}\n",
        ),
        package_regex()
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packages_are_unique_and_safe_for_the_filter() {
        for (i, g) in GAMES.iter().enumerate() {
            // The app puts these into a fixed awk pattern: only plain package characters.
            assert!(g.package.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '.' || c == '_'));
            assert!(GAMES[i + 1..].iter().all(|o| o.package != g.package));
        }
    }

    #[test]
    fn awk_filter_lists_every_package_and_is_shell_safe() {
        let awk = awk_filter();
        for g in GAMES {
            assert!(awk.contains(&g.package.replace('.', "\\.")), "{}", g.package);
        }
        // The app wraps the program in single quotes for `sh -c`.
        assert!(!awk.contains('\''));
        assert!(awk.contains("/SurfaceView\\[(com\\.pubg\\.imobile|"));
        assert!(awk.contains(")\\//)"));
    }

    #[test]
    fn looks_up_names() {
        assert_eq!(name_of("com.pubg.imobile"), Some("BGMI"));
        assert_eq!(name_of("com.tencent.iglite"), Some("PUBG Mobile Lite"));
        assert_eq!(name_of("com.example.other"), None);
    }
}
