package com.lukas.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** One voice the engine offers, as the settings screen lists it. */
data class VoiceOption(
    val name: String,
    val label: String,
    val language: String,
    val network: Boolean,
    val quality: Int
)

/**
 * Android's built-in TTS. Free, offline on most devices, and good enough that
 * paying for a cloud voice would be hard to justify for an assistant that
 * mostly says one or two sentences at a time.
 *
 * Two things here exist because the engine can fail quietly. A reply spoken
 * before the engine has finished starting is queued rather than dropped, and a
 * sentence the engine refuses outright still ends the "speaking" state — before
 * that, one refused sentence left the assistant showing "speaking" forever and
 * never handed the microphone back.
 *
 * With a Fish Audio key it speaks through S2.1 instead: each sentence is
 * fetched while the one before it plays, in whatever language it is written.
 * Any sentence the cloud cannot deliver — no network, a refused key — is read
 * by the phone's own voice in its place, so a reply is never lost to it.
 */
class Speaker(context: Context) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * A 0..1 envelope while speaking, for the reactor to pulse with. The engine
     * does not report loudness, so this is shaped from the words being said:
     * it rises on each word and falls in the gaps.
     */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Called when a whole reply has finished playing — drives hands-free mode. */
    var onFinished: (() -> Unit)? = null

    @Volatile
    private var lastUtteranceId: String? = null

    /** The reply that arrived before the engine was up, spoken once it is. */
    @Volatile
    private var pending: String? = null

    private var rate = 1.05f
    private var pitch = 1f
    private var voiceName = ""

    /** Read each reply in the voice of the language it is written in. */
    @Volatile
    private var autoLanguage = true

    /**
     * The language the reply being spoken turned out to be in. A short
     * sentence ("Bien sûr.") gives too little away on its own, so it keeps
     * the language the reply started in; a new reply starts afresh.
     */
    @Volatile
    private var replyLanguage: String? = null
    private var languageTag = ""

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            _ready.value = true
            applyVoice()
            pending?.let {
                pending = null
                speak(it, currentDone)
            }
        } else {
            // No engine at all: nothing will ever be spoken, so do not leave a
            // reply waiting on one.
            pending = null
            finish()
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // A word is starting: lift the envelope; the decay below
                // lets it fall between words.
                _level.value = (0.55f + 0.45f * sin((start % 7) / 7f * PI.toFloat())).coerceIn(0f, 1f)
                decay()
            }

            override fun onDone(utteranceId: String?) {
                release(utteranceId)
                // Only the final chunk of a reply counts as "finished".
                if (utteranceId != null && utteranceId == lastUtteranceId) finish()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                release(utteranceId)
                if (utteranceId == null || utteranceId == lastUtteranceId) finish()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                release(utteranceId)
                if (utteranceId == null || utteranceId == lastUtteranceId) finish()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                release(utteranceId)
                // A stopped phone voice standing in for the cloud one is not
                // the end of the reply; the cloud queue says when that is.
                if (cloudActive) return
                _speaking.value = false
                _level.value = 0f
            }
        })
    }

    /**
     * Who asked for the reply now playing to be told when it ends. The app's
     * hands-free hook is the default; the floating dot passes its own, so a
     * reply spoken from the home screen never opens the app's microphone.
     */
    @Volatile
    private var currentDone: (() -> Unit)? = null

    private fun finish() {
        _speaking.value = false
        _level.value = 0f
        lastUtteranceId = null
        val done = currentDone
        currentDone = null
        (done ?: onFinished)?.invoke()
    }

    private var decayThread: Thread? = null

    /** Lets the envelope fall back towards silence between words. */
    private fun decay() {
        if (decayThread?.isAlive == true) return
        decayThread = Thread {
            while (_speaking.value) {
                Thread.sleep(45)
                _level.value = (_level.value * 0.82f).let { if (abs(it) < 0.02f) 0f else it }
            }
            _level.value = 0f
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun configure(
        rate: Float,
        pitch: Float,
        voiceName: String = "",
        language: String = "",
        autoLanguage: Boolean = true,
        cloud: FishConfig? = null
    ) {
        if (cloud != this.cloud) {
            // A new key or voice deserves a fresh try, whatever the old one did.
            cloudDownUntil = 0L
            _cloudError.value = null
        }
        this.cloud = cloud
        this.rate = rate
        this.pitch = pitch
        this.voiceName = voiceName
        this.languageTag = language
        this.autoLanguage = autoLanguage
        if (_ready.value) applyVoice()
    }

    private fun applyVoice() {
        runCatching {
            tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val locale = languageTag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)
                ?: Locale.getDefault()
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            } else {
                tts.language = Locale.US
            }
            if (voiceName.isNotBlank()) {
                tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
            }
        }
    }

    /** The voices worth offering: installed, and in the language being spoken. */
    fun voices(): List<VoiceOption> {
        if (!_ready.value) return emptyList()
        val all: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        val wanted = (languageTag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)
            ?: Locale.getDefault()).language
        return all
            .filter { voice ->
                voice.locale.language == wanted &&
                    !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .mapIndexed { index, voice ->
                VoiceOption(
                    name = voice.name,
                    label = describe(voice, index),
                    language = voice.locale.toLanguageTag(),
                    network = voice.isNetworkConnectionRequired,
                    quality = voice.quality
                )
            }
    }

    private fun describe(voice: Voice, index: Int): String {
        val region = voice.locale.getDisplayCountry(Locale.getDefault()).ifBlank {
            voice.locale.getDisplayLanguage(Locale.getDefault())
        }
        val tier = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "studio"
            voice.quality >= Voice.QUALITY_HIGH -> "natural"
            else -> "standard"
        }
        val online = if (voice.isNetworkConnectionRequired) ", online" else ""
        return "Voice ${index + 1} · $region · $tier$online"
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) = speakWith(text, onDone, homeLanguage = null)

    private fun speakWith(text: String, onDone: (() -> Unit)?, homeLanguage: String?) {
        // A whole reply replaces whatever a failed streamed one left behind.
        streaming = false
        val clean = sanitize(text)
        currentDone = onDone
        if (clean.isBlank()) {
            finish()
            return
        }
        if (useCloud()) {
            val pieces = cloudPieces(clean)
            val stamp = System.currentTimeMillis()
            lastUtteranceId = "jarvis_${stamp}_${pieces.lastIndex}"
            _speaking.value = true
            pieces.forEachIndexed { index, part -> cloudSay(part, "jarvis_${stamp}_$index", flush = index == 0, homeLanguage) }
            return
        }
        // A cloud reply still playing gives way to this one.
        if (cloudActive) cloudStop()
        if (!_ready.value) {
            // The engine is still starting; say it the moment it can.
            pending = clean
            _speaking.value = true
            return
        }
        val chunks = chunk(clean)
        val stamp = System.currentTimeMillis()
        lastUtteranceId = "jarvis_${stamp}_${chunks.lastIndex}"
        _speaking.value = true
        var refused = false
        chunks.forEachIndexed { index, part ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = runCatching {
                say(part, mode, "jarvis_${stamp}_$index", homeLanguage)
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) refused = true
        }
        // A refused sentence gets no callback at all, so end the turn here.
        if (refused) finish()
    }

    /**
     * Says [text] in [language] — a translation for the person opposite —
     * then goes back to the usual voice. When the phone has no voice for that
     * language the usual one does its best.
     */
    fun speakIn(text: String, language: String, onDone: (() -> Unit)? = null) {
        if (useCloud()) {
            // The cloud voice reads every language itself, in the same voice.
            speakWith(text, onDone, homeLanguage = language)
            return
        }
        if (!_ready.value) {
            speak(text, onDone)
            return
        }
        val switched = switchTo(language)
        // Queued in that language; say() leaves a switched voice alone when
        // the text is all in one alphabet, and restores it otherwise.
        speakWith(text, onDone, homeLanguage = language)
        if (switched) applyVoice()
    }

    // --------------------------------------------------------- streamed speech

    /**
     * Speech that starts before the reply is finished: each sentence is queued
     * the moment it is complete, so the first words are heard while the model
     * is still writing the rest. [endStream] queues whatever is left and says
     * when the whole reply has been spoken.
     */
    @Volatile
    private var streaming = false
    private var streamCounter = 0

    /** Queues one finished sentence of a reply still being written. */
    fun feed(sentence: String) {
        val clean = sanitize(sentence)
        val cloudy = useCloud()
        if (clean.isBlank() || (!_ready.value && !cloudy)) return
        val first = !streaming
        streaming = true
        _speaking.value = true
        val id = "jarvis_stream_${System.currentTimeMillis()}_${streamCounter++}"
        if (cloudy || (cloudActive && !first)) {
            // Once a reply has started in the cloud voice it stays in that
            // queue, so its sentences keep their order even if one falls back.
            cloudSay(clean, id, flush = first)
            return
        }
        if (first && cloudActive) cloudStop()
        runCatching {
            say(clean, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, id)
        }
    }

    /** True once [feed] has started speaking this reply. */
    val isStreaming: Boolean get() = streaming

    /**
     * The last of a streamed reply. [rest] may be empty when every sentence
     * was already fed; a moment of silence is queued then, because the end of
     * the reply needs an utterance of its own to report its end.
     */
    fun endStream(rest: String, onDone: (() -> Unit)? = null) {
        if (!streaming) {
            speak(rest, onDone)
            return
        }
        streaming = false
        currentDone = onDone
        val id = "jarvis_stream_end_${System.currentTimeMillis()}"
        lastUtteranceId = id
        val clean = sanitize(rest)
        if (cloudActive) {
            if (clean.isBlank()) cloudEnd(id) else cloudPieces(clean).let { pieces ->
                pieces.forEachIndexed { index, part ->
                    cloudSay(part, if (index == pieces.lastIndex) id else "${id}_$index", flush = false)
                }
            }
            return
        }
        val result = runCatching {
            if (clean.isBlank()) {
                tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, id)
            } else {
                say(clean, TextToSpeech.QUEUE_ADD, id)
            }
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) finish()
    }

    /**
     * Queues [text], handing any stretch in another alphabet to a voice for
     * that language when the phone has one. The last piece carries [id], so
     * the end of the whole text is still the end the callbacks wait for.
     */
    private fun say(text: String, mode: Int, id: String, homeLanguage: String? = null): Int {
        // Stretches in the language already being spoken keep the chosen voice.
        val home = homeLanguage
            ?: (languageTag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()).language
        if (mode == TextToSpeech.QUEUE_FLUSH) replyLanguage = null
        val pieces = Scripts.split(text).map { if (it.language == home) it.copy(language = null) else it }
        if (pieces.none { it.language != null }) {
            // All in the Latin alphabet: French, Spanish, English… are told
            // apart by their words, and read by a voice of their own rather
            // than with the home voice's accent. Not for the interpreter,
            // which says exactly which language it wants.
            val spoken = if (autoLanguage && homeLanguage == null) {
                LanguageGuess.of(text)?.also { replyLanguage = it } ?: replyLanguage
            } else {
                null
            }
            if (spoken == null || spoken == home) return tts.speak(text, mode, Bundle(), id)
            val switched = switchTo(spoken)
            val queued = tts.speak(text, mode, Bundle(), id)
            if (switched) applyVoice()
            return queued
        }
        var result = TextToSpeech.SUCCESS
        pieces.forEachIndexed { index, piece ->
            val pieceId = if (index == pieces.lastIndex) id else "${id}_p$index"
            val switched = piece.language?.let { switchTo(it) } == true
            val queued = tts.speak(piece.text, if (index == 0) mode else TextToSpeech.QUEUE_ADD, Bundle(), pieceId)
            // The language is taken when a piece is queued, so the voice the
            // text started in can come straight back for the next one.
            if (switched) {
                if (homeLanguage != null) switchTo(homeLanguage) else applyVoice()
            }
            if (queued == TextToSpeech.ERROR) result = TextToSpeech.ERROR
        }
        return result
    }

    /** Switches to a voice for [language] if one is installed; false leaves the voice as it was. */
    private fun switchTo(language: String): Boolean = runCatching {
        val locale = Locale.forLanguageTag(language)
        if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return false
        tts.language = locale
        true
    }.getOrDefault(false)

    /**
     * A short line that must not cut off what is being said — a timer going
     * off mid-answer. Queued after the reply, which keeps its own ending and
     * callback; said at once when nothing is playing.
     */
    fun announce(text: String) {
        val clean = sanitize(text)
        if (clean.isBlank() || (!_ready.value && !useCloud())) return
        if (_speaking.value && cloudActive) {
            cloudSay(clean, "jarvis_announce_${System.currentTimeMillis()}", flush = false)
            return
        }
        if (_speaking.value) {
            runCatching { tts.speak(clean, TextToSpeech.QUEUE_ADD, Bundle(), "jarvis_announce_${System.currentTimeMillis()}") }
        } else {
            speak(clean) { }
        }
    }

    /** Ends a streamed reply that will not be finished, so the speaker does not wait on it. */
    fun abandonStream() {
        if (streaming) endStream("") { }
    }

    fun stop() {
        streaming = false
        pending = null
        currentDone = null
        cloudStop()
        runCatching { tts.stop() }
        _speaking.value = false
        _level.value = 0f
    }

    fun shutdown() {
        cloudStop()
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        cloudPlayer.shutdownNow()
        cloudFetch.shutdownNow()
    }

    // ------------------------------------------------------------ cloud voice

    private val fish = FishVoice(context.applicationContext.cacheDir)

    @Volatile
    private var cloud: FishConfig? = null

    /** After a failure the phone's voice takes over for a while, rather than every sentence waiting to fail. */
    @Volatile
    private var cloudDownUntil = 0L

    /** True while the reply now playing is going through the cloud queue. */
    @Volatile
    private var cloudActive = false

    /** Bumped by every stop and every new reply; queued work from before it is dropped. */
    @Volatile
    private var generation = 0

    private val _cloudError = MutableStateFlow<String?>(null)
    /** Why the cloud voice last fell back to the phone's own, for the settings screen. */
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    /** Plays one sentence after another, in order. */
    private val cloudPlayer = Executors.newSingleThreadExecutor { Thread(it, "jarvis-voice").apply { isDaemon = true } }
    /** Fetches ahead of the player, so the next sentence is ready when this one ends. */
    private val cloudFetch = Executors.newFixedThreadPool(2) { Thread(it, "jarvis-voice-fetch").apply { isDaemon = true } }

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var playing: CountDownLatch? = null

    /** Phone-voice sentences standing in for cloud ones, waited on so the order holds. */
    private val waiters = ConcurrentHashMap<String, CountDownLatch>()

    private fun release(utteranceId: String?) {
        utteranceId?.let { waiters.remove(it)?.countDown() }
    }

    private fun useCloud(): Boolean = cloud != null && System.currentTimeMillis() >= cloudDownUntil

    /** Voices from Fish Audio's library, for the picker. Throws when the key or the network fails. */
    fun cloudVoices(key: String, query: String, language: String, mine: Boolean): List<FishVoiceOption> =
        fish.voices(key, query, language, mine)

    private fun cloudSay(text: String, id: String, flush: Boolean, homeLanguage: String? = null) {
        val config = cloud
        if (flush) cloudStop()
        cloudActive = true
        // Whatever the phone's voice was still saying gives way to the new reply.
        if (flush) runCatching { tts.stop() }
        _speaking.value = true
        val gen = generation
        val audio = if (config != null && useCloud()) {
            runCatching { cloudFetch.submit(Callable { fish.synthesize(text, config) }) }.getOrNull()
        } else {
            null
        }
        runCatching {
            cloudPlayer.execute {
                if (gen != generation) return@execute
                val file = audio?.let { future ->
                    runCatching { future.get(35, TimeUnit.SECONDS) }
                        .onFailure { cloudFailed(it) }
                        .getOrNull()
                }
                if (gen != generation) return@execute
                if (file != null) {
                    _cloudError.value = null
                    play(file, gen)
                } else {
                    sayOnPhone(text, id, homeLanguage)
                }
                if (gen == generation && id == lastUtteranceId) {
                    cloudActive = false
                    finish()
                }
            }
        }
    }

    /** The end of a streamed reply whose sentences have all been queued already. */
    private fun cloudEnd(id: String) {
        val gen = generation
        runCatching {
            cloudPlayer.execute {
                if (gen == generation && id == lastUtteranceId) {
                    cloudActive = false
                    finish()
                }
            }
        }
    }

    private fun cloudFailed(error: Throwable) {
        val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
        val code = (cause as? FishVoice.FishError)?.code
        _cloudError.value = cause.message ?: "the cloud voice failed"
        // A refused key will not start working in a minute; a dropped network may.
        val pause = if (code == 401 || code == 402) 10 * 60_000L else 60_000L
        cloudDownUntil = System.currentTimeMillis() + pause
    }

    /** One sentence in the phone's voice, waited on so the next cloud one does not talk over it. */
    private fun sayOnPhone(text: String, id: String, homeLanguage: String?) {
        if (!_ready.value) return
        val waitId = "${id}_phone"
        val latch = CountDownLatch(1)
        waiters[waitId] = latch
        val queued = runCatching { say(text, TextToSpeech.QUEUE_ADD, waitId, homeLanguage) }
            .getOrDefault(TextToSpeech.ERROR)
        if (queued == TextToSpeech.ERROR) {
            waiters.remove(waitId)
            return
        }
        // Long enough for a long sentence; a stop releases it at once.
        latch.await(20L + text.length / 8, TimeUnit.SECONDS)
        waiters.remove(waitId)
    }

    private fun play(file: File, gen: Int) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            synchronized(lock) {
                if (gen != generation) return
                player = mp
                playing = done
            }
            meter = runCatching { AudioMeter(mp.audioSessionId) }.getOrNull()
            mp.start()
            pulse(gen)
            done.await(mp.duration.coerceAtLeast(1_000).toLong() + 5_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // A file that will not play is a bad download: forget it.
            file.delete()
        } finally {
            synchronized(lock) {
                if (player === mp) {
                    player = null
                    playing = null
                }
            }
            meter?.release()
            meter = null
            runCatching { mp.release() }
        }
    }

    /** The cloud voice's real loudness, while one is playing and the phone lets it be read. */
    @Volatile
    private var meter: AudioMeter? = null

    /** Stops the cloud voice mid-sentence and drops everything queued behind it. */
    private fun cloudStop() {
        generation++
        cloudActive = false
        synchronized(lock) {
            runCatching { player?.pause() }
            playing?.countDown()
        }
        waiters.values.forEach { it.countDown() }
        waiters.clear()
    }

    private var pulseThread: Thread? = null

    /**
     * The envelope while the cloud voice plays, which Mochi's mouth follows.
     * It is the voice's real loudness, measured off the player's own audio
     * session; where the phone will not report it, it breathes with the
     * syllables' rough rhythm instead.
     */
    private fun pulse(gen: Int) {
        if (pulseThread?.isAlive == true) return
        pulseThread = Thread {
            var t = 0f
            var silent = 0
            while (gen == generation && synchronized(lock) { player != null }) {
                t += 0.21f
                val beat = abs(sin(t * 2.3f)) * (0.6f + 0.4f * abs(sin(t * 0.7f)))
                val measured = meter?.level()
                // Some phones hand back silence for every reading; a second of
                // nothing while the voice plays means the meter is not real.
                silent = if (measured == null || measured > 0.01f) 0 else silent + 1
                _level.value = if (measured != null && silent < 18) measured else (0.2f + 0.8f * beat).coerceIn(0f, 1f)
                Thread.sleep(55)
            }
            _level.value = 0f
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Sentences for the cloud voice: the first one short, so the voice starts
     * quickly, the later ones grouped so it does not sound clipped.
     */
    private fun cloudPieces(text: String): List<String> = Sentences.forCloud(text)

    /** Markdown and emoji sound terrible read aloud, so strip them first. */
    private fun sanitize(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code block ")
        .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** TTS rejects very long strings, so split on sentence ends. */
    private fun chunk(text: String, limit: Int = 3500): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (current.length + sentence.length + 1 > limit && current.isNotEmpty()) {
                out.add(current.toString().trim())
                current.setLength(0)
            }
            // A single sentence longer than the limit is cut rather than refused.
            sentence.chunked(limit).forEach { piece ->
                if (current.length + piece.length + 1 > limit && current.isNotEmpty()) {
                    out.add(current.toString().trim())
                    current.setLength(0)
                }
                current.append(piece).append(' ')
            }
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out
    }
}

/**
 * Reads how loud the cloud voice is right now, from the player's audio
 * session. It needs the microphone permission the app already has; without
 * it, creating one throws and the speaker keeps its rhythm instead.
 */
internal class AudioMeter(session: Int) {
    private val visualizer = android.media.audiofx.Visualizer(session).apply {
        enabled = false
        captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
        scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_NORMALIZED
        measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS
        enabled = true
    }
    private val reading = android.media.audiofx.Visualizer.MeasurementPeakRms()

    /** 0 silent to 1 loud, or null when the phone gave no reading. */
    fun level(): Float? = runCatching {
        if (visualizer.getMeasurementPeakRms(reading) != android.media.audiofx.Visualizer.SUCCESS) return null
        // RMS in millibels: around -9600 for silence and 0 at full scale; speech sits near -3000.
        ((reading.mRms + 5200) / 4200f).coerceIn(0f, 1f)
    }.getOrNull()

    fun release() {
        runCatching { visualizer.release() }
    }
}
