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
     * over the rendered code. This is standard, correct behaviour for Monaco
     * on a desktop browser with a physical keyboard, where no "should I show
     * a virtual keyboard" decision is ever needed.
     *
     * On Android, *something* has to decide whether to pop the soft keyboard
     * when a `<textarea>` gains focus, and the heuristics WebView/Chromium use
     * for that decision are known to be unreliable specifically for inputs
     * that are near-zero-size or visually hidden — exactly Monaco's case. In
     * a full Chrome tab the IME can still appear correctly because Chrome's
     * own focus-handling stack differs slightly from an embedded WebView's;
     * inside our embedded WebView it can silently fail to show the keyboard
     * at all, even though focus genuinely moved to the editable element. This
     * matches the reported symptom precisely: tapping into the editor doesn't
     * bring up the keyboard.
     *
     * ── The fix ──────────────────────────────────────────────────────────────
     * After every short tap, once the tap has had a moment to land and update
     * `document.activeElement` (via [super.onTouchEvent] dispatching the real
     * touch/click first), we query whether the now-focused element is
     * text-editable (`<textarea>`, `<input>`, or `contenteditable`). If so, we
     * explicitly call [InputMethodManager.showSoftInput] ourselves — bypassing
     * whatever unreliable automatic decision the WebView would otherwise make.
     *
     * Deliberately scoped to only fire when an editable element is actually
     * focused (not unconditionally on every tap), so tapping non-text UI like
     * the Explorer tree, Activity Bar, or Status Bar never spuriously pops the
     * keyboard.
     */
    private fun checkAndShowKeyboard() {
        postDelayed({
            evaluateJavascript(ACTIVE_ELEMENT_IS_EDITABLE_JS) { result ->
                if (result == "true") {
                    requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? InputMethodManager
                    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    Logger.d(tag, "Forced soft keyboard show (editable element focused)")
                }
            }
        }, KEYBOARD_CHECK_DELAY_MS)
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
