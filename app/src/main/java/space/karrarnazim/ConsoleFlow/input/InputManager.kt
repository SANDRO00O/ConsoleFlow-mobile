package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unified controller system for ConsoleFlow.
 *
 * Handles:
 * - TV remote / D-pad
 * - Gamepad buttons and sticks
 * - Keyboard shortcuts
 * - Cursor movement and click flashes
 * - WebView click bridging via a tiny injected JS helper
 */
class InputManager(
    private val activity: Activity,
    private val rootView: ViewGroup,
    private val webViewContainer: FrameLayout,
    private val topBar: LinearLayout,
    private val bottomBar: LinearLayout,
    private val textUrl: EditText,
    private val joystickCursor: View,
    private val onNewTab: () -> Unit,
    private val onCloseTab: () -> Unit,
    private val onReload: () -> Unit,
    private val onFind: () -> Unit,
    private val onFocusUrlBar: () -> Unit,
    private val onToggleMenu: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onNavigateForward: () -> Unit,
    private val onToggleFullscreen: () -> Unit,
    private val onToggleDarkMode: () -> Unit,
    private val onCursorClickAt: (Float, Float) -> Boolean,
    private val onCursorClickHighlight: (Float, Float) -> Unit,
    private val onCursorClickFlash: () -> Unit
) {

    companion object {
        private const val STICK_DEADZONE = 0.18f
        private const val LEFT_STICK_SCROLL_SPEED_PX_PER_SEC = 1800f
        private const val RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC = 1300f
        private const val DPAD_STEP_DP = 46f
        private const val WEB_POINTER_HELPER_ID = "__cf_pointer_helper"
        private const val WEB_FOCUS_HELPER_ID = "__cf_focus_helper"
    }

    private val registeredWebViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    private var joystickCursorX = 0f
    private var joystickCursorY = 0f
    private var joystickCursorInitialized = false

    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f

    private var analogLoopRunning = false
    private var lastFrameTimeMs = 0L

    private val dpadStepPx: Float
        get() = (activity.resources.displayMetrics.density * DPAD_STEP_DP).coerceAtLeast(28f)

    private val analogUpdateRunnable = object : Runnable {
        override fun run() {
            val currentWebView = webViewContainer.getChildAt(0) as? WebView
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameTimeMs == 0L) 1f / 60f else ((now - lastFrameTimeMs).coerceAtLeast(1L) / 1000f)
            lastFrameTimeMs = now

            var active = false

            if (abs(leftStickX) > STICK_DEADZONE || abs(leftStickY) > STICK_DEADZONE) {
                if (currentWebView != null) {
                    val scrollX = (leftStickX * LEFT_STICK_SCROLL_SPEED_PX_PER_SEC * dt).roundToInt()
                    val scrollY = (leftStickY * LEFT_STICK_SCROLL_SPEED_PX_PER_SEC * dt).roundToInt()
                    if (scrollX != 0 || scrollY != 0) {
                        currentWebView.scrollBy(scrollX, scrollY)
                    }
                }
                active = true
            }

            if (abs(rightStickX) > STICK_DEADZONE || abs(rightStickY) > STICK_DEADZONE) {
                val moveX = rightStickX * RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC * dt
                val moveY = rightStickY * RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC * dt
                moveCursorBy(moveX, moveY)
                active = true
            }

            if (active) {
                rootView.postOnAnimation(this)
            } else {
                analogLoopRunning = false
                lastFrameTimeMs = 0L
            }
        }
    }

    /**
     * Handles all key events from remotes, gamepads, and keyboards.
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (handleKeyboardShortcut(keyCode, event)) return true

        if (isControllerNavigationSource(event)) {
            if (handleControllerKey(keyCode, event)) return true
        }

        if (handleSpecialKeys(keyCode)) return true

        return false
    }

    /**
     * Handles joystick / analog stick motion.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!isJoystick) return false

        leftStickX = centeredAxis(event, MotionEvent.AXIS_X)
        leftStickY = centeredAxis(event, MotionEvent.AXIS_Y)
        rightStickX = centeredAxis(event, MotionEvent.AXIS_Z)
        rightStickY = centeredAxis(event, MotionEvent.AXIS_RZ)

        val hasAnalogInput =
            abs(leftStickX) > STICK_DEADZONE ||
            abs(leftStickY) > STICK_DEADZONE ||
            abs(rightStickX) > STICK_DEADZONE ||
            abs(rightStickY) > STICK_DEADZONE

        if (hasAnalogInput) {
            startAnalogLoop()
            return true
        }

        val leftTrigger = centeredAxis(event, MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = centeredAxis(event, MotionEvent.AXIS_RTRIGGER)
        val currentWebView = webViewContainer.getChildAt(0) as? WebView

        if (currentWebView != null) {
            when {
                leftTrigger > 0.5f -> {
                    currentWebView.scrollBy(0, -320)
                    return true
                }
                rightTrigger > 0.5f -> {
                    currentWebView.scrollBy(0, 320)
                    return true
                }
            }
        }

        return hasAnalogInput
    }

    /**
     * Registers a WebView for pointer injection. Call this before loading the page when possible.
     */
    fun initializeWebView(webView: WebView) {
        if (!registeredWebViews.add(webView)) return

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        installWebFocusCSS(webView)
        installWebPointerBridge(webView, documentStart = true)
    }

    /**
     * Ensures the page-side pointer helper exists even for already loaded pages.
     */
    fun injectControllerScript(webView: WebView) {
        installWebFocusCSS(webView)
        installWebPointerBridge(webView, documentStart = false)
    }

    private fun handleKeyboardShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val isCtrlPressed = event.isCtrlPressed
        val isShiftPressed = event.isShiftPressed
        val isAltPressed = event.isAltPressed

        return when {
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_T -> { onNewTab(); true }
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_W -> { onCloseTab(); true }
            (isCtrlPressed && keyCode == KeyEvent.KEYCODE_R) || keyCode == KeyEvent.KEYCODE_F5 -> { onReload(); true }
            (isCtrlPressed && keyCode == KeyEvent.KEYCODE_F) || keyCode == KeyEvent.KEYCODE_F3 -> { onFind(); true }
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_L -> { onFocusUrlBar(); true }
            isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { onNavigateBack(); true }
            isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { onNavigateForward(); true }
            keyCode == KeyEvent.KEYCODE_F11 || (isCtrlPressed && isShiftPressed && keyCode == KeyEvent.KEYCODE_F) -> { onToggleFullscreen(); true }
            isCtrlPressed && isShiftPressed && keyCode == KeyEvent.KEYCODE_D -> { onToggleDarkMode(); true }
            isAltPressed && keyCode == KeyEvent.KEYCODE_M -> { onToggleMenu(); true }
            keyCode == KeyEvent.KEYCODE_TAB -> {
                moveFocusInWebView(isShiftPressed)
                true
            }
            else -> false
        }
    }

    private fun handleControllerKey(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveCursorBy(0f, -dpadStepPx)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveCursorBy(0f, dpadStepPx)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveCursorBy(-dpadStepPx, 0f)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveCursorBy(dpadStepPx, 0f)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) {
                    onCursorClickFlash()
                    performPointerClick()
                }
                true
            }
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B -> {
                onNavigateBack()
                true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                onToggleMenu()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                onToggleMenu()
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                onReload()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                onNavigateBack()
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                onNavigateForward()
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                onFocusUrlBar()
                true
            }
            else -> false
        }
    }

    private fun handleSpecialKeys(keyCode: Int): Boolean {
        val currentWebView = webViewContainer.getChildAt(0) as? WebView ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                currentWebView.evaluateJavascript("window.scrollTo(0, 0);", null)
                true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                currentWebView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                currentWebView.evaluateJavascript("window.scrollBy(0, -window.innerHeight);", null)
                true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                currentWebView.evaluateJavascript("window.scrollBy(0, window.innerHeight);", null)
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                currentWebView.evaluateJavascript(
                    "(function(){document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape'}));})();",
                    null
                )
                true
            }
            else -> false
        }
    }

    private fun startAnalogLoop() {
        if (analogLoopRunning) return
        analogLoopRunning = true
        lastFrameTimeMs = 0L
        rootView.postOnAnimation(analogUpdateRunnable)
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        if (rootView.width <= 0 || rootView.height <= 0) {
            rootView.post { moveCursorBy(dx, dy) }
            return
        }

        if (!joystickCursorInitialized) {
            joystickCursorX = rootView.width / 2f
            joystickCursorY = rootView.height / 2f
            joystickCursorInitialized = true
        }

        if (joystickCursor.width <= 0 || joystickCursor.height <= 0) {
            joystickCursor.post { moveCursorBy(dx, dy) }
            return
        }

        val halfW = joystickCursor.width / 2f
        val halfH = joystickCursor.height / 2f
        val minX = halfW
        val minY = halfH
        val maxX = (rootView.width - halfW).coerceAtLeast(halfW)
        val maxY = (rootView.height - halfH).coerceAtLeast(halfH)

        joystickCursorX = (joystickCursorX + dx).coerceIn(minX, maxX)
        joystickCursorY = (joystickCursorY + dy).coerceIn(minY, maxY)

        if (joystickCursor.visibility != View.VISIBLE) {
            joystickCursor.visibility = View.VISIBLE
        }
        joystickCursor.translationX = joystickCursorX - halfW
        joystickCursor.translationY = joystickCursorY - halfH
        joystickCursor.bringToFront()
    }

    private fun performPointerClick(): Boolean {
        if (!joystickCursorInitialized) {
            if (rootView.width <= 0 || rootView.height <= 0) return false
            joystickCursorX = rootView.width / 2f
            joystickCursorY = rootView.height / 2f
            joystickCursorInitialized = true
        }

        val x = joystickCursorX
        val y = joystickCursorY
        return onCursorClickAt(x, y)
    }

    private fun moveFocusInWebView(shift: Boolean) {
        val currentWebView = webViewContainer.getChildAt(0) as? WebView ?: return
        currentWebView.evaluateJavascript(
            """
            (function() {
                var focusableElements = Array.from(document.querySelectorAll(
                    'a, button, input, textarea, select, [tabindex]:not([tabindex="-1"])'
                )).filter(function(el) {
                    try {
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
                    } catch (e) {
                        return true;
                    }
                });
                if (focusableElements.length === 0) return;
                var focused = document.activeElement;
                var index = focusableElements.indexOf(focused);
                if ($shift) {
                    index = index <= 0 ? focusableElements.length - 1 : index - 1;
                } else {
                    index = index >= focusableElements.length - 1 ? 0 : index + 1;
                }
                var target = focusableElements[index];
                if (target && typeof target.focus === 'function') {
                    target.focus({ preventScroll: true });
                    if (typeof target.scrollIntoView === 'function') {
                        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun centeredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val flat = range.flat
        val value = event.getAxisValue(axis)
        return if (abs(value) > flat) value else 0f
    }

    private fun isControllerNavigationSource(event: KeyEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_DPAD) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
    }

    private fun installWebFocusCSS(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    if (document.getElementById('$WEB_FOCUS_HELPER_ID')) return;
                    var style = document.createElement('style');
                    style.id = '$WEB_FOCUS_HELPER_ID';
                    style.textContent = `
                        *:focus {
                            outline: 2px solid #7DD3FC !important;
                            outline-offset: 2px !important;
                        }
                        a, button, input, textarea, select, [tabindex]:not([tabindex="-1"]) {
                            scroll-margin: 20px;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun installWebPointerBridge(webView: WebView, documentStart: Boolean) {
        val script = controllerBridgeScript()
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart && supported) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }
        }
        webView.evaluateJavascript(script, null)
    }

    private fun controllerBridgeScript(): String = """
        (function() {
            if (window.__cfPointerController) return;

            function resolveScale() {
                try {
                    if (window.visualViewport && typeof window.visualViewport.scale === 'number' && window.visualViewport.scale > 0) {
                        return window.visualViewport.scale;
                    }
                } catch (e) {}
                return 1;
            }

            function deepElementFromPoint(doc, x, y) {
                if (!doc) return null;
                var el = null;
                try {
                    if (doc.elementsFromPoint) {
                        var stack = doc.elementsFromPoint(x, y) || [];
                        for (var i = 0; i < stack.length; i++) {
                            if (stack[i] && stack[i].tagName !== 'HTML' && stack[i].tagName !== 'BODY') {
                                el = stack[i];
                                break;
                            }
                        }
                        if (!el && stack.length > 0) el = stack[0];
                    } else {
                        el = doc.elementFromPoint(x, y);
                    }
                } catch (e) {
                    try { el = doc.elementFromPoint(x, y); } catch (e2) {}
                }
                if (!el) return null;

                try {
                    var rect = el.getBoundingClientRect && el.getBoundingClientRect();
                    if (el.tagName === 'IFRAME' && rect) {
                        var innerDoc = null;
                        try { innerDoc = el.contentDocument || (el.contentWindow && el.contentWindow.document); } catch (e3) {}
                        if (innerDoc) {
                            var inner = deepElementFromPoint(innerDoc, x - rect.left, y - rect.top);
                            if (inner) return inner;
                        }
                    }
                } catch (e4) {}
                return el;
            }

            function dispatchSequence(el, x, y) {
                var types = ['pointermove', 'mousemove', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (var i = 0; i < types.length; i++) {
                    var type = types[i];
                    var isDown = type === 'pointerdown' || type === 'mousedown';
                    var isUp = type === 'pointerup' || type === 'mouseup';
                    try {
                        var evt;
                        if (type.indexOf('pointer') === 0 && typeof PointerEvent !== 'undefined') {
                            evt = new PointerEvent(type, {
                                bubbles: true,
                                cancelable: true,
                                view: window,
                                clientX: x,
                                clientY: y,
                                pointerId: 1,
                                pointerType: 'mouse',
                                isPrimary: true,
                                buttons: isDown ? 1 : 0
                            });
                        } else {
                            evt = new MouseEvent(type, {
                                bubbles: true,
                                cancelable: true,
                                view: window,
                                clientX: x,
                                clientY: y,
                                buttons: isDown ? 1 : 0
                            });
                        }
                        el.dispatchEvent(evt);
                    } catch (e) {
                        try {
                            var legacy = document.createEvent('MouseEvents');
                            legacy.initMouseEvent(type, true, true, window, 1, 0, 0, x, y, false, false, false, false, isUp ? 0 : 1, null);
                            el.dispatchEvent(legacy);
                        } catch (e2) {}
                    }
                }

                try {
                    if (typeof el.click === 'function') el.click();
                } catch (e3) {}
            }

            function ensureHighlightLayer() {
                var id = '$WEB_POINTER_HELPER_ID';
                var layer = document.getElementById(id);
                if (layer) return layer;

                layer = document.createElement('div');
                layer.id = id;
                layer.style.position = 'fixed';
                layer.style.left = '0';
                layer.style.top = '0';
                layer.style.width = '42px';
                layer.style.height = '42px';
                layer.style.marginLeft = '-21px';
                layer.style.marginTop = '-21px';
                layer.style.borderRadius = '999px';
                layer.style.pointerEvents = 'none';
                layer.style.zIndex = '2147483647';
                layer.style.opacity = '0';
                layer.style.transform = 'scale(0.35)';
                layer.style.boxSizing = 'border-box';
                layer.style.border = '3px solid rgba(255,255,255,0.96)';
                layer.style.boxShadow = '0 0 0 2px rgba(0,0,0,0.96), inset 0 0 0 2px rgba(0,0,0,0.96)';
                layer.style.background = 'radial-gradient(circle at center, rgba(255,255,255,0.88) 0 30%, rgba(0,0,0,0.72) 31% 62%, rgba(255,255,255,0.0) 63% 100%)';
                layer.style.transition = 'transform 120ms ease-out, opacity 120ms ease-out';
                (document.body || document.documentElement).appendChild(layer);
                return layer;
            }

            window.__cfPointerController = {
                clickAt: function(x, y) {
                    x = x / resolveScale();
                    y = y / resolveScale();
                    var target = deepElementFromPoint(document, x, y) || document.activeElement || document.body;
                    if (!target) return false;
                    try {
                        if (typeof target.focus === 'function') {
                            target.focus({ preventScroll: true });
                        }
                    } catch (e) {}
                    dispatchSequence(target, x, y);
                    this.showClickHighlight(x, y);
                    return true;
                },
                showClickHighlight: function(x, y) {
                    var layer = ensureHighlightLayer();
                    layer.style.left = x + 'px';
                    layer.style.top = y + 'px';
                    layer.style.opacity = '1';
                    layer.style.transform = 'scale(1)';
                    clearTimeout(layer.__cfHideTimer);
                    layer.__cfHideTimer = setTimeout(function() {
                        layer.style.opacity = '0';
                        layer.style.transform = 'scale(1.18)';
                    }, 110);
                }
            };
        })();
    """.trimIndent()
}
