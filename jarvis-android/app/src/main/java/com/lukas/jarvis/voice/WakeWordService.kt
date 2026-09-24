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

    private var language = ""

    override fun onCreate() {
        super.onCreate()
        Reminders(this)
        // The app's own store, so a phrase changed a moment ago is the one used.
        val settings = (application as com.lukas.jarvis.JarvisApp).container.settings.current
        phrase = settings.wakePhrase.lowercase(Locale.ROOT).trim().ifBlank { "jarvis" }
        language = settings.speechLanguage
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
        runCatching { conversation.release() }
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
                language.takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            }
            runCatching { engine.startListening(intent) }
                .onFailure { listenAgain(RETRY_DELAY_MS) }
        }, delayMillis)
    }

    private fun heard(candidates: List<String>): String? =
        candidates.firstOrNull { it.lowercase(Locale.ROOT).contains(phrase) }

    /**
     * The phrase was heard. The answer happens right here, spoken, without
     * the app: Android 10 and later refuse to bring an app forward from the
     * background, which made "Jarvis, …" from across the room open nothing.
     * Whatever followed the name in the same breath is the request; if nothing
     * did, a tone says "go ahead" and the next sentence is.
     */
    private fun wake(said: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        // The recognizer is let go entirely: the turn uses its own, and two
        // cannot hold the microphone at once.
        runCatching { recognizer?.destroy() }
        recognizer = null

        val lower = said.lowercase(Locale.ROOT)
        val after = lower.substringAfter(phrase, "").trim(' ', ',', '.', '!', '?')
        val start = lower.indexOf(phrase) + phrase.length
        val request = if (after.split(" ").count { it.isNotBlank() } >= 2 && start <= said.length) {
            said.substring(start).trim(' ', ',', '.', '!', '?')
        } else {
            ""
        }

        val resume = { resumeListening() }
        val problem: (String) -> Unit = { message -> notifyProblem(message) }
        if (request.isNotBlank()) {
            conversation.ask(request, problem, resume)
        } else {
            conversation.listen(problem, resume)
        }
    }

    private val conversation: com.lukas.jarvis.overlay.Conversation by lazy {
        com.lukas.jarvis.overlay.Conversation((application as com.lukas.jarvis.JarvisApp).container)
    }

    private fun resumeListening() {
        if (running) return
        running = true
        consecutiveFailures = 0
        listenAgain(AFTER_TURN_DELAY_MS)
    }

    /** A problem is shown where it can be seen from anywhere: in the notification. */
    private fun notifyProblem(message: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, Reminders.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis could not answer")
            .setContentText(message.take(160))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(PROBLEM_ID, notification)
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
            // Only the final words count: a partial result stops at "Jarvis,
            // what's the" and the request would be lost.
            val said = heard(all)
            if (said != null) wake(said) else listenAgain(RETRY_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val RETRY_DELAY_MS = 400L
        private const val LONG_RETRY_DELAY_MS = 5_000L
        private const val AFTER_TURN_DELAY_MS = 700L
        private const val PROBLEM_ID = 4712
        const val ACTION_STOP = "com.lukas.jarvis.STOP_WAKE"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops it without starting it: delivering a stop command through
         * startService created the service on every app resume just to stop
         * it again, and throws when the app is in the background.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }
    }
}
