//! Phone heat, from `dumpsys thermalservice` and `dumpsys battery`, filtered
//! on the phone to these lines (verified on an Android 16 phone):
//!
//! ```text
//! Thermal Status: 2
//! Cached temperatures:                  <- stale: only updated on heat events
//!     Temperature{mValue=66.4, mType=0, mName=CPU0, mStatus=0}
//! Current temperatures from HAL:        <- live: the only section we use
//!     Temperature{mValue=52.9, mType=0, mName=CPU0, mStatus=0}
//!   temperature: 355                    <- dumpsys battery, tenths of °C
//! ```

/// Android temperature types (android.os.Temperature).
const TYPE_CPU: u32 = 0;
const TYPE_GPU: u32 = 1;
const TYPE_BATTERY: u32 = 2;
const TYPE_SKIN: u32 = 3;

/// One heat reading. Temperatures in °C; each is the hottest sensor of
/// that kind.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Thermal {
    /// 0 None, 1 Light, 2 Moderate, 3 Severe, 4 Critical, 5 Emergency, 6 Shutdown.
    pub status: Option<u32>,
    pub battery_c: Option<f64>,
    pub cpu_c: Option<f64>,
    pub gpu_c: Option<f64>,
    pub skin_c: Option<f64>,
}

/// Highest values seen over a session, plus the battery temperature at the start.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct ThermalPeaks {
    pub start_battery_c: Option<f64>,
    pub status: Option<u32>,
    pub battery_c: Option<f64>,
    pub cpu_c: Option<f64>,
    pub gpu_c: Option<f64>,
    pub skin_c: Option<f64>,
}

impl ThermalPeaks {
    pub fn add(&mut self, t: &Thermal) {
        self.start_battery_c = self.start_battery_c.or(t.battery_c);
        self.status = max_opt(self.status, t.status);
        self.battery_c = max_opt(self.battery_c, t.battery_c);
        self.cpu_c = max_opt(self.cpu_c, t.cpu_c);
        self.gpu_c = max_opt(self.gpu_c, t.gpu_c);
        self.skin_c = max_opt(self.skin_c, t.skin_c);
    }
}

fn max_opt<T: PartialOrd>(a: Option<T>, b: Option<T>) -> Option<T> {
    match (a, b) {
        (Some(a), Some(b)) => Some(if b > a { b } else { a }),
        (a, b) => a.or(b),
    }
}

pub fn status_name(status: u32) -> &'static str {
    match status {
        0 => "None",
        1 => "Light",
        2 => "Moderate",
        3 => "Severe",
        4 => "Critical",
        5 => "Emergency",
        6 => "Shutdown",
        _ => "Unknown",
    }
}

pub fn parse_thermal(text: &str) -> Thermal {
    let mut t = Thermal::default();
    let mut hal_battery = None;
    // Only the live "Current temperatures from HAL" section counts.
    let mut live = false;

    for raw in text.lines() {
        let line = raw.trim();
        if let Some(v) = line.strip_prefix("Thermal Status:") {
            t.status = v.trim().parse().ok();
        } else if line.starts_with("Current temperatures from HAL") {
            live = true;
        } else if line.ends_with("temperatures:") {
            live = false;
        } else if let Some(v) = line.strip_prefix("temperature:") {
            // dumpsys battery: tenths of a degree.
            t.battery_c = v.trim().parse::<f64>().ok().map(|tenths| tenths / 10.0);
        } else if live && line.starts_with("Temperature{") {
            let Some((kind, value)) = parse_temperature(line) else { continue };
            // 0.0 means "no reading" for some sensors (vbat, socd).
            if value <= 0.0 {
                continue;
            }
            let slot = match kind {
                TYPE_CPU => &mut t.cpu_c,
                TYPE_GPU => &mut t.gpu_c,
                TYPE_SKIN => &mut t.skin_c,
                TYPE_BATTERY => &mut hal_battery,
                _ => continue,
            };
            *slot = max_opt(*slot, Some(value));
        }
    }
    t.battery_c = t.battery_c.or(hal_battery);
    t
}

/// "Temperature{mValue=52.9, mType=0, mName=CPU0, mStatus=0}" -> (0, 52.9)
fn parse_temperature(line: &str) -> Option<(u32, f64)> {
    let inner = line.strip_prefix("Temperature{")?.strip_suffix('}')?;
    let mut value = None;
    let mut kind = None;
    for field in inner.split(", ") {
        match field.split_once('=') {
            Some(("mValue", v)) => value = v.parse().ok(),
            Some(("mType", v)) => kind = v.parse().ok(),
            _ => {}
        }
    }
    Some((kind?, value?))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "Thermal Status: 2
Cached temperatures:
\tTemperature{mValue=36.5, mType=2, mName=battery, mStatus=0}
\tTemperature{mValue=71.8, mType=0, mName=CPU7, mStatus=0}
\tTemperature{mValue=99.0, mType=3, mName=skin, mStatus=0}
Current temperatures from HAL:
\tTemperature{mValue=0.0, mType=6, mName=vbat, mStatus=6}
\tTemperature{mValue=35.5, mType=2, mName=battery, mStatus=0}
\tTemperature{mValue=53.2, mType=0, mName=CPU4, mStatus=0}
\tTemperature{mValue=48.6, mType=0, mName=CPU7, mStatus=0}
\tTemperature{mValue=45.9, mType=1, mName=GPU5, mStatus=0}
\tTemperature{mValue=38.987, mType=3, mName=skin, mStatus=0}
\tTemperature{mValue=42.4, mType=9, mName=nsp6, mStatus=0}
  temperature: 408
";

    #[test]
    fn reads_live_values_only() {
        let t = parse_thermal(SAMPLE);
        assert_eq!(t.status, Some(2));
        assert_eq!(t.cpu_c, Some(53.2)); // not the stale cached 71.8
        assert_eq!(t.gpu_c, Some(45.9));
        assert_eq!(t.skin_c, Some(38.987)); // not the cached 99.0
        assert_eq!(t.battery_c, Some(40.8)); // dumpsys battery wins over HAL 35.5
    }

    #[test]
    fn battery_falls_back_to_hal_sensor() {
        let text = SAMPLE.replace("  temperature: 408\n", "");
        assert_eq!(parse_thermal(&text).battery_c, Some(35.5));
    }

    #[test]
    fn empty_text_gives_nothing() {
        assert_eq!(parse_thermal(""), Thermal::default());
    }

    #[test]
    fn peaks_keep_highest_and_first_battery() {
        let mut p = ThermalPeaks::default();
        p.add(&Thermal { status: Some(0), battery_c: Some(35.5), cpu_c: Some(50.0), ..Default::default() });
        p.add(&Thermal { status: Some(2), battery_c: Some(40.8), cpu_c: Some(48.0), ..Default::default() });
        p.add(&Thermal { status: Some(1), battery_c: Some(39.0), ..Default::default() });
        assert_eq!(p.start_battery_c, Some(35.5));
        assert_eq!(p.status, Some(2));
        assert_eq!(p.battery_c, Some(40.8));
        assert_eq!(p.cpu_c, Some(50.0));
    }
}
