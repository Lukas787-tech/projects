package com.lukas.jarvis.voice

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The two short tones that say the microphone just opened or closed.
 *
 * Talking to a screen you are not looking at — the phone on the table, the
 * assistant woken from across the room — needs a sound to say "go ahead" and
 * another to say "got it". The platform's tone generator makes both with no
 * audio files in the app.
 */
class Earcon {

    private val tones: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 35)
    }.getOrNull()

    fun listening() = play(ToneGenerator.TONE_PROP_BEEP, 90)

    fun heard() = play(ToneGenerator.TONE_PROP_ACK, 110)

    fun failed() = play(ToneGenerator.TONE_PROP_NACK, 140)

    private fun play(tone: Int, durationMs: Int) {
        runCatching { tones?.startTone(tone, durationMs) }
    }

    fun release() {
        runCatching { tones?.release() }
    }
}
