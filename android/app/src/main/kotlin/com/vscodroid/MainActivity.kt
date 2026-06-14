package com.vscodroid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.FrameLayout
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.ExtensionBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.DesktopModeJS
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.VSCodroidWebViewComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    // -- Views --
    private var webView: VSCodroidWebViewComponent? = null
    private var extraKeyRow: ExtraKeyRow? = null

    // -- Service --
    private var nodeService: NodeService? = null
    private var serviceBound = false
    private var serviceBindingInitiated = false

    // -- State --
    private var serverPort = 0
    private var backgroundedAt = 0L
    private var bridgeInitialized = false
    private var pendingFileUri: Uri? = null

    // -- Bridges --
    private lateinit var securityManager: SecurityManager
    private lateinit var safManager: SafStorageManager
    private lateinit var extensionBridge: ExtensionBridge

    // -- File chooser state --
    /**
     * Pending callback from [VSCodroidWebChromeClient.onShowFileChooser].
     * Must be fulfilled (or null-cancelled) before another picker can open.
     * Held here on the Activity so the ActivityResult launcher can access it.
     */
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    // =====================================================================
    // Activity Result Launchers
    // =====================================================================

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Logger.i(tag, "Notification permission granted=$granted") }

    /**
     * SAF folder picker — called from [AndroidBridge.openFolderPicker] via JS.
     */
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleSafFolderSelected(it) } }

    /**
     * Generic file picker for WebView [VSCodroidWebChromeClient.onShowFileChooser].
     * Accepts any file type (VS Code's "Open File" dialog and similar).
     */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val cb = pendingFileChooserCallback
        pendingFileChooserCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    /**
     * VSIX-specific file picker — restricts selection to .vsix files.
     * Called from the "Install Extension from VSIX" command in VS Code's palette.
     */
    private val vsixPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Logger.i(tag, "VSIX selected: $uri")
        val token = securityManager.getSessionToken()
        val result = extensionBridge.installVsixFromUri(uri.toString(), token)
        Logger.i(tag, "VSIX install result: $result")
        runOnUiThread {
            Toast.makeText(this, "Extension installed. Reloading VS Code…", Toast.LENGTH_SHORT).show()
            // Reload VS Code so the extension is activated
            webView?.reload()
        }
    }

    // =====================================================================
    // Service connection
    // =====================================================================

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NodeService.LocalBinder
            nodeService = binder.getService()
            serviceBound = true
            Logger.i(tag, "Bound to NodeService")
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeService = null
            serviceBound = false
            Logger.w(tag, "Disconnected from NodeService")
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply system bar insets to the CONTAINER (FrameLayout), not the WebView.
        // This keeps env(safe-area-inset-*) CSS values correct inside VS Code —
        // if we padded the WebView directly, VS Code's own safe-area CSS would
        // double-compensate and render content too far from the edges.
        val container = findViewById<android.view.View>(R.id.webViewContainer)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        safManager = SafStorageManager(this)

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
        checkPreviousCrash()
        checkStorageHealth()

        val launchUri = intent?.data
        if (launchUri != null && launchUri.scheme != "vscodroid") {
            pendingFileUri = launchUri
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data
        when {
            uri?.scheme == "vscodroid" && uri.host == "callback" -> handleExtensionCallback(uri)
            uri?.scheme == "vscodroid" && uri.host == "oauth"    -> handleOAuthCallback(uri)
            else -> handleIntent(intent)
        }
    }

    override fun onDestroy() {
        safManager.stopFileWatcher()
        if (serviceBindingInitiated) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBindingInitiated = false
            serviceBound = false
        }
        // Cancel any pending file chooser so the WebView isn't left waiting
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (serverPort > 0) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        handleResumeFromBackground()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Logger.w(tag, "Low memory signal: level=$level")
            writeMemoryPressure(level)
            webView?.evaluateJavascript("window.__vscodroid?.onLowMemory?.($level)", null)
        }
    }

    // =====================================================================
    // SAF Folder Picker
    // =====================================================================

    fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    fun openVsixPicker() {
        vsixPickerLauncher.launch("*/*")
    }

    private fun handleSafFolderSelected(uri: Uri) {
        Logger.i(tag, "SAF folder selected: $uri")
        safManager.persistPermission(uri)
        val displayName = safManager.getDisplayName(uri)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Opening folder")
            .setMessage("Syncing \"$displayName\"…")
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                val mirrorDir = withContext(Dispatchers.IO) {
                    safManager.syncToLocal(uri) { done, total ->
                        runOnUiThread {
                            dialog.setMessage("Syncing \"$displayName\"\n$done / $total files…")
                        }
                    }
                }
                safManager.stopFileWatcher()
                safManager.startFileWatcher(mirrorDir, uri)
                writeActiveFolder(mirrorDir.absolutePath)
                dialog.dismiss()
                if (serverPort > 0) navigateToFolder(serverPort, mirrorDir.absolutePath)
            } catch (e: SecurityException) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity,
                    "Permission denied. Please select the folder again.", Toast.LENGTH_LONG).show()
                Logger.e(tag, "SAF permission revoked during sync", e)
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity,
                    "Failed to open folder: ${e.message}", Toast.LENGTH_LONG).show()
                Logger.e(tag, "SAF sync failed", e)
            }
        }
    }

    fun openRecentSafFolder(uri: Uri) {
        if (!safManager.hasPersistedPermission(uri)) {
            Toast.makeText(this, "Permission expired. Please select the folder again.", Toast.LENGTH_LONG).show()
            openFolderPicker()
            return
        }
        handleSafFolderSelected(uri)
    }

    // =====================================================================
    // WebView setup
    // =====================================================================

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView?.let { wv ->
            VSCodroidWebView.configure(wv)
            // Dark loading screen — stays visible until VS Code URL loads.
            // Background matches VS Code's dark theme (#1e1e1e) so there's no
            // white flash when VS Code's CSS applies after the navigation.
            wv.loadData("""<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1.0,viewport-fit=cover">
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:100%;height:100%;background:#1e1e1e;overflow:hidden}
  .c{display:flex;flex-direction:column;align-items:center;justify-content:center;
     height:100vh;color:#858585;font-family:-apple-system,sans-serif}
  .logo{font-size:28px;color:#cccccc;margin-bottom:16px;letter-spacing:1px}
  .msg{font-size:14px;margin-bottom:24px}
  .spinner{width:32px;height:32px;border:3px solid #333;border-top-color:#007acc;
           border-radius:50%;animation:spin 0.8s linear infinite}
  @keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
  <div class="c">
    <div class="logo">VSCodroid</div>
    <div class="msg">Starting server…</div>
    <div class="spinner"></div>
  </div>
</body>
</html>""", "text/html", "utf-8")
        }
    }

    private fun setupExtraKeyRow() {
        extraKeyRow = findViewById(R.id.extraKeyRow)
        extraKeyRow?.setupWithRootView(findViewById(R.id.webViewContainer))
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView?.evaluateJavascript(
                    "(function() { return window.AndroidBridge?.onBackPressed?.() || false; })()"
                ) { result -> if (result != "true") moveTaskToBack(true) }
            }
        })
    }

    // =====================================================================
    // Service
    // =====================================================================

    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startAndBindService() {
        val intent = Intent(this, NodeService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBindingInitiated = true
    }

    private fun setupServiceCallbacks() {
        nodeService?.onServerReady = { port ->
            serverPort = port
            runOnUiThread { loadVSCode(port) }
        }
        nodeService?.onServerError = { message ->
            runOnUiThread {
                Logger.e(tag, "Server error: $message")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        val service = nodeService ?: return
        val port = service.getPort()
        if (port > 0 && service.isServerRunning()) {
            Logger.i(tag, "Server already running on port $port, loading immediately")
            serverPort = port
            loadVSCode(port)
        }
    }

    // =====================================================================
    // VS Code loading
    // =====================================================================

    private fun loadVSCode(port: Int, folderPath: String? = null) {
        initBridge(port)
        navigateToFolder(port, folderPath ?: Environment.getProjectsDir(this))
    }

    /**
     * Initializes the WebView bridge infrastructure — called once per server lifecycle.
     *
     * Sets up in order:
     * 1. [SecurityManager] (token generation)
     * 2. [ExtensionBridge] (VSIX install, extension management)
     * 3. [AndroidBridge] (core Android ↔ JS bridge)
     * 4. [VSCodroidWebViewClient] with [onEarlyPageStart] for matchMedia override
     * 5. [VSCodroidWebChromeClient] with file-chooser callback
     * 6. [KeyInjector] for extra key row modifier forwarding
     */
    private fun initBridge(port: Int) {
        val wv = webView ?: return
        if (bridgeInitialized) return
        bridgeInitialized = true

        securityManager = SecurityManager(port)

        // Extension bridge — VSIX install, extension listing
        extensionBridge = ExtensionBridge(
            context = this,
            security = securityManager,
            onVsixInstalled = { extensionId ->
                Logger.i(tag, "Extension installed via bridge: $extensionId")
            }
        )
        wv.addJavascriptInterface(extensionBridge, "AndroidExtensionBridge")

        // Core AndroidBridge
        val clipboardBridge = ClipboardBridge(this)
        val bridge = AndroidBridge(
            context = this,
            security = securityManager,
            clipboard = clipboardBridge,
            onBackPressed = { false },
            onMinimize = { moveTaskToBack(true) },
            onOpenFolderPicker = { openFolderPicker() },
            onOpenRecentFolder = { uri -> openRecentSafFolder(uri) },
            onOpenVsixPicker = { openVsixPicker() },
            onShowAbout = { runOnUiThread { showAboutDialog() } },
            safManager = safManager
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // ServiceWorker interception must be set up before loadUrl
        VSCodroidWebViewClient.setupServiceWorkerInterception(port)

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            onCrash = { recreateWebView() },
            onPageLoaded = { injectBridgeToken() }
            // onEarlyPageStart intentionally omitted:
            // evaluateJavascript() in onPageStarted is unreliable across WebView
            // versions and runs AFTER VS Code's bundle anyway (JS is queued, not
            // synchronous). All injections run safely in onPageFinished instead.
        )

        // WebChromeClient with file chooser routing.
        // The callback lambda captures pendingFileChooserCallback and opens the file picker.
        wv.webChromeClient = VSCodroidWebChromeClient(
            onFileChooserRequested = { callback ->
                pendingFileChooserCallback?.onReceiveValue(null)  // cancel any stale callback
                pendingFileChooserCallback = callback
                filePickerLauncher.launch("*/*")
            },
            onWindowCreateRequested = { newWebView ->
                // Extension webview panels open as new Window contexts.
                // We configure them with the same settings and don't display them
                // separately — VS Code's internal iframe routing handles the display.
                VSCodroidWebView.configure(newWebView)
                Logger.d(tag, "Extension webview window created")
            }
        )

        val keyInjector = KeyInjector(wv)
        extraKeyRow?.keyInjector = keyInjector
    }

    private fun navigateToFolder(port: Int, folderPath: String) {
        val wv = webView ?: return
        val url = "http://127.0.0.1:$port/?folder=${Uri.encode(folderPath)}"
        Logger.i(tag, "Loading VS Code at $url")
        wv.loadUrl(url)
    }

    private fun writeActiveFolder(folderPath: String) {
        try {
            File(Environment.getHomeDir(this), ".vscodroid_folder").writeText(folderPath)
        } catch (e: Exception) {
            Logger.d(tag, "Failed to write active folder: ${e.message}")
        }
    }

    // =====================================================================
    // JavaScript injections — page loaded (onPageFinished)
    // =====================================================================

    /**
     * Injected after VS Code's page finishes loading.
     *
     * This fires from [VSCodroidWebViewClient.onPageFinished]. At this point
     * VS Code's DOM is ready and we can safely inject CSS and install bridges
     * that wait for VS Code's module registry to be available.
     */
    private fun injectBridgeToken() {
        val wv = webView ?: return
        val token = securityManager.getSessionToken()

        // 1. Expose auth token — must be first so all other injections can use it
        wv.evaluateJavascript(
            "window.__vscodroid=window.__vscodroid||{};" +
            "window.__vscodroid.authToken='$token';", null
        )

        // 2. matchMedia override — makes VS Code render desktop layout.
        //    Safe here (onPageFinished): VS Code's workbench re-checks matchMedia
        //    when the window is resized or when layout recalculates. Our override
        //    takes effect on the next such check and on any new matchMedia() call.
        wv.evaluateJavascript(DesktopModeJS.MATCH_MEDIA_OVERRIDE, null)

        // 3. Desktop CSS — enforce desktop proportions
        wv.evaluateJavascript(DesktopModeJS.DESKTOP_CSS, null)

        // 4. window.open override — external links open in browser
        wv.evaluateJavascript(DesktopModeJS.WINDOW_OPEN_OVERRIDE, null)

        // 5. Context menu bridge — enables long-press right-click
        wv.evaluateJavascript(DesktopModeJS.CONTEXT_MENU_BRIDGE, null)

        // 6. Memory pressure handler
        wv.evaluateJavascript(DesktopModeJS.MEMORY_PRESSURE_HANDLER, null)

        // 7. BroadcastChannel relay for Web Worker → AndroidBridge calls
        wv.evaluateJavascript(DesktopModeJS.BRIDGE_RELAY, null)

        // 8. Command palette commands (runs async retry loop — safe to fire early)
        wv.evaluateJavascript(DesktopModeJS.PALETTE_COMMANDS, null)

        // 9. Extra key row modifier interceptor
        extraKeyRow?.keyInjector?.setupModifierInterceptor()

        // 10. Process any file-open intent from cold start
        pendingFileUri?.let { uri ->
            pendingFileUri = null
            handleIntent(Intent().apply { data = uri })
        }
    }

    // =====================================================================
    // WebView recreation
    // =====================================================================

    private fun recreateWebView() {
        Logger.w(tag, "Recreating WebView after render process crash")
        val wv = webView ?: return
        val lastUrl = wv.url
        val container = findViewById<android.widget.FrameLayout>(R.id.webViewContainer)
        container.removeView(wv)
        wv.destroy()

        val newWebView = VSCodroidWebViewComponent(this)
        newWebView.id = R.id.webView
        container.addView(newWebView, 0,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView = newWebView
        bridgeInitialized = false

        setupWebView()
        if (serverPort > 0) {
            if (lastUrl != null && lastUrl.startsWith("http://127.0.0.1")) {
                newWebView.loadUrl(lastUrl)
            } else {
                loadVSCode(serverPort)
            }
        }
    }

    // =====================================================================
    // Background resume
    // =====================================================================

    private fun handleResumeFromBackground() {
        val ts = backgroundedAt
        if (ts == 0L || serverPort == 0) return
        backgroundedAt = 0
        if (nodeService?.isServerRunning() != true) return

        val bgMs = SystemClock.elapsedRealtime() - ts
        when {
            bgMs > FORCE_RELOAD_THRESHOLD_MS -> {
                Logger.i(tag, "Reloading after ${bgMs / 1000}s background")
                webView?.reload()
            }
            bgMs > HEALTH_CHECK_THRESHOLD_MS -> checkConnectionHealth(bgMs)
        }
    }

    private fun checkConnectionHealth(bgMs: Long) {
        val wv = webView ?: return
        wv.evaluateJavascript("""
(function() {
    var dialogs = document.querySelectorAll('.monaco-dialog-box');
    for (var i = 0; i < dialogs.length; i++) {
        var t = (dialogs[i].textContent || '').toLowerCase();
        if (t.indexOf('reconnect') >= 0 || t.indexOf('lost') >= 0) {
            console.warn('[VSCodroid] Connection lost, reloading');
            window.location.reload();
            return 'reload:connection-lost';
        }
    }
    try {
        var req = indexedDB.open('vscode-web-db');
        req.onerror = function() { console.warn('[VSCodroid] IndexedDB broken, reloading'); window.location.reload(); };
        req.onsuccess = function() { req.result.close(); };
    } catch(e) { window.location.reload(); return 'reload:idb-exception'; }
    return 'ok';
})()""") { result -> Logger.i(tag, "Health check after ${bgMs / 1000}s: ${result?.trim('"')}") }
    }

    // =====================================================================
    // Deep link callbacks
    // =====================================================================

    private fun handleExtensionCallback(uri: Uri) {
        val dataParam = uri.getQueryParameter("data") ?: return
        Logger.i(tag, "Extension callback relay received")
        val escaped = org.json.JSONObject.quote(dataParam)
        webView?.evaluateJavascript("""
(function() {
    try {
        var d = JSON.parse(decodeURIComponent($escaped));
        var key = 'vscode-web.url-callbacks[' + d.id + ']';
        var value = JSON.stringify(d.uri);
        localStorage.setItem(key, value);
        window.dispatchEvent(new StorageEvent('storage', {
            key: key, newValue: value, oldValue: null,
            storageArea: localStorage, url: window.location.href
        }));
    } catch(e) { console.error('[VSCodroid] Callback relay error:', e); }
})();""", null)
    }

    private fun handleOAuthCallback(uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state") ?: return
        Logger.i(tag, "OAuth callback received (state=$state)")
        val escapedCode = org.json.JSONObject.quote(code)
        val escapedState = org.json.JSONObject.quote(state)
        webView?.evaluateJavascript(
            "window.__vscodroid?.onOAuthCallback?.($escapedCode, $escapedState)", null)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        Logger.i(tag, "Received intent with URI: $uri")
        val escaped = org.json.JSONObject.quote(uri.toString())
        webView?.evaluateJavascript("window.__vscodroid?.onFileOpen?.($escaped)", null)
    }

    // =====================================================================
    // About dialog
    // =====================================================================

    fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "unknown" }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage("${getString(R.string.about_version_format, versionName)}\n\n${getString(R.string.legal_disclaimer)}")
            .setPositiveButton("OK", null)
            .setNeutralButton("Source Code") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rmyndharis/VSCodroid")))
            }
            .setNegativeButton("Privacy Policy") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rmyndharis.github.io/VSCodroid/privacy-policy.html")))
            }
            .show()
    }

    // =====================================================================
    // Health checks
    // =====================================================================

    private fun checkPreviousCrash() {
        if (!CrashReporter.hasPendingCrash()) return
        val lastCrash = CrashReporter.getLastCrash() ?: return
        val preview = if (lastCrash.length > 500) lastCrash.take(500) + "\n…" else lastCrash
        AlertDialog.Builder(this)
            .setTitle("VSCodroid crashed")
            .setMessage("The app crashed in a previous session.\n\n$preview")
            .setPositiveButton("Dismiss") { _, _ -> CrashReporter.clearCrashLogs() }
            .setNeutralButton("Copy Report") { _, _ ->
                val report = CrashReporter.generateBugReport(this)
                val cb = getSystemService(android.content.ClipboardManager::class.java)
                cb.setPrimaryClip(android.content.ClipData.newPlainText("VSCodroid Bug Report", report))
                Toast.makeText(this, "Bug report copied", Toast.LENGTH_SHORT).show()
                CrashReporter.clearCrashLogs()
            }
            .setCancelable(true)
            .show()
    }

    private fun checkStorageHealth() {
        if (!StorageManager.isStorageLow(this)) return
        val available = StorageManager.formatSize(StorageManager.getAvailableStorage(this))
        Toast.makeText(this, "Storage low ($available available). Clear caches in Settings.", Toast.LENGTH_LONG).show()
        Logger.w(tag, "Storage low: $available available")
    }

    private fun writeMemoryPressure(level: Int) {
        try {
            File(cacheDir, "tmp").also { it.mkdirs() }
                .let { File(it, "vscodroid-memory-pressure").writeText(level.toString()) }
        } catch (e: Exception) {
            Logger.d(tag, "Failed to write memory pressure: ${e.message}")
        }
    }

    // =====================================================================
    // Constants
    // =====================================================================

    companion object {
        private const val HEALTH_CHECK_THRESHOLD_MS = 60_000L    // 1 minute
        private const val FORCE_RELOAD_THRESHOLD_MS = 300_000L   // 5 minutes
    }
}
