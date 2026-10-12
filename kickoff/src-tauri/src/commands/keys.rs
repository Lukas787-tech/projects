//! Key library commands.

use kickoff_core::keys::KeyMeta;
use tauri::State;

use super::blocking;
use crate::ai::ProviderTest;
use crate::error::CmdResult;
use crate::keys::{KeyEntry, NewKeyInput};
use crate::state::AppState;

#[tauri::command(rename_all = "snake_case")]
pub async fn list_keys(state: State<'_, AppState>) -> CmdResult<Vec<KeyEntry>> {
    let keys = state.keys.clone();
    blocking(move || Ok(keys.list()?)).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn add_key(state: State<'_, AppState>, input: NewKeyInput) -> CmdResult<KeyMeta> {
    let keys = state.keys.clone();
    blocking(move || Ok(keys.add(input)?)).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn update_key(
    state: State<'_, AppState>,
    id: String,
    label: String,
    env_var: String,
    notes: Option<String>,
) -> CmdResult<KeyMeta> {
    let keys = state.keys.clone();
    blocking(move || Ok(keys.update(&id, &label, &env_var, notes.as_deref())?)).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn replace_key_secret(state: State<'_, AppState>, id: String, secret: String) -> CmdResult<()> {
    let keys = state.keys.clone();
    blocking(move || Ok(keys.replace_secret(&id, &secret)?)).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn delete_key(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    let keys = state.keys.clone();
    blocking(move || Ok(keys.delete(&id)?)).await
}

/// Test a saved key with a real request and remember the result.
#[tauri::command(rename_all = "snake_case")]
pub async fn test_key(state: State<'_, AppState>, id: String) -> CmdResult<ProviderTest> {
    let keys = state.keys.clone();
    let lookup_id = id.clone();
    let (meta, secret) = blocking(move || Ok((keys.meta(&lookup_id)?, keys.secret(&lookup_id)?))).await?;
    let provider_id = meta.provider_id.clone().unwrap_or_default();

    let result = if state.presets.llm(&provider_id).is_some() {
        state.ai.test_provider(&provider_id, secret).await
    } else {
        ProviderTest {
            ok: false,
            provider_id,
            model: None,
            latency_ms: 0,
            error_kind: Some("untested".into()),
            message: "This kind of key can't be tested yet; it will be checked the first time it's used.".into(),
        }
    };
    if result.error_kind.as_deref() != Some("untested") {
        let keys = state.keys.clone();
        let ok = result.ok;
        blocking(move || Ok(keys.record_test(&id, ok)?)).await?;
    }
    Ok(result)
}
