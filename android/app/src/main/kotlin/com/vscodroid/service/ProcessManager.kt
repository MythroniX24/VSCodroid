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
 * ── Arg format ───────────────────────────────────────────────────────────────
 * All flags passed as --flag=value (NOT space-separated --flag value).
 * server.js parses with arg.indexOf('=') which only works with = format.
 * Using space-separated breaks server.js: VS Code gets no port, no paths,
 * starts with defaults → serves nothing → blank WebView.
 *
 * ── Health check ─────────────────────────────────────────────────────────────
 * We check PORT directly — NOT a side-channel port. This is essential:
 *   • When vscode-reh is present: server-main.js eventually binds PORT.
 *     Until then, health checks return "connection refused" → we wait.
 *     When VS Code is actually ready the check passes → WebView loads.
 *   • When vscode-reh is absent: fallback server.js binds PORT immediately
 *     and responds to /healthz.
 * A side-port health server (PORT+1) would give a false positive the instant
 * server.js starts, loading the WebView before VS Code is ready on PORT →
 * connection refused → blank page. That was the bug.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * serverProcessRef  AtomicReference  written by startServer (IO thread)
 *                                    read by isRunning (main) / watchdog
 * isShuttingDown    @Volatile        set by stopServer, read by watchdog
 * _port             @Volatile        written once, read from any thread
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false

    val port: Int get() = _port

    var onServerReady:   (() -> Unit)?              = null
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null
    var onServerOutput:  ((line: String) -> Unit)?  = null

    // ── Start ─────────────────────────────────────────────────────────────────

    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "Already running")
            return false
        }
        isShuttingDown = false

        // ── Port ──────────────────────────────────────────────────────────────
        _port = findPort() ?: run {
            Logger.e(tag, "No free port found")
            return false
        }

        // ── Validate binaries ─────────────────────────────────────────────────
        val nodePath = Environment.getNodePath(context)
        val nodeFile = File(nodePath)
        if (!nodeFile.exists()) {
            Logger.e(tag, "libnode.so missing: $nodePath")
            return false
        }
        if (nodeFile.length() < MIN_BINARY_SIZE) {
            Logger.e(tag, "libnode.so is a stub (${nodeFile.length()} bytes). Release build needed.")
            return false
        }

        val serverScript = Environment.getServerScript(context)
        if (!File(serverScript).exists()) {
            Logger.e(tag, "server.js missing: $serverScript")
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
        // MUST use --flag=value format — server.js arg parser splits on '='
        // Space-separated (--flag value) silently discards the value.
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
        Logger.i(tag, "Port=$_port Heap=${heapMb}MB")
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
            Logger.e(tag, "Process start failed", e)
            false
        }
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * Waits until VS Code is actually ready to serve the WebView.
     *
     * Polls PORT directly — the only authoritative signal:
     *  • fallback server (no vscode-reh): responds to /healthz immediately
     *  • VS Code server (vscode-reh present): responds to / after 30–90 s
     *
     * Do NOT add a side-port health check here. A side-port would give an
     * instant false positive and send the WebView to PORT before VS Code
     * has bound it, producing a blank page.
     */
    suspend fun waitForReady(timeoutMs: Long = 120_000, pollMs: Long = 800): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var n = 0
        while (System.currentTimeMillis() < deadline) {
            if (isServerHealthy()) {
                Logger.i(tag, "Server ready (polls=$n)")
                onServerReady?.invoke()
                return true
            }
            n++
            if (n % 15 == 0) {
                val rem = (deadline - System.currentTimeMillis()) / 1000
                Logger.d(tag, "Waiting… ${rem}s left")
            }
            delay(pollMs)
        }
        Logger.e(tag, "Timed out after ${timeoutMs / 1000}s")
        return false
    }

    fun isServerHealthy(): Boolean {
        if (_port == 0) return false
        // /healthz → fallback server responds here
        // /        → VS Code server responds here (200 or 302)
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
        repeat(5) { val p = PortFinder.findAvailablePort(); if (p > 0) return p }
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
                if (code == 137) Thread.sleep(3_000)   // wait after OOM kill
                onServerCrashed?.invoke(code)
            } catch (_: InterruptedException) {}
        }
    }

    private fun pid(p: Process): Long? = try {
        p.javaClass.getDeclaredField("pid").also { it.isAccessible = true }.getInt(p).toLong()
    } catch (_: Exception) { null }

    companion object {
        private const val MIN_BINARY_SIZE = 1_024L   // 0-byte stubs caught here
        private const val DEFAULT_HEAP    = 512
        private const val MIN_HEAP        = 384
        private const val MAX_HEAP        = 1024
    }
}
