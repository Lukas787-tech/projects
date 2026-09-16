package com.lukas.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android's built-in TTS. Free, offline on most devices, and good enough that
 * paying for a cloud voice would be hard to justify for an assistant that
 * mostly says one or two sentences at a time.
 */
class Speaker(context: Context) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Called when a whole reply has finished playing — drives hands-free mode. */
    var onFinished: (() -> Unit)? = null

    private var lastUtteranceId: String? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            _ready.value = true
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // Only the final chunk of a reply counts as "finished".
                if (utteranceId != null && utteranceId == lastUtteranceId) {
                    _speaking.value = false
                    onFinished?.invoke()
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                _speaking.value = false
                onFinished?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _speaking.value = false
                onFinished?.invoke()
            }
        })
    }

    fun configure(rate: Float, pitch: Float) {
        runCatching {
            tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val locale = Locale.getDefault()
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            } else {
                tts.language = Locale.US
            }
        }
    }

    fun speak(text: String) {
        val clean = sanitize(text)
        if (clean.isBlank()) {
            onFinished?.invoke()
            return
        }
        val chunks = chunk(clean)
        val stamp = System.currentTimeMillis()
        lastUtteranceId = "jarvis_${stamp}_${chunks.lastIndex}"
        _speaking.value = true
        chunks.forEachIndexed { index, part ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(part, mode, Bundle(), "jarvis_${stamp}_$index")
        }
    }

    fun stop() {
        runCatching { tts.stop() }
        _speaking.value = false
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
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        .replace(Regex("[\\p{So}\\p{Cn}]"), "")
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
            current.append(sentence).append(' ')
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out
    }
}
