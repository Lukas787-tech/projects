//! Build a live provider from a preset and (if needed) a key.

use std::sync::Arc;

use kickoff_core::llm::LlmProvider;
use kickoff_core::{APP_NAME, Secret};

use crate::anthropic::Anthropic;
use crate::ollama::Ollama;
use crate::openai_compat::OpenAiCompat;
use crate::presets::{LlmKind, LlmPreset};

#[derive(Debug, thiserror::Error)]
pub enum FactoryError {
    #[error("{0} needs an API key; add one in the Key Library")]
    MissingKey(String),
}

pub fn build_llm(preset: &LlmPreset, key: Option<Secret>) -> Result<Arc<dyn LlmProvider>, FactoryError> {
    let key = key.filter(|k| !k.is_empty());
    if preset.needs_key() && key.is_none() {
        return Err(FactoryError::MissingKey(preset.display_name.clone()));
    }
    Ok(match preset.kind {
        LlmKind::OpenaiCompat => {
            let headers =
                preset.extra_headers.iter().map(|(k, v)| (k.clone(), v.replace("{app_name}", APP_NAME))).collect();
            Arc::new(OpenAiCompat::new(&preset.id, &preset.base_url, key, preset.json_mode, headers))
        }
        LlmKind::Ollama => Arc::new(Ollama::new(&preset.id, &preset.base_url)),
        LlmKind::Anthropic => Arc::new(Anthropic::new(&preset.id, &preset.base_url, key.expect("checked above"))),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Presets;

    #[test]
    fn builds_every_bundled_llm_preset() {
        let presets = Presets::bundled().unwrap();
        for preset in &presets.llm {
            let key = preset.needs_key().then(|| Secret::new("test-key-123456"));
            let provider = build_llm(preset, key).unwrap();
            assert_eq!(provider.id(), preset.id);
        }
    }

    #[test]
    fn missing_key_is_reported() {
        let presets = Presets::bundled().unwrap();
        let err = build_llm(presets.llm("gemini").unwrap(), None).err().unwrap();
        assert!(err.to_string().contains("Google Gemini"));
        assert!(build_llm(presets.llm("ollama").unwrap(), None).is_ok());
    }
}
