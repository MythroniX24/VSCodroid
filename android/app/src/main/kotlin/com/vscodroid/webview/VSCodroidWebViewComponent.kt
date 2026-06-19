package com.vscodroid.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.webkit.WebView
import com.vscodroid.util.Logger
import kotlin.math.abs

/**
 * Custom [WebView] subclass that adds one piece of desktop-class input handling
 * on top of [VSCodroidWebView]'s configuration: **long press → contextmenu**.
 *
 * A long press (≥550ms without movement) fires a synthetic `contextmenu` event
 * at the touch coordinates, replicating desktop right-click. Haptic feedback
 * confirms the action.
 *
 * ── Pinch-to-zoom ─────────────────────────────────────────────────────────────
 * Deliberately NOT handled here. [VSCodroidWebView.configure] enables the
 * WebView's own native pinch-to-zoom (`setSupportZoom`/`builtInZoomControls`),
 * which scales the entire rendered page — exactly matching how stock Chrome
 * renders this same VS Code server (proven by direct comparison: the same URL
 * in Chrome shows the correct, fully-proportioned desktop 3-pane layout shrunk
 * to fit the screen, with pinch-zoom available to zoom in).
 *
 * An earlier version of this class intercepted two-finger gestures with a
 * [android.view.ScaleGestureDetector] and translated them into a synthetic
 * Ctrl+=/Ctrl- keystroke aimed at changing VS Code's *editor font size* only.
 * That fought against native zoom in two ways: it consumed the pinch gesture
 * before the WebView's own zoom handling could see it, and even when it fired,
 * it only resized editor text — leaving the activity bar, explorer, tabs, and
 * status bar permanently tiny with no way to zoom into them. Removing it and
 * relying on native zoom instead is what makes pinch-zoom actually scale the
 * whole UI, matching the Chrome reference rendering.
 *
 * Used in [activity_main.xml] as `com.vscodroid.webview.VSCodroidWebViewComponent`.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class VSCodroidWebViewComponent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val tag = "VSCWebViewComponent"

    // -- Long press state --
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressX = 0f
    private var longPressY = 0f
    private var isLongPressFired = false
    private var isMultiTouch = false

    // Threshold: if touch moves more than this (dp) during a down event, cancel long press
    private val moveCancelThresholdPx: Float by lazy {
        MOVE_CANCEL_THRESHOLD_DP * resources.displayMetrics.density
    }

    private val longPressRunnable = Runnable {
        if (!isMultiTouch) {
            isLongPressFired = true
            fireContextMenuAt(longPressX, longPressY)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Logger.d(tag, "Long press → contextmenu at (${longPressX}, ${longPressY})")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressX = event.x
                longPressY = event.y
                isLongPressFired = false
                isMultiTouch = false
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landed — this is a pinch/multi-touch gesture,
                // not a long-press-for-context-menu. Cancel the pending long
                // press and let the event fall through to super.onTouchEvent()
                // UNTOUCHED so the WebView's own native pinch-zoom handling
                // (enabled in VSCodroidWebView.configure) receives it normally.
                isMultiTouch = true
                longPressHandler.removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isMultiTouch && event.pointerCount == 1) {
                    val dx = abs(event.x - longPressX)
                    val dy = abs(event.y - longPressY)
                    if (dx > moveCancelThresholdPx || dy > moveCancelThresholdPx) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                // If a long press just fired, suppress the tap-up so VS Code
                // doesn't also register a click on the context menu location.
                if (isLongPressFired) {
                    isLongPressFired = false
                    return true  // consume the event
                }
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        longPressHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    // -- Context menu injection --

    /**
     * Fires a synthetic `contextmenu` event inside VS Code at device pixel coordinates
     * ([rawX], [rawY]) relative to the WebView.
     *
     * The JS bridge function [DesktopModeJS.CONTEXT_MENU_BRIDGE] must have been
     * injected before this is called (done in [MainActivity.injectBridgeToken]).
     */
    fun fireContextMenuAt(rawX: Float, rawY: Float) {
        val scrolledX = rawX + scrollX
        val scrolledY = rawY + scrollY
        evaluateJavascript(
            "window.__vscodroid && window.__vscodroid.fireContextMenu && " +
                "window.__vscodroid.fireContextMenu($scrolledX, $scrolledY);",
            null
        )
    }

    companion object {
        /** Minimum hold time (ms) before long-press fires. */
        private const val LONG_PRESS_TIMEOUT_MS = 550L

        /** Movement tolerance in dp before long-press is cancelled. */
        private const val MOVE_CANCEL_THRESHOLD_DP = 8f
    }
}
