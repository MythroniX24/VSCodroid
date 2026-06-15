package com.vscodroid.webview

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * Configures a [WebView] for hosting VS Code web workbench.
 *
 * KEY DECISIONS:
 *
 * 1. User-agent: We strip " Mobile" from the stock Android WebView UA instead
 *    of replacing it with a desktop UA entirely. This is important because:
 *    - Removing "Mobile" stops VS Code's `isMobile` check from triggering
 *    - Keeping the rest of the Android/Chrome UA ensures VS Code doesn't serve
 *      unexpected content or hit platform-detection code paths that crash WebView
 *    - A full Linux desktop UA caused VS Code to fail initialization (white screen)
 *
 * 2. loadWithOverviewMode: kept TRUE. Setting it to false with useWideViewPort=true
 *    causes the page to render at native resolution before VS Code's CSS loads,
 *    producing a white flash that stays white if any JS error occurs.
 *
 * 3. setSupportMultipleWindows: FALSE. VS Code's internal webview panels use iframes,
 *    not window.open(). Enabling multiple windows changes iframe routing in ways
 *    that break VS Code's extension panel rendering.
 */
object VSCodroidWebView {
    private const val TAG = "VSCodroidWebView"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        // ── User Agent: strip "Mobile" only ─────────────────────────────────
        // Original UA example: "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36
        //   (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        // After strip:        "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36
        //   (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        //
        // VS Code checks: userAgent.indexOf('Mobile') >= 0  → must be -1 for desktop mode
        val originalUA = webView.settings.userAgentString ?: ""
        val desktopUA  = originalUA
            .replace(" Mobile Safari/", " Safari/")   // removes "Mobile" from UA string
            .replace(" Mobile/", "/")                  // catch alternate formats
        webView.settings.userAgentString = desktopUA
        Logger.i(TAG, "UA: $desktopUA")

        webView.settings.apply {
            // ── JavaScript + storage ─────────────────────────────────────────
            javaScriptEnabled  = true
            domStorageEnabled  = true
            @Suppress("DEPRECATION") databaseEnabled = true

            // ── Viewport ─────────────────────────────────────────────────────
            // useWideViewPort=true  → honour <meta name="viewport">
            // loadWithOverviewMode=true → fit to screen initially (prevents white flash
            //   before VS Code's CSS loads)
            useWideViewPort      = true
            loadWithOverviewMode = true

            // ── Zoom ─────────────────────────────────────────────────────────
            // Disable native WebView zoom; VSCodroidWebViewComponent handles
            // pinch-to-zoom by injecting Ctrl+=/Ctrl+- into VS Code instead.
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false

            // Text zoom: lock at 100% so system font-scale can't distort editor layout
            textZoom = 100

            // ── Content access ────────────────────────────────────────────────
            allowFileAccess    = false  // VS Code uses HTTP, not file://
            allowContentAccess = true   // needed for content:// URIs from file pickers

            // Mixed content: server is HTTP (localhost) but loads HTTPS CDN resources
            // (all intercepted). ALWAYS_ALLOW is required for this configuration.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // ── Cache ────────────────────────────────────────────────────────
            cacheMode = WebSettings.LOAD_DEFAULT

            // ── Media ────────────────────────────────────────────────────────
            mediaPlaybackRequiresUserGesture = false

            // ── Windows ──────────────────────────────────────────────────────
            // false: VS Code uses iframes for webview panels, not window.open().
            // true caused extension panels to route incorrectly.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true

            // ── Encoding ────────────────────────────────────────────────────
            defaultTextEncodingName = "utf-8"
        }

        // ── Hardware acceleration ─────────────────────────────────────────────
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        // ── Scrollbars ────────────────────────────────────────────────────────
        // VS Code renders its own Monaco scrollbars; hide Android's duplicates
        webView.isScrollbarFadingEnabled     = true
        webView.isVerticalScrollBarEnabled   = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode               = WebView.OVER_SCROLL_NEVER

        // ── Remote debugging ─────────────────────────────────────────────────
        if (Logger.debugEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
            Logger.d(TAG, "Chrome remote debugging enabled (chrome://inspect)")
        }
    }
}
