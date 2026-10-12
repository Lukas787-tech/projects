//! Kickoff app shell: wires state, logging and commands into Tauri.
//! Domain logic lives in the `kickoff-*` crates, not here.

mod ai;
mod commands;
mod error;
mod interview;
mod keys;
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
            let state = app.state::<AppState>();
            state.observer.attach(app.handle().clone());
            // Scrub every stored key from logs from the start.
            let keys = state.keys.clone();
            tauri::async_runtime::spawn_blocking(move || keys.register_all_with_redactor());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_info,
            commands::get_settings,
            commands::save_settings,
            commands::system_status,
            commands::keys::list_keys,
            commands::keys::add_key,
            commands::keys::update_key,
            commands::keys::replace_key_secret,
            commands::keys::delete_key,
            commands::keys::test_key,
            commands::ai::list_presets,
            commands::ai::get_ai_settings,
            commands::ai::save_ai_settings,
            commands::ai::list_models,
            commands::ai::test_provider,
            commands::ai::detect_ollama,
            commands::ai::usage_today,
            commands::ai::check_task,
            commands::interview::create_project,
            commands::interview::list_projects,
            commands::interview::delete_project,
            commands::interview::get_interview,
            commands::interview::send_message,
            commands::interview::continue_interview,
            commands::interview::edit_brief_field,
            commands::interview::revisit_field,
            commands::interview::finish_interview,
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
