package com.vscodroid.webview

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * Configures a [WebView] (or [VSCodroidWebViewComponent]) for hosting the VS Code
 * web workbench with desktop-class rendering behaviour.
 *
 * Key principles:
 * - **Desktop user agent**: Overrides Android's default mobile UA so VS Code web
 *   serves the full desktop layout instead of a simplified mobile variant.
 * - **Wide viewport**: Enables `viewport` meta-tag support and disables overview
 *   (fit-to-screen) scaling so VS Code renders at 1:1 pixel ratio.
 * - **No built-in zoom**: Native WebView zoom is disabled; zoom is handled at the
 *   VS Code level via [VSCodroidWebViewComponent]'s pinch-to-zoom handler.
 * - **Text zoom 100%**: Prevents system font-size settings from distorting VS Code's
 *   carefully tuned editor layout.
 * - **Hardware acceleration**: Explicitly set on the WebView view itself (redundant
 *   with the app-level flag but ensures it survives dynamic WebView recreation).
 */
object VSCodroidWebView {
    private const val TAG = "VSCodroidWebView"

    /**
     * Desktop Chrome user agent string (Linux, x86_64).
     *
     * Using Linux rather than Windows avoids triggering any Windows-specific
     * code paths in VS Code's platform detection. The version (131) is kept
     * reasonably current so feature-detection checks pass.
     *
     * IMPORTANT: This string must NOT contain "Mobile", "Android", "Tablet",
     * or "Touch" — all of which trigger VS Code's mobile/compact layout code.
     */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            // -- JavaScript & storage --
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true

            // -- DESKTOP USER AGENT --
            // Must be set before any page load. Causes VS Code web to serve the
            // full desktop workbench instead of the mobile-simplified layout.
            userAgentString = DESKTOP_USER_AGENT

            // -- Viewport (desktop-class rendering) --
            // useWideViewPort=true  → respects <meta name="viewport"> tags
            // loadWithOverviewMode=false → renders at 100% initial scale, no fit-to-screen
            // Together: VS Code renders at device native resolution without any scaling.
            useWideViewPort = true
            loadWithOverviewMode = false

            // -- Zoom: disabled at WebView level --
            // VSCodroidWebViewComponent handles pinch-to-zoom by injecting
            // Ctrl+= / Ctrl+- keyboard events into VS Code's own zoom system.
            // Enabling native WebView zoom would double-zoom with VS Code's font resize.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Text zoom locked to 100% — system accessibility font-size must not
            // distort VS Code's monospace editor layout.
            textZoom = 100

            // -- Content access --
            // allowFileAccess=false: VS Code fetches all resources via HTTP, not file://
            // allowContentAccess=true: needed for content:// URIs from SAF/file pickers
            allowFileAccess = false
            allowContentAccess = true

            // Mixed content: VS Code server is HTTP (localhost), loads HTTPS resources
            // (fonts, CDN — intercepted). ALWAYS_ALLOW is required for this config.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // -- Cache --
            cacheMode = WebSettings.LOAD_DEFAULT

            // -- Media --
            mediaPlaybackRequiresUserGesture = false

            // -- Window management --
            // javaScriptCanOpenWindowsAutomatically=true: VS Code's extension host
            // needs to open popup windows for OAuth flows.
            javaScriptCanOpenWindowsAutomatically = true
            // setSupportMultipleWindows=true: Required for extension webview panels
            // (they render in separate window contexts). onCreateWindow fires in
            // VSCodroidWebChromeClient to route them correctly.
            setSupportMultipleWindows(true)

            // -- Encoding --
            defaultTextEncodingName = "utf-8"
        }

        // -- Hardware acceleration (explicit on the View level) --
        // App-level hardwareAccelerated=true in AndroidManifest covers most cases,
        // but explicitly setting LAYER_TYPE_HARDWARE on the WebView ensures hardware
        // compositing is used even after dynamic WebView recreation (recreateWebView).
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        // -- Scrollbars --
        webView.isScrollbarFadingEnabled = true
        // Vertical scrollbar: VS Code manages its own (Monaco scrollbar), so we hide
        // Android's to avoid a duplicate bar appearing on the right edge.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        // -- Remote debugging (debug builds only) --
        if (Logger.debugEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
            Logger.d(TAG, "WebView remote debugging enabled (chrome://inspect)")
        }

        Logger.i(TAG, "WebView configured (UA: desktop Chrome/Linux, wideViewport=true)")
    }
}
