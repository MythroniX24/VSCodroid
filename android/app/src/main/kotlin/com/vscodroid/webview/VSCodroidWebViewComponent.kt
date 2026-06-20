package com.vscodroid.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import com.vscodroid.util.Logger
import kotlin.math.abs

/**
 * Custom [WebView] subclass that adds two pieces of desktop-class input handling
 * on top of [VSCodroidWebView]'s configuration:
 *
 * 1. **Long press → contextmenu**: a long press (≥550ms without movement) fires
 *    a synthetic `contextmenu` event at the touch coordinates, replicating
 *    desktop right-click. Haptic feedback confirms the action.
 *
 * 2. **Forced soft-keyboard show on editable tap**: see [checkAndShowKeyboard]
 *    for the detailed reasoning. Short version: Monaco (VS Code's editor
 *    component) focuses input via a visually hidden/near-zero-size `<textarea>`
 *    positioned at the text cursor — a well-known class of element for which
 *    Android's automatic "should I pop the IME for this focused element?"
 *    heuristic is unreliable inside an embedded WebView (unlike a full Chrome
 *    tab). We explicitly detect an editable focus after each tap and force
 *    the soft keyboard open as a safety net.
 *
 * ── Pinch-to-zoom ─────────────────────────────────────────────────────────────
 * Deliberately NOT handled here. [VSCodroidWebView.configure] enables the
 * WebView's own native pinch-to-zoom (`setSupportZoom`/`builtInZoomControls`),
 * which scales the entire rendered page — exactly matching how stock Chrome
 * renders this same VS Code server (proven by direct comparison: the same URL
 * in Chrome shows the correct, fully-proportioned desktop 3-pane layout shrunk
 * to fit the screen, with pinch-zoom available to zoom in).
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

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (isLongPressFired) {
                    // Long press just fired its context menu — suppress the
                    // tap-up so VS Code doesn't ALSO register a click there.
                    isLongPressFired = false
                    return true  // consume the event
                }
                if (!isMultiTouch) {
                    // Normal short tap (not the end of a pinch gesture) — let
                    // the tap reach the page normally first (super call below),
                    // then check whether it focused something editable and
                    // force the soft keyboard if so. See checkAndShowKeyboard().
                    checkAndShowKeyboard()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                isLongPressFired = false
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

    // -- Soft keyboard --

    /**
     * Forces the soft keyboard open if the tap that just landed focused an
     * editable element — explicitly, rather than relying solely on the
     * WebView's own automatic "focused input → show IME" behaviour.
     *
     * ── Why this is needed ───────────────────────────────────────────────────
     * VS Code's editor (Monaco) does not use a normal, fully-visible
     * `<textarea>` for keyboard input. It captures keystrokes through a
     * `<textarea class="inputarea">` that Monaco deliberately keeps visually
     * near-invisible (effectively zero-size / fully transparent, repositioned
     * to track the text cursor) so it never shows a visible text-input box
     * over the rendered code — correct behaviour on desktop with a physical
     * keyboard, where no "should I show a virtual keyboard" decision exists.
     *
     * ── Why a plain showSoftInput() call isn't enough ────────────────────────
     * Android's [InputMethodManager] decides whether/how to show the IME based
     * on the focused [View]'s current [android.view.inputmethod.InputConnection]
     * — for a WebView, that connection is established internally by the
     * Chromium engine's own `ImeAdapter`, tied to whichever DOM element the
     * PAGE currently considers focused. Simply calling `showSoftInput()` from
     * OUTSIDE does not guarantee the system has an up-to-date, correctly-bound
     * InputConnection for Monaco's specific (near-invisible) textarea — the
     * missing piece in the reported bug isn't "nobody asked for the keyboard",
     * it's that the system's InputConnection binding for this specific kind of
     * focused element can go stale or never gets (re-)established at all.
     *
     * The fix: explicitly call [InputMethodManager.restartInput] FIRST — this
     * forces Android to re-query [onCreateInputConnection] on the WebView right
     * now (which Chromium implements correctly once the DOM element genuinely
     * has focus), establishing a fresh, correctly-bound InputConnection — THEN
     * call [InputMethodManager.showSoftInput] with the stronger
     * [InputMethodManager.SHOW_FORCED] flag (rather than `SHOW_IMPLICIT`, which
     * silently no-ops under several common conditions `SHOW_FORCED` does not).
     *
     * A short, two-attempt retry (immediate + [KEYBOARD_RETRY_DELAY_MS] later)
     * covers the case where Monaco's own internal `.focus()` call on its
     * textarea happens asynchronously, one tick after the click/tap handler —
     * the first attempt may run before that focus call has actually landed.
     */
    private fun checkAndShowKeyboard() {
        postDelayed({ attemptShowKeyboard(retriesLeft = 1) }, KEYBOARD_CHECK_DELAY_MS)
    }

    private fun attemptShowKeyboard(retriesLeft: Int) {
        evaluateJavascript(ACTIVE_ELEMENT_IS_EDITABLE_JS) { result ->
            if (result == "true") {
                forceShowKeyboard()
            } else if (retriesLeft > 0) {
                // Monaco's focus() may land a tick later than our first check —
                // try once more before giving up.
                postDelayed({ attemptShowKeyboard(retriesLeft - 1) }, KEYBOARD_RETRY_DELAY_MS)
            }
        }
    }

    private fun forceShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm == null) {
            Logger.w(tag, "InputMethodManager unavailable")
            return
        }
        // Re-establish a fresh InputConnection binding for the currently
        // focused DOM element before asking the system to display the IME.
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        Logger.d(tag, "Forced soft keyboard show (restartInput + SHOW_FORCED)")
    }

    companion object {
        /** Minimum hold time (ms) before long-press fires. */
        private const val LONG_PRESS_TIMEOUT_MS = 550L

        /** Movement tolerance in dp before long-press is cancelled. */
        private const val MOVE_CANCEL_THRESHOLD_DP = 8f

        /**
         * Delay (ms) after ACTION_UP before checking document.activeElement.
         * Gives the tap's click/focus DOM events time to land before we query
         * which element ended up focused.
         */
        private const val KEYBOARD_CHECK_DELAY_MS = 120L

        /** Delay (ms) before the second (retry) activeElement check. */
        private const val KEYBOARD_RETRY_DELAY_MS = 200L

        /** Returns "true"/"false" (as a JS boolean) depending on whether the currently focused element accepts text input. */
        private const val ACTIVE_ELEMENT_IS_EDITABLE_JS = """
(function(){
    var el = document.activeElement;
    if (!el) return false;
    var tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (tag === 'textarea' || tag === 'input') return true;
    if (el.isContentEditable) return true;
    return false;
})();
"""
    }
}
