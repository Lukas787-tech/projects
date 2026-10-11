//! Kickoff app shell: wires state, logging and commands into Tauri.
//! Domain logic lives in the `kickoff-*` crates, not here.

mod commands;
mod error;
mod logging;
mod state;

use kickoff_core::APP_NAME;
use kickoff_platform::AppPaths;
use tauri::Manager;

use crate::state::AppState;

pub fn run() {
    let paths = AppPaths::resolve().expect("cannot resolve the user's data directory");
    let _log_guard = logging::init(&paths.log_dir);
    tracing::info!(version = kickoff_core::APP_VERSION, os = kickoff_platform::os_name(), "starting {APP_NAME}");

    let state = match AppState::init(paths) {
        Ok(state) => state,
        Err(e) => {
            tracing::error!(error = %e, "startup failed");
            panic!("{APP_NAME} could not start: {e}");
        }
    };

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .manage(state)
        .setup(|app| {
            if let Some(window) = app.get_webview_window("main") {
                window.set_title(APP_NAME)?;
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_info,
            commands::get_settings,
            commands::save_settings,
            commands::system_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the Tauri application");
}

#[cfg(test)]
mod tests {
    use kickoff_core::{APP_ID, APP_NAME, APP_VERSION};

    /// The app name and id are defined once in `kickoff_core::branding`; the
    /// Tauri config cannot import them, so keep it in sync here.
    #[test]
    fn tauri_config_matches_branding() {
        let conf: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        assert_eq!(conf["productName"], APP_NAME, "update productName in tauri.conf.json");
        assert_eq!(conf["identifier"], APP_ID, "update identifier in tauri.conf.json");
        assert_eq!(conf["version"], APP_VERSION, "keep tauri.conf.json version in sync with Cargo");
        assert_eq!(conf["app"]["windows"][0]["title"], APP_NAME);
    }
}
