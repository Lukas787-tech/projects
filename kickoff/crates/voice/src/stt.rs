//! Speech to text: local Whisper (whisper.cpp) and Groq's hosted Whisper.

use std::time::Duration;

use async_trait::async_trait;
use kickoff_core::Secret;
use kickoff_core::voice::{AudioClip, SpeechToText, VoiceError, rms};

use crate::capture::SPEECH_RATE;
use crate::dsp::resample;
use crate::wav;

/// Shorter or quieter than this is treated as "nothing said". Whisper tends
/// to invent a polite sentence for silence, so it never sees such clips.
const MIN_SPEECH: Duration = Duration::from_millis(300);
const MIN_LEVEL: f32 = 0.003;

fn too_quiet(clip: &AudioClip) -> bool {
    clip.duration() < MIN_SPEECH || rms(&clip.samples) < MIN_LEVEL
}

fn at_speech_rate(clip: &AudioClip) -> AudioClip {
    if clip.sample_rate == SPEECH_RATE {
        clip.clone()
    } else {
        AudioClip::new(resample(&clip.samples, clip.sample_rate, SPEECH_RATE), SPEECH_RATE)
    }
}

/// OpenAI-compatible `POST /audio/transcriptions` (Groq).
pub struct CloudWhisper {
    id: String,
    client: reqwest::Client,
    base_url: String,
    key: Secret,
    model: String,
}

impl CloudWhisper {
    pub fn new(id: &str, client: reqwest::Client, base_url: &str, key: Secret, model: &str) -> Self {
        Self {
            id: id.to_owned(),
            client,
            base_url: base_url.trim_end_matches('/').to_owned(),
            key,
            model: model.to_owned(),
        }
    }
}

#[derive(serde::Deserialize)]
struct Transcription {
    text: String,
}

#[async_trait]
impl SpeechToText for CloudWhisper {
    fn id(&self) -> &str {
        &self.id
    }

    async fn transcribe(&self, clip: &AudioClip, language: Option<&str>) -> Result<String, VoiceError> {
        if too_quiet(clip) {
            return Ok(String::new());
        }
        let wav = wav::encode_pcm16(&at_speech_rate(clip));
        let file = reqwest::multipart::Part::bytes(wav)
            .file_name("speech.wav")
            .mime_str("audio/wav")
            .map_err(|e| VoiceError::Failed(e.to_string()))?;
        let mut form = reqwest::multipart::Form::new()
            .part("file", file)
            .text("model", self.model.clone())
            .text("response_format", "json")
            .text("temperature", "0");
        if let Some(language) = language {
            form = form.text("language", language.to_owned());
        }
        let response = self
            .client
            .post(format!("{}/audio/transcriptions", self.base_url))
            .bearer_auth(self.key.expose())
            .multipart(form)
            .timeout(Duration::from_secs(60))
            .send()
            .await
            .map_err(|e| VoiceError::Unavailable(format!("couldn't reach the speech service: {e}")))?;
        let status = response.status();
        if !status.is_success() {
            return Err(http_error(
                status.as_u16(),
                retry_after(&response),
                &response.text().await.unwrap_or_default(),
            ));
        }
        let body: Transcription =
            response.json().await.map_err(|e| VoiceError::Invalid(format!("unexpected transcription reply: {e}")))?;
        Ok(body.text.trim().to_owned())
    }
}

pub(crate) fn retry_after(response: &reqwest::Response) -> Option<Duration> {
    response
        .headers()
        .get(reqwest::header::RETRY_AFTER)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.trim().parse::<f64>().ok())
        .map(Duration::from_secs_f64)
}

/// Map an HTTP failure to a voice error. `body` is shortened for messages.
pub(crate) fn http_error(status: u16, retry_after: Option<Duration>, body: &str) -> VoiceError {
    let detail: String = body.chars().take(200).collect();
    match status {
        401 => VoiceError::Auth(detail),
        402 => VoiceError::Quota(detail),
        403 => VoiceError::Quota(format!("not allowed on this plan: {detail}")),
        413 => VoiceError::Invalid("the recording is too long for this service".into()),
        429 => VoiceError::RateLimited { retry_after },
        500.. => VoiceError::Unavailable(format!("the service is having problems (HTTP {status})")),
        _ => VoiceError::Failed(format!("HTTP {status}: {detail}")),
    }
}

#[cfg(feature = "local-whisper")]
pub use local::LocalWhisper;

#[cfg(feature = "local-whisper")]
mod local {
    use std::path::Path;
    use std::sync::{Arc, Once};

    use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

    use super::*;

    /// A segment more likely silence than speech is dropped.
    const MAX_NO_SPEECH: f32 = 0.6;

    /// whisper.cpp running on this computer's CPU.
    pub struct LocalWhisper {
        context: Arc<WhisperContext>,
    }

    impl LocalWhisper {
        /// Load a ggml model file. Slow (hundreds of ms to seconds): call it
        /// off the async runtime and keep the result.
        pub fn load(model: &Path) -> Result<Self, VoiceError> {
            static HOOKS: Once = Once::new();
            HOOKS.call_once(whisper_rs::install_logging_hooks);
            let path = model.to_str().ok_or_else(|| VoiceError::Invalid("model path is not valid UTF-8".into()))?;
            let context = WhisperContext::new_with_params(path, WhisperContextParameters::default())
                .map_err(|e| VoiceError::Unavailable(format!("couldn't load the speech model: {e}")))?;
            Ok(Self { context: Arc::new(context) })
        }

        fn run(context: &WhisperContext, clip: &AudioClip, language: Option<&str>) -> Result<String, VoiceError> {
            let failed = |e: whisper_rs::WhisperError| VoiceError::Failed(format!("speech recognition failed: {e}"));
            let mut state = context.create_state().map_err(failed)?;
            let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
            let threads = std::thread::available_parallelism().map_or(4, |n| n.get()).clamp(1, 8);
            params.set_n_threads(threads as i32);
            params.set_language(Some(language.unwrap_or("auto")));
            params.set_translate(false);
            params.set_no_context(true);
            params.set_suppress_blank(true);
            params.set_suppress_nst(true);
            params.set_print_special(false);
            params.set_print_progress(false);
            params.set_print_realtime(false);
            params.set_print_timestamps(false);
            state.full(params, &clip.samples).map_err(failed)?;
            let text: Vec<String> = state
                .as_iter()
                .filter(|s| s.no_speech_probability() < MAX_NO_SPEECH)
                .filter_map(|s| s.to_str_lossy().ok().map(|t| t.trim().to_owned()))
                .filter(|t| !t.is_empty())
                .collect();
            Ok(text.join(" "))
        }
    }

    #[async_trait]
    impl SpeechToText for LocalWhisper {
        fn id(&self) -> &str {
            "whisper_local"
        }

        async fn transcribe(&self, clip: &AudioClip, language: Option<&str>) -> Result<String, VoiceError> {
            if too_quiet(clip) {
                return Ok(String::new());
            }
            let clip = at_speech_rate(clip);
            let context = self.context.clone();
            let language = language.map(str::to_owned);
            tokio::task::spawn_blocking(move || Self::run(&context, &clip, language.as_deref()))
                .await
                .map_err(|e| VoiceError::Failed(e.to_string()))?
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// Runs the whole pipeline when `KICKOFF_WHISPER_TEST_MODEL` and
        /// `KICKOFF_WHISPER_TEST_WAV` point at a ggml model and a 16 kHz WAV
        /// (for example whisper.cpp's `for-tests-ggml-tiny.bin` and
        /// `samples/jfk.wav`). Skipped otherwise.
        #[tokio::test]
        async fn transcribes_with_a_real_model() {
            let (Ok(model), Ok(wav)) =
                (std::env::var("KICKOFF_WHISPER_TEST_MODEL"), std::env::var("KICKOFF_WHISPER_TEST_WAV"))
            else {
                eprintln!("set KICKOFF_WHISPER_TEST_MODEL and KICKOFF_WHISPER_TEST_WAV to run");
                return;
            };
            let whisper = LocalWhisper::load(Path::new(&model)).unwrap();
            let clip = wav::decode(&std::fs::read(wav).unwrap()).unwrap();
            let text = whisper.transcribe(&clip, Some("en")).await.unwrap();
            eprintln!("transcript: {text:?}");
            assert_eq!(whisper.transcribe(&AudioClip::new(vec![0.0; 16_000], 16_000), None).await.unwrap(), "");
        }

        #[test]
        fn a_missing_model_is_unavailable() {
            assert!(matches!(
                LocalWhisper::load(Path::new("/nonexistent/ggml-base.bin")),
                Err(VoiceError::Unavailable(_))
            ));
        }
    }
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{header, method, path};
    use wiremock::{Match, Mock, MockServer, Request, ResponseTemplate};

    use super::*;

    /// Like `body_string_contains`, for bodies that also hold binary audio.
    struct BodyHas(&'static str);

    impl Match for BodyHas {
        fn matches(&self, request: &Request) -> bool {
            request.body.windows(self.0.len()).any(|w| w == self.0.as_bytes())
        }
    }

    fn speech() -> AudioClip {
        let samples = (0..48_000).map(|i| ((i as f32) * 0.05).sin() * 0.3).collect();
        AudioClip::new(samples, 48_000)
    }

    fn groq(server: &MockServer) -> CloudWhisper {
        CloudWhisper::new(
            "groq_whisper",
            reqwest::Client::new(),
            &format!("{}/openai/v1", server.uri()),
            Secret::new("gsk_test"),
            "whisper-large-v3-turbo",
        )
    }

    #[tokio::test]
    async fn uploads_16k_wav_and_reads_the_text() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/openai/v1/audio/transcriptions"))
            .and(header("authorization", "Bearer gsk_test"))
            .and(BodyHas("whisper-large-v3-turbo"))
            .and(BodyHas("name=\"language\"\r\n\r\nde"))
            .and(BodyHas("filename=\"speech.wav\""))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"text": " Ein Leuchtturm im Winter. "})),
            )
            .mount(&server)
            .await;
        let text = groq(&server).transcribe(&speech(), Some("de")).await.unwrap();
        assert_eq!(text, "Ein Leuchtturm im Winter.");
        let request = &server.received_requests().await.unwrap()[0];
        let body = String::from_utf8_lossy(&request.body);
        assert!(body.contains("RIFF"), "sends WAV");
        // One second at 16 kHz, 16-bit, plus the header.
        assert!(request.body.len() > 32_000 && request.body.len() < 34_000, "{}", request.body.len());
    }

    #[tokio::test]
    async fn silence_is_not_sent() {
        let server = MockServer::start().await;
        let text = groq(&server).transcribe(&AudioClip::new(vec![0.0; 32_000], 16_000), None).await.unwrap();
        assert_eq!(text, "");
        assert!(server.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn maps_http_errors() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(429).insert_header("retry-after", "7"))
            .mount(&server)
            .await;
        let err = groq(&server).transcribe(&speech(), None).await.unwrap_err();
        assert_eq!(err, VoiceError::RateLimited { retry_after: Some(Duration::from_secs(7)) });
        assert!(matches!(http_error(401, None, "bad key"), VoiceError::Auth(_)));
        assert!(matches!(http_error(402, None, ""), VoiceError::Quota(_)));
        assert!(matches!(http_error(503, None, ""), VoiceError::Unavailable(_)));
    }
}
