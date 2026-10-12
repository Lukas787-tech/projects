//! Text to speech: Fish Audio (cloud), Piper (local process) and the
//! system's own voices.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use async_trait::async_trait;
use futures::{StreamExt, stream};
use kickoff_core::Secret;
use kickoff_core::voice::{AudioClip, SpeechStream, TextToSpeech, VoiceError};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::dsp::i16_to_f32;
use crate::stt::{http_error, retry_after};
use crate::wav;

/// Audio is handed to the speakers in pieces of at least this length, so
/// playback isn't a stream of tiny fragments.
const MIN_CHUNK: Duration = Duration::from_millis(150);

/// Collects decoded audio until a chunk is long enough to queue.
#[derive(Default)]
struct Chunker {
    pending: Vec<f32>,
    rate: u32,
}

impl Chunker {
    fn push(&mut self, clip: AudioClip) -> Option<AudioClip> {
        self.rate = clip.sample_rate;
        self.pending.extend(clip.samples);
        let min = (MIN_CHUNK.as_secs_f64() * f64::from(self.rate)) as usize;
        (self.pending.len() >= min).then(|| self.take())
    }

    fn take(&mut self) -> AudioClip {
        AudioClip::new(std::mem::take(&mut self.pending), self.rate)
    }

    fn finish(&mut self) -> Option<AudioClip> {
        (!self.pending.is_empty()).then(|| self.take())
    }
}

// ------------------------------------------------------------- Fish Audio

/// Fish Audio's HTTP API, as used by the official SDK: `POST /v1/tts` with a
/// MessagePack body and the model in a `model` header; the audio streams
/// back in the requested format (WAV here, so the sample rate is known).
#[derive(Clone)]
pub struct FishAudio {
    client: reqwest::Client,
    base_url: String,
    key: Secret,
    model: String,
    voice: Option<String>,
}

#[derive(Serialize)]
struct FishTts<'a> {
    text: &'a str,
    format: &'static str,
    latency: &'static str,
    normalize: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    reference_id: Option<&'a str>,
}

/// A Fish Audio voice from the public library.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FishVoice {
    #[serde(rename = "_id")]
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub languages: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default, rename = "type")]
    pub kind: Option<String>,
    #[serde(default)]
    pub cover_image: Option<String>,
}

#[derive(Debug, Deserialize)]
struct FishPage {
    #[serde(default)]
    total: u64,
    #[serde(default)]
    items: Vec<FishVoice>,
}

/// Result of checking a key: which model works, and whether it spends credits.
#[derive(Debug, Clone, PartialEq)]
pub struct FishCheck {
    pub model: String,
    pub free: bool,
    pub credit: Option<f64>,
}

impl FishAudio {
    pub fn new(client: reqwest::Client, base_url: &str, key: Secret, model: &str, voice: Option<&str>) -> Self {
        Self {
            client,
            base_url: base_url.trim_end_matches('/').to_owned(),
            key,
            model: model.to_owned(),
            voice: voice.map(str::to_owned),
        }
    }

    pub fn with_model(&self, model: &str) -> Self {
        Self { model: model.to_owned(), ..self.clone() }
    }

    async fn get(&self, path: &str, query: &[(&str, String)]) -> Result<reqwest::Response, VoiceError> {
        let response = self
            .client
            .get(format!("{}{path}", self.base_url))
            .bearer_auth(self.key.expose())
            .query(query)
            .timeout(Duration::from_secs(20))
            .send()
            .await
            .map_err(|e| VoiceError::Unavailable(format!("couldn't reach Fish Audio: {e}")))?;
        if !response.status().is_success() {
            let status = response.status().as_u16();
            return Err(http_error(status, retry_after(&response), &response.text().await.unwrap_or_default()));
        }
        Ok(response)
    }

    /// The account's API credit balance (`GET /wallet/self/api-credit`).
    pub async fn credit(&self) -> Result<f64, VoiceError> {
        let body: serde_json::Value = self
            .get("/wallet/self/api-credit", &[])
            .await?
            .json()
            .await
            .map_err(|e| VoiceError::Invalid(e.to_string()))?;
        // A decimal on the server: sent as a number or a string.
        match &body["credit"] {
            serde_json::Value::Number(n) => n.as_f64(),
            serde_json::Value::String(s) => s.parse().ok(),
            _ => None,
        }
        .ok_or_else(|| VoiceError::Invalid("no credit balance in the reply".into()))
    }

    /// Search the public voice library (`GET /model`), most used first.
    pub async fn voices(
        &self,
        title: Option<&str>,
        language: Option<&str>,
        page: u32,
    ) -> Result<(u64, Vec<FishVoice>), VoiceError> {
        let mut query = vec![
            ("page_size", "20".to_owned()),
            ("page_number", page.max(1).to_string()),
            ("sort_by", "task_count".to_owned()),
        ];
        if let Some(t) = title.filter(|t| !t.is_empty()) {
            query.push(("title", t.to_owned()));
        }
        if let Some(l) = language {
            query.push(("language", l.to_owned()));
        }
        let page: FishPage =
            self.get("/model", &query).await?.json().await.map_err(|e| VoiceError::Invalid(e.to_string()))?;
        let voices = page.items.into_iter().filter(|v| v.kind.as_deref().is_none_or(|k| k == "tts")).collect();
        Ok((page.total, voices))
    }

    /// Find a model this key can use: the free ones first, then paid ones
    /// only if the account has credit.
    pub async fn check(&self, models: &[String], free_models: &[String]) -> Result<FishCheck, VoiceError> {
        let credit = self.credit().await?;
        let mut last = None;
        for model in models {
            let free = free_models.contains(model);
            if !free && credit <= 0.0 {
                continue;
            }
            match collect(self.with_model(model).synthesize("Hi.").await).await {
                Ok(_) => return Ok(FishCheck { model: model.clone(), free, credit: Some(credit) }),
                Err(e @ VoiceError::Auth(_)) => return Err(e),
                Err(e) => last = Some(e),
            }
        }
        Err(match last {
            Some(e) => e,
            None => VoiceError::Quota("no free model is available and the account has no credits".into()),
        })
    }
}

/// Drain a synthesis into one clip.
pub async fn collect(stream: Result<SpeechStream, VoiceError>) -> Result<AudioClip, VoiceError> {
    let mut stream = stream?;
    let mut all = AudioClip::default();
    while let Some(clip) = stream.next().await {
        let clip = clip?;
        all.sample_rate = clip.sample_rate;
        all.samples.extend(clip.samples);
    }
    if all.is_empty() {
        return Err(VoiceError::Failed("no audio came back".into()));
    }
    Ok(all)
}

#[async_trait]
impl TextToSpeech for FishAudio {
    fn id(&self) -> &str {
        "fish_audio"
    }

    async fn synthesize(&self, text: &str) -> Result<SpeechStream, VoiceError> {
        let body = rmp_serde::to_vec_named(&FishTts {
            text,
            format: "wav",
            latency: "balanced",
            normalize: true,
            reference_id: self.voice.as_deref(),
        })
        .map_err(|e| VoiceError::Failed(e.to_string()))?;
        let response = self
            .client
            .post(format!("{}/v1/tts", self.base_url))
            .bearer_auth(self.key.expose())
            .header(reqwest::header::CONTENT_TYPE, "application/msgpack")
            .header("model", &self.model)
            .body(body)
            .timeout(Duration::from_secs(60))
            .send()
            .await
            .map_err(|e| VoiceError::Unavailable(format!("couldn't reach Fish Audio: {e}")))?;
        if !response.status().is_success() {
            let status = response.status().as_u16();
            return Err(http_error(status, retry_after(&response), &response.text().await.unwrap_or_default()));
        }
        let state =
            (response.bytes_stream(), wav::StreamDecoder::default(), Chunker::default(), VecDeque::new(), false);
        let audio = stream::unfold(state, |(mut body, mut decoder, mut chunker, mut ready, mut ended)| async move {
            loop {
                if let Some(item) = ready.pop_front() {
                    return Some((item, (body, decoder, chunker, ready, ended)));
                }
                if ended {
                    return None;
                }
                match body.next().await {
                    Some(Ok(bytes)) => match decoder.push(&bytes) {
                        Ok(Some(clip)) => ready.extend(chunker.push(clip).map(Ok)),
                        Ok(None) => {}
                        Err(e) => {
                            ready.push_back(Err(e));
                            ended = true;
                        }
                    },
                    Some(Err(e)) => {
                        ready.push_back(Err(VoiceError::Failed(format!("the audio stream broke off: {e}"))));
                        ended = true;
                    }
                    None => {
                        ready.extend(chunker.finish().map(Ok));
                        ended = true;
                    }
                }
            }
        });
        Ok(audio.boxed())
    }
}

// ------------------------------------------------------------- Piper

/// One file of a Piper voice.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VoiceFile {
    /// Path under the voices repository, e.g. `en/en_US/lessac/medium/…onnx`.
    pub path: String,
    pub size: u64,
    pub md5: String,
}

impl VoiceFile {
    pub fn name(&self) -> &str {
        self.path.rsplit('/').next().unwrap_or(&self.path)
    }
}

/// A voice from Piper's catalog (`data/piper-voices.json`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PiperVoice {
    /// e.g. `en_US-lessac-medium`.
    pub key: String,
    /// e.g. `en_US`.
    pub language: String,
    pub language_name: String,
    pub country: String,
    pub quality: String,
    pub speakers: u32,
    pub files: Vec<VoiceFile>,
}

impl PiperVoice {
    pub fn size(&self) -> u64 {
        self.files.iter().map(|f| f.size).sum()
    }

    /// Where the voice's files live once downloaded.
    pub fn dir(&self, voices_dir: &Path) -> PathBuf {
        voices_dir.join(&self.key)
    }

    pub fn model_path(&self, voices_dir: &Path) -> PathBuf {
        self.dir(voices_dir).join(format!("{}.onnx", self.key))
    }

    pub fn is_installed(&self, voices_dir: &Path) -> bool {
        self.files
            .iter()
            .all(|f| std::fs::metadata(self.dir(voices_dir).join(f.name())).is_ok_and(|m| m.len() == f.size))
    }
}

#[derive(Deserialize)]
struct RawVoice {
    key: String,
    language: RawLanguage,
    quality: String,
    #[serde(default)]
    num_speakers: u32,
    files: std::collections::BTreeMap<String, RawFile>,
}

#[derive(Deserialize)]
struct RawLanguage {
    code: String,
    name_english: String,
    country_english: String,
}

#[derive(Deserialize)]
struct RawFile {
    size_bytes: u64,
    md5_digest: String,
}

/// Every voice in the bundled catalog, sorted by language then name.
pub fn piper_voices() -> Vec<PiperVoice> {
    let raw: std::collections::BTreeMap<String, RawVoice> =
        serde_json::from_str(include_str!("../../../data/piper-voices.json")).expect("bundled Piper catalog is valid");
    let mut voices: Vec<PiperVoice> = raw
        .into_values()
        .map(|v| PiperVoice {
            key: v.key,
            language: v.language.code,
            language_name: v.language.name_english,
            country: v.language.country_english,
            quality: v.quality,
            speakers: v.num_speakers.max(1),
            files: v
                .files
                .into_iter()
                .map(|(path, f)| VoiceFile { path, size: f.size_bytes, md5: f.md5_digest })
                .collect(),
        })
        .collect();
    voices.sort_by(|a, b| (&a.language, &a.key).cmp(&(&b.language, &b.key)));
    voices
}

/// Unpack the Piper release archive into `dir`.
pub fn extract_archive(archive: &Path, dir: &Path) -> Result<(), VoiceError> {
    let file = std::fs::File::open(archive).map_err(|e| VoiceError::Failed(e.to_string()))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| VoiceError::Invalid(format!("not a valid archive: {e}")))?;
    zip.extract(dir).map_err(|e| VoiceError::Failed(format!("couldn't unpack Piper: {e}")))
}

/// Piper, run as a separate program per sentence:
/// `piper --model <voice>.onnx --output_raw`, text on stdin, 16-bit mono PCM
/// on stdout at the voice's sample rate.
pub struct Piper {
    binary: PathBuf,
    model: PathBuf,
    sample_rate: u32,
}

impl Piper {
    pub fn new(binary: &Path, model: &Path) -> Result<Self, VoiceError> {
        if !binary.is_file() {
            return Err(VoiceError::Unavailable("Piper isn't installed yet".into()));
        }
        let config = model.with_extension("onnx.json");
        let config: serde_json::Value = std::fs::read_to_string(&config)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .ok_or_else(|| VoiceError::Unavailable("this Piper voice isn't downloaded yet".into()))?;
        if !model.is_file() {
            return Err(VoiceError::Unavailable("this Piper voice isn't downloaded yet".into()));
        }
        let sample_rate = config["audio"]["sample_rate"].as_u64().unwrap_or(22_050) as u32;
        Ok(Self { binary: binary.to_owned(), model: model.to_owned(), sample_rate })
    }
}

#[async_trait]
impl TextToSpeech for Piper {
    fn id(&self) -> &str {
        "piper"
    }

    async fn synthesize(&self, text: &str) -> Result<SpeechStream, VoiceError> {
        let mut command = tokio::process::Command::from(kickoff_platform::process::background_command(&self.binary));
        command
            .arg("--model")
            .arg(&self.model)
            .arg("--output_raw")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            // Barge-in drops the stream, which ends the process.
            .kill_on_drop(true);
        let mut child = command.spawn().map_err(|e| VoiceError::Unavailable(format!("couldn't start Piper: {e}")))?;
        let mut stdin = child.stdin.take().expect("stdin is piped");
        // Piper speaks one utterance per line.
        let line = format!("{}\n", text.replace(['\r', '\n'], " "));
        stdin.write_all(line.as_bytes()).await.map_err(|e| VoiceError::Failed(format!("Piper: {e}")))?;
        drop(stdin);
        let stdout = child.stdout.take().expect("stdout is piped");
        let rate = self.sample_rate;
        let state = (child, stdout, Vec::<u8>::new(), Chunker::default(), false);
        let audio = stream::unfold(state, move |(mut child, mut out, mut odd, mut chunker, done)| async move {
            if done {
                return None;
            }
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match out.read(&mut buf).await {
                    Ok(0) => {
                        let status = child.wait().await;
                        let mut err = String::new();
                        if let Some(mut stderr) = child.stderr.take() {
                            let _ = stderr.read_to_string(&mut err).await;
                        }
                        return match status {
                            Ok(s) if s.success() => chunker.finish().map(|c| (Ok(c), (child, out, odd, chunker, true))),
                            _ => {
                                let tail: String =
                                    err.trim().chars().rev().take(300).collect::<Vec<_>>().into_iter().rev().collect();
                                let error = VoiceError::Failed(format!("Piper failed: {tail}"));
                                Some((Err(error), (child, out, odd, chunker, true)))
                            }
                        };
                    }
                    Ok(n) => {
                        odd.extend_from_slice(&buf[..n]);
                        let even = odd.len() - odd.len() % 2;
                        let pcm: Vec<u8> = odd.drain(..even).collect();
                        let samples: Vec<f32> =
                            pcm.as_chunks::<2>().0.iter().map(|b| i16_to_f32(i16::from_le_bytes(*b))).collect();
                        if let Some(clip) = chunker.push(AudioClip::new(samples, rate)) {
                            return Some((Ok(clip), (child, out, odd, chunker, false)));
                        }
                    }
                    Err(e) => {
                        return Some((
                            Err(VoiceError::Failed(format!("Piper: {e}"))),
                            (child, out, odd, chunker, true),
                        ));
                    }
                }
            }
        });
        Ok(audio.boxed())
    }
}

// ------------------------------------------------------------- system voice

/// The operating system's built-in voices (Windows only).
pub struct SystemVoiceTts {
    voice: Option<String>,
}

impl SystemVoiceTts {
    pub fn new(voice: Option<&str>) -> Self {
        Self { voice: voice.map(str::to_owned) }
    }
}

#[async_trait]
impl TextToSpeech for SystemVoiceTts {
    fn id(&self) -> &str {
        "system"
    }

    async fn synthesize(&self, text: &str) -> Result<SpeechStream, VoiceError> {
        let (text, voice) = (text.to_owned(), self.voice.clone());
        let wav =
            tokio::task::spawn_blocking(move || kickoff_platform::speech::system_speak_wav(&text, voice.as_deref()))
                .await
                .map_err(|e| VoiceError::Failed(e.to_string()))?
                .map_err(|e| VoiceError::Unavailable(e.to_string()))?;
        let clip = wav::decode(&wav)?;
        Ok(stream::iter([Ok(clip)]).boxed())
    }
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{header, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn wav_bytes(samples: usize, rate: u32) -> Vec<u8> {
        let tone: Vec<f32> = (0..samples).map(|i| ((i as f32) * 0.1).sin() * 0.4).collect();
        wav::encode_pcm16(&AudioClip::new(tone, rate))
    }

    fn fish(server: &MockServer) -> FishAudio {
        FishAudio::new(
            reqwest::Client::new(),
            &server.uri(),
            Secret::new("fish-key"),
            "s2.1-pro-free",
            Some("voice-123"),
        )
    }

    #[tokio::test]
    async fn fish_sends_msgpack_and_streams_wav() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/tts"))
            .and(header("authorization", "Bearer fish-key"))
            .and(header("model", "s2.1-pro-free"))
            .and(header("content-type", "application/msgpack"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(wav_bytes(44_100, 44_100)))
            .mount(&server)
            .await;
        let clip = collect(fish(&server).synthesize("Hello there.").await).await.unwrap();
        assert_eq!(clip.sample_rate, 44_100);
        assert_eq!(clip.samples.len(), 44_100);

        let request = &server.received_requests().await.unwrap()[0];
        let body: serde_json::Value = rmp_serde::from_slice(&request.body).unwrap();
        assert_eq!(body["text"], "Hello there.");
        assert_eq!(body["format"], "wav");
        assert_eq!(body["reference_id"], "voice-123");
    }

    #[tokio::test]
    async fn fish_check_prefers_free_models_and_spares_empty_accounts() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/wallet/self/api-credit"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"_id": "x", "credit": "0.00"})))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(header("model", "s2.1-pro-free"))
            .respond_with(ResponseTemplate::new(402).set_body_json(serde_json::json!({"message": "promotion ended"})))
            .mount(&server)
            .await;
        let models = vec!["s2.1-pro-free".to_owned(), "s2.1-pro".to_owned()];
        let err = fish(&server).check(&models, &models[..1]).await.unwrap_err();
        assert!(matches!(err, VoiceError::Quota(ref m) if m.contains("promotion ended")), "{err}");
        // The paid model was never tried with no credit.
        let posts = server
            .received_requests()
            .await
            .unwrap()
            .into_iter()
            .filter(|r| r.method == wiremock::http::Method::POST)
            .count();
        assert_eq!(posts, 1);
    }

    #[tokio::test]
    async fn fish_check_reports_the_working_model() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/wallet/self/api-credit"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"credit": 12.5})))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(wav_bytes(2000, 24_000)))
            .mount(&server)
            .await;
        let models = vec!["s2.1-pro-free".to_owned()];
        let check = fish(&server).check(&models, &models).await.unwrap();
        assert_eq!(check, FishCheck { model: "s2.1-pro-free".into(), free: true, credit: Some(12.5) });
    }

    #[tokio::test]
    async fn fish_bad_key_and_voice_search() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/wallet/self/api-credit"))
            .respond_with(ResponseTemplate::new(401).set_body_json(serde_json::json!({"message": "invalid api key"})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/model"))
            .and(query_param("title", "narrator"))
            .and(query_param("sort_by", "task_count"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "total": 2,
                "items": [
                    {"_id": "a1", "type": "tts", "title": "Warm Narrator", "languages": ["en"], "tags": ["male"]},
                    {"_id": "b2", "type": "svc", "title": "Singer"}
                ]
            })))
            .mount(&server)
            .await;
        let f = fish(&server);
        assert!(matches!(f.credit().await, Err(VoiceError::Auth(m)) if m.contains("invalid api key")));
        let (total, voices) = f.voices(Some("narrator"), None, 1).await.unwrap();
        assert_eq!(total, 2);
        assert_eq!(voices.len(), 1, "singing models are left out");
        assert_eq!(voices[0].title, "Warm Narrator");
    }

    #[test]
    fn piper_catalog_is_bundled() {
        let voices = piper_voices();
        assert!(voices.len() > 50);
        let lessac = voices.iter().find(|v| v.key == "en_US-lessac-medium").unwrap();
        assert_eq!(lessac.language, "en_US");
        assert_eq!(lessac.files.len(), 3);
        let onnx = lessac.files.iter().find(|f| f.name() == "en_US-lessac-medium.onnx").unwrap();
        assert_eq!(onnx.md5, "2fc642b535197b6305c7c8f92dc8b24f");
        assert_eq!(onnx.path, "en/en_US/lessac/medium/en_US-lessac-medium.onnx");
    }

    /// A stand-in for the Piper program: reads a line, prints raw PCM.
    #[cfg(unix)]
    fn fake_piper(dir: &Path, script: &str) -> (PathBuf, PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let bin = dir.join("piper");
        std::fs::write(&bin, format!("#!/bin/sh\n{script}\n")).unwrap();
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        let model = dir.join("voice.onnx");
        std::fs::write(&model, b"onnx").unwrap();
        std::fs::write(dir.join("voice.onnx.json"), r#"{"audio": {"sample_rate": 16000}}"#).unwrap();
        (bin, model)
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn piper_streams_raw_pcm_from_the_process() {
        let dir = tempfile::tempdir().unwrap();
        // One second of 16-bit silence-ish samples (value 0x0101).
        let (bin, model) = fake_piper(dir.path(), "read line; head -c 32000 /dev/zero | tr '\\000' '\\001'");
        let piper = Piper::new(&bin, &model).unwrap();
        let clip = collect(piper.synthesize("Hello there.").await).await.unwrap();
        assert_eq!(clip.sample_rate, 16_000);
        assert_eq!(clip.samples.len(), 16_000);
        assert!((clip.samples[0] - i16_to_f32(0x0101)).abs() < 1e-6);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn piper_failures_are_reported() {
        let dir = tempfile::tempdir().unwrap();
        let (bin, model) = fake_piper(dir.path(), "read line; echo 'espeak-ng-data missing' >&2; exit 3");
        let piper = Piper::new(&bin, &model).unwrap();
        let err = collect(piper.synthesize("Hello.").await).await.unwrap_err();
        assert!(matches!(err, VoiceError::Failed(ref m) if m.contains("espeak-ng-data missing")), "{err}");
        assert!(matches!(Piper::new(&dir.path().join("nope"), &model), Err(VoiceError::Unavailable(_))));
    }

    #[tokio::test]
    async fn system_voice_works_on_windows_only() {
        let result = collect(SystemVoiceTts::new(None).synthesize("Hello.").await).await;
        if !cfg!(windows) {
            assert!(matches!(result, Err(VoiceError::Unavailable(_))));
        }
    }
}
