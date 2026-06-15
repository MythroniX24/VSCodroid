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
 * Launch strategy
 * ───────────────
 * We launch node → server.js (the bootstrap wrapper) rather than server-main.js
 * directly. server.js is responsible for:
 *   • patching product.json (branding, gallery, trusted domains)
 *   • setting up VS Code-specific env vars before forking server-main.js
 *   • forwarding all flags to the fork in the correct SPACE-SEPARATED format
 *     that VS Code's argv parser expects (--flag value, not --flag=value)
 *
 * IMPORTANT: VS Code's server-main.js arg parser requires SPACE-SEPARATED args:
 *     node server-main.js --host 127.0.0.1 --port 13337   ← CORRECT
 *     node server-main.js --host=127.0.0.1 --port=13337   ← SILENTLY IGNORED
 * Passing = format causes VS Code to start with default paths and serve nothing,
 * which is why the WebView shows a white page with no content.
 *
 * Thread safety
 * ─────────────
 * serverProcessRef   AtomicReference   written by startServer(IO) / read by
 *                                      isRunning(main) / watchdog(daemon)
 * isShuttingDown     @Volatile         read by watchdog, written by stopServer
 * _port              @Volatile         written once; read from any thread
 */
class ProcessManager(private val context: Context) {

    private val tag = "ProcessManager"

    private val serverProcessRef = AtomicReference<Process?>(null)
    private var watchdogThread: Thread? = null

    @Volatile private var _port: Int = 0
    @Volatile private var isShuttingDown = false

    val port: Int get() = _port

    var onServerReady:   (() -> Unit)?          = null
    var onServerCrashed: ((exitCode: Int) -> Unit)? = null
    var onServerOutput:  ((line: String) -> Unit)?  = null

    // ── Start ─────────────────────────────────────────────────────────────────

    fun startServer(): Boolean {
        if (serverProcessRef.get() != null) {
            Logger.w(tag, "startServer called while already running — ignoring")
            return false
        }
        isShuttingDown = false

        // ── Port ──────────────────────────────────────────────────────────────
        _port = findAvailablePort() ?: run {
            Logger.e(tag, "No available TCP port after retries")
            return false
        }

        // ── Paths ─────────────────────────────────────────────────────────────
        val nodePath  = Environment.getNodePath(context)
        val nodeFile  = File(nodePath)

        if (!nodeFile.exists()) {
            Logger.e(tag, "libnode.so not found: $nodePath")
            return false
        }
        if (nodeFile.length() < MIN_VALID_SIZE) {
            Logger.e(tag, "libnode.so is a placeholder stub (${nodeFile.length()} bytes). " +
                "A release build is required.")
            return false
        }

        val serverScript = Environment.getServerScript(context)
        if (!File(serverScript).exists()) {
            Logger.e(tag, "server.js not found: $serverScript")
            return false
        }

        // ── Pre-launch patches ─────────────────────────────────────────────────
        File(context.cacheDir, "tmp").mkdirs()
        patchProductJson()      // brand + gallery + trusted domains

        // ── Environment ───────────────────────────────────────────────────────
        val env = Environment.buildProcessEnvironment(context, _port).toMutableMap()
        env["VSCODE_NLS_CONFIG"]  = """{"locale":"en","availableLanguages":{}}"""
        env["VSCODE_AGENT_FOLDER"] = Environment.getUserDataDir(context)

        // ── Heap ──────────────────────────────────────────────────────────────
        val heapMb = computeHeapMb()
        Logger.i(tag, "Starting server on port=$_port heap=${heapMb}MB")

        // ── Command ───────────────────────────────────────────────────────────
        // CRITICAL: VS Code's argv parser requires SPACE-SEPARATED flags.
        //   WRONG:  --extensions-dir=/path  (= format silently discarded)
        //   RIGHT:  "--extensions-dir", "/path"  (two separate list elements)
        // We pass all flags to server.js; it forwards them to server-main.js
        // via fork(), preserving the space-separated format.
        val command = listOf(
            nodePath,
            "--max-old-space-size=$heapMb",
            serverScript,
            "--host",            "127.0.0.1",
            "--port",            "$_port",
            "--without-connection-token",
            "--accept-server-license-terms",
            "--extensions-dir",  Environment.getExtensionsDir(context),
            "--user-data-dir",   Environment.getUserDataDir(context),
            "--server-data-dir", Environment.getUserDataDir(context),
            "--logsPath",        Environment.getLogsDir(context),
            "--log",             "info"
        )
        Logger.d(tag, "Command: ${command.joinToString(" ")}")

        // ── Spawn ─────────────────────────────────────────────────────────────
        return try {
            val pb = ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectErrorStream(true)
                directory(context.filesDir)
            }
            val proc = pb.start().also { it.outputStream.close() }
            serverProcessRef.set(proc)
            startOutputReader(proc)
            startWatchdog(proc)
            Logger.i(tag, "Server process started (PID=${readPid(proc) ?: "?"})")
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to start server process", e)
            false
        }
    }

    // ── Health + Wait ─────────────────────────────────────────────────────────

    /**
     * Polls until the server is healthy or [timeoutMs] elapses.
     *
     * First launch is slow: product-bundled JS modules JIT-compile,
     * extension host activates. Allow 120 s.
     * Subsequent hot-starts are fast (< 5 s usually).
     */
    suspend fun waitForReady(timeoutMs: Long = 120_000, pollMs: Long = 700): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            if (isServerHealthy()) {
                val elapsed = timeoutMs - (deadline - System.currentTimeMillis())
                Logger.i(tag, "Server healthy in ${elapsed/1000}s ($polls polls)")
                onServerReady?.invoke()
                return true
            }
            polls++
            if (polls % 20 == 0) {
                val rem = (deadline - System.currentTimeMillis()) / 1000
                Logger.d(tag, "Waiting for server… ${rem}s remaining")
            }
            delay(pollMs)
        }
        Logger.e(tag, "Server did not respond within ${timeoutMs/1000}s")
        return false
    }

    fun isServerHealthy(): Boolean {
        if (_port == 0) return false
        // server.js runs a health server on PORT+1 when the full VS Code server
        // is launched (so the health server doesn't block VS Code from binding PORT).
        // When vscode-reh is absent, the fallback server runs on PORT itself.
        // Try both so health check works in both scenarios.
        return checkEndpoint(_port + 1, "/healthz")   // full VS Code mode
            || checkEndpoint(_port,     "/healthz")   // fallback mode
            || checkEndpoint(_port,     "/")          // last resort
    }

    private fun checkEndpoint(p: Int, path: String): Boolean = try {
        val conn = URL("http://127.0.0.1:$p$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout    = 2000
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
                Logger.w(tag, "Graceful shutdown timed out — force-killing")
                proc.destroyForcibly()
            } else {
                Logger.i(tag, "Server stopped (exit=${proc.exitValue()})")
            }
        } catch (e: Exception) {
            proc.destroyForcibly()
        }
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    fun isRunning(): Boolean = serverProcessRef.get()?.isAlive == true

    // ── product.json ──────────────────────────────────────────────────────────

    /**
     * Patches VS Code's product.json with VSCodroid branding, Open VSX gallery,
     * and trusted domains. Must run BEFORE server starts so VS Code reads the
     * patched values at boot time.
     *
     * Idempotent — re-patching the same values is safe.
     */
    private fun patchProductJson() {
        val file = File(context.filesDir, "server/vscode-reh/product.json")
        if (!file.exists()) {
            Logger.d(tag, "product.json not found — skipping patch (vscode-reh not extracted?)")
            return
        }
        try {
            val obj = JSONObject(file.readText())
            obj.put("nameShort",        "VSCodroid")
            obj.put("nameLong",         "VSCodroid")
            obj.put("applicationName",  "vscodroid")
            obj.put("dataFolderName",   ".vscodroid")
            obj.put("quality",          "stable")
            obj.put("enableTelemetry",  false)
            obj.put("updateUrl",        "")
            obj.put("extensionsGallery", JSONObject().apply {
                put("serviceUrl", "https://open-vsx.org/vscode/gallery")
                put("itemUrl",    "https://open-vsx.org/vscode/item")
                put("resourceUrlTemplate",
                    "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}")
                put("controlUrl", "")
            })
            obj.put("linkProtectionTrustedDomains", JSONArray().apply {
                put("https://open-vsx.org")
                put("https://useblackbox.io")
                put("https://github.com")
                put("https://raw.githubusercontent.com")
            })
            file.writeText(obj.toString(2))
            Logger.i(tag, "product.json patched")
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

    private fun startWatchdog(proc: Process) {
        watchdogThread = thread(name = "node-watchdog", isDaemon = true) {
            try {
                val code = proc.waitFor()
                if (isShuttingDown) { Logger.i(tag, "Clean exit ($code)"); return@thread }
                Logger.w(tag, "Unexpected server exit (code=$code)")
                if (code == 137) {
                    Logger.e(tag, "OOM kill or phantom process limit — waiting 3s…")
                    Thread.sleep(3_000)
                }
                onServerCrashed?.invoke(code)
            } catch (_: InterruptedException) {
                Logger.d(tag, "Watchdog interrupted")
            }
        }
    }

    private fun readPid(proc: Process): Long? = try {
        val f = proc.javaClass.getDeclaredField("pid").also { it.isAccessible = true }
        f.getInt(proc).toLong()
    } catch (_: Exception) { null }

    companion object {
        private const val MIN_VALID_SIZE  = 1_024L   // stubs are 0 bytes
        private const val DEFAULT_HEAP_MB = 512
        private const val MIN_HEAP_MB     = 384
        private const val MAX_HEAP_MB     = 1024
    }
}
