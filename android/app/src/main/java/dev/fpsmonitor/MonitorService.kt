package dev.fpsmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Polls the PUBG game's frame stats every [intervalMs] while monitoring is on, even
 * with the game in front. Live FPS shows in the notification and in
 * [MainActivity]; every reading is also written to logcat (tag FpsMonitor).
 *
 * Can also be started/stopped from the PC over adb, so tests can run while
 * the game stays in front (only the adb shell can do this: see the manifest):
 *   am start-foreground-service -n dev.fpsmonitor/.MonitorService --ei interval_ms 500
 *   am stopservice -n dev.fpsmonitor/.MonitorService
 */
class MonitorService : Service() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var monitor = 0L
    private var lastNotifyMs = 0L
    private var polls = 0L
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var heatEvery = 1L
    @Volatile private var stopped = false
    private val mainThread = Handler(android.os.Looper.getMainLooper())
    private var overlay: Overlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_NOT_STICKY
        intervalMs = (intent?.getIntExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS.toInt())?.toLong()
            ?: DEFAULT_INTERVAL_MS).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        heatEvery = (HEAT_EVERY_MS / intervalMs).coerceAtLeast(1)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val thread = HandlerThread("fps-poll").also { it.start() }
        worker = thread
        handler = Handler(thread.looper).also { it.post(::begin) }
        overlay = Overlay(this).also { it.sync() }
        settingsChanged = { mainThread.post { overlay?.sync() } }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A poll still running now (or frozen by Android and resumed later)
        // must not publish or schedule anything after this.
        stopped = true
        running = false
        settingsChanged = null
        mainThread.removeCallbacksAndMessages(null)
        overlay?.remove()
        overlay = null
        handler?.let { h ->
            h.removeCallbacksAndMessages(null)
            // Runs after any poll in progress, on the same thread that uses the handle.
            h.post {
                FpsNative.destroy(monitor)
                monitor = 0
            }
        }
        worker?.quitSafely()
        super.onDestroy()
    }

    private fun begin() {
        // When started from adb the app process may be brand new, and Shizuku
        // hands over its connection a moment after start-up.
        val deadline = SystemClock.uptimeMillis() + SHIZUKU_WAIT_MS
        while (!Shizuku.pingBinder() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            publish(Reading.error("Shizuku not running or permission not granted"))
            stopSelf()
            return
        }
        Log.i(TAG, "Monitoring started, polling every $intervalMs ms")
        try {
            ShellRunner.enableStats()
            ShellRunner.clearStats()
            // "" = every known PUBG version; Rust measures whichever is drawing.
            monitor = FpsNative.create("", Sessions.newLogFile(this).path)
        } catch (e: LinkageError) {
            // Rust library missing, or older than this app (a native function
            // or the game filter is missing): say so instead of failing every poll.
            Log.e(TAG, "Native library problem", e)
            publish(Reading.error(Reading.OUTDATED_LIBRARY))
            stopSelf()
            return
        } catch (e: Exception) {
            publish(Reading.error("Could not reach Shizuku: ${e.cause ?: e}"))
            stopSelf()
            return
        }
        handler?.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            val next = SystemClock.uptimeMillis() + intervalMs
            // Heat changes slowly: read it on the first poll, then every few seconds.
            if (polls++ % heatEvery == 0L) {
                try {
                    FpsNative.feedThermal(monitor, ShellRunner.readHeat())
                } catch (e: Exception) {
                    Log.w(TAG, "Heat read failed: ${e.cause ?: e}")
                }
            }
            val reading = try {
                val before = SystemClock.elapsedRealtimeNanos()
                val text = ShellRunner.readGameStats()
                val after = SystemClock.elapsedRealtimeNanos()
                // Stopped while the command ran: don't log a stale reading.
                if (stopped) return
                // The stats were read somewhere during the command: use the midpoint.
                val r = FpsNative.feed(monitor, text, (before + after) / 2)?.let(Reading::from)
                    ?: Reading.error("Rust feed failed")
                // The graph data is only needed while the app screen is showing.
                if (listener != null) r.copy(trail = FpsNative.history(monitor)) else r
            } catch (e: Exception) {
                Reading.error("${e.cause ?: e}")
            }
            if (stopped) return
            publish(reading)
            handler?.postAtTime(this, next)
        }
    }

    private fun publish(r: Reading) {
        latest = r
        listener?.invoke(r)
        mainThread.post { overlay?.update(r) }
        Log.i(TAG, describe(r))
        val now = SystemClock.uptimeMillis()
        if (now - lastNotifyMs >= NOTIFY_EVERY_MS) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(headline(r)))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Live FPS", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fps)
            .setContentTitle("FPS Monitor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "FpsMonitor"
        private const val CHANNEL = "live_fps"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_INTERVAL_MS = "interval_ms"
        private const val DEFAULT_INTERVAL_MS = 500L
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_INTERVAL_MS = 5000L
        private const val NOTIFY_EVERY_MS = 1000L
        private const val HEAT_EVERY_MS = 5000L
        private const val SHIZUKU_WAIT_MS = 3000L

        @Volatile var running = false
            private set
        @Volatile var latest: Reading? = null
            private set
        /** Called on the polling thread. */
        @Volatile var listener: ((Reading) -> Unit)? = null
        /** Set while running: call after changing overlay settings or permission. */
        @Volatile var settingsChanged: (() -> Unit)? = null
            private set

        fun headline(r: Reading): String = when (r.status) {
            Reading.Status.NO_GAME -> "Waiting for a PUBG game…"
            Reading.Status.FIRST, Reading.Status.RESET -> r.game?.let { "$it · measuring…" } ?: "Measuring…"
            Reading.Status.OK -> "%s%.0f%s FPS · %d stutters%s".format(
                r.game?.let { "$it · " } ?: "",
                r.smoothedFps ?: r.fps,
                r.targetFps?.let { " / %.0f".format(it) } ?: "",
                r.sessionStutters,
                r.batteryC?.let { " · battery %.1f°C".format(it) } ?: "",
            )
            Reading.Status.ERROR -> "Error: ${r.error}"
        }

        fun describe(r: Reading): String = when (r.status) {
            Reading.Status.OK -> "fps=%.1f smooth=%.1f target=%.0f frames=%d skipped=%d stutters=%d pauses=%d | session fps=%.1f worst=%.1f secs=%.1f stutters=%d log=%s | heat=%d batt=%.1f cpu=%.1f skin=%.1f"
                .format(
                    r.fps, r.smoothedFps ?: -1.0, r.targetFps ?: -1.0, r.frames, r.skipped, r.stutters, r.longPauses,
                    r.sessionFps, r.worstFps ?: -1.0, r.sessionSeconds, r.sessionStutters, r.logOk,
                    r.heatStatus ?: -1, r.batteryC ?: -1.0, r.cpuC ?: -1.0, r.skinC ?: -1.0,
                )
            else -> headline(r)
        }
    }
}
