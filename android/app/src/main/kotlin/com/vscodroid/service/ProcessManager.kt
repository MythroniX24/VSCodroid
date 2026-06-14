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
 * Manages the Node.js server process lifecycle.
 *
 * Responsibilities:
 * - Starting and stopping the Node.js code-server process
 * - Health-checking the server via HTTP on the root endpoint
 * - Monitoring process liveness via a watchdog thread
 * - Streaming stdout/stderr output for diagnostics
 *
 * Thread safety:
 * - [serverProcessRef] is an [AtomicReference] — reads/writes are always atomic
 *   and visible across the IO dispatcher, main thread, and watchdog thread.
 * - [isShuttingDown] is [@Volatile] — the watchdog thread checks it on every
 *   wake-up; the IO thread sets it before calling [Process.destroy].
 * - [_port] is [@Volatile] — written once by [startServer] on the IO thread,
 *   read by the main thread via [port].
 *
 * This class does NOT handle restart logic or Android service concerns;
 * those belong to [NodeService].
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    /** Atomic holder for the running server process. Null when stopped. */
    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false

    /** The port the server is listening on. Only valid after [startServer] returns true. */
    val port: Int get() = _port

    // -- Callbacks --

    /** Invoked on the caller's coroutine when the server responds to a health check. */
    var onServerReady: (() -> Unit)? = null

    /** Invoked on the watchdog thread when the server process exits unexpectedly. */
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null

    /** Invoked on the watchdog thread before an automatic restart attempt. */
    var onServerRestarting: (() -> Unit)? = null

    /** Invoked on the output-reader thread for every line of server stdout/stderr. */
    var onServerOutput: ((line: String) -> Unit)? = null

    // -- Lifecycle --

    /**
     * Starts the Node.js code-server process.
     *
     * - Allocates an available port (with retry on collision)
     * - Builds the command line from [Environment] paths
     * - Dynamically scales `--max-old-space-size` based on available RAM
     * - Spawns the process and begins output reading + watchdog monitoring
     *
     * @return `true` if the process was spawned successfully, `false` on error
     *         or if a process is already running.
     */
    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "Server already running")
            return false
        }

        isShuttingDown = false

        // Port allocation with retry (handles rare TOCTOU race)
        _port = allocatePortWithRetry() ?: run {
            Logger.e(tag, "Failed to allocate available port after retries")
            return false
        }
        Logger.i(tag, "Starting server on port $_port")

        // Ensure TMPDIR exists — Android may clear cache between launches
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val nodePath = Environment.getNodePath(context)
        val serverScript = Environment.getServerScript(context)
        val env = Environment.buildProcessEnvironment(context, _port)

        // Dynamic heap: use ~35% of available RAM, clamped to [256, 1024] MB.
        // A 3 GB device gets ~1050 MB available → 368 MB heap.
        // A 6 GB device gets ~2100 MB available → 735 MB heap.
        // A 8 GB device gets ~2800 MB available → 980 MB heap, clamped to 1024.
        val heapMb = computeOptimalHeapMb()
        Logger.i(tag, "Node.js heap: ${heapMb}MB (available RAM: ${getAvailableRamMb()}MB)")

        val command = listOf(
            nodePath,
            "--max-old-space-size=$heapMb",
            "--max-semi-space-size=32",      // Keep young-gen small to reduce GC pause
            "--no-lazy",                     // Eager compilation for faster cold start
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

        return try {
            val processBuilder = ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectErrorStream(true)
                directory(context.filesDir)
            }
            val process = processBuilder.start().also { it.outputStream.close() }
            serverProcessRef.set(process)
            startOutputReader(process)
            startWatchdog(process)
            Logger.i(tag, "Server process started with PID ${getServerPid()}")
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start server", e)
            false
        }
    }

    /**
     * Suspends until the server responds to a health check or the timeout elapses.
     *
     * Polls [isServerHealthy] at [pollIntervalMs] intervals. On success, invokes
     * [onServerReady] and returns `true`. On timeout, returns `false`.
     */
    suspend fun waitForReady(timeoutMs: Long = 30_000, pollIntervalMs: Long = 200): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isServerHealthy()) {
                Logger.i(tag, "Server ready after ${System.currentTimeMillis() - startTime}ms")
                onServerReady?.invoke()
                return true
            }
            delay(pollIntervalMs)
        }
        Logger.e(tag, "Server failed to become ready within ${timeoutMs}ms")
        return false
    }

    /**
     * Performs a synchronous HTTP GET to `http://127.0.0.1:{port}/`.
     *
     * Accepts any non-server-error response (< 500) as healthy.
     * Uses a short timeout (1 s) to keep polling snappy.
     */
    fun isServerHealthy(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$_port/")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stops the server process.
     *
     * Sets [isShuttingDown] first to suppress crash callbacks, then attempts
     * graceful SIGTERM followed by forcible SIGKILL on timeout.
     */
    fun stopServer() {
        isShuttingDown = true
        Logger.i(tag, "Stopping server...")
        val process = serverProcessRef.getAndSet(null) ?: return
        try {
            process.destroy()
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (exited) {
                Logger.i(tag, "Server stopped with exit code ${process.exitValue()}")
            } else {
                Logger.w(tag, "Graceful shutdown timed out, force killing")
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Logger.w(tag, "Shutdown error, force killing", e)
            process.destroyForcibly()
        }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /** Returns the PID of the running server process, or `null` if not running. */
    fun getServerPid(): Long? {
        val process = serverProcessRef.get() ?: return null
        return try {
            val pidMethod = process.javaClass.methods.firstOrNull {
                it.name == "pid" && it.parameterCount == 0
            }
            if (pidMethod != null) {
                (pidMethod.invoke(process) as? Long)
            } else {
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                pidField.getInt(process).toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns `true` if the server process is alive. */
    fun isRunning(): Boolean = serverProcessRef.get()?.isAlive == true

    // -- Internal --

    /**
     * Allocates an available TCP port. Retries up to [MAX_PORT_RETRIES] times to
     * handle the TOCTOU race between [PortFinder.findAvailablePort] and process start.
     */
    private fun allocatePortWithRetry(): Int? {
        repeat(MAX_PORT_RETRIES) {
            val candidate = PortFinder.findAvailablePort()
            if (candidate > 0) return candidate
        }
        return null
    }

    /**
     * Computes the optimal Node.js old-space heap size based on available RAM.
     *
     * Formula: 35% of available RAM, clamped to [MIN_HEAP_MB, MAX_HEAP_MB].
     */
    private fun computeOptimalHeapMb(): Int {
        val availMb = getAvailableRamMb()
        val optimal = (availMb * 0.35).toInt()
        return optimal.coerceIn(MIN_HEAP_MB, MAX_HEAP_MB)
    }

    private fun getAvailableRamMb(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            DEFAULT_AVAILABLE_RAM_MB
        }
    }

    /**
     * Starts a daemon thread that reads every line from the merged stdout/stderr
     * and forwards it to [onServerOutput] and the debug log.
     */
    private fun startOutputReader(process: Process) {
        thread(name = "node-stdout", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Logger.d(tag, "[node] $line")
                        onServerOutput?.invoke(line)
                    }
                }
            } catch (e: Exception) {
                if (!isShuttingDown) {
                    Logger.w(tag, "Output reader stopped", e)
                }
            }
        }
    }

    /**
     * Starts a daemon watchdog thread that waits for the process to exit.
     *
     * On unexpected exit, invokes [onServerCrashed] with the exit code.
     * Exit code 137 typically means OOM kill; we add a brief back-off delay
     * before the callback fires so that memory pressure can subside before
     * [NodeService] schedules a restart.
     *
     * Exit code interpretation:
     * - 0   → clean exit (server.stop() was called)
     * - 137 → SIGKILL (OOM killer or Android phantom process limit)
     * - other → unexpected crash
     */
    private fun startWatchdog(process: Process) {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val exitCode = process.waitFor()
                if (isShuttingDown) {
                    Logger.i(tag, "Server shut down gracefully (exit=$exitCode)")
                    return@thread
                }
                when (exitCode) {
                    0    -> Logger.i(tag, "Server exited cleanly")
                    137  -> {
                        Logger.w(tag, "Server killed (OOM or phantom process limit)")
                        // Brief delay: let Android's memory reclaimer run before restart
                        Thread.sleep(OOM_RESTART_DELAY_MS)
                    }
                    else -> Logger.e(tag, "Server crashed with exit code $exitCode")
                }
                onServerCrashed?.invoke(exitCode)
            } catch (e: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted (expected on stopServer)")
            }
        }
    }

    companion object {
        private const val MAX_PORT_RETRIES = 3
        private const val MIN_HEAP_MB = 256
        private const val MAX_HEAP_MB = 1024
        private const val DEFAULT_AVAILABLE_RAM_MB = 1500L

        /** Delay before crash callback fires on OOM kill (ms). */
        private const val OOM_RESTART_DELAY_MS = 3000L
    }
}
