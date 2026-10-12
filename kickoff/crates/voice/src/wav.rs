//! WAV in and out: encoding recordings for upload, and decoding speech that
//! arrives as a WAV stream (whose header may carry no final length).

use kickoff_core::voice::{AudioClip, VoiceError};

use crate::dsp::{downmix, f32_to_i16, i16_to_f32};

/// 16-bit mono PCM WAV.
pub fn encode_pcm16(clip: &AudioClip) -> Vec<u8> {
    let data_len = (clip.samples.len() * 2) as u32;
    let mut out = Vec::with_capacity(44 + data_len as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&clip.sample_rate.to_le_bytes());
    out.extend_from_slice(&(clip.sample_rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    for s in &clip.samples {
        out.extend_from_slice(&f32_to_i16(*s).to_le_bytes());
    }
    out
}

/// A whole WAV file.
pub fn decode(bytes: &[u8]) -> Result<AudioClip, VoiceError> {
    let mut decoder = StreamDecoder::default();
    let mut clip = decoder.push(bytes)?.unwrap_or_default();
    if decoder.format.is_none() {
        return Err(VoiceError::Invalid("not a WAV file".into()));
    }
    clip.sample_rate = decoder.format.map_or(0, |f| f.rate);
    Ok(clip)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Format {
    channels: u16,
    rate: u32,
    bits: u16,
    float: bool,
}

impl Format {
    fn frame_bytes(&self) -> usize {
        usize::from(self.channels) * usize::from(self.bits / 8)
    }
}

/// Decodes WAV bytes as they arrive. Everything after the `data` chunk header
/// is treated as samples, so streams with a placeholder length work.
#[derive(Debug, Default)]
pub struct StreamDecoder {
    buf: Vec<u8>,
    format: Option<Format>,
    in_data: bool,
    header_checked: bool,
}

impl StreamDecoder {
    /// Feed bytes; returns any complete samples, mixed to mono.
    pub fn push(&mut self, bytes: &[u8]) -> Result<Option<AudioClip>, VoiceError> {
        self.buf.extend_from_slice(bytes);
        if !self.in_data && !self.read_header()? {
            return Ok(None);
        }
        let format = self.format.expect("format is known once in data");
        let usable = self.buf.len() - self.buf.len() % format.frame_bytes();
        if usable == 0 {
            return Ok(None);
        }
        let raw: Vec<u8> = self.buf.drain(..usable).collect();
        let interleaved: Vec<f32> = match (format.bits, format.float) {
            (16, false) => raw.chunks_exact(2).map(|b| i16_to_f32(i16::from_le_bytes([b[0], b[1]]))).collect(),
            (32, true) => raw.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect(),
            (8, false) => raw.iter().map(|&b| (f32::from(b) - 128.0) / 128.0).collect(),
            (bits, float) => {
                return Err(VoiceError::Invalid(format!(
                    "unsupported WAV sample format ({bits}-bit{})",
                    if float { " float" } else { "" }
                )));
            }
        };
        Ok(Some(AudioClip::new(downmix(&interleaved, format.channels), format.rate)))
    }

    /// Parse chunks up to the start of the samples. `Ok(false)` means more
    /// bytes are needed.
    fn read_header(&mut self) -> Result<bool, VoiceError> {
        if !self.header_checked {
            if self.buf.len() < 12 {
                return Ok(false);
            }
            if &self.buf[..4] != b"RIFF" || &self.buf[8..12] != b"WAVE" {
                return Err(VoiceError::Invalid("expected WAV audio".into()));
            }
            self.buf.drain(..12);
            self.header_checked = true;
        }
        loop {
            if self.buf.len() < 8 {
                return Ok(false);
            }
            let id: [u8; 4] = self.buf[..4].try_into().expect("4 bytes");
            let size = u32::from_le_bytes(self.buf[4..8].try_into().expect("4 bytes")) as usize;
            if &id == b"data" {
                if self.format.is_none() {
                    return Err(VoiceError::Invalid("WAV data before format".into()));
                }
                self.buf.drain(..8);
                self.in_data = true;
                return Ok(true);
            }
            let padded = size + size % 2;
            if self.buf.len() < 8 + padded {
                return Ok(false);
            }
            if &id == b"fmt " {
                let f = &self.buf[8..8 + size];
                if f.len() < 16 {
                    return Err(VoiceError::Invalid("short WAV format chunk".into()));
                }
                let tag = u16::from_le_bytes([f[0], f[1]]);
                let bits = u16::from_le_bytes([f[14], f[15]]);
                // 0xFFFE (extensible) carries the real type in its sub-format GUID.
                let float = tag == 3 || (tag == 0xFFFE && f.len() >= 26 && u16::from_le_bytes([f[24], f[25]]) == 3);
                self.format = Some(Format {
                    channels: u16::from_le_bytes([f[2], f[3]]).max(1),
                    rate: u32::from_le_bytes([f[4], f[5], f[6], f[7]]),
                    bits,
                    float,
                });
            }
            self.buf.drain(..8 + padded);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_pcm16() {
        let clip = AudioClip::new(vec![0.0, 0.5, -0.5, 0.25], 16_000);
        let back = decode(&encode_pcm16(&clip)).unwrap();
        assert_eq!(back.sample_rate, 16_000);
        for (a, b) in clip.samples.iter().zip(&back.samples) {
            assert!((a - b).abs() < 1e-4);
        }
    }

    #[test]
    fn decodes_a_stream_in_odd_pieces_with_extra_chunks_and_stereo() {
        // Stereo 16-bit at 22,050 Hz, a LIST chunk before data, and a
        // streaming placeholder length.
        let mut wav = b"RIFF\xff\xff\xff\xffWAVE".to_vec();
        wav.extend_from_slice(b"fmt \x10\x00\x00\x00\x01\x00\x02\x00");
        wav.extend_from_slice(&22_050u32.to_le_bytes());
        wav.extend_from_slice(&(22_050u32 * 4).to_le_bytes());
        wav.extend_from_slice(&[4, 0, 16, 0]);
        wav.extend_from_slice(b"LIST\x03\x00\x00\x00abc\x00");
        wav.extend_from_slice(b"data\xff\xff\xff\xff");
        for (l, r) in [(1000i16, 3000i16), (-2000, -2000), (0, 32767)] {
            wav.extend_from_slice(&l.to_le_bytes());
            wav.extend_from_slice(&r.to_le_bytes());
        }
        let mut d = StreamDecoder::default();
        let mut samples = Vec::new();
        for piece in wav.chunks(5) {
            if let Some(clip) = d.push(piece).unwrap() {
                assert_eq!(clip.sample_rate, 22_050);
                samples.extend(clip.samples);
            }
        }
        assert_eq!(samples.len(), 3);
        assert!((samples[0] - i16_to_f32(2000)).abs() < 1e-6);
        assert!((samples[1] - i16_to_f32(-2000)).abs() < 1e-6);
    }

    #[test]
    fn rejects_other_formats() {
        assert!(decode(b"ID3\x04 not a wav at all").is_err());
        let mut d = StreamDecoder::default();
        assert!(d.push(b"RIFF").unwrap().is_none(), "waits for more bytes");
    }
}
