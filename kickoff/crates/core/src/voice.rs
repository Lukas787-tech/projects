//! Speech in and out, independent of engines and audio devices.
//!
//! - [`SpeechToText`] and [`TextToSpeech`] are implemented in `kickoff-voice`.
//! - [`SentenceSplitter`] cuts the producer's streamed reply into sentences,
//!   so speech starts before the reply is finished.
//! - [`Narrator`] speaks those sentences through an ordered list of engines.
//!   When one fails it moves on to the next, and it stops at once when the
//!   user interrupts.
//! - [`SilenceDetector`] ends a hands-free recording when the user stops
//!   talking.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;
use serde::{Deserialize, Serialize};
use tokio::sync::{Notify, mpsc};
use ts_rs::TS;

/// Mono audio, samples in -1.0..=1.0.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct AudioClip {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
}

impl AudioClip {
    pub fn new(samples: Vec<f32>, sample_rate: u32) -> Self {
        Self { samples, sample_rate }
    }

    pub fn duration(&self) -> Duration {
        if self.sample_rate == 0 {
            return Duration::ZERO;
        }
        Duration::from_secs_f64(self.samples.len() as f64 / f64::from(self.sample_rate))
    }

    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }
}

/// Root mean square of a block of samples: a simple loudness measure.
pub fn rms(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    (samples.iter().map(|s| s * s).sum::<f32>() / samples.len() as f32).sqrt()
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum VoiceError {
    /// Not installed, not downloaded, offline or unreachable.
    #[error("{0}")]
    Unavailable(String),
    #[error("the key was rejected ({0})")]
    Auth(String),
    /// Out of credits, or the free allowance is used up.
    #[error("no credits or free allowance left ({0})")]
    Quota(String),
    #[error("rate limited{}", retry_after.map(|d| format!(", retry in {} s", d.as_secs())).unwrap_or_default())]
    RateLimited { retry_after: Option<Duration> },
    #[error("{0}")]
    Invalid(String),
    #[error("stopped")]
    Cancelled,
    #[error("{0}")]
    Failed(String),
}

impl VoiceError {
    /// Machine-readable category, for the UI and for deciding how long to
    /// leave a failing engine alone.
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Unavailable(_) => "unavailable",
            Self::Auth(_) => "auth",
            Self::Quota(_) => "quota",
            Self::RateLimited { .. } => "rate_limited",
            Self::Invalid(_) => "invalid",
            Self::Cancelled => "cancelled",
            Self::Failed(_) => "failed",
        }
    }
}

#[async_trait]
pub trait SpeechToText: Send + Sync {
    fn id(&self) -> &str;
    /// Transcribe a recording. `language` is an ISO 639-1 code, or `None` to
    /// detect it.
    async fn transcribe(&self, clip: &AudioClip, language: Option<&str>) -> Result<String, VoiceError>;
}

/// Synthesized audio, delivered as it is produced.
pub type SpeechStream = BoxStream<'static, Result<AudioClip, VoiceError>>;

#[async_trait]
pub trait TextToSpeech: Send + Sync {
    fn id(&self) -> &str;
    /// Speak one short piece of text (a sentence or two).
    async fn synthesize(&self, text: &str) -> Result<SpeechStream, VoiceError>;
}

/// Where synthesized speech goes: the speakers, or a recorder in tests.
pub trait AudioSink: Send + Sync {
    /// Queue audio to play after what is already queued. Returns at once.
    fn enqueue(&self, clip: AudioClip);
    /// Stop playing and drop everything queued.
    fn stop(&self);
}

/// A shared "stop now" signal for barge-in.
#[derive(Clone, Default)]
pub struct Cancel(Arc<(AtomicBool, Notify)>);

impl Cancel {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.0.store(true, Ordering::SeqCst);
        self.0.1.notify_waiters();
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.0.load(Ordering::SeqCst)
    }

    /// Resolves once [`Cancel::cancel`] has been called.
    pub async fn cancelled(&self) {
        loop {
            let notified = self.0.1.notified();
            if self.is_cancelled() {
                return;
            }
            notified.await;
        }
    }
}

impl std::fmt::Debug for Cancel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("Cancel").field(&self.is_cancelled()).finish()
    }
}

// ------------------------------------------------------------- sentences

/// Text fit to be read aloud: markdown symbols and links removed.
pub fn speakable(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '*' | '_' | '#' | '`' | '~' | '>' => {}
            _ => out.push(c),
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

const ABBREVIATIONS: &[&str] =
    &["e.g.", "i.e.", "mr.", "mrs.", "ms.", "dr.", "vs.", "etc.", "approx.", "no.", "st.", "u.s.", "a.k.a."];

/// Cuts streamed text into sentences for speech.
///
/// A sentence ends at `.`, `!`, `?` or `…` followed by whitespace, or at a
/// line break. Very short sentences ("Nice.") are joined to the next one so
/// the voice doesn't sound choppy, and a run-on longer than `max_chars` is
/// cut at the last comma or space.
#[derive(Debug)]
pub struct SentenceSplitter {
    buf: String,
    held: String,
    min_chars: usize,
    max_chars: usize,
}

impl Default for SentenceSplitter {
    fn default() -> Self {
        Self::new(12, 280)
    }
}

impl SentenceSplitter {
    pub fn new(min_chars: usize, max_chars: usize) -> Self {
        Self { buf: String::new(), held: String::new(), min_chars, max_chars }
    }

    /// Add streamed text; returns the sentences it completed.
    pub fn push(&mut self, text: &str) -> Vec<String> {
        self.buf.push_str(text);
        let mut out = Vec::new();
        while let Some(end) = self.boundary() {
            let sentence: String = self.buf.drain(..end).collect();
            self.emit(sentence, &mut out);
        }
        out
    }

    /// The rest of the text once the stream has ended.
    pub fn finish(&mut self) -> Option<String> {
        let rest = std::mem::take(&mut self.buf);
        let joined = join(std::mem::take(&mut self.held), rest.trim());
        (!joined.is_empty()).then_some(joined)
    }

    fn emit(&mut self, sentence: String, out: &mut Vec<String>) {
        let sentence = join(std::mem::take(&mut self.held), sentence.trim());
        if sentence.is_empty() {
            return;
        }
        if sentence.chars().count() < self.min_chars {
            self.held = sentence;
        } else {
            out.push(sentence);
        }
    }

    /// Byte index just past the next sentence end, if one is complete.
    fn boundary(&self) -> Option<usize> {
        let chars: Vec<(usize, char)> = self.buf.char_indices().collect();
        for (i, &(at, c)) in chars.iter().enumerate() {
            let next = chars.get(i + 1).map(|&(_, n)| n);
            let end = at + c.len_utf8();
            match c {
                '\n' => return Some(end),
                '.' | '!' | '?' | '…' => {
                    // Wait for the next character to know whether this ends a sentence.
                    let next = next?;
                    if !next.is_whitespace() || (c == '.' && self.is_abbreviation(end)) {
                        continue;
                    }
                    return Some(end);
                }
                _ => {}
            }
        }
        if self.buf.chars().count() > self.max_chars {
            let limit = self.buf.char_indices().nth(self.max_chars).map_or(self.buf.len(), |(i, _)| i);
            let head = &self.buf[..limit];
            let cut = head.rfind([',', ';', ':']).map(|i| i + 1).or_else(|| head.rfind(' ')).unwrap_or(limit);
            return Some(cut.max(1));
        }
        None
    }

    fn is_abbreviation(&self, end: usize) -> bool {
        let before = &self.buf[..end];
        let word = before.rsplit(char::is_whitespace).next().unwrap_or(before).to_lowercase();
        ABBREVIATIONS.contains(&word.as_str())
    }
}

fn join(held: String, next: &str) -> String {
    match (held.is_empty(), next.is_empty()) {
        (true, _) => next.to_owned(),
        (false, true) => held,
        (false, false) => format!("{held} {next}"),
    }
}

// ------------------------------------------------------------- narration

/// What happened while speaking a reply.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(tag = "type", rename_all = "snake_case")]
#[ts(export)]
pub enum NarratorEvent {
    /// An engine failed; the rest is spoken by the next one.
    Switched { from: String, to: String, reason: String, error_kind: String },
    /// Every engine failed; the reply is shown as text only.
    GaveUp { reason: String },
    /// An engine spoke `bytes` of text (UTF-8), for usage tracking.
    Spoke { engine: String, bytes: usize },
}

/// Speaks a stream of sentences, falling back through `engines` in order.
pub struct Narrator {
    engines: Vec<Arc<dyn TextToSpeech>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NarrationEnd {
    Finished,
    Cancelled,
    GaveUp,
}

impl Narrator {
    pub fn new(engines: Vec<Arc<dyn TextToSpeech>>) -> Self {
        Self { engines }
    }

    pub fn is_empty(&self) -> bool {
        self.engines.is_empty()
    }

    /// Speak sentences as they arrive until the channel closes or `cancel`
    /// fires. Audio is queued on `sink`, so synthesis runs ahead of playback.
    pub async fn speak(
        &self,
        mut sentences: mpsc::UnboundedReceiver<String>,
        sink: &dyn AudioSink,
        cancel: &Cancel,
        on_event: &mut (dyn FnMut(NarratorEvent) + Send),
    ) -> NarrationEnd {
        let mut engine = 0;
        loop {
            let sentence = tokio::select! {
                biased;
                _ = cancel.cancelled() => return self.stop(sink),
                s = sentences.recv() => match s {
                    Some(s) => s,
                    None => return NarrationEnd::Finished,
                },
            };
            let text = speakable(&sentence);
            if text.is_empty() {
                continue;
            }
            loop {
                let Some(current) = self.engines.get(engine) else {
                    return NarrationEnd::GaveUp;
                };
                match say(current.as_ref(), &text, sink, cancel).await {
                    Ok(()) => {
                        on_event(NarratorEvent::Spoke { engine: current.id().to_owned(), bytes: text.len() });
                        break;
                    }
                    Err((VoiceError::Cancelled, _)) => return self.stop(sink),
                    Err((error, partly_spoken)) => {
                        tracing::warn!(engine = current.id(), %error, "speech engine failed");
                        engine += 1;
                        match self.engines.get(engine) {
                            Some(next) => on_event(NarratorEvent::Switched {
                                from: current.id().to_owned(),
                                to: next.id().to_owned(),
                                reason: error.to_string(),
                                error_kind: error.kind().to_owned(),
                            }),
                            None => {
                                on_event(NarratorEvent::GaveUp { reason: error.to_string() });
                                return NarrationEnd::GaveUp;
                            }
                        }
                        // Don't repeat a sentence the listener already half heard.
                        if partly_spoken {
                            break;
                        }
                    }
                }
            }
        }
    }

    fn stop(&self, sink: &dyn AudioSink) -> NarrationEnd {
        sink.stop();
        NarrationEnd::Cancelled
    }
}

/// Synthesize one sentence into the sink. On error, also says whether some
/// of it was already queued.
async fn say(
    engine: &dyn TextToSpeech,
    text: &str,
    sink: &dyn AudioSink,
    cancel: &Cancel,
) -> Result<(), (VoiceError, bool)> {
    let mut stream = tokio::select! {
        biased;
        _ = cancel.cancelled() => return Err((VoiceError::Cancelled, false)),
        s = engine.synthesize(text) => s.map_err(|e| (e, false))?,
    };
    let mut queued = false;
    loop {
        let chunk = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Err((VoiceError::Cancelled, queued)),
            c = stream.next() => c,
        };
        match chunk {
            None if queued => return Ok(()),
            None => return Err((VoiceError::Failed("the engine returned no audio".into()), false)),
            Some(Ok(clip)) if clip.is_empty() => {}
            Some(Ok(clip)) => {
                sink.enqueue(clip);
                queued = true;
            }
            Some(Err(e)) => return Err((e, queued)),
        }
    }
}

// ------------------------------------------------------------- hands-free

/// Why a hands-free recording stopped.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum StopReason {
    /// The user spoke and then went quiet.
    EndOfSpeech,
    /// Nothing was said.
    NoSpeech,
    /// The recording reached its length limit.
    TooLong,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VadDecision {
    Waiting,
    Hearing,
    Stop(StopReason),
}

/// Energy-based end-of-speech detection. Learns the room's noise level from
/// quiet blocks and treats anything well above it as speech.
#[derive(Debug, Clone)]
pub struct SilenceDetector {
    pub end_silence: Duration,
    pub min_speech: Duration,
    pub max_wait: Duration,
    pub max_length: Duration,
    noise: Option<f32>,
    speech: Duration,
    silence: Duration,
    elapsed: Duration,
}

impl Default for SilenceDetector {
    fn default() -> Self {
        Self {
            end_silence: Duration::from_millis(1300),
            min_speech: Duration::from_millis(300),
            max_wait: Duration::from_secs(8),
            max_length: Duration::from_secs(120),
            noise: None,
            speech: Duration::ZERO,
            silence: Duration::ZERO,
            elapsed: Duration::ZERO,
        }
    }
}

impl SilenceDetector {
    const MIN_THRESHOLD: f32 = 0.012;
    /// The first moments set the noise floor; speech has gaps, so the
    /// quietest block wins.
    const CALIBRATION: Duration = Duration::from_millis(300);
    /// A floor above this is someone already talking, not the room.
    const MAX_NOISE: f32 = 0.04;

    /// Feed the loudness of the next `dt` of audio.
    pub fn feed(&mut self, level: f32, dt: Duration) -> VadDecision {
        self.elapsed += dt;
        if self.elapsed <= Self::CALIBRATION {
            let floor = self.noise.map_or(level, |n| n.min(level));
            self.noise = Some(floor.min(Self::MAX_NOISE));
            return VadDecision::Waiting;
        }
        let threshold = self.noise.map_or(Self::MIN_THRESHOLD, |n| (n * 3.0).max(Self::MIN_THRESHOLD));
        let loud = level > threshold;
        if loud {
            self.speech += dt;
            self.silence = Duration::ZERO;
        } else {
            self.silence += dt;
            // Track the noise floor only while quiet.
            self.noise = Some(self.noise.map_or(level, |n| n * 0.9 + level * 0.1).min(Self::MAX_NOISE));
        }
        let heard = self.speech >= self.min_speech;
        if self.elapsed >= self.max_length {
            return VadDecision::Stop(StopReason::TooLong);
        }
        if heard && self.silence >= self.end_silence {
            return VadDecision::Stop(StopReason::EndOfSpeech);
        }
        if !heard && self.elapsed >= self.max_wait {
            return VadDecision::Stop(StopReason::NoSpeech);
        }
        if heard { VadDecision::Hearing } else { VadDecision::Waiting }
    }

    pub fn heard_speech(&self) -> bool {
        self.speech >= self.min_speech
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use futures::stream;

    use super::*;

    fn split_all(chunks: &[&str]) -> Vec<String> {
        let mut s = SentenceSplitter::default();
        let mut out: Vec<String> = chunks.iter().flat_map(|c| s.push(c)).collect();
        out.extend(s.finish());
        out
    }

    #[test]
    fn splits_streamed_text_into_sentences() {
        let out = split_all(&[
            "Love the lighthouse idea",
            ". Who do you picture play",
            "ing it? And what do they play to",
            "day",
        ]);
        assert_eq!(out, ["Love the lighthouse idea.", "Who do you picture playing it?", "And what do they play today"]);
    }

    #[test]
    fn waits_for_whitespace_after_a_full_stop() {
        let mut s = SentenceSplitter::default();
        assert!(s.push("It costs about 3.").is_empty());
        assert!(s.push("5 dollars on Steam").is_empty());
        assert_eq!(s.finish().unwrap(), "It costs about 3.5 dollars on Steam");
    }

    #[test]
    fn keeps_abbreviations_and_joins_short_sentences() {
        let out = split_all(&["Nice. Games like Stardew, e.g. cozy farming sims, sell well. Right?"]);
        assert_eq!(out, ["Nice. Games like Stardew, e.g. cozy farming sims, sell well.", "Right?"]);
    }

    #[test]
    fn cuts_run_on_text_at_a_comma() {
        let mut s = SentenceSplitter::new(5, 40);
        let out = s.push("one two three four, five six seven eight nine ten eleven");
        assert_eq!(out, ["one two three four,"]);
    }

    #[test]
    fn line_breaks_end_sentences() {
        assert_eq!(split_all(&["First idea here\nSecond idea here"]), ["First idea here", "Second idea here"]);
    }

    #[test]
    fn speakable_drops_markdown() {
        assert_eq!(speakable("**Bold** and `code`\n# heading"), "Bold and code heading");
    }

    struct FakeTts {
        id: &'static str,
        fail_with: Option<VoiceError>,
        fail_after_first_chunk: bool,
        spoken: Mutex<Vec<String>>,
    }

    impl FakeTts {
        fn ok(id: &'static str) -> Arc<Self> {
            Arc::new(Self { id, fail_with: None, fail_after_first_chunk: false, spoken: Mutex::default() })
        }
        fn failing(id: &'static str, e: VoiceError) -> Arc<Self> {
            Arc::new(Self { id, fail_with: Some(e), fail_after_first_chunk: false, spoken: Mutex::default() })
        }
    }

    #[async_trait]
    impl TextToSpeech for FakeTts {
        fn id(&self) -> &str {
            self.id
        }
        async fn synthesize(&self, text: &str) -> Result<SpeechStream, VoiceError> {
            self.spoken.lock().unwrap().push(text.to_owned());
            let clip = AudioClip::new(vec![0.1; 10], 16_000);
            match (&self.fail_with, self.fail_after_first_chunk) {
                (Some(e), false) => Err(e.clone()),
                (Some(e), true) => Ok(stream::iter(vec![Ok(clip), Err(e.clone())]).boxed()),
                (None, _) => Ok(stream::iter(vec![Ok(clip.clone()), Ok(clip)]).boxed()),
            }
        }
    }

    #[derive(Default)]
    struct FakeSink {
        clips: Mutex<usize>,
        stopped: AtomicBool,
    }

    impl AudioSink for FakeSink {
        fn enqueue(&self, _clip: AudioClip) {
            *self.clips.lock().unwrap() += 1;
        }
        fn stop(&self) {
            self.stopped.store(true, Ordering::SeqCst);
        }
    }

    async fn narrate(
        engines: Vec<Arc<dyn TextToSpeech>>,
        sentences: &[&str],
    ) -> (NarrationEnd, Vec<NarratorEvent>, FakeSink) {
        let (tx, rx) = mpsc::unbounded_channel();
        for s in sentences {
            tx.send((*s).to_owned()).unwrap();
        }
        drop(tx);
        let sink = FakeSink::default();
        let mut events = Vec::new();
        let end = Narrator::new(engines).speak(rx, &sink, &Cancel::new(), &mut |e| events.push(e)).await;
        (end, events, sink)
    }

    #[tokio::test]
    async fn speaks_every_sentence_and_reports_usage() {
        let fish = FakeTts::ok("fish_audio");
        let (end, events, sink) = narrate(vec![fish.clone()], &["Hello there.", "**How** are you?"]).await;
        assert_eq!(end, NarrationEnd::Finished);
        assert_eq!(*sink.clips.lock().unwrap(), 4);
        assert_eq!(*fish.spoken.lock().unwrap(), ["Hello there.", "How are you?"]);
        assert_eq!(events[1], NarratorEvent::Spoke { engine: "fish_audio".into(), bytes: 12 });
    }

    #[tokio::test]
    async fn falls_back_and_stays_on_the_working_engine() {
        let fish = FakeTts::failing("fish_audio", VoiceError::Quota("402".into()));
        let piper = FakeTts::ok("piper");
        let (end, events, _) = narrate(vec![fish.clone(), piper.clone()], &["One sentence.", "Two sentences."]).await;
        assert_eq!(end, NarrationEnd::Finished);
        assert_eq!(fish.spoken.lock().unwrap().len(), 1, "a failed engine isn't retried");
        assert_eq!(*piper.spoken.lock().unwrap(), ["One sentence.", "Two sentences."]);
        assert!(
            matches!(&events[0], NarratorEvent::Switched { from, to, .. } if from == "fish_audio" && to == "piper")
        );
    }

    #[tokio::test]
    async fn a_half_spoken_sentence_is_not_repeated() {
        let flaky = Arc::new(FakeTts {
            id: "fish_audio",
            fail_with: Some(VoiceError::Failed("connection reset".into())),
            fail_after_first_chunk: true,
            spoken: Mutex::default(),
        });
        let piper = FakeTts::ok("piper");
        let (_, _, _) = narrate(vec![flaky, piper.clone()], &["First one here.", "Second one here."]).await;
        assert_eq!(*piper.spoken.lock().unwrap(), ["Second one here."]);
    }

    #[tokio::test]
    async fn gives_up_when_every_engine_fails() {
        let a = FakeTts::failing("fish_audio", VoiceError::Unavailable("offline".into()));
        let b = FakeTts::failing("system", VoiceError::Unavailable("no voices".into()));
        let (end, events, _) = narrate(vec![a, b], &["Hello there friend."]).await;
        assert_eq!(end, NarrationEnd::GaveUp);
        assert!(matches!(events.last(), Some(NarratorEvent::GaveUp { reason }) if reason == "no voices"));
    }

    #[tokio::test]
    async fn cancel_stops_playback() {
        let (tx, rx) = mpsc::unbounded_channel::<String>();
        let sink = FakeSink::default();
        let cancel = Cancel::new();
        let narrator = Narrator::new(vec![FakeTts::ok("piper")]);
        let stopper = cancel.clone();
        tx.send("Hello there friend.".into()).unwrap();
        let task = async {
            tokio::task::yield_now().await;
            stopper.cancel();
        };
        // The channel stays open, so only the cancel can end the narration.
        let mut ignore = |_| {};
        let (end, ()) = tokio::join!(narrator.speak(rx, &sink, &cancel, &mut ignore), task);
        assert_eq!(end, NarrationEnd::Cancelled);
        assert!(sink.stopped.load(Ordering::SeqCst));
        drop(tx);
    }

    fn feed(d: &mut SilenceDetector, level: f32, ms: u64) -> VadDecision {
        let mut last = VadDecision::Waiting;
        for _ in 0..ms / 50 {
            last = d.feed(level, Duration::from_millis(50));
            if matches!(last, VadDecision::Stop(_)) {
                break;
            }
        }
        last
    }

    #[test]
    fn hands_free_stops_after_speech_then_silence() {
        let mut d = SilenceDetector::default();
        assert_eq!(feed(&mut d, 0.004, 500), VadDecision::Waiting);
        assert_eq!(feed(&mut d, 0.2, 1000), VadDecision::Hearing);
        assert_eq!(feed(&mut d, 0.005, 1000), VadDecision::Hearing, "a short pause keeps listening");
        assert_eq!(feed(&mut d, 0.2, 200), VadDecision::Hearing);
        assert_eq!(feed(&mut d, 0.005, 2000), VadDecision::Stop(StopReason::EndOfSpeech));
    }

    #[test]
    fn hands_free_gives_up_without_speech_and_adapts_to_noise() {
        let mut d = SilenceDetector::default();
        // A noisy room (fan hum) is not mistaken for speech.
        assert_eq!(feed(&mut d, 0.03, 300), VadDecision::Waiting);
        assert_eq!(feed(&mut d, 0.04, 9000), VadDecision::Stop(StopReason::NoSpeech));
    }

    #[tokio::test]
    async fn cancel_wakes_waiters() {
        let c = Cancel::new();
        let c2 = c.clone();
        let waiter = tokio::spawn(async move { c2.cancelled().await });
        tokio::task::yield_now().await;
        c.cancel();
        waiter.await.unwrap();
        assert!(c.is_cancelled());
        c.cancelled().await;
    }
}
