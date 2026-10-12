//! Provider catalog, AI routing settings, testing and the usage meter.

use kickoff_core::keys::KeyKind;
use kickoff_core::llm::ModelInfo;
use kickoff_core::{AiSettings, Secret, Task};
use kickoff_providers::presets::{Access, Auth, Confidence, LlmPreset};
use serde::Serialize;
use tauri::State;
use ts_rs::TS;

use super::blocking;
use crate::ai::{OllamaInfo, ProviderTest, TaskCheck};
use crate::error::CmdResult;
use crate::keys::clean_secret;
use crate::state::AppState;

/// One provider preset, as the UI needs it (signup links, free-tier facts).
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct ProviderSummary {
    pub id: String,
    pub kind: KeyKind,
    pub display_name: String,
    /// `free`, `free_credit`, `free_promo`, `trial`, `paid` or `local`.
    pub access: String,
    /// Usable without paying or entering a card.
    pub free: bool,
    pub card_required: bool,
    pub needs_key: bool,
    pub key_env: Option<String>,
    pub signup_url: Option<String>,
    pub docs_url: Option<String>,
    pub privacy_note: Option<String>,
    pub notes: Option<String>,
    /// How the free-tier facts were verified: `high`, `medium` or `low`.
    pub confidence: String,
    pub verified_at: String,
    pub rpm: Option<u32>,
    pub rpd: Option<u32>,
    pub tpd: Option<u32>,
}

fn access_str(a: Access) -> &'static str {
    match a {
        Access::Free => "free",
        Access::FreeCredit => "free_credit",
        Access::FreePromo => "free_promo",
        Access::Trial => "trial",
        Access::Paid => "paid",
        Access::Local => "local",
    }
}

fn confidence_str(c: Confidence) -> &'static str {
    match c {
        Confidence::High => "high",
        Confidence::Medium => "medium",
        Confidence::Low => "low",
    }
}

fn llm_summary(p: &LlmPreset) -> ProviderSummary {
    let limits = p.limits_for(&p.default_model).or_else(|| p.limits.last());
    ProviderSummary {
        card_required: p.card_required,
        needs_key: p.needs_key(),
        key_env: p.key_env.clone(),
        signup_url: p.signup_url.clone(),
        docs_url: p.docs_url.clone(),
        privacy_note: p.privacy_note.clone(),
        notes: p.notes.clone(),
        rpm: limits.and_then(|l| l.rpm),
        rpd: limits.and_then(|l| l.rpd),
        tpd: limits.and_then(|l| l.tpd),
        ..base(&p.id, KeyKind::Llm, &p.display_name, p.access, p.confidence, &p.verified_at)
    }
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct PresetCatalog {
    pub llm: Vec<ProviderSummary>,
    pub stt: Vec<ProviderSummary>,
    pub tts: Vec<ProviderSummary>,
    pub search: Vec<ProviderSummary>,
    /// Order the setup wizard recommends free cloud providers in.
    pub wizard_order: Vec<String>,
    pub updated: String,
}

/// Summary fields shared by every preset kind; callers fill in the rest.
fn base(
    id: &str,
    kind: KeyKind,
    name: &str,
    access: Access,
    confidence: Confidence,
    verified_at: &str,
) -> ProviderSummary {
    ProviderSummary {
        id: id.to_owned(),
        kind,
        display_name: name.to_owned(),
        access: access_str(access).into(),
        free: access.is_free(),
        card_required: false,
        needs_key: false,
        key_env: None,
        signup_url: None,
        docs_url: None,
        privacy_note: None,
        notes: None,
        confidence: confidence_str(confidence).into(),
        verified_at: verified_at.to_owned(),
        rpm: None,
        rpd: None,
        tpd: None,
    }
}

#[tauri::command(rename_all = "snake_case")]
pub fn list_presets(state: State<'_, AppState>) -> PresetCatalog {
    preset_catalog(&state.presets)
}

pub fn preset_catalog(p: &kickoff_providers::Presets) -> PresetCatalog {
    PresetCatalog {
        llm: p.llm.iter().map(llm_summary).collect(),
        stt: p
            .stt
            .iter()
            .map(|s| ProviderSummary {
                needs_key: s.auth != Auth::None,
                key_env: s.key_env.clone(),
                notes: s.notes.clone(),
                ..base(&s.id, KeyKind::Stt, &s.display_name, s.access, s.confidence, &s.verified_at)
            })
            .collect(),
        tts: p
            .tts
            .iter()
            .map(|t| ProviderSummary {
                needs_key: t.auth != Auth::None,
                key_env: t.key_env.clone(),
                signup_url: t.signup_url.clone(),
                docs_url: t.docs_url.clone(),
                notes: t.notes.clone(),
                ..base(&t.id, KeyKind::Tts, &t.display_name, t.access, t.confidence, &t.verified_at)
            })
            .collect(),
        search: p
            .search
            .iter()
            .map(|s| ProviderSummary {
                card_required: s.card_required,
                needs_key: s.auth != Auth::None,
                key_env: s.key_env.clone(),
                signup_url: s.signup_url.clone(),
                docs_url: s.docs_url.clone(),
                notes: s.notes.clone(),
                ..base(&s.id, KeyKind::Search, &s.display_name, s.access, s.confidence, &s.verified_at)
            })
            .collect(),
        wizard_order: p.defaults.wizard_order.clone(),
        updated: p.updated.clone(),
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_ai_settings(state: State<'_, AppState>) -> CmdResult<AiSettings> {
    let ai = state.ai.clone();
    blocking(move || Ok(ai.settings()?)).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn save_ai_settings(state: State<'_, AppState>, settings: AiSettings) -> CmdResult<AiSettings> {
    let ai = state.ai.clone();
    blocking(move || {
        ai.save_settings(&settings)?;
        Ok(settings)
    })
    .await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn list_models(state: State<'_, AppState>, provider_id: String, refresh: bool) -> CmdResult<Vec<ModelInfo>> {
    Ok(state.ai.models(&provider_id, refresh).await?)
}

/// Test a provider with a real request. `secret` tests a key before it is
/// saved (setup wizard); without it the library key (or no key, for Ollama) is used.
#[tauri::command(rename_all = "snake_case")]
pub async fn test_provider(
    state: State<'_, AppState>,
    provider_id: String,
    secret: Option<String>,
) -> CmdResult<ProviderTest> {
    let secret: Option<Secret> = secret.map(|s| clean_secret(&s)).transpose()?;
    Ok(state.ai.test_provider(&provider_id, secret).await)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn detect_ollama(state: State<'_, AppState>) -> CmdResult<OllamaInfo> {
    Ok(state.ai.detect_ollama().await)
}

/// Today's usage per LLM provider, next to its known free-tier limits.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct UsageSummary {
    pub provider_id: String,
    pub display_name: String,
    #[ts(type = "number")]
    pub calls: u64,
    #[ts(type = "number")]
    pub tokens: u64,
    #[ts(type = "number")]
    pub rate_limited: u64,
    pub rpd: Option<u32>,
    pub tpd: Option<u32>,
}

#[tauri::command(rename_all = "snake_case")]
pub async fn usage_today(state: State<'_, AppState>) -> CmdResult<Vec<UsageSummary>> {
    let db = state.db.clone();
    let rows = blocking(move || Ok(db.usage_for_day(&kickoff_store::time::local_day())?)).await?;
    let chain: Vec<String> =
        state.ai.settings().map(|s| s.fallback_chain.into_iter().map(|r| r.provider_id).collect()).unwrap_or_default();
    let mut out = Vec::new();
    for preset in &state.presets.llm {
        let row = rows.iter().find(|r| r.provider_id == preset.id);
        if row.is_none() && !chain.contains(&preset.id) {
            continue;
        }
        let summary = llm_summary(preset);
        out.push(UsageSummary {
            provider_id: preset.id.clone(),
            display_name: preset.display_name.clone(),
            calls: row.map_or(0, |r| r.calls),
            tokens: row.map_or(0, |r| r.prompt_tokens + r.completion_tokens),
            rate_limited: row.map_or(0, |r| r.rate_limited),
            rpd: summary.rpd,
            tpd: summary.tpd,
        });
    }
    Ok(out)
}

/// Send a tiny prompt through a task's fallback chain.
#[tauri::command(rename_all = "snake_case")]
pub async fn check_task(state: State<'_, AppState>, task: Task) -> CmdResult<TaskCheck> {
    Ok(state.ai.check_task(task).await)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The browser preview and frontend tests use the real catalog. Like the
    /// ts-rs bindings, this file is regenerated by `cargo test` and CI fails if
    /// the committed copy is stale.
    #[test]
    fn export_preset_catalog_fixture() {
        let catalog = preset_catalog(&kickoff_providers::Presets::bundled().unwrap());
        let json = serde_json::to_string_pretty(&catalog).unwrap() + "\n";
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../src/bindings/fixtures/presetCatalog.json");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        if std::fs::read_to_string(&path).ok().as_deref() != Some(json.as_str()) {
            std::fs::write(&path, json).unwrap();
        }
    }

    #[test]
    fn catalog_marks_free_providers() {
        let catalog = preset_catalog(&kickoff_providers::Presets::bundled().unwrap());
        let gemini = catalog.llm.iter().find(|p| p.id == "gemini").unwrap();
        assert!(gemini.free && gemini.needs_key && gemini.signup_url.is_some());
        let cerebras = catalog.llm.iter().find(|p| p.id == "cerebras").unwrap();
        assert!(!cerebras.free && cerebras.card_required);
        let brave = catalog.search.iter().find(|p| p.id == "brave").unwrap();
        assert!(brave.card_required);
    }
}
