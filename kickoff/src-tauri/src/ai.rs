//! Turns settings + key library + presets into live LLM routes, and reports
//! router activity (rate limits, fallbacks, usage) to the UI and the meter.

use std::collections::HashMap;
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use kickoff_core::llm::router::RetryPolicy;
use kickoff_core::llm::{
    ChatMessage, ChatRequest, LlmError, LlmProvider, ModelInfo, Route, Router, RouterEvent, RouterObserver, WaitReason,
};
use kickoff_core::{AiSettings, RouteSpec, Secret, Task};
use kickoff_providers::ollama::{Ollama, parameter_billions};
use kickoff_providers::presets::LlmPreset;
use kickoff_providers::{Presets, build_llm};
use kickoff_store::{Database, UsageDelta, time};
use serde::Serialize;
use tauri::{AppHandle, Emitter};
use ts_rs::TS;

use crate::keys::{KeyError, KeyLibrary};

/// Live model lists are refreshed after this long.
const MODEL_CACHE_TTL: Duration = Duration::from_secs(6 * 3600);
/// Local models at least this big are good enough to recommend for interviews.
const CAPABLE_LOCAL_BILLIONS: f64 = 7.0;

#[derive(Debug, thiserror::Error)]
pub enum AiError {
    #[error("unknown provider `{0}`")]
    UnknownProvider(String),
    #[error("{0} needs an API key; add one in the Key Library")]
    MissingKey(String),
    #[error(transparent)]
    Keys(#[from] KeyError),
    #[error(transparent)]
    Store(#[from] kickoff_store::StoreError),
    #[error(transparent)]
    Llm(#[from] LlmError),
}

/// Result of testing a key (or a local provider) with a real request.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct ProviderTest {
    pub ok: bool,
    pub provider_id: String,
    pub model: Option<String>,
    #[ts(type = "number")]
    pub latency_ms: u64,
    /// Machine-readable error category when not ok (`auth`, `quota_exhausted` …).
    pub error_kind: Option<String>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct OllamaModel {
    pub id: String,
    pub parameter_size: Option<String>,
    pub billions: Option<f64>,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct OllamaInfo {
    pub running: bool,
    pub version: Option<String>,
    pub models: Vec<OllamaModel>,
    /// Best installed model for Kickoff, if any.
    pub recommended_model: Option<String>,
    /// True when an installed model is big enough to recommend over cloud providers.
    pub capable: bool,
}

/// Router activity, pushed to the UI as `ai-status` events.
#[derive(Debug, Clone, Serialize, TS)]
#[serde(tag = "type", rename_all = "snake_case")]
#[ts(export)]
pub enum AiStatus {
    Waiting {
        provider_id: String,
        model: String,
        #[ts(type = "number")]
        seconds: u64,
        reason: String,
    },
    FellBack {
        from_provider: String,
        to_provider: String,
        reason: String,
    },
    Failed {
        provider_id: String,
        error_kind: String,
        message: String,
    },
}

pub const AI_STATUS_EVENT: &str = "ai-status";

/// Result of sending a tiny prompt through a task's whole fallback chain.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct TaskCheck {
    pub ok: bool,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    /// How many routes the chain has (after skipping ones without keys).
    #[ts(type = "number")]
    pub routes: usize,
    pub message: String,
}

/// Records usage and forwards router events to the frontend.
pub struct AppObserver {
    db: Arc<Database>,
    app: OnceLock<AppHandle>,
}

impl AppObserver {
    pub fn new(db: Arc<Database>) -> Arc<Self> {
        Arc::new(Self { db, app: OnceLock::new() })
    }

    /// Called once the Tauri app exists.
    pub fn attach(&self, app: AppHandle) {
        let _ = self.app.set(app);
    }

    fn record(&self, provider_id: &str, delta: UsageDelta) {
        let db = self.db.clone();
        let provider_id = provider_id.to_owned();
        tauri::async_runtime::spawn_blocking(move || {
            if let Err(e) = db.record_usage(&provider_id, &time::local_day(), delta) {
                tracing::warn!(error = %e, "could not record usage");
            }
        });
    }

    fn emit(&self, status: AiStatus) {
        if let Some(app) = self.app.get() {
            let _ = app.emit(AI_STATUS_EVENT, status);
        }
    }
}

impl RouterObserver for AppObserver {
    fn on_event(&self, event: &RouterEvent) {
        match event {
            RouterEvent::Completed { provider_id, usage, .. } => self.record(
                provider_id,
                UsageDelta {
                    calls: 1,
                    prompt_tokens: usage.prompt_tokens,
                    completion_tokens: usage.completion_tokens,
                    ..Default::default()
                },
            ),
            RouterEvent::Failed { provider_id, error_kind, message, .. } => {
                if matches!(*error_kind, "rate_limited" | "quota_exhausted") {
                    self.record(provider_id, UsageDelta { calls: 1, rate_limited: 1, ..Default::default() });
                }
                tracing::info!(provider = %provider_id, kind = error_kind, %message, "AI call failed");
                self.emit(AiStatus::Failed {
                    provider_id: provider_id.clone(),
                    error_kind: (*error_kind).to_owned(),
                    message: message.clone(),
                });
            }
            RouterEvent::Waiting { provider_id, model, seconds, reason } => self.emit(AiStatus::Waiting {
                provider_id: provider_id.clone(),
                model: model.clone(),
                seconds: *seconds,
                reason: match reason {
                    WaitReason::RateLimited => "rate_limited",
                    WaitReason::Throttled => "throttled",
                    WaitReason::Retrying => "retrying",
                }
                .to_owned(),
            }),
            RouterEvent::FellBack { from_provider, to_provider, reason, .. } => self.emit(AiStatus::FellBack {
                from_provider: from_provider.clone(),
                to_provider: to_provider.clone(),
                reason: reason.clone(),
            }),
        }
    }
}

pub struct AiService {
    db: Arc<Database>,
    presets: Arc<Presets>,
    keys: Arc<KeyLibrary>,
    router: Router,
    models: tokio::sync::Mutex<HashMap<String, (Instant, Vec<ModelInfo>)>>,
}

impl AiService {
    pub fn new(
        db: Arc<Database>,
        presets: Arc<Presets>,
        keys: Arc<KeyLibrary>,
        observer: Arc<dyn RouterObserver>,
    ) -> Self {
        Self {
            db,
            presets,
            keys,
            router: Router::new(RetryPolicy::default(), observer),
            models: tokio::sync::Mutex::new(HashMap::new()),
        }
    }

    pub fn router(&self) -> &Router {
        &self.router
    }

    /// Send a one-word prompt through the task's chain, exactly as real work
    /// would go (throttling, retries, fallback).
    pub async fn check_task(&self, task: Task) -> TaskCheck {
        let routes = match self.routes(task).await {
            Ok(r) => r,
            Err(e) => {
                return TaskCheck { ok: false, provider_id: None, model: None, routes: 0, message: e.to_string() };
            }
        };
        if routes.is_empty() {
            return TaskCheck {
                ok: false,
                provider_id: None,
                model: None,
                routes: 0,
                message: "No AI provider is ready for this task. Add a key in the Key Library or start Ollama.".into(),
            };
        }
        let request = ChatRequest {
            max_tokens: Some(512),
            ..ChatRequest::new(vec![ChatMessage::user("Reply with the single word OK.")])
        };
        match self.router.chat(&routes, &request).await {
            Ok(answer) => TaskCheck {
                ok: true,
                message: format!("Answered by {} ({}).", answer.provider_id, answer.response.model),
                provider_id: Some(answer.provider_id),
                model: Some(answer.response.model),
                routes: routes.len(),
            },
            Err(e) => {
                TaskCheck { ok: false, provider_id: None, model: None, routes: routes.len(), message: e.to_string() }
            }
        }
    }

    /// Saved AI settings, or the preset defaults before the user has chosen.
    pub fn settings(&self) -> Result<AiSettings, AiError> {
        let saved: Option<AiSettings> = self.db.get_setting(AiSettings::KEY)?;
        Ok(saved.unwrap_or_else(|| AiSettings {
            fallback_chain: self.presets.defaults.fallback_chain.iter().map(|id| RouteSpec::auto(id)).collect(),
            ..Default::default()
        }))
    }

    pub fn save_settings(&self, settings: &AiSettings) -> Result<(), AiError> {
        for spec in settings.fallback_chain.iter().chain(Task::ALL.iter().filter_map(|t| settings.primary(*t))) {
            self.preset(&spec.provider_id)?;
        }
        self.db.set_setting(AiSettings::KEY, settings)?;
        Ok(())
    }

    fn preset(&self, provider_id: &str) -> Result<&LlmPreset, AiError> {
        self.presets.llm(provider_id).ok_or_else(|| AiError::UnknownProvider(provider_id.to_owned()))
    }

    /// A live client for a provider, using its key from the library.
    pub fn provider(&self, provider_id: &str) -> Result<Arc<dyn LlmProvider>, AiError> {
        let preset = self.preset(provider_id)?;
        let key = if preset.needs_key() { self.keys.secret_for_provider(provider_id)?.map(|(_, s)| s) } else { None };
        build_llm(preset, key).map_err(|_| AiError::MissingKey(preset.display_name.clone()))
    }

    /// Live model list, cached for a few hours.
    pub async fn models(&self, provider_id: &str, refresh: bool) -> Result<Vec<ModelInfo>, AiError> {
        if !refresh
            && let Some((at, list)) = self.models.lock().await.get(provider_id)
            && at.elapsed() < MODEL_CACHE_TTL
        {
            return Ok(list.clone());
        }
        let provider = self.provider(provider_id)?;
        let list = provider.list_models().await?;
        self.models.lock().await.insert(provider_id.to_owned(), (Instant::now(), list.clone()));
        Ok(list)
    }

    async fn resolve_model(&self, preset: &LlmPreset, wanted: Option<&str>) -> Option<String> {
        if let Some(model) = wanted.filter(|m| !m.is_empty()) {
            return Some(model.to_owned());
        }
        if preset.model_list
            && let Ok(list) = self.models(&preset.id, false).await
        {
            let ids: Vec<String> = list.into_iter().map(|m| m.id).collect();
            if let Some(m) = preset.pick_model(&ids) {
                return Some(m);
            }
        }
        (!preset.default_model.is_empty()).then(|| preset.default_model.clone())
    }

    /// The routes for a task, skipping providers that have no key or no model.
    pub async fn routes(&self, task: Task) -> Result<Vec<Route>, AiError> {
        let mut routes = Vec::new();
        for spec in self.settings()?.chain_for(task) {
            let Ok(preset) = self.preset(&spec.provider_id) else { continue };
            let provider = match self.provider(&spec.provider_id) {
                Ok(p) => p,
                Err(e) => {
                    tracing::debug!(provider = %spec.provider_id, error = %e, "skipping route");
                    continue;
                }
            };
            let Some(model) = self.resolve_model(preset, spec.model.as_deref()).await else {
                tracing::debug!(provider = %spec.provider_id, "no model available; skipping route");
                continue;
            };
            let rpm = preset.limits_for(&model).and_then(|l| l.rpm);
            routes.push(Route { provider, model, rpm });
        }
        Ok(routes)
    }

    /// Check a provider works with a real (tiny) request. `key` overrides the
    /// library key, for testing a key before saving it.
    pub async fn test_provider(&self, provider_id: &str, key: Option<Secret>) -> ProviderTest {
        let started = Instant::now();
        let result = self.run_test(provider_id, key).await;
        let latency_ms = started.elapsed().as_millis() as u64;
        match result {
            Ok(model) => ProviderTest {
                ok: true,
                provider_id: provider_id.to_owned(),
                message: format!("Working: {model} answered in {:.1} s.", latency_ms as f64 / 1000.0),
                model: Some(model),
                latency_ms,
                error_kind: None,
            },
            // A 429 means the key authenticated; the provider is just busy.
            Err((model, AiError::Llm(e @ LlmError::RateLimited { .. }))) => ProviderTest {
                ok: e.kind() == "rate_limited",
                provider_id: provider_id.to_owned(),
                model,
                latency_ms,
                error_kind: Some(e.kind().to_owned()),
                message: if e.kind() == "rate_limited" {
                    "The key works, but the provider is rate limiting right now.".into()
                } else {
                    format!("The key works, but today's free quota is used up: {e}")
                },
            },
            Err((model, e)) => ProviderTest {
                ok: false,
                provider_id: provider_id.to_owned(),
                model,
                latency_ms,
                error_kind: Some(match &e {
                    AiError::Llm(l) => l.kind().to_owned(),
                    AiError::MissingKey(_) => "missing_key".to_owned(),
                    _ => "error".to_owned(),
                }),
                message: friendly_error(&e),
            },
        }
    }

    async fn run_test(&self, provider_id: &str, key: Option<Secret>) -> Result<String, (Option<String>, AiError)> {
        let preset = self.preset(provider_id).map_err(|e| (None, e))?;
        let key = match key {
            Some(k) => Some(k),
            None if preset.needs_key() => {
                self.keys.secret_for_provider(provider_id).map_err(|e| (None, e.into()))?.map(|(_, s)| s)
            }
            None => None,
        };
        let provider = build_llm(preset, key).map_err(|_| (None, AiError::MissingKey(preset.display_name.clone())))?;

        let mut model = None;
        if preset.model_list {
            let list = provider.list_models().await.map_err(|e| (None, e.into()))?;
            let ids: Vec<String> = list.iter().map(|m| m.id.clone()).collect();
            model = preset.pick_model(&ids);
            self.models.lock().await.insert(provider_id.to_owned(), (Instant::now(), list));
        }
        let model =
            model.or_else(|| (!preset.default_model.is_empty()).then(|| preset.default_model.clone())).ok_or_else(
                || (None, LlmError::ModelNotFound("no suitable model is available for this key".into()).into()),
            )?;

        let request = ChatRequest {
            model: model.clone(),
            // Reasoning models spend tokens before answering; leave room.
            max_tokens: Some(512),
            ..ChatRequest::new(vec![ChatMessage::user("Reply with the single word OK.")])
        };
        provider.chat(&request).await.map_err(|e| (Some(model.clone()), e.into()))?;
        Ok(model)
    }

    pub async fn detect_ollama(&self) -> OllamaInfo {
        let Some(preset) = self.presets.llm("ollama") else {
            return OllamaInfo {
                running: false,
                version: None,
                models: vec![],
                recommended_model: None,
                capable: false,
            };
        };
        let ollama = Ollama::new(&preset.id, &preset.base_url);
        match ollama.detect().await {
            Err(_) => {
                OllamaInfo { running: false, version: None, models: vec![], recommended_model: None, capable: false }
            }
            Ok(status) => {
                let models: Vec<OllamaModel> = status
                    .models
                    .iter()
                    .map(|m| OllamaModel {
                        id: m.id.clone(),
                        parameter_size: m.parameter_size.clone(),
                        billions: m.parameter_size.as_deref().and_then(parameter_billions),
                    })
                    .collect();
                let capable_ids: Vec<String> = models
                    .iter()
                    .filter(|m| m.billions.is_some_and(|b| b >= CAPABLE_LOCAL_BILLIONS))
                    .map(|m| m.id.clone())
                    .collect();
                let all_ids: Vec<String> = models.iter().map(|m| m.id.clone()).collect();
                let recommended_model = preset.pick_model(&capable_ids).or_else(|| preset.pick_model(&all_ids));
                self.models.lock().await.insert(preset.id.clone(), (Instant::now(), status.models));
                OllamaInfo {
                    running: true,
                    version: Some(status.version),
                    capable: !capable_ids.is_empty(),
                    models,
                    recommended_model,
                }
            }
        }
    }
}

/// Plain-language explanation for the setup wizard and key tests.
fn friendly_error(e: &AiError) -> String {
    match e {
        AiError::Llm(LlmError::Auth(_)) => {
            "The key was rejected. Check that you copied the whole key, and that it's for this provider.".into()
        }
        AiError::Llm(LlmError::Unavailable(_)) | AiError::Llm(LlmError::Network(_)) => {
            "Couldn't reach the provider. Check your internet connection (or that Ollama is running).".into()
        }
        AiError::Llm(LlmError::ModelNotFound(m)) => format!("No usable model: {m}"),
        AiError::Llm(LlmError::Timeout) => "The provider didn't answer in time. Try again.".into(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::testing::RecordingObserver;
    use kickoff_platform::{MemorySecretStore, SecretStore};
    use kickoff_providers::presets::BUNDLED;
    use serde_json::json;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;
    use crate::keys::NewKeyInput;

    /// Bundled presets with Groq and Ollama pointed at a local mock server.
    fn service(groq_url: &str, ollama_url: &str) -> (AiService, Arc<KeyLibrary>) {
        let overrides = format!(
            "[[llm]]\nid = \"groq\"\nbase_url = \"{groq_url}\"\n\n[[llm]]\nid = \"ollama\"\nbase_url = \"{ollama_url}\"\n"
        );
        let presets = Arc::new(Presets::parse(BUNDLED, Some(&overrides)).unwrap());
        let db = Arc::new(Database::open_in_memory().unwrap());
        let secrets: Arc<dyn SecretStore> = Arc::new(MemorySecretStore::new());
        let keys = Arc::new(KeyLibrary::new(db.clone(), secrets));
        (AiService::new(db, presets, keys.clone(), RecordingObserver::new()), keys)
    }

    fn groq_key() -> NewKeyInput {
        NewKeyInput {
            label: "Groq".into(),
            kind: kickoff_core::keys::KeyKind::Llm,
            provider_id: Some("groq".into()),
            env_var: "GROQ_API_KEY".into(),
            secret: "gsk_test_value_0123456789".into(),
            notes: None,
        }
    }

    async fn mock_groq() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/models"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [
                {"id": "openai/gpt-oss-20b"}, {"id": "openai/gpt-oss-120b"}, {"id": "whisper-large-v3-turbo"}
            ]})))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/chat/completions"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "model": "openai/gpt-oss-120b",
                "choices": [{"message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 1}
            })))
            .mount(&server)
            .await;
        server
    }

    #[tokio::test]
    async fn default_settings_follow_presets() {
        let (ai, _) = service("http://127.0.0.1:9", "http://127.0.0.1:9");
        let ids: Vec<_> = ai.settings().unwrap().fallback_chain.into_iter().map(|r| r.provider_id).collect();
        assert_eq!(ids, ["gemini", "groq", "openrouter", "ollama"]);
        let bad = AiSettings { fallback_chain: vec![RouteSpec::auto("nope")], ..Default::default() };
        assert!(matches!(ai.save_settings(&bad), Err(AiError::UnknownProvider(_))));
    }

    #[tokio::test]
    async fn no_keys_and_no_ollama_means_no_routes() {
        let (ai, _) = service("http://127.0.0.1:9", "http://127.0.0.1:9");
        assert!(ai.routes(Task::Interview).await.unwrap().is_empty());
        let check = ai.check_task(Task::Interview).await;
        assert!(!check.ok);
        assert!(check.message.contains("Key Library"));
    }

    #[tokio::test]
    async fn task_check_runs_through_the_chain_with_a_saved_key() {
        let groq = mock_groq().await;
        let (ai, keys) = service(&groq.uri(), "http://127.0.0.1:9");
        keys.add(groq_key()).unwrap();

        let routes = ai.routes(Task::Interview).await.unwrap();
        assert_eq!(routes.len(), 1, "only Groq has a key; Ollama isn't running");
        assert_eq!(routes[0].model, "openai/gpt-oss-120b", "preference picks the bigger gpt-oss");
        assert_eq!(routes[0].rpm, Some(30));

        let check = ai.check_task(Task::Interview).await;
        assert!(check.ok, "{}", check.message);
        assert_eq!(check.provider_id.as_deref(), Some("groq"));
    }

    #[tokio::test]
    async fn testing_a_key_before_saving_it() {
        let groq = mock_groq().await;
        let (ai, _) = service(&groq.uri(), "http://127.0.0.1:9");
        let result = ai.test_provider("groq", Some(Secret::new("gsk_candidate_123456789"))).await;
        assert!(result.ok, "{}", result.message);
        assert_eq!(result.model.as_deref(), Some("openai/gpt-oss-120b"));

        let missing = ai.test_provider("gemini", None).await;
        assert!(!missing.ok);
        assert_eq!(missing.error_kind.as_deref(), Some("missing_key"));
    }

    #[tokio::test]
    async fn rejected_key_gets_a_plain_explanation() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(401).set_body_json(json!({"error": {"message": "Invalid API Key"}})))
            .mount(&server)
            .await;
        let (ai, _) = service(&server.uri(), "http://127.0.0.1:9");
        let result = ai.test_provider("groq", Some(Secret::new("gsk_wrong_000000000000"))).await;
        assert!(!result.ok);
        assert_eq!(result.error_kind.as_deref(), Some("auth"));
        assert!(result.message.contains("rejected"));
    }

    #[tokio::test]
    async fn detects_ollama_and_recommends_a_capable_model() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/version"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"version": "0.12.0"})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/tags"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"models": [
                {"name": "llama3.2:3b", "details": {"parameter_size": "3.2B"}},
                {"name": "qwen3:8b", "details": {"parameter_size": "8.2B"}}
            ]})))
            .mount(&server)
            .await;
        let (ai, _) = service("http://127.0.0.1:9", &server.uri());
        let info = ai.detect_ollama().await;
        assert!(info.running && info.capable);
        assert_eq!(info.recommended_model.as_deref(), Some("qwen3:8b"));

        let (ai, _) = service("http://127.0.0.1:9", "http://127.0.0.1:9");
        assert!(!ai.detect_ollama().await.running);
    }
}
