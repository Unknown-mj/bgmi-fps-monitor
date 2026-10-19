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
    fn looks_up_names() {
        assert_eq!(name_of("com.pubg.imobile"), Some("BGMI"));
        assert_eq!(name_of("com.tencent.iglite"), Some("PUBG Mobile Lite"));
        assert_eq!(name_of("com.example.other"), None);
    }
}
