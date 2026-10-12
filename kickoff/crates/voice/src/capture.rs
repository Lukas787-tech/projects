//! Microphone capture. Audio streams can't move between threads on every
//! platform, so each recording owns a small thread of its own.

use std::sync::{Arc, Mutex, mpsc};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, Sample, SizedSample};
use kickoff_core::voice::{AudioClip, VoiceError};

use crate::dsp::resample;

/// Whisper and the cloud engines want 16 kHz mono.
pub const SPEECH_RATE: u32 = 16_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputDevice {
    /// Stable identifier to store in settings.
    pub id: String,
    pub name: String,
    pub is_default: bool,
}

fn unavailable(e: impl std::fmt::Display) -> VoiceError {
    VoiceError::Unavailable(format!("microphone: {e}"))
}

/// Microphones on this computer, default first.
pub fn input_devices() -> Result<Vec<InputDevice>, VoiceError> {
    let host = cpal::default_host();
    let default_id = host.default_input_device().and_then(|d| d.id().ok());
    let mut devices: Vec<InputDevice> = host
        .input_devices()
        .map_err(unavailable)?
        .filter_map(|d| {
            let id = d.id().ok()?;
            let name = d.description().map(|desc| desc.name().to_owned()).unwrap_or_else(|_| id.to_string());
            Some(InputDevice { is_default: Some(&id) == default_id.as_ref(), id: id.to_string(), name })
        })
        .collect();
    devices.sort_by_key(|d| !d.is_default);
    Ok(devices)
}

fn find_device(id: Option<&str>) -> Result<cpal::Device, VoiceError> {
    let host = cpal::default_host();
    let chosen = id.and_then(|id| id.parse().ok()).and_then(|id| host.device_by_id(&id));
    chosen.or_else(|| host.default_input_device()).ok_or_else(|| unavailable("no microphone found"))
}

/// A recording in progress. Dropping it discards the audio.
pub struct Recording {
    stop: mpsc::Sender<()>,
    done: mpsc::Receiver<AudioClip>,
}

impl Recording {
    /// Start recording from `device_id` (or the default microphone). Loudness
    /// (RMS, 0..1) is reported about every 50 ms. Recording stops by itself
    /// after `max`.
    pub fn start(
        device_id: Option<&str>,
        max: Duration,
        on_level: impl FnMut(f32) + Send + 'static,
    ) -> Result<Self, VoiceError> {
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(), VoiceError>>();
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let (done_tx, done_rx) = mpsc::channel();
        let device_id = device_id.map(str::to_owned);
        std::thread::Builder::new()
            .name("microphone".into())
            .spawn(move || {
                let samples = Arc::new(Mutex::new(Vec::new()));
                let (stream, rate) = match open(device_id.as_deref(), samples.clone(), Box::new(on_level)) {
                    Ok(opened) => opened,
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };
                let _ = ready_tx.send(Ok(()));
                // Ends on stop, on drop of the recording, or at the time limit.
                let _ = stop_rx.recv_timeout(max);
                drop(stream);
                let raw = std::mem::take(&mut *samples.lock().unwrap_or_else(|p| p.into_inner()));
                let _ = done_tx.send(AudioClip::new(resample(&raw, rate, SPEECH_RATE), SPEECH_RATE));
            })
            .map_err(unavailable)?;
        ready_rx.recv().map_err(unavailable)??;
        Ok(Self { stop: stop_tx, done: done_rx })
    }

    /// Stop and return the audio at 16 kHz mono. Blocks briefly.
    pub fn finish(self) -> Result<AudioClip, VoiceError> {
        let _ = self.stop.send(());
        self.done.recv().map_err(|_| VoiceError::Failed("the recording thread stopped unexpectedly".into()))
    }
}

type LevelFn = Box<dyn FnMut(f32) + Send>;

fn open(
    device_id: Option<&str>,
    samples: Arc<Mutex<Vec<f32>>>,
    on_level: LevelFn,
) -> Result<(cpal::Stream, u32), VoiceError> {
    let device = find_device(device_id)?;
    let supported = device.default_input_config().map_err(unavailable)?;
    let config: cpal::StreamConfig = supported.config();
    let rate = config.sample_rate;
    let stream = match supported.sample_format() {
        cpal::SampleFormat::F32 => build::<f32>(&device, &config, samples, on_level),
        cpal::SampleFormat::I16 => build::<i16>(&device, &config, samples, on_level),
        cpal::SampleFormat::I32 => build::<i32>(&device, &config, samples, on_level),
        cpal::SampleFormat::U16 => build::<u16>(&device, &config, samples, on_level),
        cpal::SampleFormat::U8 => build::<u8>(&device, &config, samples, on_level),
        other => return Err(unavailable(format!("unsupported sample format {other:?}"))),
    }
    .map_err(unavailable)?;
    stream.play().map_err(unavailable)?;
    Ok((stream, rate))
}

fn build<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    samples: Arc<Mutex<Vec<f32>>>,
    mut on_level: LevelFn,
) -> Result<cpal::Stream, cpal::Error>
where
    T: SizedSample,
    f32: FromSample<T>,
{
    let channels = usize::from(config.channels.max(1));
    let block = (config.sample_rate / 20).max(1) as usize;
    let (mut squares, mut count) = (0.0f32, 0usize);
    device.build_input_stream(
        *config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            let mono: Vec<f32> = data
                .chunks(channels)
                .map(|frame| frame.iter().map(|s| f32::from_sample(*s)).sum::<f32>() / channels as f32)
                .collect();
            for s in &mono {
                squares += s * s;
                count += 1;
                if count >= block {
                    on_level((squares / count as f32).sqrt());
                    (squares, count) = (0.0, 0);
                }
            }
            if let Ok(mut all) = samples.lock() {
                all.extend_from_slice(&mono);
            }
        },
        |e| tracing::warn!(error = %e, "microphone stream error"),
        None,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Machines without a microphone (CI, containers) must fail cleanly,
    /// never panic or hang.
    #[test]
    fn records_or_reports_no_microphone() {
        let _ = input_devices();
        match Recording::start(None, Duration::from_secs(2), |_| {}) {
            Ok(rec) => {
                std::thread::sleep(Duration::from_millis(200));
                let clip = rec.finish().unwrap();
                assert_eq!(clip.sample_rate, SPEECH_RATE);
            }
            Err(VoiceError::Unavailable(message)) => assert!(message.starts_with("microphone"), "{message}"),
            Err(e) => panic!("unexpected error: {e}"),
        }
    }
}
