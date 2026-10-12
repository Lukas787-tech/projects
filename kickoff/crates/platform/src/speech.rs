//! The operating system's own text-to-speech voices: the zero-download last
//! resort when no other speech engine works. On Windows this is WinRT
//! `Windows.Media.SpeechSynthesis`; elsewhere it reports itself unavailable.

/// A voice installed on this computer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SystemVoice {
    pub id: String,
    pub name: String,
    /// BCP 47 tag such as `en-US`.
    pub language: String,
    pub female: bool,
}

#[derive(Debug, thiserror::Error)]
pub enum SpeechError {
    #[error("system voices are not available on this platform")]
    Unsupported,
    #[error("no system voice is installed")]
    NoVoices,
    #[error("the system voice failed: {0}")]
    Failed(String),
}

/// Installed voices, default voice first.
pub fn system_voices() -> Result<Vec<SystemVoice>, SpeechError> {
    imp::voices()
}

/// Speak `text` with the voice `voice_id` (or the default voice) and return
/// the audio as a WAV file. Blocks; call it off the async runtime.
pub fn system_speak_wav(text: &str, voice_id: Option<&str>) -> Result<Vec<u8>, SpeechError> {
    imp::speak_wav(text, voice_id)
}

#[cfg(windows)]
mod imp {
    use windows::Media::SpeechSynthesis::{SpeechSynthesizer, VoiceGender, VoiceInformation};
    use windows::Storage::Streams::DataReader;
    use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx};
    use windows::core::HSTRING;

    use super::{SpeechError, SystemVoice};

    fn failed(e: windows::core::Error) -> SpeechError {
        SpeechError::Failed(e.message())
    }

    /// WinRT needs COM on the calling thread. A thread that is already set
    /// up (for example as single-threaded) is fine as it is.
    fn init_com() {
        // SAFETY: plain COM initialisation with no reserved pointer; a
        // "changed mode" result just means the thread was already set up.
        let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
    }

    fn describe(v: &VoiceInformation) -> windows::core::Result<SystemVoice> {
        Ok(SystemVoice {
            id: v.Id()?.to_string(),
            name: v.DisplayName()?.to_string(),
            language: v.Language()?.to_string(),
            female: v.Gender()? == VoiceGender::Female,
        })
    }

    pub fn voices() -> Result<Vec<SystemVoice>, SpeechError> {
        init_com();
        let default_id = SpeechSynthesizer::DefaultVoice().and_then(|v| v.Id()).ok();
        let mut voices: Vec<SystemVoice> =
            SpeechSynthesizer::AllVoices().map_err(failed)?.into_iter().filter_map(|v| describe(&v).ok()).collect();
        voices.sort_by_key(|v| Some(HSTRING::from(v.id.as_str())) != default_id);
        Ok(voices)
    }

    pub fn speak_wav(text: &str, voice_id: Option<&str>) -> Result<Vec<u8>, SpeechError> {
        init_com();
        let synth = SpeechSynthesizer::new().map_err(failed)?;
        if let Some(id) = voice_id {
            let all = SpeechSynthesizer::AllVoices().map_err(failed)?;
            if let Some(voice) = all.into_iter().find(|v| v.Id().is_ok_and(|v| v == id)) {
                synth.SetVoice(&voice).map_err(failed)?;
            }
        }
        let stream = synth.SynthesizeTextToStreamAsync(&HSTRING::from(text)).map_err(failed)?.join().map_err(failed)?;
        let size =
            u32::try_from(stream.Size().map_err(failed)?).map_err(|_| SpeechError::Failed("speech too long".into()))?;
        let reader = DataReader::CreateDataReader(&stream.GetInputStreamAt(0).map_err(failed)?).map_err(failed)?;
        let loaded = reader.LoadAsync(size).map_err(failed)?.join().map_err(failed)?;
        let mut bytes = vec![0u8; loaded as usize];
        reader.ReadBytes(&mut bytes).map_err(failed)?;
        Ok(bytes)
    }
}

#[cfg(not(windows))]
mod imp {
    use super::{SpeechError, SystemVoice};

    pub fn voices() -> Result<Vec<SystemVoice>, SpeechError> {
        Err(SpeechError::Unsupported)
    }

    pub fn speak_wav(_text: &str, _voice_id: Option<&str>) -> Result<Vec<u8>, SpeechError> {
        Err(SpeechError::Unsupported)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// On Windows (CI included) this exercises the real voices when the
    /// machine has any; elsewhere it checks the unavailable path.
    #[test]
    fn system_voices_work_or_say_why() {
        match system_voices() {
            Ok(voices) if !voices.is_empty() => {
                let wav = system_speak_wav("Hello.", Some(&voices[0].id)).unwrap();
                assert_eq!(&wav[..4], b"RIFF");
                assert!(wav.len() > 1000);
            }
            Ok(_) => eprintln!("no system voices installed; skipping synthesis"),
            Err(SpeechError::Unsupported) if !cfg!(windows) => {}
            Err(e) => panic!("{e}"),
        }
    }
}
