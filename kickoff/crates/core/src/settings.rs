//! User-facing app settings.

use serde::{Deserialize, Serialize};
use ts_rs::TS;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Theme {
    #[default]
    Dark,
    Light,
    System,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(default)]
#[ts(export)]
pub struct AppSettings {
    pub theme: Theme,
    /// Where generated projects go. `None` until the user picks one; the UI
    /// suggests the platform default.
    pub projects_dir: Option<String>,
    /// Set when the first-run setup wizard has been completed or skipped.
    pub onboarding_done: bool,
}

impl AppSettings {
    /// Settings key in the database.
    pub const KEY: &'static str = "app";
}

/// The jobs Kickoff asks an AI to do. Each can use a different model, so the
/// best free model can go where quality matters most.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Task {
    /// The producer's side of the conversation.
    Interview,
    /// Turning answers into brief fields (structured output).
    Extraction,
    /// Search queries and reference summaries.
    Research,
    /// Writing the starter kit files.
    Kit,
}

impl Task {
    pub const ALL: [Task; 4] = [Task::Interview, Task::Extraction, Task::Research, Task::Kit];
}

/// One provider + model choice. `model: None` means "pick the best available".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct RouteSpec {
    pub provider_id: String,
    pub model: Option<String>,
}

impl RouteSpec {
    pub fn auto(provider_id: &str) -> Self {
        Self { provider_id: provider_id.to_owned(), model: None }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(default)]
#[ts(export)]
pub struct AiSettings {
    /// Tried in order for every task, after the task's own choice.
    pub fallback_chain: Vec<RouteSpec>,
    pub interview: Option<RouteSpec>,
    pub extraction: Option<RouteSpec>,
    pub research: Option<RouteSpec>,
    pub kit: Option<RouteSpec>,
}

impl AiSettings {
    pub const KEY: &'static str = "ai";

    pub fn primary(&self, task: Task) -> Option<&RouteSpec> {
        match task {
            Task::Interview => self.interview.as_ref(),
            Task::Extraction => self.extraction.as_ref(),
            Task::Research => self.research.as_ref(),
            Task::Kit => self.kit.as_ref(),
        }
    }

    /// The ordered routes for a task: its own choice first, then the
    /// fallback chain, without repeats.
    pub fn chain_for(&self, task: Task) -> Vec<RouteSpec> {
        let mut chain: Vec<RouteSpec> = Vec::new();
        for spec in self.primary(task).into_iter().chain(&self.fallback_chain) {
            if !chain.contains(spec) {
                chain.push(spec.clone());
            }
        }
        chain
    }
}

/// How the user talks to the producer.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum SpeechInput {
    /// whisper.cpp on this computer.
    #[default]
    WhisperLocal,
    /// Groq's hosted Whisper (free tier, uses the Groq key).
    GroqWhisper,
}

/// A chosen voice: its engine-specific id and a name to show.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct VoiceRef {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(default)]
#[ts(export)]
pub struct VoiceSettings {
    /// Off means text only: no microphone, no speech.
    pub enabled: bool,
    pub input: SpeechInput,
    /// Local Whisper model name (`tiny`, `base`, `small` …).
    pub whisper_model: String,
    /// ISO 639-1 language of the user's speech, or `None` to detect it.
    pub language: Option<String>,
    /// Microphone device id, or `None` for the system default.
    pub microphone: Option<String>,
    /// Stop recording automatically when the user stops talking.
    pub hands_free: bool,
    /// Show the transcript for editing instead of sending it straight away.
    pub review_transcript: bool,
    /// Read the producer's replies aloud.
    pub speak_replies: bool,
    /// Speech engines to try, in order (`fish_audio`, `piper`, `system`).
    pub output_order: Vec<String>,
    /// The Fish Audio model found to work for this key.
    pub fish_model: Option<String>,
    pub fish_voice: Option<VoiceRef>,
    /// Piper voice key, e.g. `en_US-lessac-medium`.
    pub piper_voice: String,
    pub system_voice: Option<VoiceRef>,
    /// Monthly Fish Audio budget in UTF-8 bytes of text; warnings at 80% and 95%.
    #[ts(type = "number | null")]
    pub fish_monthly_budget: Option<u64>,
}

impl Default for VoiceSettings {
    fn default() -> Self {
        Self {
            enabled: true,
            input: SpeechInput::WhisperLocal,
            whisper_model: "base".into(),
            language: None,
            microphone: None,
            hands_free: false,
            review_transcript: false,
            speak_replies: true,
            output_order: vec!["fish_audio".into(), "piper".into(), "system".into()],
            fish_model: None,
            fish_voice: None,
            piper_voice: "en_US-lessac-medium".into(),
            system_voice: None,
            fish_monthly_budget: None,
        }
    }
}

impl VoiceSettings {
    pub const KEY: &'static str = "voice";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn voice_defaults_are_free_and_local() {
        let v: VoiceSettings = serde_json::from_str(r#"{"hands_free": true}"#).unwrap();
        assert!(v.hands_free && v.enabled && v.speak_replies);
        assert_eq!(v.input, SpeechInput::WhisperLocal);
        assert_eq!(v.output_order, ["fish_audio", "piper", "system"]);
    }

    #[test]
    fn task_chain_puts_task_choice_first_without_repeats() {
        let settings = AiSettings {
            fallback_chain: vec![RouteSpec::auto("gemini"), RouteSpec::auto("groq"), RouteSpec::auto("ollama")],
            extraction: Some(RouteSpec::auto("groq")),
            ..Default::default()
        };
        let ids = |t| settings.chain_for(t).into_iter().map(|r| r.provider_id).collect::<Vec<_>>();
        assert_eq!(ids(Task::Extraction), ["groq", "gemini", "ollama"]);
        assert_eq!(ids(Task::Interview), ["gemini", "groq", "ollama"]);
    }

    #[test]
    fn defaults_to_dark() {
        assert_eq!(AppSettings::default().theme, Theme::Dark);
    }

    #[test]
    fn missing_fields_take_defaults() {
        let s: AppSettings = serde_json::from_str(r#"{"theme":"light"}"#).unwrap();
        assert_eq!(s.theme, Theme::Light);
        assert!(!s.onboarding_done);
    }
}
