package com.vscodroid.webview

import android.content.DialogInterface
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.vscodroid.util.Logger

/**
 * Chrome client for the VS Code WebView.
 *
 * Handles:
 * - **Console messages**: Forward to [Logger] with appropriate severity level.
 * - **JavaScript dialogs**: alert(), confirm(), prompt() — VS Code uses confirm()
 *   in several flows (e.g. "Close all tabs?"). Without implementing these the
 *   dialogs silently dismiss and user actions are lost.
 * - **File chooser**: VS Code's "Open File" dialog and extension install flows use
 *   `<input type="file">`. Without this, file pickers silently fail.
 * - **Window creation**: Extension webview panels open in a new Window context.
 *   We route them to a delegate [WebView] inside the same Activity.
 * - **Permission requests**: Camera / microphone for voice-coding extensions,
 *   clipboard-read for paste operations.
 */
class VSCodroidWebChromeClient(
    private val onFileChooserRequested: ((ValueCallback<Array<Uri>>) -> Unit)? = null,
    private val onWindowCreateRequested: ((WebView) -> Unit)? = null,
    /**
     * Forwards every captured console message (level + text) to a sink other
     * than Logcat — wired to [com.vscodroid.debug.DebugConsoleOverlay.logFromWebView]
     * by [MainActivity] so console output (including JS errors our own
     * injected scripts' try/catch blocks would otherwise swallow silently) is
     * visible directly on-device without needing chrome://inspect on a PC.
     */
    private val onConsoleMessageCaptured: ((level: String, message: String) -> Unit)? = null
) : WebChromeClient() {

    private val tag = "WebChromeClient"

    // -- Console Logging --

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val message = "[JS:${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] " +
            consoleMessage.message()
        val level = consoleMessage.messageLevel()
        when (level) {
            ConsoleMessage.MessageLevel.ERROR   -> Logger.e(tag, message)
            ConsoleMessage.MessageLevel.WARNING -> Logger.w(tag, message)
            ConsoleMessage.MessageLevel.LOG     -> Logger.d(tag, message)
            ConsoleMessage.MessageLevel.DEBUG   -> Logger.d(tag, message)
            ConsoleMessage.MessageLevel.TIP     -> Logger.d(tag, message)
            else                               -> Logger.d(tag, message)
        }
        onConsoleMessageCaptured?.invoke(level.name, message)
        return true  // consumed — don't show default WebView console overlay
    }

    // -- JavaScript Dialogs --

    /**
     * Handles JavaScript `alert()` calls from VS Code.
     *
     * VS Code uses `alert()` rarely (mostly in extension webview panels). Without
     * this override, the default WebView implementation shows a bare dialog without
     * the app title which looks broken.
     */
    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        Logger.d(tag, "JS alert: $message")
        val ctx = view.context
        try {
            AlertDialog.Builder(ctx)
                .setMessage(message ?: "")
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
        } catch (e: Exception) {
            // Activity may have been destroyed — fall back to default WebView handling
            Logger.w(tag, "Could not show JS alert dialog: ${e.message}")
            return false
        }
        return true
    }

    /**
     * Handles JavaScript `confirm()` calls.
     *
     * VS Code uses `confirm()` in several critical flows:
     * - "Close all unsaved editors?" when closing a workspace
     * - Certain extension uninstall confirmations
     * - The diff editor's "Accept All Changes?" prompt
     */
    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        Logger.d(tag, "JS confirm: $message")
        val ctx = view.context
        try {
            AlertDialog.Builder(ctx)
                .setMessage(message ?: "")
                .setPositiveButton("OK")     { _, _ -> result.confirm() }
                .setNegativeButton("Cancel") { _, _ -> result.cancel()  }
                .setOnCancelListener { result.cancel() }
                .show()
        } catch (e: Exception) {
            Logger.w(tag, "Could not show JS confirm dialog: ${e.message}")
            return false
        }
        return true
    }

    /**
     * Handles JavaScript `prompt()` calls.
     *
     * VS Code and some extensions use `prompt()` for one-off user input:
     * - "Rename Symbol" in some language extensions
     * - "Enter git commit message" fallback
     */
    override fun onJsPrompt(
        view: WebView, url: String?,
        message: String?, defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        Logger.d(tag, "JS prompt: $message")
        val ctx = view.context
        try {
            val editText = android.widget.EditText(ctx).apply {
                setText(defaultValue ?: "")
                setSingleLine()
                setPadding(48, 24, 48, 24)
            }
            AlertDialog.Builder(ctx)
                .setMessage(message ?: "")
                .setView(editText)
                .setPositiveButton("OK") { _, _ ->
                    result.confirm(editText.text.toString())
                }
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
        } catch (e: Exception) {
            Logger.w(tag, "Could not show JS prompt dialog: ${e.message}")
            return false
        }
        return true
    }

    // -- File Chooser --

    /**
     * Handles `<input type="file">` file picker requests from VS Code.
     *
     * VS Code web uses `<input type="file">` for:
     * - "Open File" from the menu
     * - "Upload to workspace" (drag-and-drop fallback)
     * - Extension VSIX installation via the extensions panel
     *
     * Without this override, the file picker silently returns null and no file
     * is selected — the user is left confused why "Open File" doesn't work.
     *
     * Delegates to [MainActivity.vsixPickerLauncher] or the generic
     * [MainActivity.filePickerLauncher] via [onFileChooserRequested].
     */
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        Logger.i(tag, "File chooser requested (accept=${fileChooserParams.acceptTypes?.joinToString()})")

        val callback = onFileChooserRequested
        if (callback != null) {
            callback(filePathCallback)
            return true
        }

        // No handler registered — cancel cleanly (don't leave callback hanging)
        filePathCallback.onReceiveValue(null)
        return false
    }

    // -- Permission Requests --

    /**
     * Handles permission requests from VS Code extensions that need hardware access.
     *
     * Currently auto-grants:
     * - `RESOURCE_AUDIO_CAPTURE` — voice coding extensions (e.g., Whisper-based)
     * - `RESOURCE_VIDEO_CAPTURE` — camera-based extensions
     *
     * Denies all other resources (e.g., protected media ID, MIDI).
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val granted = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> true
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> true
                else -> false
            }
        }.toTypedArray()

        if (granted.isNotEmpty()) {
            Logger.i(tag, "Granting WebView permissions: ${granted.joinToString()}")
            request.grant(granted)
        } else {
            Logger.d(tag, "Denying WebView permissions: ${request.resources.joinToString()}")
            request.deny()
        }
    }

    // -- Window creation (Extension WebviewPanels) --

    /**
     * Handles `window.open()` and `setSupportMultipleWindows(true)` window creation.
     *
     * VS Code extension webview panels (e.g., Blackbox AI chat sidebar, Markdown
     * preview) are hosted as separate `Window` contexts within the same WebView
     * process. VS Code's internal webview panel system manages them via iframes,
     * but some extensions open popup windows that need routing.
     *
     * When [onWindowCreateRequested] is provided, the callback receives the new
     * [WebView] so the caller can manage its lifecycle. If no callback is set,
     * we return `false` to allow default WebView behaviour (which typically opens
     * the window in-place).
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        val newWebView = WebView(view.context)
        VSCodroidWebView.configure(newWebView)

        // Route the new WebView's transport to the caller
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport != null) {
            transport.webView = newWebView
            resultMsg?.sendToTarget()
            Logger.d(tag, "Window.open() routed to new WebView (isDialog=$isDialog)")
            onWindowCreateRequested?.invoke(newWebView)
            return true
        }

        return false
    }

    override fun onCloseWindow(window: WebView) {
        Logger.d(tag, "Window.close() called")
        // No explicit action needed — the window WebView will be GC'd if no
        // references are held. onWindowCreateRequested callback is responsible
        // for releasing its reference when the window is closed.
    }

    // -- Geolocation (deny: location not needed in a code editor) --

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        Logger.d(tag, "Geolocation request denied from $origin")
        callback?.invoke(origin, false, false)
    }
}
