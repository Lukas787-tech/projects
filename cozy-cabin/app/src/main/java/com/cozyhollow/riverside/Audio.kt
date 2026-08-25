package com.cozyhollow.riverside

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object Sfx {
    const val TAP = 0
    const val BACK = 1
    const val CHOP = 2
    const val WATER = 3
    const val SPLASH = 4
    const val COIN = 5
    const val HARVEST = 6
    const val UPGRADE = 7
    const val CATCH = 8
    const val FAIL = 9
    const val SLEEP = 10
    const val PLANT = 11
    const val BITE = 12
    const val TILL = 13
}

/**
 * A very small software synth. Everything you hear is generated at runtime, so the
 * APK ships no audio files at all. One streaming AudioTrack mixes up to 28 voices.
 */
class Audio {

    private val sr = 22050
    private val voiceCap = 28

    // waveform ids
    private val W_SINE = 0
    private val W_TRI = 1
    private val W_NOISE = 2

    private val vFreq = FloatArray(voiceCap)
    private val vPhase = FloatArray(voiceCap)
    private val vAmp = FloatArray(voiceCap)
    private val vDecay = FloatArray(voiceCap)
    private val vAttack = FloatArray(voiceCap)
    private val vAge = FloatArray(voiceCap)
    private val vWave = IntArray(voiceCap)
    private val vMusic = BooleanArray(voiceCap)
    private val vActive = BooleanArray(voiceCap)
    private val vBend = FloatArray(voiceCap)

    private val sineTable = FloatArray(2048) { sin(it * 2.0 * PI / 2048.0).toFloat() }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var musicVol = 0.6f
    @Volatile var sfxVol = 0.8f
    /** 0 = day, 1 = night. Shifts the generative music down an octave. */
    @Volatile var mood = 0f
    @Volatile var muted = false

    private val lock = Any()
    private var rngState = 0x2F6E2B1

    // music scheduler
    private var beatSamples = 0
    private var sampleClock = 0L
    private var nextBeat = 0L
    private var beatIndex = 0

    // pentatonic scale, two octaves (C major pentatonic)
    private val scale = floatArrayOf(
        261.63f, 293.66f, 329.63f, 392.00f, 440.00f,
        523.25f, 587.33f, 659.25f, 783.99f, 880.00f
    )
    private var melodyIdx = 4

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try { audioLoop() } catch (_: Throwable) { }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 2; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) { }
        thread = null
        try { track?.stop(); track?.release() } catch (_: Throwable) { }
        track = null
    }

    private fun audioLoop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, 4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()

        beatSamples = (sr * 60f / 62f / 2f).toInt()  // half-beats at 62bpm
        nextBeat = 0

        val frames = 512
        val buf = ShortArray(frames)
        while (running) {
            synchronized(lock) {
                if (sampleClock >= nextBeat) {
                    scheduleMusic()
                    nextBeat += beatSamples
                }
                render(buf, frames)
            }
            try {
                t.write(buf, 0, frames)
            } catch (_: Throwable) {
                break
            }
            sampleClock += frames
        }
        try { t.stop(); t.release() } catch (_: Throwable) { }
    }

    private fun rnd(): Float {
        rngState = rngState * 1664525 + 1013904223
        return ((rngState ushr 8) and 0xFFFF) / 65535f
    }

    private fun render(buf: ShortArray, frames: Int) {
        val dt = 1f / sr
        val mv = if (muted) 0f else musicVol
        val sv = if (muted) 0f else sfxVol
        for (i in 0 until frames) {
            var acc = 0f
            for (v in 0 until voiceCap) {
                if (!vActive[v]) continue
                vAge[v] += dt
                val env: Float = if (vAge[v] < vAttack[v]) {
                    if (vAttack[v] <= 0f) 1f else vAge[v] / vAttack[v]
                } else {
                    val d = vAge[v] - vAttack[v]
                    kotlin.math.exp(-d * vDecay[v])
                }
                if (env < 0.0006f) { vActive[v] = false; continue }
                val f = vFreq[v] * (1f + vBend[v] * vAge[v])
                vPhase[v] += f * dt
                if (vPhase[v] > 1f) vPhase[v] -= vPhase[v].toInt().toFloat()
                val s = when (vWave[v]) {
                    W_NOISE -> rnd() * 2f - 1f
                    W_TRI -> {
                        val p = vPhase[v]
                        if (p < 0.5f) -1f + 4f * p else 3f - 4f * p
                    }
                    else -> sineTable[((vPhase[v] * 2048f).toInt()) and 2047]
                }
                acc += s * env * vAmp[v] * (if (vMusic[v]) mv else sv)
            }
            // gentle soft clip keeps the mix warm instead of harsh
            acc *= 0.34f
            val out = (acc / (1f + kotlin.math.abs(acc))) * 1.6f
            buf[i] = (out.coerceIn(-1f, 1f) * 30000f).toInt().toShort()
        }
    }

    private fun voice(
        freq: Float, amp: Float, attack: Float, decay: Float,
        wave: Int, music: Boolean, bend: Float = 0f
    ) {
        var slot = -1
        var oldest = -1f
        for (v in 0 until voiceCap) {
            if (!vActive[v]) { slot = v; break }
            if (vAge[v] > oldest) { oldest = vAge[v]; slot = v }
        }
        if (slot < 0) return
        vFreq[slot] = freq; vPhase[slot] = 0f; vAmp[slot] = amp
        vAttack[slot] = attack; vDecay[slot] = decay; vAge[slot] = 0f
        vWave[slot] = wave; vMusic[slot] = music; vActive[slot] = true
        vBend[slot] = bend
    }

    // -------------------------------------------------------------- music

    private fun scheduleMusic() {
        if (musicVol <= 0.001f || muted) { beatIndex++; return }
        val octave = if (mood > 0.5f) 0.5f else 1f
        val b = beatIndex % 16

        // soft pad on the downbeat of each bar
        if (b == 0 || b == 8) {
            val root = scale[if (b == 0) 0 else 3] * octave * 0.5f
            voice(root, 0.20f, 0.9f, 0.42f, W_SINE, true)
            voice(root * 1.5f, 0.12f, 1.1f, 0.40f, W_SINE, true)
        }
        // melody: a gentle random walk that rests often
        if (rnd() < 0.52f) {
            melodyIdx += (rnd() * 3f).toInt() - 1
            if (melodyIdx < 0) melodyIdx = 1
            if (melodyIdx >= scale.size) melodyIdx = scale.size - 2
            val f = scale[melodyIdx] * octave
            voice(f, 0.26f, 0.012f, 3.4f, W_TRI, true)
            voice(f * 2f, 0.07f, 0.010f, 6.5f, W_SINE, true)
        }
        // sparse high bell
        if (b % 8 == 6 && rnd() < 0.45f) {
            voice(scale[scale.size - 1] * octave * 2f, 0.10f, 0.005f, 2.4f, W_SINE, true)
        }
        beatIndex++
    }

    // ---------------------------------------------------------------- sfx

    fun play(kind: Int) {
        if (sfxVol <= 0.001f || muted) return
        synchronized(lock) {
            when (kind) {
                Sfx.TAP -> voice(880f, 0.30f, 0.004f, 26f, W_TRI, false)
                Sfx.BACK -> voice(440f, 0.28f, 0.004f, 22f, W_TRI, false)
                Sfx.TILL -> {
                    voice(150f, 0.34f, 0.002f, 24f, W_NOISE, false)
                    voice(90f, 0.24f, 0.004f, 15f, W_SINE, false, -0.4f)
                }
                Sfx.CHOP -> {
                    voice(200f, 0.42f, 0.001f, 30f, W_NOISE, false)
                    voice(120f, 0.32f, 0.003f, 14f, W_TRI, false, -0.55f)
                }
                Sfx.WATER -> {
                    voice(600f, 0.16f, 0.05f, 5.5f, W_NOISE, false)
                    voice(320f, 0.10f, 0.08f, 4.5f, W_NOISE, false)
                }
                Sfx.SPLASH -> {
                    voice(900f, 0.30f, 0.004f, 11f, W_NOISE, false)
                    voice(420f, 0.22f, 0.01f, 8f, W_SINE, false, -0.55f)
                }
                Sfx.PLANT -> {
                    voice(523.25f, 0.22f, 0.006f, 14f, W_SINE, false)
                    voice(783.99f, 0.16f, 0.02f, 10f, W_SINE, false)
                }
                Sfx.COIN -> {
                    voice(1046.5f, 0.24f, 0.003f, 16f, W_SINE, false)
                    voice(1318.5f, 0.20f, 0.05f, 13f, W_SINE, false)
                }
                Sfx.HARVEST -> {
                    voice(659.25f, 0.24f, 0.004f, 12f, W_TRI, false)
                    voice(783.99f, 0.22f, 0.06f, 11f, W_TRI, false)
                    voice(1046.5f, 0.20f, 0.12f, 10f, W_SINE, false)
                }
                Sfx.BITE -> {
                    voice(1200f, 0.26f, 0.002f, 20f, W_SINE, false)
                    voice(1600f, 0.20f, 0.03f, 18f, W_SINE, false)
                }
                Sfx.CATCH -> {
                    voice(523.25f, 0.24f, 0.004f, 9f, W_TRI, false)
                    voice(659.25f, 0.24f, 0.09f, 9f, W_TRI, false)
                    voice(783.99f, 0.24f, 0.18f, 8f, W_TRI, false)
                    voice(1046.5f, 0.26f, 0.27f, 6f, W_SINE, false)
                }
                Sfx.UPGRADE -> {
                    voice(392f, 0.24f, 0.004f, 7f, W_TRI, false)
                    voice(523.25f, 0.24f, 0.10f, 7f, W_TRI, false)
                    voice(659.25f, 0.24f, 0.20f, 6f, W_TRI, false)
                    voice(880f, 0.26f, 0.30f, 5f, W_SINE, false)
                    voice(1318.5f, 0.18f, 0.42f, 4f, W_SINE, false)
                }
                Sfx.FAIL -> {
                    voice(330f, 0.24f, 0.004f, 12f, W_TRI, false)
                    voice(247f, 0.24f, 0.09f, 11f, W_TRI, false)
                }
                Sfx.SLEEP -> {
                    voice(196f, 0.22f, 0.35f, 1.3f, W_SINE, false)
                    voice(261.63f, 0.18f, 0.5f, 1.2f, W_SINE, false)
                    voice(392f, 0.14f, 0.7f, 1.1f, W_SINE, false)
                }
            }
        }
    }
}
