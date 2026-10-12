//! Provider presets loaded from `data/providers.toml`.
//!
//! The bundled file is compiled in. A user override file can replace fields of
//! any entry (matched by `id`) or add new entries, so free-tier changes can be
//! picked up without a new build.

use std::cmp::Ordering;
use std::collections::HashSet;

use globset::{Glob, GlobMatcher};
use serde::{Deserialize, Deserializer};

/// The presets file shipped with the app.
pub const BUNDLED: &str = include_str!("../../../data/providers.toml");

#[derive(Debug, thiserror::Error)]
pub enum PresetsError {
    #[error("presets file is not valid TOML: {0}")]
    Parse(#[from] toml::de::Error),
    #[error("invalid presets: {0}")]
    Invalid(String),
}

#[derive(Debug, Clone, Deserialize)]
pub struct Presets {
    pub schema_version: u32,
    pub updated: String,
    pub defaults: Defaults,
    #[serde(default)]
    pub llm: Vec<LlmPreset>,
    #[serde(default)]
    pub stt: Vec<SttPreset>,
    #[serde(default)]
    pub tts: Vec<TtsPreset>,
    #[serde(default)]
    pub search: Vec<SearchPreset>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Defaults {
    pub wizard_order: Vec<String>,
    pub fallback_chain: Vec<String>,
    pub search: String,
    pub stt: String,
    pub tts: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LlmKind {
    OpenaiCompat,
    Ollama,
    Anthropic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Access {
    /// Free tier, no card.
    Free,
    /// Recurring free credit, usually behind a card.
    FreeCredit,
    /// A promotional free offer that may end.
    FreePromo,
    /// Time-limited trial.
    Trial,
    Paid,
    Local,
}

impl Access {
    /// Usable without paying or entering a card.
    pub fn is_free(self) -> bool {
        matches!(self, Access::Free | Access::FreePromo | Access::Local)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum JsonMode {
    None,
    JsonObject,
    JsonSchema,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Confidence {
    High,
    Medium,
    Low,
}

/// How a key is sent: `bearer`, `x_api_key`, `none`, or `header:<Name>`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Auth {
    None,
    Bearer,
    XApiKey,
    Header(String),
}

impl<'de> Deserialize<'de> for Auth {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let s = String::deserialize(d)?;
        match s.as_str() {
            "none" => Ok(Auth::None),
            "bearer" => Ok(Auth::Bearer),
            "x_api_key" => Ok(Auth::XApiKey),
            other => match other.strip_prefix("header:") {
                Some(name) if !name.is_empty() => Ok(Auth::Header(name.to_owned())),
                _ => Err(serde::de::Error::custom(format!("unknown auth style `{other}`"))),
            },
        }
    }
}

fn default_auth() -> Auth {
    Auth::None
}

/// Free-tier limits for the models matching `models` (a glob).
#[derive(Debug, Clone, Deserialize)]
pub struct LimitRule {
    pub models: String,
    pub rpm: Option<u32>,
    pub rpd: Option<u32>,
    pub tpm: Option<u32>,
    pub tpd: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelFilter {
    /// Only model ids ending in `:free` (OpenRouter).
    FreeOnly,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LlmPreset {
    pub id: String,
    pub display_name: String,
    pub kind: LlmKind,
    pub base_url: String,
    #[serde(default = "default_auth")]
    pub auth: Auth,
    pub key_env: Option<String>,
    pub signup_url: Option<String>,
    pub docs_url: Option<String>,
    pub access: Access,
    #[serde(default)]
    pub card_required: bool,
    pub json_mode: JsonMode,
    #[serde(default)]
    pub streaming: bool,
    #[serde(default)]
    pub model_list: bool,
    pub model_filter: Option<ModelFilter>,
    #[serde(default)]
    pub model_preference: Vec<String>,
    #[serde(default)]
    pub model_exclude: Vec<String>,
    #[serde(default)]
    pub default_model: String,
    #[serde(default)]
    pub extra_headers: std::collections::BTreeMap<String, String>,
    pub privacy_note: Option<String>,
    pub notes: Option<String>,
    #[serde(default)]
    pub limits: Vec<LimitRule>,
    pub confidence: Confidence,
    pub verified_at: String,
}

impl LlmPreset {
    pub fn needs_key(&self) -> bool {
        self.auth != Auth::None
    }

    /// The first limit rule whose glob matches `model`.
    pub fn limits_for(&self, model: &str) -> Option<&LimitRule> {
        self.limits.iter().find(|rule| glob(&rule.models).is_some_and(|g| g.is_match(model)))
    }

    /// Pick the best model from a live model list.
    ///
    /// Models are filtered by `model_filter` and `model_exclude`, then the first
    /// `model_preference` pattern with any match wins; among several matches the
    /// highest version (natural sort) is taken, so `gemini-3.5-flash` beats
    /// `gemini-3-flash`. Falls back to `default_model` if it is available.
    pub fn pick_model(&self, available: &[String]) -> Option<String> {
        let excluded: Vec<GlobMatcher> = self.model_exclude.iter().filter_map(|p| glob(p)).collect();
        let candidates: Vec<&String> = available
            .iter()
            .filter(|m| match self.model_filter {
                Some(ModelFilter::FreeOnly) => m.ends_with(":free"),
                None => true,
            })
            .filter(|m| !excluded.iter().any(|g| g.is_match(m.as_str())))
            .collect();

        for pattern in &self.model_preference {
            let Some(g) = glob(pattern) else { continue };
            if let Some(best) = candidates.iter().filter(|m| g.is_match(m.as_str())).max_by(|a, b| natural_cmp(a, b)) {
                return Some((*best).clone());
            }
        }
        if !self.default_model.is_empty() && candidates.iter().any(|m| **m == self.default_model) {
            return Some(self.default_model.clone());
        }
        None
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct WhisperModel {
    pub name: String,
    pub size_mb: u32,
    pub sha1: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SttLimits {
    pub rpm: Option<u32>,
    pub rpd: Option<u32>,
    pub audio_seconds_per_day: Option<u32>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SttPreset {
    pub id: String,
    pub display_name: String,
    pub kind: String,
    pub access: Access,
    pub base_url: Option<String>,
    #[serde(default = "default_auth")]
    pub auth: Auth,
    pub key_env: Option<String>,
    pub model_url_template: Option<String>,
    pub default_model: String,
    #[serde(default)]
    pub models: Vec<WhisperModel>,
    pub max_upload_mb: Option<u32>,
    pub limits: Option<SttLimits>,
    pub notes: Option<String>,
    pub confidence: Confidence,
    pub verified_at: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TtsPreset {
    pub id: String,
    pub display_name: String,
    pub kind: String,
    pub access: Access,
    pub base_url: Option<String>,
    pub ws_url: Option<String>,
    #[serde(default = "default_auth")]
    pub auth: Auth,
    pub key_env: Option<String>,
    pub signup_url: Option<String>,
    pub docs_url: Option<String>,
    #[serde(default)]
    pub models: Vec<String>,
    pub usage_unit: Option<String>,
    pub monthly_cap: Option<u64>,
    /// Models that need no paid credits.
    #[serde(default)]
    pub free_models: Vec<String>,
    pub binary_url_windows: Option<String>,
    pub binary_sha256_windows: Option<String>,
    pub binary_size_windows: Option<u64>,
    /// Path of the program inside the downloaded archive.
    pub binary_path_in_archive: Option<String>,
    pub binary_license: Option<String>,
    pub voices_base_url: Option<String>,
    pub default_voice: Option<String>,
    pub notes: Option<String>,
    pub confidence: Confidence,
    pub verified_at: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SearchPreset {
    pub id: String,
    pub display_name: String,
    pub kind: String,
    pub endpoint: String,
    #[serde(default = "default_auth")]
    pub auth: Auth,
    pub key_env: Option<String>,
    pub signup_url: Option<String>,
    pub docs_url: Option<String>,
    pub access: Access,
    #[serde(default)]
    pub card_required: bool,
    pub monthly_free_units: Option<u32>,
    pub unit: Option<String>,
    #[serde(default)]
    pub attribution_required: bool,
    pub notes: Option<String>,
    pub confidence: Confidence,
    pub verified_at: String,
}

impl Presets {
    /// The bundled presets.
    pub fn bundled() -> Result<Self, PresetsError> {
        Self::parse(BUNDLED, None)
    }

    /// Parse presets, applying an optional override document on top.
    pub fn parse(base: &str, overrides: Option<&str>) -> Result<Self, PresetsError> {
        let mut value: toml::Table = toml::from_str(base)?;
        if let Some(over) = overrides {
            let over: toml::Table = toml::from_str(over)?;
            merge_presets(&mut value, over);
        }
        let presets: Presets = toml::Value::Table(value).try_into()?;
        presets.validate()?;
        Ok(presets)
    }

    pub fn llm(&self, id: &str) -> Option<&LlmPreset> {
        self.llm.iter().find(|p| p.id == id)
    }

    pub fn tts(&self, id: &str) -> Option<&TtsPreset> {
        self.tts.iter().find(|p| p.id == id)
    }

    pub fn stt(&self, id: &str) -> Option<&SttPreset> {
        self.stt.iter().find(|p| p.id == id)
    }

    pub fn search(&self, id: &str) -> Option<&SearchPreset> {
        self.search.iter().find(|p| p.id == id)
    }

    fn validate(&self) -> Result<(), PresetsError> {
        fn unique<'a>(section: &str, ids: impl Iterator<Item = &'a str>) -> Result<(), PresetsError> {
            let mut seen = HashSet::new();
            for id in ids {
                if !seen.insert(id) {
                    return Err(PresetsError::Invalid(format!("duplicate {section} id `{id}`")));
                }
            }
            Ok(())
        }
        unique("llm", self.llm.iter().map(|p| p.id.as_str()))?;
        unique("stt", self.stt.iter().map(|p| p.id.as_str()))?;
        unique("tts", self.tts.iter().map(|p| p.id.as_str()))?;
        unique("search", self.search.iter().map(|p| p.id.as_str()))?;

        let missing =
            |section: &str, id: &str| PresetsError::Invalid(format!("defaults refer to unknown {section} `{id}`"));
        for id in self.defaults.fallback_chain.iter().chain(&self.defaults.wizard_order) {
            self.llm(id).ok_or_else(|| missing("llm", id))?;
        }
        self.search(&self.defaults.search).ok_or_else(|| missing("search", &self.defaults.search))?;
        self.stt(&self.defaults.stt).ok_or_else(|| missing("stt", &self.defaults.stt))?;
        for id in &self.defaults.tts {
            self.tts(id).ok_or_else(|| missing("tts", id))?;
        }

        for p in &self.llm {
            let patterns = p.model_preference.iter().chain(&p.model_exclude).chain(p.limits.iter().map(|l| &l.models));
            for pattern in patterns {
                if glob(pattern).is_none() {
                    return Err(PresetsError::Invalid(format!("llm `{}` has invalid glob `{pattern}`", p.id)));
                }
            }
            if p.needs_key() && p.key_env.is_none() {
                return Err(PresetsError::Invalid(format!("llm `{}` needs a key but has no key_env", p.id)));
            }
        }
        Ok(())
    }
}

/// Merge `over` into `base`: top-level tables merge key by key; arrays of
/// tables (`llm`, `tts`, …) merge entries with the same `id` and append new ones.
fn merge_presets(base: &mut toml::Table, over: toml::Table) {
    for (key, over_value) in over {
        match (base.get_mut(&key), over_value) {
            (Some(toml::Value::Table(b)), toml::Value::Table(o)) => {
                for (k, v) in o {
                    b.insert(k, v);
                }
            }
            (Some(toml::Value::Array(b)), toml::Value::Array(o)) => {
                for entry in o {
                    let id = entry.get("id").and_then(toml::Value::as_str).map(str::to_owned);
                    let existing = id
                        .as_deref()
                        .and_then(|id| b.iter_mut().find(|e| e.get("id").and_then(toml::Value::as_str) == Some(id)));
                    match (existing, entry) {
                        (Some(toml::Value::Table(e)), toml::Value::Table(fields)) => {
                            for (k, v) in fields {
                                e.insert(k, v);
                            }
                        }
                        (_, entry) => b.push(entry),
                    }
                }
            }
            (_, v) => {
                base.insert(key, v);
            }
        }
    }
}

fn glob(pattern: &str) -> Option<GlobMatcher> {
    Glob::new(pattern).ok().map(|g| g.compile_matcher())
}

/// Compare strings so that digit runs compare numerically: `a-10` > `a-9`.
fn natural_cmp(a: &str, b: &str) -> Ordering {
    fn chunks(s: &str) -> Vec<(bool, &str)> {
        let mut out = Vec::new();
        let mut start = 0;
        let bytes = s.as_bytes();
        for i in 1..=bytes.len() {
            if i == bytes.len() || bytes[i].is_ascii_digit() != bytes[start].is_ascii_digit() {
                out.push((bytes[start].is_ascii_digit(), &s[start..i]));
                start = i;
            }
        }
        out
    }
    let (ca, cb) = (chunks(a), chunks(b));
    for (x, y) in ca.iter().zip(&cb) {
        let ord = match (x, y) {
            ((true, xs), (true, ys)) => {
                xs.trim_start_matches('0').len().cmp(&ys.trim_start_matches('0').len()).then_with(|| xs.cmp(ys))
            }
            ((_, xs), (_, ys)) => xs.cmp(ys),
        };
        if ord != Ordering::Equal {
            return ord;
        }
    }
    ca.len().cmp(&cb.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn models(ids: &[&str]) -> Vec<String> {
        ids.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn bundled_presets_parse_and_validate() {
        let p = Presets::bundled().unwrap();
        assert_eq!(p.schema_version, 1);
        assert!(p.llm("gemini").is_some());
        assert!(p.llm("ollama").is_some_and(|o| !o.needs_key()));
        assert!(p.tts("fish_audio").is_some());
        // Every provider in the default chain must be usable for free.
        for id in &p.defaults.fallback_chain {
            assert!(p.llm(id).unwrap().access.is_free(), "{id} in default chain is not free");
        }
    }

    #[test]
    fn override_replaces_fields_and_adds_entries() {
        let over = r#"
            [defaults]
            fallback_chain = ["groq", "ollama"]

            [[llm]]
            id = "groq"
            default_model = "openai/gpt-oss-20b"

            [[llm]]
            id = "local-lmstudio"
            display_name = "LM Studio"
            kind = "openai_compat"
            base_url = "http://localhost:1234/v1"
            access = "local"
            json_mode = "json_object"
            confidence = "low"
            verified_at = "2026-10-11"
        "#;
        let p = Presets::parse(BUNDLED, Some(over)).unwrap();
        assert_eq!(p.defaults.fallback_chain, ["groq", "ollama"]);
        let groq = p.llm("groq").unwrap();
        assert_eq!(groq.default_model, "openai/gpt-oss-20b");
        assert_eq!(groq.base_url, "https://api.groq.com/openai/v1", "untouched fields survive");
        assert!(p.llm("local-lmstudio").is_some());
        // Bundled search defaults survive an override that doesn't mention them.
        assert_eq!(p.defaults.search, "tavily");
    }

    #[test]
    fn rejects_unknown_ids_in_defaults() {
        let over = "[defaults]\nfallback_chain = [\"nope\"]\n";
        assert!(matches!(Presets::parse(BUNDLED, Some(over)), Err(PresetsError::Invalid(_))));
    }

    #[test]
    fn picks_newest_preferred_model() {
        let p = Presets::bundled().unwrap();
        let gemini = p.llm("gemini").unwrap();
        let live = models(&[
            "gemini-2.5-flash",
            "gemini-3-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.5-pro",
        ]);
        assert_eq!(gemini.pick_model(&live).as_deref(), Some("gemini-3.5-flash"));
        assert_eq!(gemini.pick_model(&models(&["gemini-2.5-flash"])).as_deref(), Some("gemini-2.5-flash"));
        assert_eq!(gemini.pick_model(&models(&["something-else"])), None);
    }

    #[test]
    fn openrouter_only_offers_free_models() {
        let p = Presets::bundled().unwrap();
        let or = p.llm("openrouter").unwrap();
        let live = models(&["vendor/big-model", "vendor/small-model:free"]);
        assert_eq!(or.pick_model(&live).as_deref(), Some("vendor/small-model:free"));
        assert_eq!(or.pick_model(&models(&["vendor/big-model"])), None);
    }

    #[test]
    fn limits_match_by_glob() {
        let p = Presets::bundled().unwrap();
        let groq = p.llm("groq").unwrap();
        assert_eq!(groq.limits_for("openai/gpt-oss-120b").and_then(|l| l.tpm), Some(8000));
        assert_eq!(groq.limits_for("qwen/qwen3").and_then(|l| l.tpm), None);
        assert_eq!(groq.limits_for("qwen/qwen3").and_then(|l| l.rpm), Some(30));
    }

    #[test]
    fn natural_ordering() {
        assert_eq!(natural_cmp("gemini-3.5-flash", "gemini-3-flash"), Ordering::Greater);
        assert_eq!(natural_cmp("model-10", "model-9"), Ordering::Greater);
        assert_eq!(natural_cmp("a", "a"), Ordering::Equal);
    }

    #[test]
    fn auth_parsing() {
        let p = Presets::bundled().unwrap();
        assert_eq!(p.search("brave").unwrap().auth, Auth::Header("X-Subscription-Token".into()));
        assert_eq!(p.llm("anthropic").unwrap().auth, Auth::XApiKey);
    }
}
