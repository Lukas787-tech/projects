package com.lukas.jarvis.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.core.SettingsStore
import com.lukas.jarvis.notify.Reminders
import java.util.Locale

/**
 * Keeps the recognizer running in a loop and watches for the wake phrase.
 *
 * Honest limitations, because there is no free always-on wake-word engine on
 * Android: this burns noticeably more battery than a dedicated hotword DSP, the
 * platform recognizer needs restarting after every utterance, and on Android 10+
 * background activity launches are restricted, so waking the UI works reliably
 * only while the app is already open. It is off by default.
 */
class WakeWordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var phrase = "jarvis"
    private var consecutiveFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Reminders(this)
        phrase = SettingsStore(this).current.wakePhrase.lowercase(Locale.ROOT).trim()
            .ifBlank { "jarvis" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) {
            running = true
            listenAgain(0)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, Reminders.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for \"$phrase\"")
            .setContentText("Tap to stop")
            .setOngoing(true)
            .setContentIntent(stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun listenAgain(delayMillis: Long) {
        if (!running) return
        handler.postDelayed({
            if (!running) return@postDelayed
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stopSelf()
                return@postDelayed
            }
            val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            runCatching { engine.startListening(intent) }
                .onFailure { listenAgain(RETRY_DELAY_MS) }
        }, delayMillis)
    }

    private fun heard(candidates: List<String>): Boolean =
        candidates.any { it.lowercase(Locale.ROOT).contains(phrase) }

    private fun wake() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }

        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_START_LISTENING, true)
        runCatching { startActivity(open) }
        stopSelf()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            // Repeated hard failures usually mean the recognizer was taken over
            // by another app; backing off avoids a hot restart loop.
            consecutiveFailures++
            val delay = if (consecutiveFailures > 5) LONG_RETRY_DELAY_MS else RETRY_DELAY_MS
            if (error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            ) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            listenAgain(delay)
        }

        override fun onResults(results: Bundle?) {
            consecutiveFailures = 0
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (heard(all)) wake() else listenAgain(RETRY_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val all = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            if (heard(all)) wake()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val RETRY_DELAY_MS = 400L
        private const val LONG_RETRY_DELAY_MS = 5_000L
        const val ACTION_STOP = "com.lukas.jarvis.STOP_WAKE"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
