package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.PortFinder
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Manages the Node.js VS Code server process.
 *
 * ─── WHY WE LAUNCH server-main.js DIRECTLY ─────────────────────────────────
 *
 * The original design ran:
 *   libnode.so → server.js → fork() → server-main.js
 *
 * This creates a GRANDCHILD process. Android 12+ "Phantom Process Killer"
 * (PhantomProcessRecord) monitors and kills grandchild processes that are not
 * owned by a foreground service. Our foreground service (NodeService) protects
 * the direct child (libnode.so → server.js) but not its fork.
 *
 * New design:
 *   libnode.so → server-main.js   (single process, no fork)
 *
 * We handle the setup that server.js used to do here in Kotlin:
 *  • product.json patching (brand + Open VSX gallery + trusted domains)
 *  • VSCODE_NLS_CONFIG env var injection
 *  • Fallback to server.js's minimal HTTP health server if vscode-reh
 *    hasn't been built/extracted yet
 *
 * ─── THREAD SAFETY ───────────────────────────────────────────────────────────
 * serverProcessRef   AtomicReference  written by startServer (IO), read by
 *                                     isRunning (main), stopServer (main),
 *                                     watchdog (daemon thread)
 * isShuttingDown     @Volatile        read by watchdog; written by stopServer
 * _port              @Volatile        written once by startServer; read anywhere
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false

    val port: Int get() = _port

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onServerReady: (() -> Unit)? = null
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null
    var onServerOutput: ((line: String) -> Unit)? = null

    // ── Start ─────────────────────────────────────────────────────────────────

    /**
     * Starts the VS Code server process.
     *
     * Selects the launch target in priority order:
     *  1. [serverMainPath] (vscode-reh/out/server-main.js) — full VS Code, no fork
     *  2. [serverJsPath]   (server/server.js)              — minimal health server
     *
     * @return true if the process was spawned; false on any error.
     */
    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "startServer called while already running — ignoring")
            return false
        }
        isShuttingDown = false

        // ── Port ──────────────────────────────────────────────────────────────
        _port = findAvailablePort() ?: run {
            Logger.e(tag, "No available TCP port found after retries")
            return false
        }
        Logger.i(tag, "Allocated port: $_port")

        // ── Paths ─────────────────────────────────────────────────────────────
        val nodePath = Environment.getNodePath(context)
        val nodeFile = File(nodePath)
        if (!nodeFile.exists()) {
            Logger.e(tag, "libnode.so not found at $nodePath — APK may not include native libs")
            return false
        }
        if (nodeFile.length() < MIN_NODE_BINARY_SIZE) {
            Logger.e(tag, "libnode.so is a stub (${nodeFile.length()} bytes < $MIN_NODE_BINARY_SIZE). " +
                "A release build with the real Node.js binary is required.")
            return false
        }

        // Ensure tmp dir exists (Android clears cache between launches)
        File(context.cacheDir, "tmp").mkdirs()

        // ── Choose launch target ───────────────────────────────────────────────
        val serverMainPath = File(context.filesDir, "server/vscode-reh/out/server-main.js")
        val serverJsPath   = File(context.filesDir, "server/server.js")

        val (targetScript, useDirectLaunch) = when {
            serverMainPath.exists() -> {
                Logger.i(tag, "Direct launch: server-main.js (no fork — phantom-process safe)")
                // Patch product.json BEFORE starting; VS Code reads it at boot
                patchProductJson()
                serverMainPath to true
            }
            serverJsPath.exists() -> {
                Logger.w(tag, "server-main.js not found — falling back to minimal server.js")
                serverJsPath to false
            }
            else -> {
                Logger.e(tag, "Neither server-main.js nor server.js found — assets not extracted")
                return false
            }
        }

        // ── Environment ───────────────────────────────────────────────────────
        val env = Environment.buildProcessEnvironment(context, _port).toMutableMap()
        // Required by VS Code server to locate NLS translations
        env["VSCODE_NLS_CONFIG"] = """{"locale":"en","availableLanguages":{}}"""
        // Required for REH mode: tells VS Code we are the web host
        env["VSCODE_AGENT_FOLDER"] = Environment.getUserDataDir(context)

        // ── Heap ──────────────────────────────────────────────────────────────
        val heapMb = computeHeapMb()
        Logger.i(tag, "Heap: ${heapMb}MB  Available RAM: ${availableRamMb()}MB")

        // ── Command ───────────────────────────────────────────────────────────
        val command: List<String> = if (useDirectLaunch) {
            buildDirectCommand(nodePath, heapMb, serverMainPath.absolutePath)
        } else {
            buildFallbackCommand(nodePath, heapMb, serverJsPath.absolutePath)
        }
        Logger.d(tag, "Launch: ${command.joinToString(" ")}")

        // ── Spawn ─────────────────────────────────────────────────────────────
        return try {
            val pb = ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectErrorStream(true)        // merge stderr → stdout → single reader
                directory(context.filesDir)      // CWD = filesDir (matches server.js expectation)
            }
            val proc = pb.start().also {
                it.outputStream.close()          // EOF on stdin — server doesn't read it
            }
            serverProcessRef.set(proc)
            startOutputReader(proc)
            startWatchdog(proc)
            Logger.i(tag, "Server spawned (PID=${readPid(proc) ?: "?"})")
            true
        } catch (e: Exception) {
            Logger.e(tag, "ProcessBuilder.start() failed", e)
            false
        }
    }

    /**
     * Command for direct server-main.js launch (no fork).
     * Arguments mirror what server.js passes when calling fork(serverMain, args).
     */
    private fun buildDirectCommand(
        nodePath: String, heapMb: Int, serverMainPath: String
    ): List<String> = listOf(
        nodePath,
        "--max-old-space-size=$heapMb",
        serverMainPath,
        "--host=127.0.0.1",
        "--port=$_port",
        "--without-connection-token",
        "--accept-server-license-terms",
        "--extensions-dir=${Environment.getExtensionsDir(context)}",
        "--user-data-dir=${Environment.getUserDataDir(context)}",
        "--server-data-dir=${Environment.getUserDataDir(context)}",
        "--logsPath=${Environment.getLogsDir(context)}",
        "--log=info"
    )

    /**
     * Command for the server.js fallback (minimal health server).
     * server.js handles its own fork internally in this path.
     */
    private fun buildFallbackCommand(
        nodePath: String, heapMb: Int, serverJsPath: String
    ): List<String> = listOf(
        nodePath,
        "--max-old-space-size=$heapMb",
        serverJsPath,
        "--host=127.0.0.1",
        "--port=$_port",
        "--without-connection-token",
        "--accept-server-license-terms",
        "--extensions-dir=${Environment.getExtensionsDir(context)}",
        "--user-data-dir=${Environment.getUserDataDir(context)}",
        "--server-data-dir=${Environment.getUserDataDir(context)}",
        "--logsPath=${Environment.getLogsDir(context)}",
        "--log=info"
    )

    // ── Health + Wait ─────────────────────────────────────────────────────────

    /**
     * Polls health endpoints until the server responds or [timeoutMs] elapses.
     *
     * VS Code server is slow on first launch: it JIT-compiles JS modules,
     * sets up the extension host, and activates user extensions. Allow 120 s.
     *
     * Endpoints tried in order for each poll cycle:
     *  /healthz  — server.js minimal server
     *  /         — VS Code web workbench root
     */
    suspend fun waitForReady(timeoutMs: Long = 120_000, pollMs: Long = 600): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            if (isServerHealthy()) {
                Logger.i(tag, "Server healthy after ${polls * pollMs / 1000}s / $polls polls")
                onServerReady?.invoke()
                return true
            }
            polls++
            if (polls % 25 == 0) {   // log every ~15 s
                val remaining = (deadline - System.currentTimeMillis()) / 1000
                Logger.d(tag, "Still waiting for server... ${remaining}s remaining")
            }
            delay(pollMs)
        }
        Logger.e(tag, "Server did not become healthy within ${timeoutMs / 1000}s")
        return false
    }

    /** Returns true when any health endpoint returns HTTP 1xx–4xx. */
    fun isServerHealthy(): Boolean {
        if (_port == 0) return false
        for (path in HEALTH_ENDPOINTS) {
            if (checkEndpoint(path)) return true
        }
        return false
    }

    private fun checkEndpoint(path: String): Boolean = try {
        val conn = URL("http://127.0.0.1:$_port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout    = 1500
        conn.requestMethod  = "GET"
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        conn.disconnect()
        code in 100..499
    } catch (_: Exception) { false }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopServer() {
        isShuttingDown = true
        Logger.i(tag, "Stopping server…")
        val proc = serverProcessRef.getAndSet(null) ?: return
        try {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                Logger.w(tag, "Graceful shutdown timed out, force-killing")
                proc.destroyForcibly()
            } else {
                Logger.i(tag, "Stopped cleanly (exit=${proc.exitValue()})")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Shutdown error, force-killing", e)
            proc.destroyForcibly()
        }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    fun isRunning(): Boolean = serverProcessRef.get()?.isAlive == true

    // ── product.json ──────────────────────────────────────────────────────────

    /**
     * Patches VS Code's product.json to inject VSCodroid branding, Open VSX
     * gallery, and trusted domain list. Called in Kotlin before launch so we
     * never need server.js to do it (removes dependency on that step).
     *
     * Idempotent — re-applying the same values is harmless.
     */
    private fun patchProductJson() {
        val file = File(context.filesDir, "server/vscode-reh/product.json")
        if (!file.exists()) {
            Logger.d(tag, "product.json not found — skipping patch")
            return
        }
        try {
            val obj = JSONObject(file.readText())

            obj.put("nameShort",        "VSCodroid")
            obj.put("nameLong",         "VSCodroid")
            obj.put("applicationName",  "vscodroid")
            obj.put("dataFolderName",   ".vscodroid")
            obj.put("quality",          "stable")
            obj.put("telemetryOptIn",   false)
            obj.put("enableTelemetry",  false)
            obj.put("updateUrl",        "")
            obj.put("releaseNotesUrl",  "")
            obj.put("documentationUrl", "")
            obj.put("feedbackUrl",      "")
            obj.put("reportIssueUrl",   "")

            obj.put("extensionsGallery", JSONObject().apply {
                put("serviceUrl",              "https://open-vsx.org/vscode/gallery")
                put("itemUrl",                 "https://open-vsx.org/vscode/item")
                put("resourceUrlTemplate",
                    "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}")
                put("controlUrl",  "")
                put("nlsBaseUrl",  "")
            })

            obj.put("linkProtectionTrustedDomains", JSONArray().apply {
                put("https://open-vsx.org")
                put("https://useblackbox.io")
                put("https://github.com")
                put("https://raw.githubusercontent.com")
                put("https://marketplace.visualstudio.com")
            })

            file.writeText(obj.toString(2))
            Logger.i(tag, "product.json patched successfully")
        } catch (e: Exception) {
            Logger.w(tag, "product.json patch failed (non-fatal): ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findAvailablePort(): Int? {
        repeat(5) {
            val p = PortFinder.findAvailablePort()
            if (p > 0) return p
        }
        return null
    }

    private fun computeHeapMb(): Int {
        val avail = availableRamMb()
        if (avail <= 0L) return DEFAULT_HEAP_MB
        return (avail * 0.35).toInt().coerceIn(MIN_HEAP_MB, MAX_HEAP_MB)
    }

    private fun availableRamMb(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576L
    } catch (_: Exception) { 0L }

    private fun startOutputReader(proc: Process) {
        thread(name = "node-stdout", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Logger.d(tag, "[server] $line")
                        onServerOutput?.invoke(line)
                    }
                }
            } catch (_: Exception) {
                if (!isShuttingDown) Logger.w(tag, "Output reader closed")
            }
        }
    }

    /**
     * Daemon thread that waits for the process to exit.
     *
     * Exit codes:
     *  0   → clean shutdown
     *  1   → JS exception / startup error
     *  137 → SIGKILL (OOM or phantom process killer)
     *
     * On exit code 137 we wait 3 s before notifying so memory pressure
     * has time to subside before NodeService schedules a restart.
     */
    private fun startWatchdog(proc: Process) {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val code = proc.waitFor()
                if (isShuttingDown) { Logger.i(tag, "Watchdog: clean exit ($code)"); return@thread }
                Logger.w(tag, "Watchdog: unexpected exit (code=$code)")
                if (code == 137) {
                    Logger.e(tag, "SIGKILL detected — OOM or phantom process killer. Waiting 3s…")
                    Thread.sleep(3_000)
                }
                onServerCrashed?.invoke(code)
            } catch (_: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted (expected during stopServer)")
            }
        }
    }

    private fun readPid(proc: Process): Long? = try {
        val f = proc.javaClass.getDeclaredField("pid").also { it.isAccessible = true }
        f.getInt(proc).toLong()
    } catch (_: Exception) { null }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Minimum byte-size of a real Node.js binary.
         * Stub/placeholder libnode.so files used in debug builds are typically 0 bytes.
         * A real Node.js ARM64 binary is > 20 MB.
         */
        private const val MIN_NODE_BINARY_SIZE = 1_024L  // 1 KB — catches 0-byte stubs

        private const val DEFAULT_HEAP_MB =  512
        private const val MIN_HEAP_MB     =  384
        private const val MAX_HEAP_MB     = 1024

        /** Health check endpoints tried in order each poll cycle. */
        private val HEALTH_ENDPOINTS = listOf("/healthz", "/")
    }
}
