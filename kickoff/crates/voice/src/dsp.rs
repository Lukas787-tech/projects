//! Small audio helpers: channel mixing, sample conversion and resampling.

use std::f64::consts::PI;

/// Average interleaved channels into one.
pub fn downmix(interleaved: &[f32], channels: u16) -> Vec<f32> {
    let channels = usize::from(channels.max(1));
    if channels == 1 {
        return interleaved.to_vec();
    }
    interleaved.chunks_exact(channels).map(|frame| frame.iter().sum::<f32>() / channels as f32).collect()
}

pub fn i16_to_f32(sample: i16) -> f32 {
    f32::from(sample) / 32768.0
}

pub fn f32_to_i16(sample: f32) -> i16 {
    (sample.clamp(-1.0, 1.0) * 32767.0).round() as i16
}

/// Change the sample rate with band-limited (windowed sinc) interpolation.
/// When downsampling, content above the new Nyquist frequency is filtered out
/// instead of folding back as noise.
pub fn resample(input: &[f32], from: u32, to: u32) -> Vec<f32> {
    if from == to || input.is_empty() || from == 0 || to == 0 {
        return input.to_vec();
    }
    const HALF_TAPS: f64 = 16.0;
    let step = f64::from(from) / f64::from(to);
    // Cutoff relative to the input's Nyquist frequency, with a little margin.
    let cutoff = (f64::from(to) / f64::from(from)).min(1.0) * 0.95;
    let half = (HALF_TAPS / cutoff).ceil() as isize;
    let len = input.len() as isize;
    let out_len = (input.len() as f64 / step).floor() as usize;
    let mut out = Vec::with_capacity(out_len);
    for n in 0..out_len {
        let t = n as f64 * step;
        let center = t.floor() as isize;
        let (mut acc, mut weight) = (0.0f64, 0.0f64);
        for k in (center - half + 1).max(0)..=(center + half).min(len - 1) {
            let x = t - k as f64;
            let w = sinc(x * cutoff) * blackman(x / half as f64);
            acc += f64::from(input[k as usize]) * w;
            weight += w;
        }
        out.push(if weight.abs() > 1e-9 { (acc / weight) as f32 } else { 0.0 });
    }
    out
}

fn sinc(x: f64) -> f64 {
    if x.abs() < 1e-12 { 1.0 } else { (PI * x).sin() / (PI * x) }
}

/// Blackman window over -1..=1.
fn blackman(u: f64) -> f64 {
    if u.abs() >= 1.0 {
        return 0.0;
    }
    0.42 + 0.5 * (PI * u).cos() + 0.08 * (2.0 * PI * u).cos()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(freq: f64, rate: u32, secs: f64) -> Vec<f32> {
        (0..(f64::from(rate) * secs) as usize)
            .map(|i| (2.0 * PI * freq * i as f64 / f64::from(rate)).sin() as f32 * 0.5)
            .collect()
    }

    fn crossings(s: &[f32]) -> usize {
        s.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count()
    }

    fn peak(s: &[f32]) -> f32 {
        s.iter().fold(0.0f32, |m, x| m.max(x.abs()))
    }

    #[test]
    fn keeps_speech_frequencies_when_downsampling() {
        let input = tone(440.0, 48_000, 1.0);
        let out = resample(&input, 48_000, 16_000);
        assert_eq!(out.len(), 16_000);
        // 440 Hz has 880 zero crossings per second.
        assert!((crossings(&out) as i64 - 880).abs() <= 2, "{}", crossings(&out));
        let middle = &out[1000..15_000];
        assert!((peak(middle) - 0.5).abs() < 0.02, "{}", peak(middle));
    }

    #[test]
    fn filters_out_what_the_new_rate_cannot_hold() {
        // 12 kHz is above the 8 kHz Nyquist limit of 16 kHz audio.
        let out = resample(&tone(12_000.0, 48_000, 0.5), 48_000, 16_000);
        assert!(peak(&out[500..7500]) < 0.02, "{}", peak(&out[500..7500]));
    }

    #[test]
    fn upsamples_and_handles_trivial_cases() {
        let out = resample(&tone(300.0, 22_050, 0.5), 22_050, 44_100);
        assert_eq!(out.len(), 22_050);
        assert!((peak(&out[1000..21_000]) - 0.5).abs() < 0.02);
        assert_eq!(resample(&[0.1, 0.2], 16_000, 16_000), vec![0.1, 0.2]);
        assert!(resample(&[], 48_000, 16_000).is_empty());
    }

    #[test]
    fn mixes_and_converts() {
        assert_eq!(downmix(&[0.2, 0.4, -1.0, 1.0], 2), vec![0.3, 0.0]);
        assert_eq!(f32_to_i16(1.5), 32767);
        assert_eq!(f32_to_i16(-1.0), -32767);
        assert!((i16_to_f32(16384) - 0.5).abs() < 1e-6);
    }
}
