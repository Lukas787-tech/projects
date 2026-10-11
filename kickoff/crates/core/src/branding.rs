//! The single source of truth for the app's name.
//!
//! Renaming the app: change [`APP_NAME`] (and the matching `productName` in
//! `src-tauri/tauri.conf.json`; a test keeps them in sync). Everything the user
//! sees, including prompts and generated starter kits, reads this constant.
//!
//! [`APP_ID`] is different: it names the credential-store service, the app-data
//! directory and the database. Changing it orphans every stored key, so leave it
//! alone unless you also migrate the user's data.

use serde::Serialize;
use ts_rs::TS;

/// Display name, shown everywhere in the UI and in generated files.
pub const APP_NAME: &str = "Kickoff";

/// Stable machine identifier (reverse-DNS). Do not change casually; see module docs.
pub const APP_ID: &str = "dev.kickoff.studio";

/// App version, taken from the workspace version.
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

/// User-Agent for every outbound HTTP request. Some APIs (Poly Haven) require a
/// unique agent that names the app.
pub fn user_agent() -> String {
    format!("{APP_NAME}/{APP_VERSION} (+https://github.com/Lukas787-tech/projects)")
}

/// Basic app identity, sent to the frontend.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct AppInfo {
    pub name: String,
    pub id: String,
    pub version: String,
}

impl AppInfo {
    pub fn current() -> Self {
        Self { name: APP_NAME.to_owned(), id: APP_ID.to_owned(), version: APP_VERSION.to_owned() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_agent_names_the_app() {
        assert!(user_agent().starts_with(&format!("{APP_NAME}/")));
    }

    #[test]
    fn app_id_is_reverse_dns() {
        assert!(APP_ID.split('.').count() >= 2);
        assert!(APP_ID.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-'));
    }
}
