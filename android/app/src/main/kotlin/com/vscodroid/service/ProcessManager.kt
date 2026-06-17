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
 * ── Diagnostics ──────────────────────────────────────────────────────────────
 * Every lifecycle event (port chosen, heap size, validation failures, process
 * output) is appended to an in-memory ring buffer ([recentOutput]) capped at
 * [MAX_LOG_LINES] lines. [MainActivity] surfaces this buffer in its Failed-state
 * diagnostics screen so a startup failure is never just a blank page — the exact
 * reason and recent log lines are always visible to the user.
 *
 * [lastStartFailureReason] holds a precise, human-readable explanation of the
 * MOST RECENT reason [startServer] returned `false`. This is read by
 * [NodeService] instead of a generic string resource, so the user sees exactly
 * what went wrong (e.g. "libnode.so is 64 bytes — placeholder stub, see setup
 * instructions") rather than "Failed to start development server".
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * serverProcessRef        AtomicReference  written by startServer (IO thread)
 *                                          read by isRunning (main) / watchdog
 * isShuttingDown           @Volatile       set by stopServer, read by watchdog
 * _port                    @Volatile       written once, read from any thread
 * lastStartFailureReason   @Volatile       written by startServer, read by NodeService
 * recentOutput             synchronized    appended by output reader thread,
 *                                          snapshotted by any thread via getRecentOutput()
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false
    @Volatile private var lastStartFailureReason: String? = null

    private val recentOutput = java.util.Collections.synchronizedList(
        ArrayDeque<String>(MAX_LOG_LINES)
    )

    val port: Int get() = _port

    var onServerReady:   (() -> Unit)?              = null
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null
    var onServerOutput:  ((line: String) -> Unit)?  = null

    /** Precise reason the last [startServer] call returned false, or null if it succeeded / hasn't run. */
    fun getLastFailureReason(): String? = lastStartFailureReason

    /** Snapshot of the most recent log lines (lifecycle events + process stdout/stderr), oldest first. */
    fun getRecentOutput(): List<String> = synchronized(recentOutput) { recentOutput.toList() }

    private fun log(line: String) {
        synchronized(recentOutput) {
            recentOutput.addLast(line)
            while (recentOutput.size > MAX_LOG_LINES) recentOutput.removeFirst()
        }
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "Already running")
            return false
        }
        isShuttingDown = false
        lastStartFailureReason = null

        // ── Port ──────────────────────────────────────────────────────────────
        _port = findPort() ?: run {
            fail("No free TCP port found after ${PORT_RETRIES} attempts.")
            return false
        }

        // ── Validate libnode.so ──────────────────────────────────────────────
        // This is the single most common cause of "server never starts": the
        // GitHub Actions debug build creates a 64-byte placeholder ELF stub when
        // it cannot find a `libnode.so` release asset in the repository (see
        // .github/workflows/build.yml "Fetch libnode.so" step). A stub passes
        // File.exists() but cannot be executed — exec() fails immediately.
        // We detect this explicitly so the failure reason is unambiguous instead
        // of a generic ProcessBuilder IOException.
        val nodePath = Environment.getNodePath(context)
        val nodeFile = File(nodePath)
        if (!nodeFile.exists()) {
            fail("libnode.so not found at $nodePath. The APK may have been built " +
                "without native libraries — check jniLibs packaging in build.gradle.kts.")
            return false
        }
        val nodeSize = nodeFile.length()
        if (nodeSize < MIN_VALID_NODE_SIZE) {
            fail("libnode.so is only $nodeSize bytes — this is a placeholder stub, " +
                "not a real Node.js binary (expected 30-90 MB). Your GitHub Actions build " +
                "could not find a 'libnode.so' GitHub Release asset in this repository, so " +
                "it created a non-functional placeholder instead of failing the build. " +
                "Fix: run the 'Build libnode.so' workflow once (Actions tab → Build libnode.so " +
                "→ Run workflow), wait for it to publish a release, then re-run 'Build Debug APK'.")
            return false
        }

        val serverScript = Environment.getServerScript(context)
        if (!File(serverScript).exists()) {
            fail("server.js not found at $serverScript. Asset extraction may have failed " +
                "or the APK was built without assets/server.js.")
            return false
        }

        // ── Pre-launch ────────────────────────────────────────────────────────
        File(context.cacheDir, "tmp").mkdirs()
        patchProductJson()

        // ── Environment ───────────────────────────────────────────────────────
        val env = Environment.buildProcessEnvironment(context, _port).toMutableMap()
        env["VSCODE_NLS_CONFIG"]   = """{"locale":"en","availableLanguages":{}}"""
        env["VSCODE_AGENT_FOLDER"] = Environment.getUserDataDir(context)

        // ── Command ───────────────────────────────────────────────────────────
        // server.js's bootstrap arg parser splits on '=' — flags MUST use
        // --flag=value format. Space-separated (--flag value) is silently dropped.
        val heapMb = heapMb()
        val command = listOf(
            nodePath,
            "--max-old-space-size=$heapMb",
            serverScript,
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
        log("Starting: port=$_port heap=${heapMb}MB libnode.so=${nodeSize / 1_048_576}MB")
        Logger.i(tag, "Port=$_port Heap=${heapMb}MB NodeSize=${nodeSize}B")
        Logger.d(tag, "Cmd: ${command.joinToString(" ")}")

        // ── Spawn ─────────────────────────────────────────────────────────────
        return try {
            val proc = ProcessBuilder(command)
                .apply {
                    environment().putAll(env)
                    redirectErrorStream(true)
                    directory(context.filesDir)
                }
                .start()
                .also { it.outputStream.close() }

            serverProcessRef.set(proc)
            startOutputReader(proc)
            startWatchdog(proc)
            Logger.i(tag, "Process started (PID=${pid(proc) ?: "?"})")
            true
        } catch (e: Exception) {
            fail("ProcessBuilder failed to start node: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun fail(reason: String) {
        lastStartFailureReason = reason
        log("FAILURE: $reason")
        Logger.e(tag, reason)
    }

    // ── Health ────────────────────────────────────────────────────────────────

    suspend fun waitForReady(timeoutMs: Long = 120_000, pollMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var n = 0
        while (System.currentTimeMillis() < deadline) {
            if (isServerHealthy()) {
                log("Server healthy after ${n} polls")
                Logger.i(tag, "Server ready (polls=$n)")
                onServerReady?.invoke()
                return true
            }
            // If the process died while we were waiting, stop polling immediately —
            // no point waiting out the full timeout for a process that's gone.
            val proc = serverProcessRef.get()
            if (proc != null && !proc.isAlive && !isShuttingDown) {
                fail("Process exited while waiting for health check (exit=${proc.exitValue()}).")
                return false
            }
            n++
            if (n % 15 == 0) {
                val rem = (deadline - System.currentTimeMillis()) / 1000
                Logger.d(tag, "Waiting… ${rem}s left")
            }
            delay(pollMs)
        }
        fail("Server did not respond on port $_port within ${timeoutMs / 1000}s (process alive=${isRunning()}).")
        return false
    }

    fun isServerHealthy(): Boolean {
        if (_port == 0) return false
        return probe("/healthz") || probe("/")
    }

    private fun probe(path: String): Boolean = try {
        val c = URL("http://127.0.0.1:$_port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout    = 2000
        c.requestMethod  = "GET"
        c.instanceFollowRedirects = false
        val code = c.responseCode
        c.disconnect()
        code in 100..499
    } catch (_: Exception) { false }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopServer() {
        isShuttingDown = true
        val proc = serverProcessRef.getAndSet(null) ?: return
        Logger.i(tag, "Stopping server…")
        try {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            else Logger.i(tag, "Stopped (exit=${proc.exitValue()})")
        } catch (_: Exception) { proc.destroyForcibly() }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    fun isRunning(): Boolean = serverProcessRef.get()?.isAlive == true

    // ── product.json ──────────────────────────────────────────────────────────

    private fun patchProductJson() {
        val f = File(context.filesDir, "server/vscode-reh/product.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.put("nameShort",       "VSCodroid")
            o.put("nameLong",        "VSCodroid")
            o.put("applicationName", "vscodroid")
            o.put("dataFolderName",  ".vscodroid")
            o.put("quality",         "stable")
            o.put("enableTelemetry", false)
            o.put("updateUrl",       "")
            o.put("extensionsGallery", JSONObject().apply {
                put("serviceUrl",          "https://open-vsx.org/vscode/gallery")
                put("itemUrl",             "https://open-vsx.org/vscode/item")
                put("resourceUrlTemplate", "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}")
                put("controlUrl",          "")
            })
            o.put("linkProtectionTrustedDomains", JSONArray().apply {
                put("https://open-vsx.org")
                put("https://useblackbox.io")
                put("https://github.com")
                put("https://raw.githubusercontent.com")
            })
            f.writeText(o.toString(2))
            Logger.i(tag, "product.json patched")
        } catch (e: Exception) {
            Logger.w(tag, "product.json patch failed: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findPort(): Int? {
        repeat(PORT_RETRIES) { val p = PortFinder.findAvailablePort(); if (p > 0) return p }
        return null
    }

    private fun heapMb(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            (mi.availMem / 1_048_576L * 0.35).toInt().coerceIn(MIN_HEAP, MAX_HEAP)
        } catch (_: Exception) { DEFAULT_HEAP }
    }

    private fun startOutputReader(proc: Process) {
        thread(name = "node-out", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.lineSequence().forEach { line ->
                        Logger.d(tag, "[node] $line")
                        log(line)
                        onServerOutput?.invoke(line)
                    }
                }
            } catch (_: Exception) { if (!isShuttingDown) Logger.w(tag, "Reader closed") }
        }
    }

    private fun startWatchdog(proc: Process) {
        watchdogThread = thread(name = "node-watch", isDaemon = true) {
            try {
                val code = proc.waitFor()
                if (isShuttingDown) return@thread
                Logger.w(tag, "Server exited (code=$code)")
                log("Server process exited unexpectedly (code=$code)")
                if (code == 137) Thread.sleep(3_000)   // wait after OOM kill
                onServerCrashed?.invoke(code)
            } catch (_: InterruptedException) {}
        }
    }

    private fun pid(p: Process): Long? = try {
        p.javaClass.getDeclaredField("pid").also { it.isAccessible = true }.getInt(p).toLong()
    } catch (_: Exception) { null }

    companion object {
        /** Real Node.js ARM64 binaries are 30-90 MB. A stub from a missing release asset is 64 bytes. */
        private const val MIN_VALID_NODE_SIZE = 1_000_000L   // 1 MB — generous floor, still catches all stubs
        private const val DEFAULT_HEAP = 512
        private const val MIN_HEAP     = 384
        private const val MAX_HEAP     = 1024
        private const val PORT_RETRIES = 5
        private const val MAX_LOG_LINES = 300
    }
}
