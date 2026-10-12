//! Speaker output. Like capture, the output stream lives on its own thread;
//! [`Speakers`] sends it audio and stop requests.

use std::num::NonZero;
use std::sync::mpsc;
use std::time::Duration;

use kickoff_core::voice::{AudioClip, AudioSink, VoiceError};
use tokio::sync::watch;

enum Command {
    Play(AudioClip),
    Stop,
}

/// The default output device.
pub struct Speakers {
    commands: mpsc::Sender<Command>,
    playing: watch::Receiver<bool>,
}

impl Speakers {
    /// Open the default output device. Fails on machines without one.
    pub fn open() -> Result<Self, VoiceError> {
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(), VoiceError>>();
        let (tx, rx) = mpsc::channel::<Command>();
        let (playing_tx, playing_rx) = watch::channel(false);
        std::thread::Builder::new()
            .name("speakers".into())
            .spawn(move || {
                let mut sink = match rodio::DeviceSinkBuilder::open_default_sink() {
                    Ok(sink) => sink,
                    Err(e) => {
                        let _ =
                            ready_tx.send(Err(VoiceError::Unavailable(format!("Couldn't open the speakers ({e})."))));
                        return;
                    }
                };
                sink.log_on_drop(false);
                let player = rodio::Player::connect_new(sink.mixer());
                let _ = ready_tx.send(Ok(()));
                loop {
                    match rx.recv_timeout(Duration::from_millis(40)) {
                        Ok(Command::Play(clip)) => {
                            let (Some(channels), Some(rate)) = (NonZero::new(1u16), NonZero::new(clip.sample_rate))
                            else {
                                continue;
                            };
                            player.append(rodio::buffer::SamplesBuffer::new(channels, rate, clip.samples));
                        }
                        Ok(Command::Stop) => player.stop(),
                        Err(mpsc::RecvTimeoutError::Timeout) => {}
                        Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    }
                    let now = !player.empty();
                    playing_tx.send_if_modified(|p| std::mem::replace(p, now) != now);
                }
            })
            .map_err(|e| VoiceError::Unavailable(format!("Couldn't open the speakers ({e}).")))?;
        ready_rx.recv().map_err(|e| VoiceError::Unavailable(format!("Couldn't open the speakers ({e}).")))??;
        Ok(Self { commands: tx, playing: playing_rx })
    }

    /// `true` while audio is playing; changes are observable.
    pub fn playing(&self) -> watch::Receiver<bool> {
        self.playing.clone()
    }
}

impl AudioSink for Speakers {
    fn enqueue(&self, clip: AudioClip) {
        let _ = self.commands.send(Command::Play(clip));
    }

    fn stop(&self) {
        let _ = self.commands.send(Command::Stop);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plays_or_reports_no_speakers() {
        match Speakers::open() {
            Ok(speakers) => {
                let mut playing = speakers.playing();
                speakers.enqueue(AudioClip::new(vec![0.0; 1600], 16_000));
                std::thread::sleep(Duration::from_millis(30));
                speakers.stop();
                let _ = playing.borrow_and_update();
            }
            Err(VoiceError::Unavailable(m)) => assert!(m.contains("speakers"), "{m}"),
            Err(e) => panic!("{e}"),
        }
    }
}
