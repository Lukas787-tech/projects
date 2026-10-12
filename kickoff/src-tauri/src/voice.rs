//! Voice for the interview: recording and transcription, reading the
//! producer's replies aloud, and installing the local models and voices that
//! keep it free.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use kickoff_core::voice::{
    AudioSink, Cancel, Narrator, NarratorEvent, SentenceSplitter, SilenceDetector, SpeechToText, StopReason,
    TextToSpeech, VadDecision, VoiceError,
};
use kickoff_core::{SpeechInput, VoiceRef, VoiceSettings};
use kickoff_providers::Presets;
use kickoff_providers::presets::{SttPreset, TtsPreset};
use kickoff_store::{Database, UsageDelta, time};
use kickoff_voice::capture::{self, Recording};
use kickoff_voice::download::{self, Checksum, Download, DownloadError};
use kickoff_voice::playback::Speakers;
use kickoff_voice::stt::CloudWhisper;
use kickoff_voice::tts::{self, FishAudio, Piper, PiperVoice, SystemVoiceTts};
use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tokio::sync::mpsc;
use ts_rs::TS;

use crate::keys::{KeyError, KeyLibrary};

pub const VOICE_EVENT: &str = "voice-status";
/// Longest recording, so a forgotten microphone doesn't run forever.
const MAX_RECORDING: Duration = Duration::from_secs(120);
const PREVIEW_TEXT: &str = "Hi! I'm your producer. Tell me about the game you want to make.";
const BUDGET_WARNINGS: [u8; 2] = [80, 95];

#[derive(Debug, thiserror::Error)]
pub enum VoiceServiceError {
    #[error("{0}")]
    Voice(#[from] VoiceError),
    #[error(transparent)]
    Download(#[from] DownloadError),
    #[error(transparent)]
    Keys(#[from] KeyError),
    #[error(transparent)]
    Store(#[from] kickoff_store::StoreError),
    #[error("{0}")]
    Invalid(String),
    #[error("not recording")]
    NotRecording,
}

type Result<T, E = VoiceServiceError> = std::result::Result<T, E>;

/// Pushed to the UI as `voice-status` events.
#[derive(Debug, Clone, Serialize, TS)]
#[serde(tag = "type", rename_all = "snake_case")]
#[ts(export)]
pub enum VoiceNotice {
    Speaking { active: bool },
    Switched { from: String, to: String, reason: String },
    GaveUp { reason: String },
    Budget { engine: String, percent: u8 },
}

/// Streamed while recording.
#[derive(Debug, Clone, Serialize, TS)]
#[serde(tag = "type", rename_all = "snake_case")]
#[ts(export)]
pub enum RecordingEvent {
    /// Loudness, 0 to 1.
    Level { level: f32 },
    /// Hands-free mode heard the end of speech (or nothing at all).
    AutoStop { reason: StopReason },
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct Transcript {
    pub text: String,
    pub seconds: f32,
    pub engine: String,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct DownloadProgress {
    #[ts(type = "number")]
    pub received: u64,
    #[ts(type = "number | null")]
    pub total: Option<u64>,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct WhisperModelView {
    pub name: String,
    pub size_mb: u32,
    pub installed: bool,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct EngineView {
    pub id: String,
    pub name: String,
    pub ready: bool,
    /// Why it can't speak right now.
    pub problem: Option<String>,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct MicrophoneView {
    pub id: String,
    pub name: String,
    pub is_default: bool,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct VoiceOverview {
    pub settings: VoiceSettings,
    /// Whether this build includes local Whisper.
    pub local_whisper_available: bool,
    pub whisper_models: Vec<WhisperModelView>,
    /// Problem with the chosen speech-to-text engine, if any.
    pub input_problem: Option<String>,
    pub microphones: Vec<MicrophoneView>,
    pub microphone_problem: Option<String>,
    pub speakers_problem: Option<String>,
    /// Output engines in the user's order.
    pub engines: Vec<EngineView>,
    pub piper_supported: bool,
    pub piper_installed: bool,
    pub fish_key: bool,
    /// Fish Audio text spoken this month, in UTF-8 bytes.
    #[ts(type = "number")]
    pub fish_used_this_month: u64,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct PiperVoiceView {
    pub key: String,
    pub language: String,
    pub language_name: String,
    pub country: String,
    pub quality: String,
    pub size_mb: u32,
    pub installed: bool,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct SystemVoiceView {
    pub id: String,
    pub name: String,
    pub language: String,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct FishVoiceView {
    pub id: String,
    pub title: String,
    pub description: String,
    pub languages: Vec<String>,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct FishVoicePage {
    #[ts(type = "number")]
    pub total: u64,
    pub voices: Vec<FishVoiceView>,
}

#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct FishCheckResult {
    pub ok: bool,
    pub model: Option<String>,
    /// Whether the working model is free (otherwise it spends credits).
    pub free: bool,
    pub credit: Option<f64>,
    pub message: String,
}

struct Paths {
    whisper: PathBuf,
    piper: PathBuf,
    piper_voices: PathBuf,
}

struct ActiveRecording {
    recording: Recording,
    started: Instant,
}

pub struct VoiceService {
    db: Arc<Database>,
    presets: Arc<Presets>,
    keys: Arc<KeyLibrary>,
    paths: Paths,
    client: reqwest::Client,
    app: OnceLock<AppHandle>,
    recording: Mutex<Option<ActiveRecording>>,
    #[cfg(feature = "local-whisper")]
    whisper: tokio::sync::Mutex<Option<(String, Arc<kickoff_voice::stt::LocalWhisper>)>>,
    speakers: Mutex<Option<Arc<Speakers>>>,
    narration: Mutex<Option<Cancel>>,
    /// Engines that failed recently, skipped until the given time.
    resting: Mutex<HashMap<String, Instant>>,
    budget_warned: Mutex<HashSet<(String, u8)>>,
}

impl VoiceService {
    pub fn new(db: Arc<Database>, presets: Arc<Presets>, keys: Arc<KeyLibrary>, models_dir: &Path) -> Self {
        let client = reqwest::Client::builder()
            .user_agent(format!("{}/{}", kickoff_core::APP_NAME, kickoff_core::APP_VERSION))
            .connect_timeout(Duration::from_secs(10))
            .build()
            .expect("HTTP client builds with static settings");
        Self {
            db,
            presets,
            keys,
            paths: Paths {
                whisper: models_dir.join("whisper"),
                piper: models_dir.join("piper"),
                piper_voices: models_dir.join("piper-voices"),
            },
            client,
            app: OnceLock::new(),
            recording: Mutex::new(None),
            #[cfg(feature = "local-whisper")]
            whisper: tokio::sync::Mutex::new(None),
            speakers: Mutex::new(None),
            narration: Mutex::new(None),
            resting: Mutex::new(HashMap::new()),
            budget_warned: Mutex::new(HashSet::new()),
        }
    }

    pub fn attach(&self, app: AppHandle) {
        let _ = self.app.set(app);
    }

    fn notify(&self, notice: VoiceNotice) {
        if let Some(app) = self.app.get() {
            let _ = app.emit(VOICE_EVENT, notice);
        }
    }

    async fn blocking<T: Send + 'static>(f: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
        tokio::task::spawn_blocking(f).await.map_err(|e| VoiceServiceError::Invalid(e.to_string()))?
    }

    // ------------------------------------------------------------ settings

    pub async fn settings(&self) -> Result<VoiceSettings> {
        let db = self.db.clone();
        Self::blocking(move || Ok(db.get_setting(VoiceSettings::KEY)?.unwrap_or_default())).await
    }

    pub async fn save_settings(&self, settings: VoiceSettings) -> Result<VoiceOverview> {
        if settings.whisper_model.is_empty() || settings.output_order.iter().any(|e| self.tts_preset(e).is_none()) {
            return Err(VoiceServiceError::Invalid("unknown speech engine or model".into()));
        }
        let db = self.db.clone();
        let saved = settings.clone();
        Self::blocking(move || Ok(db.set_setting(VoiceSettings::KEY, &saved)?)).await?;
        // A change of voice or key deserves a fresh try of every engine.
        self.resting.lock().unwrap().clear();
        self.overview().await
    }

    fn stt_preset(&self, id: &str) -> Option<&SttPreset> {
        self.presets.stt.iter().find(|p| p.id == id)
    }

    fn tts_preset(&self, id: &str) -> Option<&TtsPreset> {
        self.presets.tts(id)
    }

    fn whisper_preset(&self) -> &SttPreset {
        self.stt_preset("whisper_local").expect("bundled presets include local Whisper")
    }

    fn whisper_path(&self, model: &str) -> PathBuf {
        self.paths.whisper.join(format!("ggml-{model}.bin"))
    }

    fn piper_preset(&self) -> &TtsPreset {
        self.tts_preset("piper").expect("bundled presets include Piper")
    }

    /// The Piper program, if this platform has a build and it's installed.
    /// `KICKOFF_PIPER_BIN` points at a local build during development.
    fn piper_binary(&self) -> Option<PathBuf> {
        if let Ok(dev) = std::env::var("KICKOFF_PIPER_BIN") {
            return Some(PathBuf::from(dev));
        }
        let inside = self.piper_preset().binary_path_in_archive.as_deref()?;
        Some(self.paths.piper.join(inside))
    }

    fn piper_supported(&self) -> bool {
        std::env::var("KICKOFF_PIPER_BIN").is_ok()
            || (kickoff_platform::os_name() == "windows" && self.piper_preset().binary_url_windows.is_some())
    }

    fn piper_voice(&self, key: &str) -> Option<PiperVoice> {
        tts::piper_voices().into_iter().find(|v| v.key == key)
    }

    fn is_resting(&self, engine: &str) -> bool {
        let mut resting = self.resting.lock().unwrap();
        match resting.get(engine) {
            Some(until) if *until > Instant::now() => true,
            Some(_) => {
                resting.remove(engine);
                false
            }
            None => false,
        }
    }

    // ------------------------------------------------------------ overview

    pub async fn overview(&self) -> Result<VoiceOverview> {
        let settings = self.settings().await?;
        let keys = self.keys.clone();
        let (fish_key, groq_key) = Self::blocking(move || {
            Ok((keys.secret_for_provider("fish_audio")?.is_some(), keys.secret_for_provider("groq")?.is_some()))
        })
        .await?;
        let whisper_models = self
            .whisper_preset()
            .models
            .iter()
            .map(|m| WhisperModelView {
                name: m.name.clone(),
                size_mb: m.size_mb,
                installed: self.whisper_path(&m.name).is_file(),
            })
            .collect::<Vec<_>>();

        let local_whisper_available = cfg!(feature = "local-whisper");
        let input_problem = match settings.input {
            SpeechInput::WhisperLocal if !local_whisper_available => {
                Some("This build doesn't include local speech recognition; use Groq instead.".into())
            }
            SpeechInput::WhisperLocal if !self.whisper_path(&settings.whisper_model).is_file() => {
                Some(format!("Download the {} speech model to talk.", settings.whisper_model))
            }
            SpeechInput::GroqWhisper if !groq_key => {
                Some("Add a Groq key in the Key Library to use Groq Whisper.".into())
            }
            _ => None,
        };

        let (microphones, microphone_problem) = match tokio::task::spawn_blocking(capture::input_devices).await {
            Ok(Ok(devices)) if !devices.is_empty() => (
                devices
                    .into_iter()
                    .map(|d| MicrophoneView { id: d.id, name: d.name, is_default: d.is_default })
                    .collect(),
                None,
            ),
            Ok(Ok(_)) => (Vec::new(), Some("No microphone found.".into())),
            Ok(Err(e)) => (Vec::new(), Some(e.to_string())),
            Err(e) => (Vec::new(), Some(e.to_string())),
        };
        let speakers_problem = self.speakers().await.err().map(|e| e.to_string());

        let piper_installed = self.piper_binary().is_some_and(|b| b.is_file());
        let system_voices = tokio::task::spawn_blocking(kickoff_platform::speech::system_voices).await.ok();
        let mut engines = Vec::new();
        for id in &settings.output_order {
            let name = self.tts_preset(id).map_or_else(|| id.clone(), |p| p.display_name.clone());
            let problem = match id.as_str() {
                "fish_audio" if !fish_key => Some("Add a Fish Audio key in the Key Library.".to_owned()),
                "piper" if !self.piper_supported() => Some("Piper isn't available on this system.".to_owned()),
                "piper" if !piper_installed => Some("Install Piper to use it.".to_owned()),
                "piper"
                    if !self
                        .piper_voice(&settings.piper_voice)
                        .is_some_and(|v| v.is_installed(&self.paths.piper_voices)) =>
                {
                    Some("Download a Piper voice.".to_owned())
                }
                "system" => match &system_voices {
                    Some(Ok(v)) if !v.is_empty() => None,
                    Some(Ok(_)) => Some("No Windows voice is installed.".to_owned()),
                    Some(Err(e)) => Some(e.to_string()),
                    None => Some("Couldn't list system voices.".to_owned()),
                },
                _ => None,
            }
            .or_else(|| self.is_resting(id).then(|| "Paused after an error; it will be tried again soon.".to_owned()));
            engines.push(EngineView { id: id.clone(), name, ready: problem.is_none(), problem });
        }

        let db = self.db.clone();
        let fish_used_this_month =
            Self::blocking(move || Ok(db.units_for_month("fish_audio", &time::local_day()[..7])?)).await?;

        Ok(VoiceOverview {
            settings,
            local_whisper_available,
            whisper_models,
            input_problem,
            microphones,
            microphone_problem,
            speakers_problem,
            engines,
            piper_supported: self.piper_supported(),
            piper_installed,
            fish_key,
            fish_used_this_month,
        })
    }

    // ------------------------------------------------------------ installs

    pub async fn download_whisper_model(
        &self,
        name: &str,
        on_progress: &mut (dyn FnMut(u64, Option<u64>) + Send),
    ) -> Result<()> {
        let preset = self.whisper_preset();
        let model = preset
            .models
            .iter()
            .find(|m| m.name == name)
            .ok_or_else(|| VoiceServiceError::Invalid(format!("unknown speech model `{name}`")))?;
        let template = preset.model_url_template.as_deref().unwrap_or_default();
        let d = Download {
            url: template.replace("{model}", name),
            dest: self.whisper_path(name),
            checksum: Some(Checksum::Sha1(model.sha1.clone())),
            size: None,
        };
        download::download(&self.client, &d, on_progress, &Cancel::new()).await?;
        Ok(())
    }

    pub async fn delete_whisper_model(&self, name: &str) -> Result<()> {
        let path = self.whisper_path(name);
        if path.is_file() {
            tokio::fs::remove_file(&path).await.map_err(|e| VoiceServiceError::Invalid(e.to_string()))?;
        }
        #[cfg(feature = "local-whisper")]
        {
            let mut loaded = self.whisper.lock().await;
            if loaded.as_ref().is_some_and(|(n, _)| n == name) {
                *loaded = None;
            }
        }
        Ok(())
    }

    pub async fn install_piper(&self, on_progress: &mut (dyn FnMut(u64, Option<u64>) + Send)) -> Result<()> {
        let preset = self.piper_preset();
        let url = preset
            .binary_url_windows
            .clone()
            .filter(|_| kickoff_platform::os_name() == "windows")
            .ok_or_else(|| VoiceServiceError::Invalid("Piper isn't available for this system.".into()))?;
        let archive = self.paths.piper.with_extension("zip");
        let d = Download {
            url,
            dest: archive.clone(),
            checksum: preset.binary_sha256_windows.clone().map(Checksum::Sha256),
            size: preset.binary_size_windows,
        };
        download::download(&self.client, &d, on_progress, &Cancel::new()).await?;
        let dir = self.paths.piper.clone();
        Self::blocking(move || {
            if dir.exists() {
                std::fs::remove_dir_all(&dir).map_err(|e| VoiceServiceError::Invalid(e.to_string()))?;
            }
            let unpacked = tts::extract_archive(&archive, &dir);
            let _ = std::fs::remove_file(&archive);
            Ok(unpacked?)
        })
        .await
    }

    pub fn piper_voices(&self) -> Vec<PiperVoiceView> {
        tts::piper_voices()
            .into_iter()
            .map(|v| PiperVoiceView {
                installed: v.is_installed(&self.paths.piper_voices),
                size_mb: (v.size() / 1_000_000) as u32,
                key: v.key,
                language: v.language,
                language_name: v.language_name,
                country: v.country,
                quality: v.quality,
            })
            .collect()
    }

    pub async fn download_piper_voice(
        &self,
        key: &str,
        on_progress: &mut (dyn FnMut(u64, Option<u64>) + Send),
    ) -> Result<()> {
        let voice =
            self.piper_voice(key).ok_or_else(|| VoiceServiceError::Invalid(format!("unknown voice `{key}`")))?;
        let base = self.piper_preset().voices_base_url.clone().unwrap_or_default();
        let total = voice.size();
        let mut done = 0u64;
        for file in &voice.files {
            let d = Download {
                url: format!("{base}/{}", file.path),
                dest: voice.dir(&self.paths.piper_voices).join(file.name()),
                checksum: Some(Checksum::Md5(file.md5.clone())),
                size: Some(file.size),
            };
            download::download(&self.client, &d, &mut |n, _| on_progress(done + n, Some(total)), &Cancel::new())
                .await?;
            done += file.size;
        }
        Ok(())
    }

    pub async fn system_voices(&self) -> Result<Vec<SystemVoiceView>> {
        let voices = tokio::task::spawn_blocking(kickoff_platform::speech::system_voices)
            .await
            .map_err(|e| VoiceServiceError::Invalid(e.to_string()))?
            .map_err(|e| VoiceError::Unavailable(e.to_string()))?;
        Ok(voices.into_iter().map(|v| SystemVoiceView { id: v.id, name: v.name, language: v.language }).collect())
    }

    // ------------------------------------------------------------ Fish Audio

    async fn fish(&self, settings: &VoiceSettings) -> Result<Option<FishAudio>> {
        let keys = self.keys.clone();
        let Some((_, secret)) = Self::blocking(move || Ok(keys.secret_for_provider("fish_audio")?)).await? else {
            return Ok(None);
        };
        let preset = self.tts_preset("fish_audio").expect("bundled presets include Fish Audio");
        let model = settings.fish_model.clone().or_else(|| preset.models.first().cloned()).unwrap_or_default();
        Ok(Some(FishAudio::new(
            self.client.clone(),
            preset.base_url.as_deref().unwrap_or_default(),
            secret,
            &model,
            settings.fish_voice.as_ref().map(|v| v.id.as_str()),
        )))
    }

    /// Find a Fish Audio model this key can use and remember it.
    pub async fn check_fish(&self) -> Result<FishCheckResult> {
        let mut settings = self.settings().await?;
        let Some(fish) = self.fish(&settings).await? else {
            return Ok(FishCheckResult {
                ok: false,
                model: None,
                free: false,
                credit: None,
                message: "Add a Fish Audio key in the Key Library first.".into(),
            });
        };
        let preset = self.tts_preset("fish_audio").expect("bundled presets include Fish Audio");
        let result = match fish.check(&preset.models, &preset.free_models).await {
            Ok(check) => {
                let message = if check.free {
                    format!("Working: the free {} model answered.", check.model)
                } else {
                    format!(
                        "Working with {}, which spends your Fish Audio credits ({} left). The free model isn't available for this key.",
                        check.model,
                        check.credit.map_or_else(|| "unknown".into(), |c| format!("{c:.2}"))
                    )
                };
                settings.fish_model = Some(check.model.clone());
                self.save_settings(settings).await?;
                FishCheckResult { ok: true, model: Some(check.model), free: check.free, credit: check.credit, message }
            }
            Err(e) => FishCheckResult {
                ok: false,
                model: None,
                free: false,
                credit: None,
                message: match e {
                    VoiceError::Auth(_) => "Fish Audio rejected the key. Check that you copied all of it.".into(),
                    VoiceError::Quota(_) => format!(
                        "Fish Audio's free model isn't available for this key and there are no credits ({e}). Piper or Windows voices will speak instead."
                    ),
                    _ => format!("Fish Audio didn't work: {e}. Piper or Windows voices will speak instead."),
                },
            },
        };
        let keys = self.keys.clone();
        let ok = result.ok;
        let _ = Self::blocking(move || {
            if let Some((meta, _)) = keys.secret_for_provider("fish_audio")? {
                keys.record_test(&meta.id, ok)?;
            }
            Ok(())
        })
        .await;
        Ok(result)
    }

    pub async fn fish_voices(&self, query: Option<&str>, page: u32) -> Result<FishVoicePage> {
        let settings = self.settings().await?;
        let fish = self
            .fish(&settings)
            .await?
            .ok_or_else(|| VoiceServiceError::Invalid("Add a Fish Audio key in the Key Library first.".into()))?;
        let (total, voices) = fish.voices(query, None, page).await?;
        Ok(FishVoicePage {
            total,
            voices: voices
                .into_iter()
                .map(|v| FishVoiceView {
                    id: v.id,
                    title: v.title,
                    description: v.description,
                    languages: v.languages,
                    tags: v.tags,
                })
                .collect(),
        })
    }

    // ------------------------------------------------------------ speaking

    async fn speakers(&self) -> Result<Arc<Speakers>> {
        if let Some(s) = self.speakers.lock().unwrap().clone() {
            return Ok(s);
        }
        let speakers = Arc::new(Self::blocking(|| Ok(Speakers::open()?)).await?);
        // Tell the UI when playback starts and stops, for the stop button.
        let mut playing = speakers.playing();
        let app = self.app.get().cloned();
        tokio::spawn(async move {
            while playing.changed().await.is_ok() {
                let active = *playing.borrow_and_update();
                if let Some(app) = &app {
                    let _ = app.emit(VOICE_EVENT, VoiceNotice::Speaking { active });
                }
            }
        });
        *self.speakers.lock().unwrap() = Some(speakers.clone());
        Ok(speakers)
    }

    /// The output engines to try for one reply, in the user's order.
    async fn output_engines(&self, settings: &VoiceSettings, only: Option<&str>) -> Result<Vec<Arc<dyn TextToSpeech>>> {
        let mut engines: Vec<Arc<dyn TextToSpeech>> = Vec::new();
        for id in &settings.output_order {
            if only.is_some_and(|o| o != id) || (only.is_none() && self.is_resting(id)) {
                continue;
            }
            match id.as_str() {
                "fish_audio" => {
                    if let Some(fish) = self.fish(settings).await? {
                        engines.push(Arc::new(fish));
                    }
                }
                "piper" => {
                    let voice = self.piper_voice(&settings.piper_voice);
                    if let (Some(binary), Some(voice)) = (self.piper_binary(), voice)
                        && let Ok(piper) = Piper::new(&binary, &voice.model_path(&self.paths.piper_voices))
                    {
                        engines.push(Arc::new(piper));
                    }
                }
                "system" => {
                    engines.push(Arc::new(SystemVoiceTts::new(settings.system_voice.as_ref().map(|v| v.id.as_str()))))
                }
                _ => {}
            }
        }
        Ok(engines)
    }

    /// Stop whatever is being said (barge-in).
    pub fn stop_speaking(&self) {
        if let Some(cancel) = self.narration.lock().unwrap().take() {
            cancel.cancel();
        }
        if let Some(speakers) = self.speakers.lock().unwrap().as_ref() {
            speakers.stop();
        }
    }

    /// Start reading a reply aloud, if voice output is on and possible.
    pub async fn begin_reply(self: &Arc<Self>) -> Option<ReplySpeech> {
        let settings = self.settings().await.ok()?;
        if !settings.enabled || !settings.speak_replies {
            return None;
        }
        self.speak(&settings, None).await.ok()
    }

    async fn speak(self: &Arc<Self>, settings: &VoiceSettings, only: Option<&str>) -> Result<ReplySpeech> {
        self.stop_speaking();
        let engines = self.output_engines(settings, only).await?;
        if engines.is_empty() {
            return Err(VoiceError::Unavailable("no speech engine is ready".into()).into());
        }
        let speakers = self.speakers().await?;
        let cancel = Cancel::new();
        *self.narration.lock().unwrap() = Some(cancel.clone());
        let (tx, rx) = mpsc::unbounded_channel();
        let service = self.clone();
        let budget = settings.fish_monthly_budget;
        tokio::spawn(async move {
            // Report switches and usage while speaking, not after.
            let (events_tx, mut events_rx) = mpsc::unbounded_channel();
            let speaking = async move {
                let mut forward = |e| {
                    let _ = events_tx.send(e);
                };
                Narrator::new(engines).speak(rx, speakers.as_ref() as &dyn AudioSink, &cancel, &mut forward).await;
            };
            let reporting = async {
                while let Some(event) = events_rx.recv().await {
                    service.on_narrator_event(event, budget).await;
                }
            };
            tokio::join!(speaking, reporting);
        });
        Ok(ReplySpeech { splitter: SentenceSplitter::default(), tx })
    }

    async fn on_narrator_event(&self, event: NarratorEvent, budget: Option<u64>) {
        match event {
            NarratorEvent::Spoke { engine, bytes } => {
                let db = self.db.clone();
                let id = engine.clone();
                let month_used = Self::blocking(move || {
                    let day = time::local_day();
                    db.record_usage(&id, &day, UsageDelta { calls: 1, units: bytes as u64, ..Default::default() })?;
                    Ok(db.units_for_month(&id, &day[..7])?)
                })
                .await;
                if let (Ok(used), Some(budget)) = (month_used, budget.filter(|b| *b > 0 && engine == "fish_audio")) {
                    let month = time::local_day()[..7].to_owned();
                    for threshold in BUDGET_WARNINGS {
                        let crossed = used * 100 >= budget * u64::from(threshold);
                        if crossed && self.budget_warned.lock().unwrap().insert((month.clone(), threshold)) {
                            self.notify(VoiceNotice::Budget { engine: engine.clone(), percent: threshold });
                        }
                    }
                }
            }
            NarratorEvent::Switched { from, to, reason, error_kind } => {
                // Keys and quotas don't fix themselves quickly; network trouble might.
                let rest = if matches!(error_kind.as_str(), "auth" | "quota") { 3600 } else { 300 };
                self.resting.lock().unwrap().insert(from.clone(), Instant::now() + Duration::from_secs(rest));
                self.notify(VoiceNotice::Switched { from, to, reason });
            }
            NarratorEvent::GaveUp { reason } => self.notify(VoiceNotice::GaveUp { reason }),
        }
    }

    /// Speak a sample with one engine, for the voice pickers.
    pub async fn preview(self: &Arc<Self>, engine: &str, voice: Option<VoiceRef>) -> Result<()> {
        let mut settings = self.settings().await?;
        match engine {
            "fish_audio" => settings.fish_voice = voice.or(settings.fish_voice),
            "piper" => {
                if let Some(v) = voice {
                    settings.piper_voice = v.id;
                }
            }
            "system" => settings.system_voice = voice.or(settings.system_voice),
            _ => return Err(VoiceServiceError::Invalid(format!("unknown speech engine `{engine}`"))),
        }
        let mut speech = self.speak(&settings, Some(engine)).await?;
        speech.push(PREVIEW_TEXT);
        speech.finish();
        Ok(())
    }

    // ------------------------------------------------------------ listening

    async fn transcriber(&self, settings: &VoiceSettings) -> Result<Arc<dyn SpeechToText>> {
        match settings.input {
            SpeechInput::GroqWhisper => {
                let keys = self.keys.clone();
                let (_, secret) =
                    Self::blocking(move || Ok(keys.secret_for_provider("groq")?)).await?.ok_or_else(|| {
                        VoiceServiceError::Invalid("Add a Groq key in the Key Library to use Groq Whisper.".into())
                    })?;
                let preset = self.stt_preset("groq_whisper").expect("bundled presets include Groq Whisper");
                Ok(Arc::new(CloudWhisper::new(
                    "groq_whisper",
                    self.client.clone(),
                    preset.base_url.as_deref().unwrap_or_default(),
                    secret,
                    &preset.default_model,
                )))
            }
            SpeechInput::WhisperLocal => self.local_whisper(&settings.whisper_model).await,
        }
    }

    #[cfg(feature = "local-whisper")]
    async fn local_whisper(&self, model: &str) -> Result<Arc<dyn SpeechToText>> {
        let mut loaded = self.whisper.lock().await;
        if let Some((name, whisper)) = loaded.as_ref()
            && name == model
        {
            return Ok(whisper.clone());
        }
        let path = self.whisper_path(model);
        if !path.is_file() {
            return Err(VoiceServiceError::Invalid(format!(
                "Download the {model} speech model in Settings → Voice first."
            )));
        }
        let whisper = Arc::new(Self::blocking(move || Ok(kickoff_voice::stt::LocalWhisper::load(&path)?)).await?);
        *loaded = Some((model.to_owned(), whisper.clone()));
        Ok(whisper)
    }

    #[cfg(not(feature = "local-whisper"))]
    async fn local_whisper(&self, _model: &str) -> Result<Arc<dyn SpeechToText>> {
        Err(VoiceServiceError::Invalid("This build doesn't include local speech recognition; use Groq instead.".into()))
    }

    /// Start listening. Speaking stops first, so the user can interrupt.
    pub async fn start_recording(&self, on_event: impl Fn(RecordingEvent) + Send + Sync + 'static) -> Result<()> {
        self.stop_speaking();
        let settings = self.settings().await?;
        if !settings.enabled {
            return Err(VoiceServiceError::Invalid("Voice is turned off in Settings.".into()));
        }
        // Load the speech model now, so it's ready when recording ends.
        self.transcriber(&settings).await?;
        let mut vad = settings.hands_free.then(SilenceDetector::default);
        let mut stopped = false;
        let microphone = settings.microphone.clone();
        let recording = Self::blocking(move || {
            Ok(Recording::start(microphone.as_deref(), MAX_RECORDING, move |level| {
                on_event(RecordingEvent::Level { level });
                if let Some(vad) = vad.as_mut()
                    && !stopped
                    && let VadDecision::Stop(reason) = vad.feed(level, Duration::from_millis(50))
                {
                    stopped = true;
                    on_event(RecordingEvent::AutoStop { reason });
                }
            })?)
        })
        .await?;
        let previous = self.recording.lock().unwrap().replace(ActiveRecording { recording, started: Instant::now() });
        drop(previous);
        Ok(())
    }

    /// Stop listening and transcribe what was said.
    pub async fn stop_recording(&self) -> Result<Transcript> {
        let active = self.recording.lock().unwrap().take().ok_or(VoiceServiceError::NotRecording)?;
        let seconds = active.started.elapsed().as_secs_f32();
        let clip = Self::blocking(move || Ok(active.recording.finish()?)).await?;
        let settings = self.settings().await?;
        let stt = self.transcriber(&settings).await?;
        let text = stt.transcribe(&clip, settings.language.as_deref()).await?;
        if stt.id() == "groq_whisper" {
            let db = self.db.clone();
            let secs = clip.duration().as_secs();
            let _ = Self::blocking(move || {
                Ok(db.record_usage(
                    "groq_whisper",
                    &time::local_day(),
                    UsageDelta { calls: 1, units: secs, ..Default::default() },
                )?)
            })
            .await;
        }
        Ok(Transcript { text, seconds, engine: stt.id().to_owned() })
    }

    pub fn cancel_recording(&self) {
        drop(self.recording.lock().unwrap().take());
    }
}

/// Feeds a streamed reply to the narrator, sentence by sentence.
pub struct ReplySpeech {
    splitter: SentenceSplitter,
    tx: mpsc::UnboundedSender<String>,
}

impl ReplySpeech {
    pub fn push(&mut self, delta: &str) {
        for sentence in self.splitter.push(delta) {
            let _ = self.tx.send(sentence);
        }
    }

    /// The reply is complete: speak what's left.
    pub fn finish(mut self) {
        if let Some(rest) = self.splitter.finish() {
            let _ = self.tx.send(rest);
        }
    }
}

#[cfg(test)]
mod tests {
    use kickoff_platform::MemorySecretStore;

    use super::*;

    fn service(dir: &Path) -> Arc<VoiceService> {
        let db = Arc::new(Database::open_in_memory().unwrap());
        let keys = Arc::new(KeyLibrary::new(db.clone(), Arc::new(MemorySecretStore::new())));
        Arc::new(VoiceService::new(db, Arc::new(Presets::bundled().unwrap()), keys, dir))
    }

    #[tokio::test]
    async fn overview_explains_what_is_missing() {
        let dir = tempfile::tempdir().unwrap();
        let svc = service(dir.path());
        let o = svc.overview().await.unwrap();
        assert!(o.settings.enabled);
        assert_eq!(o.whisper_models.iter().map(|m| m.name.as_str()).collect::<Vec<_>>()[..2], ["tiny", "base"]);
        assert!(o.whisper_models.iter().all(|m| !m.installed));
        if o.local_whisper_available {
            assert!(o.input_problem.as_deref().unwrap().contains("Download the base speech model"));
        }
        let fish = o.engines.iter().find(|e| e.id == "fish_audio").unwrap();
        assert!(!fish.ready);
        assert!(fish.problem.as_deref().unwrap().contains("Fish Audio key"));
        assert_eq!(o.fish_used_this_month, 0);
    }

    #[tokio::test]
    async fn settings_round_trip_and_reject_unknown_engines() {
        let dir = tempfile::tempdir().unwrap();
        let svc = service(dir.path());
        let mut s = svc.settings().await.unwrap();
        s.hands_free = true;
        s.output_order = vec!["system".into(), "piper".into()];
        let o = svc.save_settings(s.clone()).await.unwrap();
        assert_eq!(o.settings, s);
        assert_eq!(o.engines.iter().map(|e| e.id.as_str()).collect::<Vec<_>>(), ["system", "piper"]);
        s.output_order = vec!["nope".into()];
        assert!(svc.save_settings(s).await.is_err());
    }

    #[tokio::test]
    async fn speech_needs_a_ready_engine_and_recording_needs_a_model() {
        let dir = tempfile::tempdir().unwrap();
        let svc = service(dir.path());
        let mut s = svc.settings().await.unwrap();
        s.output_order = vec!["fish_audio".into()];
        svc.save_settings(s).await.unwrap();
        // No Fish key, so nothing can speak; the reply stays text only.
        assert!(svc.begin_reply().await.is_none());
        assert!(matches!(svc.stop_recording().await, Err(VoiceServiceError::NotRecording)));
        let err = svc.start_recording(|_| {}).await.unwrap_err().to_string();
        assert!(err.contains("speech model") || err.contains("Groq"), "{err}");
    }

    #[tokio::test]
    async fn budget_warnings_fire_once_per_threshold() {
        let dir = tempfile::tempdir().unwrap();
        let svc = service(dir.path());
        for _ in 0..3 {
            svc.on_narrator_event(NarratorEvent::Spoke { engine: "fish_audio".into(), bytes: 450 }, Some(1000)).await;
        }
        let month = time::local_day()[..7].to_owned();
        assert_eq!(svc.db.units_for_month("fish_audio", &month).unwrap(), 1350);
        let warned = svc.budget_warned.lock().unwrap().clone();
        assert_eq!(warned, HashSet::from([(month.clone(), 80), (month, 95)]));
    }

    #[tokio::test]
    async fn a_failed_engine_rests() {
        let dir = tempfile::tempdir().unwrap();
        let svc = service(dir.path());
        svc.on_narrator_event(
            NarratorEvent::Switched {
                from: "fish_audio".into(),
                to: "piper".into(),
                reason: "402".into(),
                error_kind: "quota".into(),
            },
            None,
        )
        .await;
        assert!(svc.is_resting("fish_audio"));
        assert!(!svc.is_resting("piper"));
        // Saving settings gives every engine a fresh chance.
        let s = svc.settings().await.unwrap();
        svc.save_settings(s).await.unwrap();
        assert!(!svc.is_resting("fish_audio"));
    }
}
