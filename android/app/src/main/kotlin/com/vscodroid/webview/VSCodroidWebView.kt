package com.vscodroid.webview

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import com.vscodroid.util.Logger

/**
 * Configures a [WebView] for hosting VS Code web workbench.
 *
 * ── Why this configuration ───────────────────────────────────────────────────
 * Evidence: loading the exact same VS Code server URL in stock Chrome (no UA
 * spoofing, no injected CSS, no matchMedia overrides) renders the correct,
 * fully-proportioned desktop 3-pane VS Code layout — narrow Activity Bar,
 * properly-sized Explorer, editor, and side panel — just zoomed out to fit the
 * screen, with native pinch-to-zoom available to zoom back in. That is Chrome's
 * standard behaviour for any page that doesn't ship a mobile-optimised
 * `<meta name="viewport">` tag: treat it as a "desktop site", lay it out at a
 * wide virtual viewport (so CSS breakpoints see desktop width), shrink the
 * whole rendered page to fit the screen, and let the user pinch-zoom into it.
 *
 * This WebView is configured to reproduce exactly that proven-working
 * behaviour — nothing more:
 *  • useWideViewPort + loadWithOverviewMode  → same "wide virtual viewport,
 *    shrink to fit" handling Chrome uses by default for non-mobile pages.
 *  • Native pinch-zoom ENABLED (setSupportZoom/builtInZoomControls = true)
 *    → the same interaction model Chrome offers, letting the user zoom into
 *    the correctly-proportioned desktop layout. A previous version of this
 *    class disabled native zoom and substituted a custom two-finger gesture
 *    that only changed VS Code's editor font size (via a synthetic Ctrl+/Ctrl-
 *    keystroke) — that left the *rest* of the UI (activity bar, explorer,
 *    tabs, status bar) permanently tiny with no way to zoom into it, which is
 *    what made the in-app layout look broken compared to Chrome.
 *  • No custom CSS overrides, no matchMedia spoofing. VS Code's own responsive
 *    layout already renders correctly once it sees a desktop-width viewport
 *    (proven by the Chrome screenshot); forcing additional CSS on top of that
 *    fights VS Code's own layout calculations instead of helping.
 *  • UA: only the literal substring " Mobile" is stripped (Android/Chrome
 *    otherwise left intact). This is a narrow, low-risk tweak for any
 *    UA-string-based (not viewport-based) `isMobile` branches VS Code may use
 *    elsewhere — it is NOT what makes the desktop layout activate (the wide
 *    viewport already does that, per the Chrome evidence), so it is safe to
 *    keep without needing to fully spoof a desktop OS in the UA.
 */
object VSCodroidWebView {
    private const val TAG = "VSCodroidWebView"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        // ── User Agent: strip "Mobile" only ─────────────────────────────────
        // "Mozilla/5.0 (Linux; Android 14; SM-G998B) ... Chrome/131.0.0.0 Mobile Safari/537.36"
        //                                          → ... Chrome/131.0.0.0 Safari/537.36
        val originalUA = webView.settings.userAgentString ?: ""
        val desktopUA  = originalUA
            .replace(" Mobile Safari/", " Safari/")
            .replace(" Mobile/", "/")
        webView.settings.userAgentString = desktopUA
        Logger.i(TAG, "UA: $desktopUA")

        webView.settings.apply {
            // ── JavaScript + storage ─────────────────────────────────────────
            javaScriptEnabled  = true
            domStorageEnabled  = true
            @Suppress("DEPRECATION") databaseEnabled = true

            // ── Viewport: reproduce Chrome's default "desktop site" handling ──
            // useWideViewPort=true       → if no mobile viewport meta tag is
            //                              present, use a wide virtual viewport
            //                              (same mechanism Chrome uses) so VS
            //                              Code's CSS sees desktop-class width.
            // loadWithOverviewMode=true  → initially zoom out to fit that wide
            //                              layout onto the physical screen,
            //                              exactly like Chrome's "desktop site
            //                              shrunk to fit" rendering.
            useWideViewPort      = true
            loadWithOverviewMode = true

            // ── Zoom: NATIVE pinch-to-zoom enabled ────────────────────────────
            // This is what actually makes the shrunk-to-fit desktop layout
            // usable — exactly mirroring Chrome's interaction model. The on-
            // screen +/- zoom buttons are hidden (displayZoomControls=false)
            // since pinch gesture alone is the natural touch interaction; the
            // buttons would just clutter the UI.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Disable Android's legacy text-autosizing layout algorithm.
            // TEXT_AUTOSIZING inflates font sizes heuristically to "improve
            // readability" on narrow columns — directly conflicting with VS
            // Code's own precisely-sized 11-13px UI text and is a classic
            // cause of embedded-WebView layouts looking visually different
            // from the same page in stock Chrome (which does not do this for
            // desktop-site-rendered pages). NORMAL disables that heuristic.
            @Suppress("DEPRECATION")
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

            // Do not let Android enforce a minimum font size — VS Code's UI
            // intentionally uses very small text (11-13px) as part of its
            // desktop-density layout; an enforced minimum would visually
            // distort proportions versus the Chrome reference rendering.
            minimumFontSize        = 1
            minimumLogicalFontSize = 1

            // Text zoom: 100% — system accessibility font-scale must not
            // additionally distort VS Code's layout on top of the pinch-zoom
            // the user controls directly.
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
