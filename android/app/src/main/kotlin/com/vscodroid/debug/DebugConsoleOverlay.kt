package com.vscodroid.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A temporary, on-device debug console overlay.
 *
 * Built specifically to diagnose "I made a fix but nothing changed on the
 * device" situations WITHOUT requiring a PC + USB + chrome://inspect (which
 * isn't always available). It surfaces, directly inside the running app:
 *
 *  - Every `console.log` / `console.warn` / `console.error` emitted by VS
 *    Code's own JS or by our injected scripts (forwarded from
 *    [com.vscodroid.webview.VSCodroidWebChromeClient.onConsoleMessage]) —
 *    critically including JS exceptions that our own injected scripts'
 *    `try/catch` blocks would otherwise swallow silently.
 *  - Direct Kotlin-side log lines from key decision points (e.g. "Open Folder
 *    picker launched", "Keyboard check result: true/false") called explicitly
 *    via [log] from [MainActivity] / [VSCodroidWebViewComponent], so both the
 *    JS and native sides of a feature can be traced end-to-end in one place.
 *
 * Always rendered as the topmost view (added last in `activity_main.xml`) so
 * its small toggle button is tappable regardless of what VS Code is currently
 * showing. Only the toggle button (and the expanded panel, while open) consume
 * touches — everything else passes through to the WebView untouched.
 *
 * Usage from Kotlin: `debugConsole?.log("Folder picker launched")`
 * Usage from injected JS: forwarded automatically via the WebChromeClient.
 */
class DebugConsoleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = StringBuilder()
    private var lineCount = 0

    private lateinit var toggleButton: Button
    private lateinit var panel: LinearLayout
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    init {
        // The overlay's own root FrameLayout never intercepts touches itself —
        // only its children (the toggle button, and the panel while expanded) do.
        buildToggleButton()
        buildPanel()
        panel.visibility = ViewGroup.GONE
    }

    // -- Public API --

    /** Appends a timestamped line to the console and auto-scrolls to it. */
    fun log(message: String) {
        mainHandler.post {
            lineCount++
            val ts = timeFormat.format(System.currentTimeMillis())
            logLines.append("[$ts] $message\n")
            // Cap retained history so this never grows unbounded during a long session.
            if (lineCount > MAX_LINES) {
                val firstNewline = logLines.indexOf("\n")
                if (firstNewline >= 0) logLines.delete(0, firstNewline + 1)
                lineCount--
            }
            if (::logTextView.isInitialized) {
                logTextView.text = logLines.toString()
                scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    /** Appends a console message captured from the WebView, prefixed by its severity level. */
    fun logFromWebView(level: String, message: String) {
        log("[$level] $message")
    }

    fun show() { panel.visibility = ViewGroup.VISIBLE; toggleButton.text = "✕" }
    fun hide() { panel.visibility = ViewGroup.GONE; toggleButton.text = "LOG" }
    fun toggle() { if (panel.visibility == ViewGroup.VISIBLE) hide() else show() }

    // -- View construction (programmatic — no extra XML resources needed) --

    private fun buildToggleButton() {
        toggleButton = Button(context).apply {
            text = "LOG"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 0, 122, 204))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { toggle() }
        }
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(12), dp(80))
        }
        addView(toggleButton, params)
    }

    private fun buildPanel() {
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 20, 20, 20))
        }

        // -- Header row: title + Copy + Clear + Close --
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "VSCodroid Debug Console"
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copyBtn = smallButton("Copy") { copyLogs() }
        val clearBtn = smallButton("Clear") { clearLogs() }
        val closeBtn = smallButton("✕") { hide() }
        header.addView(title)
        header.addView(copyBtn)
        header.addView(clearBtn)
        header.addView(closeBtn)
        panel.addView(header)

        // -- Scrollable log text --
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
        }
        logTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#33FF33"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "Waiting for log output…\n"
        }
        scrollView.addView(logTextView)
        panel.addView(scrollView)

        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.55f).toInt()
        ).apply {
            gravity = Gravity.BOTTOM
        }
        addView(panel, params)
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 60, 60, 60))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun copyLogs() {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("VSCodroid Debug Log", logLines.toString()))
        Toast.makeText(context, "Logs copied — paste them back to Claude", Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        logLines.clear()
        lineCount = 0
        logTextView.text = "Cleared.\n"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Cap on retained log lines, to bound memory during a long debug session. */
        private const val MAX_LINES = 500
    }
}
