package com.lukas.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
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

    /** This listen is being retried on the phone's own model after a network error. */
    private var offlineRetry = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** A BCP-47 tag to recognise in. Blank follows the phone. */
    var language: String = ""

    /**
     * Hear which language is spoken rather than insisting on [language]. Off
     * for the interpreter, which knows exactly whose turn — and language — it is.
     */
    @Volatile
    var autoDetect: Boolean = true

    /**
     * The language the user last spoke or typed in, when it could be told.
     * Android 14 and later detect the language as they listen; older phones
     * listen in this one, since people tend to carry on in the language they
     * started in.
     */
    @Volatile
    var lastHeard: String? = null

    fun start(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        retriedAfterBusy = false
        offlineRetry = false
        begin(onResult, onFailure)
    }

    /**
     * The shared body of a first start and the one retry after "busy". Kept
     * apart from [start] so the retry does not reset the flag that allows it
     * — which it used to, turning a microphone held by another app into a
     * retry every half second, forever.
     */
    private fun begin(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!available) {
            onFailure("No speech recognition on this device. Install or enable Google app voice services.")
            return
        }
        this.onResult = onResult
        this.onFailure = onFailure

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
        // A retry after "busy" still waiting would reopen the mic just
        // after the user closed it.
        handler.removeCallbacksAndMessages(null)
        _listening.value = false
        _level.value = 0f
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
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
        val home = language.ifBlank { Locale.getDefault().toLanguageTag() }
        val homeBase = Locale.forLanguageTag(home).language
        val recent = lastHeard?.takeIf { autoDetect && it != homeBase }
        if (autoDetect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The recogniser tells the language itself among these, and may
            // switch mid-conversation: German, then a French sentence, works.
            val allowed = ArrayList(
                (listOf(home) + listOfNotNull(recent?.let { fullTag(it) }) + DETECTABLE)
                    .distinctBy { Locale.forLanguageTag(it).language }
                    .take(MAX_DETECTED)
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, home)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
        } else {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recent?.let { fullTag(it) } ?: home)
        }
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // With no network, or after the network failed this listen, the
        // phone's own speech model is asked for; many phones have one for
        // their language, and voice then works offline like the reflexes.
        if (offlineRetry || !online()) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Give people a beat to think mid-sentence instead of cutting them off.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
    }

    private fun online(): Boolean = runCatching {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        manager?.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

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
            if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) &&
                !offlineRetry
            ) {
                offlineRetry = true
                val resume = onResult
                val fail = onFailure
                if (resume != null && fail != null) {
                    handler.postDelayed({ begin(resume, fail) }, BUSY_RETRY_MS)
                    return
                }
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retriedAfterBusy) {
                retriedAfterBusy = true
                val resume = onResult
                val fail = onFailure
                runCatching { recognizer?.destroy() }
                recognizer = null
                if (resume != null && fail != null) {
                    handler.postDelayed({ begin(resume, fail) }, BUSY_RETRY_MS)
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
            if (autoDetect) LanguageGuess.of(text)?.let { lastHeard = it }
            if (text.isBlank()) onFailure?.invoke("") else onResult?.invoke(text)
        }

        /** Android 14+: the language the recogniser heard, before the words. */
        override fun onLanguageDetection(results: Bundle) {
            if (!autoDetect) return
            results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                ?.let { Locale.forLanguageTag(it).language }
                ?.takeIf { it.isNotBlank() && it != "und" }
                ?.let { lastHeard = it }
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
        /** Languages the recogniser is asked to listen for besides the main one. */
        private val DETECTABLE = listOf("en-US", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-PT", "nl-NL", "tr-TR", "pl-PL")
        private const val MAX_DETECTED = 6

        /** "fr" -> "fr-FR": recognisers want a region. */
        fun fullTag(language: String): String =
            DETECTABLE.firstOrNull { Locale.forLanguageTag(it).language == language } ?: language

        const val BUSY_RETRY_MS = 450L
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer was interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs a network connection, and this " +
            "phone has no offline speech model for the language. Typing works offline."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Another app is holding the microphone. Close Google Assistant or any " +
                "voice keyboard, then try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech service error."
        else -> "Speech recognition failed ($error)."
    }
}
