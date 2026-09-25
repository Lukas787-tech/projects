package com.lukas.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
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
                // Only the final chunk of a reply counts as "finished".
                if (utteranceId != null && utteranceId == lastUtteranceId) finish()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == null || utteranceId == lastUtteranceId) finish()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null || utteranceId == lastUtteranceId) finish()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
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

    fun configure(rate: Float, pitch: Float, voiceName: String = "", language: String = "") {
        this.rate = rate
        this.pitch = pitch
        this.voiceName = voiceName
        this.languageTag = language
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
        val clean = sanitize(text)
        currentDone = onDone
        if (clean.isBlank()) {
            finish()
            return
        }
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
        if (clean.isBlank() || !_ready.value) return
        val first = !streaming
        streaming = true
        _speaking.value = true
        val id = "jarvis_stream_${System.currentTimeMillis()}_${streamCounter++}"
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
        val pieces = Scripts.split(text).map { if (it.language == home) it.copy(language = null) else it }
        if (pieces.none { it.language != null }) return tts.speak(text, mode, Bundle(), id)
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

    fun stop() {
        streaming = false
        pending = null
        currentDone = null
        runCatching { tts.stop() }
        _speaking.value = false
        _level.value = 0f
    }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }

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
