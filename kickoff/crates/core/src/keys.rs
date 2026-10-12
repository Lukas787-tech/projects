//! Key library types. The secret values never appear here: these describe
//! keys; the values live in the OS credential store.

use serde::{Deserialize, Serialize};
use ts_rs::TS;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum KeyKind {
    Llm,
    Stt,
    Tts,
    Search,
    Asset,
    Analytics,
    Other,
}

impl KeyKind {
    pub fn as_str(self) -> &'static str {
        match self {
            KeyKind::Llm => "llm",
            KeyKind::Stt => "stt",
            KeyKind::Tts => "tts",
            KeyKind::Search => "search",
            KeyKind::Asset => "asset",
            KeyKind::Analytics => "analytics",
            KeyKind::Other => "other",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "llm" => KeyKind::Llm,
            "stt" => KeyKind::Stt,
            "tts" => KeyKind::Tts,
            "search" => KeyKind::Search,
            "asset" => KeyKind::Asset,
            "analytics" => KeyKind::Analytics,
            "other" => KeyKind::Other,
            _ => return None,
        })
    }
}

/// A key as listed in the library (no secret).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct KeyMeta {
    pub id: String,
    pub label: String,
    pub kind: KeyKind,
    /// Preset id this key belongs to (`gemini`, `tavily`, …), if any.
    pub provider_id: Option<String>,
    /// Environment variable name generated projects use for this key.
    pub env_var: String,
    pub created_at: String,
    pub last_used_at: Option<String>,
    pub last_test_at: Option<String>,
    pub last_test_ok: Option<bool>,
    pub notes: Option<String>,
}

/// Environment variables Claude Code (or the shell around it) reads for its
/// own authentication and networking. Injecting a project key under one of
/// these names would change how Claude Code itself signs in or connects.
const RESERVED_PREFIXES: &[&str] = &["ANTHROPIC_", "CLAUDE_", "AWS_", "GOOGLE_APPLICATION_", "VERTEX_"];
const RESERVED_NAMES: &[&str] =
    &["PATH", "HOME", "USERPROFILE", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "NPM_TOKEN", "NODE_OPTIONS"];

/// Check an env-var name for a key: `UPPER_SNAKE_CASE`, not reserved.
pub fn validate_env_var(name: &str) -> Result<(), String> {
    let valid_chars = name.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_');
    if name.is_empty() || !valid_chars || !name.starts_with(|c: char| c.is_ascii_uppercase()) {
        return Err(format!("`{name}` must be UPPER_SNAKE_CASE, like TAVILY_API_KEY"));
    }
    if name.len() > 64 {
        return Err("environment variable names are limited to 64 characters".into());
    }
    if RESERVED_NAMES.contains(&name) || RESERVED_PREFIXES.iter().any(|p| name.starts_with(p)) {
        return Err(format!(
            "`{name}` is used by Claude Code or your system; choose another name (for example with a KICKOFF_ prefix)"
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_var_rules() {
        assert!(validate_env_var("TAVILY_API_KEY").is_ok());
        assert!(validate_env_var("KICKOFF_ANTHROPIC_KEY").is_ok());
        assert!(validate_env_var("tavily").is_err());
        assert!(validate_env_var("1KEY").is_err());
        assert!(validate_env_var("MY-KEY").is_err());
        assert!(validate_env_var("ANTHROPIC_API_KEY").unwrap_err().contains("Claude Code"));
        assert!(validate_env_var("HTTPS_PROXY").is_err());
    }

    #[test]
    fn kind_round_trip() {
        for kind in [
            KeyKind::Llm,
            KeyKind::Stt,
            KeyKind::Tts,
            KeyKind::Search,
            KeyKind::Asset,
            KeyKind::Analytics,
            KeyKind::Other,
        ] {
            assert_eq!(KeyKind::parse(kind.as_str()), Some(kind));
        }
    }
}
