//! User-facing app settings.

use serde::{Deserialize, Serialize};
use ts_rs::TS;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Theme {
    #[default]
    Dark,
    Light,
    System,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(default)]
#[ts(export)]
pub struct AppSettings {
    pub theme: Theme,
    /// Where generated projects go. `None` until the user picks one; the UI
    /// suggests the platform default.
    pub projects_dir: Option<String>,
    /// Set when the first-run setup wizard has been completed or skipped.
    pub onboarding_done: bool,
}

impl AppSettings {
    /// Settings key in the database.
    pub const KEY: &'static str = "app";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_to_dark() {
        assert_eq!(AppSettings::default().theme, Theme::Dark);
    }

    #[test]
    fn missing_fields_take_defaults() {
        let s: AppSettings = serde_json::from_str(r#"{"theme":"light"}"#).unwrap();
        assert_eq!(s.theme, Theme::Light);
        assert!(!s.onboarding_done);
    }
}
