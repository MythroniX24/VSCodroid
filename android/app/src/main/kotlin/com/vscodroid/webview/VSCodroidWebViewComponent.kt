package com.vscodroid.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebView
import com.vscodroid.util.Logger
import kotlin.math.abs
import kotlin.math.ln

/**
 * Custom [WebView] subclass that adds desktop-class input handling:
 *
 * 1. **Long press → contextmenu**: A long press (≥550ms without movement) fires a
 *    synthetic `contextmenu` event at the touch coordinates, replicating desktop
 *    right-click. Haptic feedback confirms the action.
 *
 * 2. **Pinch-to-zoom**: A [ScaleGestureDetector] translates two-finger pinch gestures
 *    into VS Code's `editor.action.zoomIn/Out` commands via the WebView bridge.
 *    The native WebView zoom is kept *disabled* so VS Code's own font-size zoom
 *    applies (avoids double-zoom with native scaling artifacts).
 *
 * 3. **Touch coordinate tracking**: Coordinates of every touch DOWN are recorded
 *    for use by the long-press contextmenu injection.
 *
 * Used in [activity_main.xml] as `com.vscodroid.webview.VSCodroidWebViewComponent`.
 * All [VSCodroidWebView.configure] settings are applied externally at setup time.
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
    private var isTwoFingerGesture = false

    // -- Pinch zoom state --
    private var cumulativeScale = 1.0f
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())

    // Threshold: if touch moves more than this (dp) during a down event, cancel long press
    private val moveCancelThresholdPx: Float by lazy {
        MOVE_CANCEL_THRESHOLD_DP * resources.displayMetrics.density
    }

    // -- Long press runnable --
    private val longPressRunnable = Runnable {
        if (!isTwoFingerGesture) {
            isLongPressFired = true
            fireContextMenuAt(longPressX, longPressY)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Logger.d(tag, "Long press → contextmenu at (${longPressX}, ${longPressY})")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let ScaleGestureDetector inspect ALL events for pinch detection
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressX = event.x
                longPressY = event.y
                isLongPressFired = false
                isTwoFingerGesture = false
                // Schedule long press — cancelled on move or lift
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two fingers detected — cancel pending long press
                isTwoFingerGesture = true
                longPressHandler.removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTwoFingerGesture && event.pointerCount == 1) {
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
        // Adjust for WebView scroll position
        val scrolledX = rawX + scrollX
        val scrolledY = rawY + scrollY
        evaluateJavascript(
            "window.__vscodroid && window.__vscodroid.fireContextMenu && " +
                "window.__vscodroid.fireContextMenu($scrolledX, $scrolledY);",
            null
        )
    }

    // -- Pinch zoom --

    /**
     * ScaleGestureDetector listener that translates two-finger pinch into
     * VS Code editor zoom commands.
     *
     * Strategy: Accumulate the natural log of the scale factor. When the
     * accumulated value exceeds ±[ZOOM_STEP_THRESHOLD], fire a zoom command
     * and reset the accumulator. This produces a stepped zoom that matches
     * VS Code's font-size zoom (each step = ±1 font size).
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cumulativeScale = 1.0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cumulativeScale *= detector.scaleFactor

            // Use log scale: each factor-of-1.1 triggers a zoom step
            val logAccum = ln(cumulativeScale.toDouble())

            when {
                logAccum > ZOOM_STEP_THRESHOLD -> {
                    fireVSCodeZoomIn()
                    cumulativeScale = 1.0f
                }
                logAccum < -ZOOM_STEP_THRESHOLD -> {
                    fireVSCodeZoomOut()
                    cumulativeScale = 1.0f
                }
            }
            return true
        }
    }

    private fun fireVSCodeZoomIn() {
        evaluateJavascript(
            """(function(){
                try {
                    var cs = window.require('vs/platform/commands/common/commands');
                    if (cs && cs.CommandsRegistry) {
                        var svc = window.require('vs/platform/instantiation/common/instantiation').IInstantiationService;
                    }
                } catch(e) {}
                // Fallback: dispatch Ctrl+= keyboard event
                var init = {bubbles:true,cancelable:true,key:'+',code:'Equal',keyCode:187,ctrlKey:true,composed:true};
                document.activeElement.dispatchEvent(new KeyboardEvent('keydown',init));
                document.activeElement.dispatchEvent(new KeyboardEvent('keyup',init));
            })();""",
            null
        )
        Logger.d(tag, "Zoom in via pinch")
    }

    private fun fireVSCodeZoomOut() {
        evaluateJavascript(
            """(function(){
                var init = {bubbles:true,cancelable:true,key:'-',code:'Minus',keyCode:189,ctrlKey:true,composed:true};
                var target = document.activeElement || document.body;
                target.dispatchEvent(new KeyboardEvent('keydown',init));
                target.dispatchEvent(new KeyboardEvent('keyup',init));
            })();""",
            null
        )
        Logger.d(tag, "Zoom out via pinch")
    }

    companion object {
        /** Minimum hold time (ms) before long-press fires. */
        private const val LONG_PRESS_TIMEOUT_MS = 550L

        /** Movement tolerance in dp before long-press is cancelled. */
        private const val MOVE_CANCEL_THRESHOLD_DP = 8f

        /** Log-scale accumulator threshold before a zoom step fires (~10% scale change). */
        private const val ZOOM_STEP_THRESHOLD = 0.095
    }
}
