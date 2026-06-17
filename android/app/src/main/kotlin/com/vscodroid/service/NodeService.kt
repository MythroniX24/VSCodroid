package com.vscodroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vscodroid.R
import com.vscodroid.VSCodroidApp
import com.vscodroid.MainActivity
import com.vscodroid.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service that owns the Node.js code-server process.
 *
 * ── State machine ────────────────────────────────────────────────────────────
 * [getState] exposes the CURRENT truth at all times via [ServerState], not just
 * a point-in-time callback. This matters because of a real race: [launchServer]
 * runs from [onStartCommand], which fires the instant the service is created —
 * often before [MainActivity] has finished binding and assigned [onServerReady]
 * / [onServerError]. If the very first [ProcessManager.startServer] call fails
 * FAST (e.g. a libnode.so size check, which is a synchronous file-stat, not a
 * slow process spawn), the failure callback can fire while it is still null on
 * the client side and be silently dropped — the Activity never finds out why,
 * and is left showing a static "Starting…" page forever.
 *
 * [getState]/[getLastError]/[getRecentOutput] let a newly-bound client query
 * "what already happened" immediately, closing this race regardless of timing.
 * Callbacks ([onServerReady]/[onServerError]) remain for LIVE updates after binding.
 */
class NodeService : Service() {

    private val tag = "NodeService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var processManager: ProcessManager
    private var restartCount = 0
    private var isServiceRunning = false

    /** Current lifecycle state. Always reflects the latest known truth. */
    enum class ServerState { IDLE, STARTING, WAITING_FOR_HEALTH, READY, FAILED }

    @Volatile private var currentState: ServerState = ServerState.IDLE
    @Volatile private var lastErrorMessage: String? = null
    @Volatile private var readyPort: Int = 0

    /** Invoked when the server is healthy and accepting connections. */
    var onServerReady: ((port: Int) -> Unit)? = null

    /** Invoked when the server fails to start or exceeds restart attempts. */
    var onServerError: ((message: String) -> Unit)? = null

    /** Invoked on every state transition, with the new state. Optional — for live UI updates. */
    var onStateChanged: ((ServerState) -> Unit)? = null

    // -- Binder --

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -- Service Lifecycle --

    override fun onCreate() {
        super.onCreate()
        Logger.i(tag, "Service created")
        processManager = ProcessManager(this)
        setupProcessCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETRY -> {
                Logger.i(tag, "Retry requested by client")
                restartCount = 0
                launchServer()
                return START_STICKY
            }
        }

        if (!isServiceRunning) {
            ServiceCompat.startForeground(
                this,
                VSCodroidApp.NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            isServiceRunning = true
            launchServer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i(tag, "Service destroying")
        isServiceRunning = false
        processManager.stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -- Public API for bound clients --

    /** Returns the port the server is listening on, or 0 if not yet started. */
    fun getPort(): Int = processManager.port

    /** Performs a synchronous health check against the running server. */
    fun isServerHealthy(): Boolean = processManager.isServerHealthy()

    /** Returns `true` if the Node.js process is alive. */
    fun isServerRunning(): Boolean = processManager.isRunning()

    /** Current lifecycle state — query this immediately after binding to avoid missed callbacks. */
    fun getState(): ServerState = currentState

    /** The exact reason the server failed, if [getState] is [ServerState.FAILED]. */
    fun getLastError(): String? = lastErrorMessage

    /** Recent server lifecycle/output lines for the diagnostics screen. Never empty after a failed start. */
    fun getRecentOutput(): List<String> = processManager.getRecentOutput()

    /** Number of automatic restarts attempted so far in the current session. */
    fun getRestartCount(): Int = restartCount

    // -- Internal --

    private fun setState(state: ServerState) {
        currentState = state
        onStateChanged?.invoke(state)
    }

    /**
     * Launches the server on the IO dispatcher and waits for it to become ready.
     * Notifies [onServerReady] on success or [onServerError] on failure/timeout.
     *
     * [currentState] and [lastErrorMessage] are updated BEFORE invoking callbacks,
     * so [getState]/[getLastError] are always correct even if the callback itself
     * is null (not yet assigned) when this runs.
     */
    private fun launchServer() {
        setState(ServerState.STARTING)
        serviceScope.launch(Dispatchers.IO) {
            val started = processManager.startServer()
            if (!started) {
                // Use the PRECISE reason from ProcessManager (e.g. exact libnode.so
                // size + actionable fix) instead of a generic string resource.
                val reason = processManager.getLastFailureReason()
                    ?: getString(R.string.error_server_start)
                Logger.e(tag, "Failed to start server process: $reason")
                lastErrorMessage = reason
                setState(ServerState.FAILED)
                onServerError?.invoke(reason)
                return@launch
            }

            setState(ServerState.WAITING_FOR_HEALTH)
            val ready = processManager.waitForReady()
            if (ready) {
                restartCount = 0
                readyPort = processManager.port
                Logger.i(tag, "Server is ready on port $readyPort")
                setState(ServerState.READY)
                onServerReady?.invoke(readyPort)
            } else {
                val reason = processManager.getLastFailureReason()
                    ?: getString(R.string.error_server_timeout)
                Logger.e(tag, "Server timeout: $reason")
                lastErrorMessage = reason
                setState(ServerState.FAILED)
                onServerError?.invoke(reason)
            }
        }
    }

    /**
     * Wires up the [ProcessManager.onServerCrashed] callback to trigger automatic
     * restarts with a backoff delay, up to [MAX_RESTARTS] attempts.
     */
    private fun setupProcessCallbacks() {
        processManager.onServerCrashed = { exitCode ->
            Logger.w(tag, "Server crashed (exit=$exitCode), restart #${restartCount + 1}")
            if (isServiceRunning && restartCount < MAX_RESTARTS) {
                restartCount++
                setState(ServerState.STARTING)
                serviceScope.launch(Dispatchers.IO) {
                    val backoffShift = (restartCount - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
                    val delayMs = RESTART_DELAY_MS * (1L shl backoffShift)
                    delay(delayMs)
                    launchServer()
                }
            } else {
                val reason = "Server crashed repeatedly (exit=$exitCode) after $MAX_RESTARTS restart attempts."
                Logger.e(tag, reason)
                lastErrorMessage = reason
                setState(ServerState.FAILED)
                onServerError?.invoke(reason)
            }
        }
    }

    /**
     * Builds the persistent foreground notification shown while the server is running.
     *
     * Includes:
     * - Tap action: opens [MainActivity]
     * - Stop action: sends [ACTION_STOP] to this service
     */
    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NodeService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VSCodroidApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingOpen)
            .addAction(0, getString(R.string.action_stop), pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        /** Intent action to gracefully stop the server and this service. */
        const val ACTION_STOP = "com.vscodroid.action.STOP_SERVER"

        /** Intent action to force a fresh start attempt, bypassing the isServiceRunning guard. */
        const val ACTION_RETRY = "com.vscodroid.action.RETRY_SERVER"

        /** Maximum number of automatic restart attempts before giving up. */
        private const val MAX_RESTARTS = 5

        /** Delay in milliseconds before each restart attempt. */
        private const val RESTART_DELAY_MS = 2000L

        /** Cap backoff growth to avoid unbounded delays. */
        private const val MAX_BACKOFF_SHIFT = 4
    }
}
