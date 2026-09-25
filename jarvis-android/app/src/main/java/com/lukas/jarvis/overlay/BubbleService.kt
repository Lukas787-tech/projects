package com.lukas.jarvis.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.notify.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Jarvis as a dot on top of everything else.
 *
 * The point is that the assistant is not somewhere you go. Tapping the dot
 * starts a turn where you already are — the answer is spoken without the app
 * ever coming to the front — and a long press opens the app for when you want
 * to see something. Dragging moves it, and letting go parks it against the
 * nearer edge so it is out of the way of whatever is underneath.
 *
 * It runs as a foreground service because a window over other apps has to be
 * visible in the notification shade; Android requires that, and it is the right
 * requirement for something that can listen.
 */
class BubbleService : Service() {

    private lateinit var windows: WindowManager
    private var dot: DotView? = null
    private var layout: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val conversation: Conversation by lazy {
        Conversation((application as JarvisApp).container)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (dot == null) {
            if (!canDraw(this)) {
                // The permission can be revoked while the service is running.
                stopSelf()
                return START_NOT_STICKY
            }
            attach()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        conversation.release()
        dot?.let { view -> runCatching { windows.removeView(view) } }
        dot = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ window

    private fun attach() {
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = DotView(this)
        val size = (DOT_DP * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the keyboard and the app underneath keep
            // working normally while the dot is on screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size
            y = resources.displayMetrics.heightPixels / 3
        }

        view.setOnTouchListener(DragAndTap(params, size))
        runCatching { windows.addView(view, params) }
            .onFailure {
                stopSelf()
                return
            }

        dot = view
        layout = params

        scope.launch {
            conversation.stage.collectLatest { view.stage = it }
        }
        scope.launch {
            conversation.level.collectLatest { view.level = it }
        }
    }

    /**
     * One listener for every gesture, because they are the same touch until it
     * moves: a press that never travels past the system's slop is a tap or a
     * hold, and one that does is a drag. Deciding that here rather than with
     * separate listeners is what keeps a slightly shaky tap from being
     * swallowed by the drag handler.
     *
     * The hold is measured on release rather than fired part-way through it,
     * which means a long press opens the app when the finger lifts. That is one
     * less timer to cancel correctly, and at this size nobody holds the dot
     * without meaning to.
     */
    private inner class DragAndTap(
        private val params: WindowManager.LayoutParams,
        private val size: Int
    ) : View.OnTouchListener {

        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var travelled = false
        private var downAt = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    travelled = false
                    downAt = System.currentTimeMillis()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!travelled && abs(dx) < slop && abs(dy) < slop) return true
                    travelled = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windows.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (travelled) {
                        snapToEdge(view)
                    } else if (System.currentTimeMillis() - downAt >= LONG_PRESS_MS) {
                        openApp()
                    } else {
                        conversation.toggle { problem -> say(problem) }
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }

        /** Parks against whichever side is nearer, and inside the screen. */
        private fun snapToEdge(view: View) {
            val metrics = resources.displayMetrics
            val middle = metrics.widthPixels / 2
            params.x = if (params.x + size / 2 < middle) 0 else metrics.widthPixels - size
            params.y = params.y.coerceIn(0, metrics.heightPixels - size)
            runCatching { windows.updateViewLayout(view, params) }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun say(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ foreground

    /** False when Android refused, which ends the service rather than the app. */
    private fun startForegroundCompat(): Boolean = runCatching { enterForeground() }.isSuccess

    private fun enterForeground() {
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, Reminders.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis is on screen")
            .setContentText("Tap the dot to talk · tap here to put it away")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.lukas.jarvis.BUBBLE_STOP"
        // Not the wake word's 4711: with both running, stopping one would take the other's notification.
        private const val NOTIFICATION_ID = 4713
        private const val DOT_DP = 62f
        private const val LONG_PRESS_MS = 420L

        /** Whether Android will let this app draw over other apps. */
        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** The system page where that is granted; there is no in-app way. */
        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )

        fun start(context: Context) {
            if (!canDraw(context)) return
            // A microphone service without the microphone permission is
            // refused outright on Android 14; the dot cannot listen anyway.
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, BubbleService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            // stopService, which does nothing when the dot is not running;
            // starting the service only to stop it ran its teardown, which
            // silenced a reply being spoken in the app.
            runCatching { context.stopService(Intent(context, BubbleService::class.java)) }
        }
    }
}
