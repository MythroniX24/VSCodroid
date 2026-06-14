package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Manages the Node.js code-server process lifecycle.
 *
 * Thread safety:
 * - [serverProcessRef] is an [AtomicReference] — reads/writes are atomic and visible
 *   across the IO dispatcher, main thread, and watchdog thread.
 * - [isShuttingDown] is [@Volatile] — the watchdog thread checks it on every wake-up.
 * - [_port] is [@Volatile] — written once by [startServer], read from any thread.
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    /** Atomic holder for the running server process. Null when stopped. */
    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false

    /** The port the server is listening on. Valid only after [startServer] returns true. */
    val port: Int get() = _port

    // -- Callbacks --
    var onServerReady: (() -> Unit)? = null
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null
    var onServerOutput: ((line: String) -> Unit)? = null

    // -- Lifecycle --

    /**
     * Starts the Node.js code-server process.
     *
     * @return true if the process was spawned successfully, false on error.
     */
    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "Server already running")
            return false
        }

        isShuttingDown = false

        // Port allocation with retry to handle rare race conditions
        _port = allocatePortWithRetry() ?: run {
            Logger.e(tag, "Failed to find an available port")
            return false
        }
        Logger.i(tag, "Starting server on port $_port")

        // Ensure tmp dir exists — Android may clear cache between launches
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val nodePath   = Environment.getNodePath(context)
        val serverScript = Environment.getServerScript(context)

        // Verify node binary exists before trying to launch
        if (!File(nodePath).exists()) {
            Logger.e(tag, "Node binary not found at: $nodePath")
            return false
        }
        if (!File(serverScript).exists()) {
            Logger.e(tag, "Server script not found at: $serverScript")
            return false
        }

        val env = Environment.buildProcessEnvironment(context, _port)

        // Dynamic heap: ~35% of available RAM, clamped to [384, 1024] MB.
        // Fallback to 512 MB if RAM detection fails.
        val heapMb = computeOptimalHeapMb()
        Logger.i(tag, "Node.js heap: ${heapMb}MB (available: ${getAvailableRamMb()}MB)")

        // IMPORTANT: Only use flags that are valid in the specific Node.js version
        // bundled in this app. Avoid experimental or version-specific V8 flags
        // (e.g. --no-lazy, --max-semi-space-size) which cause immediate crashes on
        // unsupported Node builds.
        val command = listOf(
            nodePath,
            "--max-old-space-size=$heapMb",
            serverScript,
            "--host=127.0.0.1",
            "--port=$_port",
            "--without-connection-token",
            "--extensions-dir=${Environment.getExtensionsDir(context)}",
            "--user-data-dir=${Environment.getUserDataDir(context)}",
            "--server-data-dir=${Environment.getUserDataDir(context)}",
            "--logsPath=${Environment.getLogsDir(context)}",
            "--accept-server-license-terms",
            "--log=info"
        )

        Logger.d(tag, "Command: ${command.joinToString(" ")}")

        return try {
            val processBuilder = ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectErrorStream(true)   // merge stderr into stdout for single reader
                directory(context.filesDir)
            }
            val process = processBuilder.start().also {
                it.outputStream.close()     // close stdin — server doesn't need it
            }
            serverProcessRef.set(process)
            startOutputReader(process)
            startWatchdog(process)
            Logger.i(tag, "Server process started (PID: ${getServerPid()})")
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start server process", e)
            false
        }
    }

    /**
     * Suspends until the server is healthy or the timeout elapses.
     *
     * First run is slow — VS Code needs to compile JS modules and activate
     * extensions. Allow up to [timeoutMs] (default 90 seconds) before giving up.
     *
     * @return true if the server became healthy within the timeout.
     */
    suspend fun waitForReady(
        timeoutMs: Long = 90_000,
        pollIntervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var checks = 0
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isServerHealthy()) {
                val elapsed = System.currentTimeMillis() - startTime
                Logger.i(tag, "Server ready after ${elapsed}ms (${checks} health checks)")
                onServerReady?.invoke()
                return true
            }
            checks++
            // Log progress every 10 seconds so GitHub Actions logs show activity
            if (checks % 20 == 0) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                Logger.d(tag, "Waiting for server... ${elapsed}s elapsed")
            }
            delay(pollIntervalMs)
        }
        Logger.e(tag, "Server did not respond within ${timeoutMs / 1000}s")
        return false
    }

    /**
     * HTTP health check against the running server root.
     * Accepts any non-5xx response as healthy (VS Code returns 200 or 302).
     */
    fun isServerHealthy(): Boolean {
        if (_port == 0) return false
        return try {
            val url = URL("http://127.0.0.1:$_port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout    = 1500
            conn.requestMethod  = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stops the server process gracefully (SIGTERM → SIGKILL after 5 s).
     */
    fun stopServer() {
        isShuttingDown = true
        Logger.i(tag, "Stopping server...")
        val process = serverProcessRef.getAndSet(null) ?: return
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                Logger.w(tag, "Graceful shutdown timed out — force killing")
                process.destroyForcibly()
            } else {
                Logger.i(tag, "Server stopped (exit=${process.exitValue()})")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Error during shutdown, force killing", e)
            process.destroyForcibly()
        }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    fun isRunning(): Boolean = serverProcessRef.get()?.isAlive == true

    fun getServerPid(): Long? {
        return try {
            serverProcessRef.get()?.let { p ->
                val pidField = p.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                pidField.getInt(p).toLong()
            }
        } catch (_: Exception) { null }
    }

    // -- Private helpers --

    private fun allocatePortWithRetry(): Int? {
        repeat(3) {
            val port = PortFinder.findAvailablePort()
            if (port > 0) return port
        }
        return null
    }

    /**
     * Compute optimal Node.js old-gen heap.
     * 35% of available RAM, clamped to [384, 1024] MB.
     * Falls back to 512 MB if detection fails.
     */
    private fun computeOptimalHeapMb(): Int {
        val availMb = getAvailableRamMb()
        if (availMb <= 0L) return DEFAULT_HEAP_MB
        val computed = (availMb * 0.35).toInt()
        return computed.coerceIn(MIN_HEAP_MB, MAX_HEAP_MB)
    }

    private fun getAvailableRamMb(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / 1_048_576L
        } catch (_: Exception) { 0L }
    }

    /** Reads every line from merged stdout+stderr and logs it. */
    private fun startOutputReader(process: Process) {
        thread(name = "node-stdout", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Logger.d(tag, "[node] $line")
                        onServerOutput?.invoke(line)
                    }
                }
            } catch (_: Exception) {
                if (!isShuttingDown) Logger.w(tag, "Output reader ended unexpectedly")
            }
        }
    }

    /**
     * Watchdog thread: waits for process exit, then fires [onServerCrashed].
     * OOM kills (exit 137) get a 3-second delay so memory pressure can subside.
     */
    private fun startWatchdog(process: Process) {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val exitCode = process.waitFor()
                if (isShuttingDown) {
                    Logger.i(tag, "Server exited cleanly (exit=$exitCode)")
                    return@thread
                }
                Logger.w(tag, "Server exited unexpectedly (exit=$exitCode)")
                if (exitCode == 137) {
                    Logger.w(tag, "OOM kill detected — waiting 3s before restart")
                    Thread.sleep(3000)
                }
                onServerCrashed?.invoke(exitCode)
            } catch (_: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted (expected on stopServer)")
            }
        }
    }

    companion object {
        private const val DEFAULT_HEAP_MB = 512
        private const val MIN_HEAP_MB     = 384
        private const val MAX_HEAP_MB     = 1024
    }
}
