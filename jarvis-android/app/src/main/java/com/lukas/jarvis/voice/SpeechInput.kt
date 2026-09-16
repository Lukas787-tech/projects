package com.lukas.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps the platform recognizer. Free, no key, and on most phones it runs on
 * device. Every method here must be called from the main thread — that is a
 * hard requirement of SpeechRecognizer, not a style choice.
 */
class SpeechInput(private val context: Context) {

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** 0..1, driven by mic RMS. The orb in the UI breathes with this. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null

    /** Guards the one automatic retry after the recognizer reports itself busy. */
    private var retriedAfterBusy = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!available) {
            onFailure("No speech recognition on this device. Install or enable Google app voice services.")
            return
        }
        this.onResult = onResult
        this.onFailure = onFailure
        retriedAfterBusy = false

        // Starting while a session is still open is itself a cause of
        // ERROR_RECOGNIZER_BUSY, so never stack two.
        if (_listening.value) runCatching { recognizer?.cancel() }

        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        _partial.value = ""
        _level.value = 0f
        _listening.value = true
        runCatching { engine.startListening(intent()) }
            .onFailure {
                _listening.value = false
                onFailure("Could not start listening: ${it.message}")
            }
    }

    fun stop() {
        _listening.value = false
        _level.value = 0f
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        _listening.value = false
        _level.value = 0f
        _partial.value = ""
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        _listening.value = false
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Give people a beat to think mid-sentence instead of cutting them off.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _listening.value = true
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // The API reports roughly -2..10 dB; map that onto 0..1.
            _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _level.value = 0f
        }

        override fun onError(error: Int) {
            _listening.value = false
            _level.value = 0f
            // A no-match or timeout is normal silence, not something worth
            // interrupting the user about.
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                onFailure?.invoke("")
                return
            }
            // "Busy" usually means the previous session has not finished letting
            // go of the microphone. Dropping the engine and trying once more
            // clears it far more often than telling the user to tap again.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retriedAfterBusy) {
                retriedAfterBusy = true
                val resume = onResult
                val fail = onFailure
                runCatching { recognizer?.destroy() }
                recognizer = null
                if (resume != null && fail != null) {
                    handler.postDelayed({ start(resume, fail) }, BUSY_RETRY_MS)
                    return
                }
            }
            onFailure?.invoke(describe(error))
        }

        override fun onResults(results: Bundle?) {
            _listening.value = false
            _level.value = 0f
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            _partial.value = ""
            if (text.isBlank()) onFailure?.invoke("") else onResult?.invoke(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) _partial.value = text
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val BUSY_RETRY_MS = 450L
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer was interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Another app is holding the microphone. Close Google Assistant or any " +
                "voice keyboard, then try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech service error."
        else -> "Speech recognition failed ($error)."
    }
}
