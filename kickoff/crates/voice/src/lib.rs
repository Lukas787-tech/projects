//! Audio and speech engines for Kickoff: microphone capture, playback, speech
//! to text (local Whisper, Groq) and text to speech (Fish Audio, Piper, the
//! system's voices). The engine-independent logic (sentence splitting,
//! fallback, barge-in) lives in `kickoff_core::voice`.

pub mod capture;
pub mod download;
pub mod dsp;
pub mod playback;
pub mod stt;
pub mod tts;
pub mod wav;
