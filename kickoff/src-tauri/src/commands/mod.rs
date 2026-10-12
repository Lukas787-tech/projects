//! Commands the frontend can invoke. Each one is async and does blocking work
//! (SQLite, the credential store) on a worker thread, never on the UI thread.
//! Arguments use snake_case on the JS side, matching the ts-rs types.

pub mod ai;
pub mod interview;
pub mod keys;
pub mod research;
pub mod voice;

use kickoff_core::{AppInfo, AppSettings};
use serde::Serialize;
use tauri::State;
use ts_rs::TS;

use crate::error::{CmdResult, CommandError, ErrorCode};
use crate::state::AppState;

/// Run blocking work off the async runtime.
pub(crate) async fn blocking<T: Send + 'static>(f: impl FnOnce() -> CmdResult<T> + Send + 'static) -> CmdResult<T> {
    tauri::async_runtime::spawn_blocking(f).await.map_err(|e| CommandError::new(ErrorCode::Internal, e))?
}

#[tauri::command(rename_all = "snake_case")]
pub fn app_info() -> AppInfo {
    AppInfo::current()
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_settings(state: State<'_, AppState>) -> CmdResult<AppSettings> {
    let db = state.db.clone();
    blocking(move || Ok(db.get_setting(AppSettings::KEY)?.unwrap_or_default())).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn save_settings(state: State<'_, AppState>, settings: AppSettings) -> CmdResult<AppSettings> {
    let db = state.db.clone();
    blocking(move || {
        db.set_setting(AppSettings::KEY, &settings)?;
        Ok(settings)
    })
    .await
}

/// Health of the pieces the app depends on, for the Settings > About page.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct SystemStatus {
    pub os: String,
    pub data_dir: String,
    pub log_dir: String,
    #[ts(type = "number")]
    pub schema_version: usize,
    pub presets_updated: String,
    /// `null` when the OS credential store works.
    pub secret_store_problem: Option<String>,
    pub default_projects_dir: Option<String>,
}

#[tauri::command(rename_all = "snake_case")]
pub async fn system_status(state: State<'_, AppState>) -> CmdResult<SystemStatus> {
    let db = state.db.clone();
    let schema_version = blocking(move || Ok(db.schema_version()?)).await?;
    Ok(SystemStatus {
        os: kickoff_platform::os_name().to_owned(),
        data_dir: state.paths.data_dir.display().to_string(),
        log_dir: state.paths.log_dir.display().to_string(),
        schema_version,
        presets_updated: state.presets.updated.clone(),
        secret_store_problem: state.secret_store_problem.clone(),
        default_projects_dir: kickoff_platform::paths::default_projects_dir().map(|p| p.display().to_string()),
    })
}
