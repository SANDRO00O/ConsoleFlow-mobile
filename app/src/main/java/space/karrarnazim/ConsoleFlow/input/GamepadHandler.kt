package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Handles Gamepad and Joystick input for ConsoleFlow.
 * Supports analog sticks for scrolling, D-Pad for navigation, and buttons for actions.
 */
class GamepadHandler(
    private val activity: Activity,
    private val webViewContainer: FrameLayout,
    private val onButtonA: () -> Unit,
    private val onButtonB: () -> Unit,
    private val onButtonX: () -> Unit,
    private val onButtonY: () -> Unit,
    private val onMenuButton: () -> Unit,
    private val onRightStickMove: (Float, Float) -> Unit
) {

    companion object {
        private const val STICK_DEADZONE = 0.18f
        private const val LEFT_STICK_SCROLL_SPEED_PX_PER_SEC = 1800f
        private const val RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC = 1300f
    }

    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f

    private var analogLoopRunning = false
    private var lastFrameTimeMs = 0L
    private var lastEmittedRightX = Float.NaN
    private var lastEmittedRightY = Float.NaN

    private val analogUpdateRunnable = object : Runnable {
        override fun run() {
            val webView = webViewContainer.getChildAt(0) as? WebView
            if (webView == null) {
                analogLoopRunning = false
                lastFrameTimeMs = 0L
                return
            }

            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameTimeMs == 0L) 1f / 60f else ((now - lastFrameTimeMs).coerceAtLeast(1L) / 1000f)
            lastFrameTimeMs = now

            var active = false

            if (abs(leftStickX) > STICK_DEADZONE || abs(leftStickY) > STICK_DEADZONE) {
                val scrollX = (leftStickX * LEFT_STICK_SCROLL_SPEED_PX_PER_SEC * dt).roundToInt()
                val scrollY = (leftStickY * LEFT_STICK_SCROLL_SPEED_PX_PER_SEC * dt).roundToInt()
                if (scrollX != 0 || scrollY != 0) {
                    webView.scrollBy(scrollX, scrollY)
                }
                active = true
            }

            if (abs(rightStickX) > STICK_DEADZONE || abs(rightStickY) > STICK_DEADZONE) {
                val moveX = rightStickX * RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC * dt
                val moveY = rightStickY * RIGHT_STICK_CURSOR_SPEED_PX_PER_SEC * dt
                if (moveX != lastEmittedRightX || moveY != lastEmittedRightY) {
                    onRightStickMove(moveX, moveY)
                    lastEmittedRightX = moveX
                    lastEmittedRightY = moveY
                }
                active = true
            }

            if (active) {
                webViewContainer.postOnAnimation(this)
            } else {
                analogLoopRunning = false
                lastFrameTimeMs = 0L
                lastEmittedRightX = Float.NaN
                lastEmittedRightY = Float.NaN
            }
        }
    }

    /**
     * Handles generic motion events (analog sticks and triggers).
     * Returns true if the event was consumed, false otherwise.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!isJoystick) return false

        leftStickX = getCenteredAxis(event, MotionEvent.AXIS_X)
        leftStickY = getCenteredAxis(event, MotionEvent.AXIS_Y)
        rightStickX = getCenteredAxis(event, MotionEvent.AXIS_Z)
        rightStickY = getCenteredAxis(event, MotionEvent.AXIS_RZ)

        val hasAnalogInput =
            abs(leftStickX) > STICK_DEADZONE ||
            abs(leftStickY) > STICK_DEADZONE ||
            abs(rightStickX) > STICK_DEADZONE ||
            abs(rightStickY) > STICK_DEADZONE

        if (hasAnalogInput) {
            startAnalogLoop()
            return true
        }

        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        val leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER)

        if (leftTrigger > 0.5f) {
            handleLeftTrigger(webView)
            return true
        }

        if (rightTrigger > 0.5f) {
            handleRightTrigger(webView)
            return true
        }

        return false
    }

    /**
     * Handles gamepad button events (A, B, X, Y, Start, Select, etc.).
     */
    fun handleGamepadButtonEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                onButtonA()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                onButtonB()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                onButtonX()
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                onButtonY()
                true
            }
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                onMenuButton()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                handleLeftShoulder()
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                handleRightShoulder()
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                handleLeftThumbClick()
                true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                handleRightThumbClick()
                true
            }
            else -> false
        }
    }

    private fun startAnalogLoop() {
        if (analogLoopRunning) return
        analogLoopRunning = true
        lastFrameTimeMs = 0L
        webViewContainer.postOnAnimation(analogUpdateRunnable)
    }

    /**
     * Handles left trigger (L2) - typically for page up or zoom out.
     */
    private fun handleLeftTrigger(webView: WebView) {
        webView.scrollBy(0, -300)
    }

    /**
     * Handles right trigger (R2) - typically for page down or zoom in.
     */
    private fun handleRightTrigger(webView: WebView) {
        webView.scrollBy(0, 300)
    }

    /**
     * Handles left shoulder button (L1) - typically for previous tab or page.
     */
    private fun handleLeftShoulder() {
        // Can be mapped to previous tab or history back
    }

    /**
     * Handles right shoulder button (R1) - typically for next tab or page.
     */
    private fun handleRightShoulder() {
        // Can be mapped to next tab or history forward
    }

    /**
     * Handles left thumb click (L3) - typically for reset or special action.
     */
    private fun handleLeftThumbClick() {
        // Can be mapped to reset zoom or special action
    }

    /**
     * Handles right thumb click (R3) - typically for context menu or special action.
     */
    private fun handleRightThumbClick() {
        // Can be mapped to context menu or special action
    }

    /**
     * Gets the centered axis value, accounting for deadzone.
     */
    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val flat = range.flat
        val value = event.getAxisValue(axis)

        return when {
            abs(value) > flat -> value
            else -> 0f
        }
    }

    /**
     * Injects JavaScript to enable Gamepad API support in WebView and to expose
     * a reliable click helper for synthetic cursor clicks.
     */
    fun injectGamepadAPISupport(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
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
                            if (!el && stack.length > 0) {
                                el = stack[0];
                            }
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
                            try { innerDoc = el.contentDocument || (el.contentWindow && el.contentWindow.document); } catch (e) {}
                            if (innerDoc) {
                                var inner = deepElementFromPoint(innerDoc, x - rect.left, y - rect.top);
                                if (inner) return inner;
                            }
                        }
                    } catch (e) {}

                    return el;
                }

                function fireMouseSequence(el, x, y) {
                    var types = ['pointermove', 'mousemove', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                    for (var i = 0; i < types.length; i++) {
                        var type = types[i];
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
                                    buttons: 1
                                });
                            } else {
                                evt = new MouseEvent(type, {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window,
                                    clientX: x,
                                    clientY: y,
                                    buttons: 1
                                });
                            }
                            el.dispatchEvent(evt);
                        } catch (e) {
                            try {
                                var legacy = document.createEvent('MouseEvents');
                                legacy.initMouseEvent(type, true, true, window, 1, 0, 0, x, y, false, false, false, false, 0, null);
                                el.dispatchEvent(legacy);
                            } catch (e2) {}
                        }
                    }

                    try {
                        if (typeof el.click === 'function') {
                            el.click();
                        }
                    } catch (e) {}
                }

                function ensureClickHighlightLayer() {
                    var id = '__cf_click_highlight';
                    var layer = document.getElementById(id);
                    if (layer) return layer;

                    layer = document.createElement('div');
                    layer.id = id;
                    layer.style.position = 'fixed';
                    layer.style.left = '0';
                    layer.style.top = '0';
                    layer.style.width = '46px';
                    layer.style.height = '46px';
                    layer.style.marginLeft = '-23px';
                    layer.style.marginTop = '-23px';
                    layer.style.borderRadius = '999px';
                    layer.style.pointerEvents = 'none';
                    layer.style.zIndex = '2147483647';
                    layer.style.opacity = '0';
                    layer.style.transform = 'scale(0.4)';
                    layer.style.boxSizing = 'border-box';
                    layer.style.border = '3px solid rgba(255,255,255,0.95)';
                    layer.style.boxShadow = '0 0 0 2px rgba(0,0,0,0.95), inset 0 0 0 2px rgba(0,0,0,0.95)';
                    layer.style.background = 'radial-gradient(circle at center, rgba(255,255,255,0.9) 0 30%, rgba(0,0,0,0.65) 31% 60%, rgba(255,255,255,0.0) 61% 100%)';
                    layer.style.transition = 'transform 180ms ease-out, opacity 180ms ease-out';
                    (document.body || document.documentElement).appendChild(layer);
                    return layer;
                }

                if (!window.__cfPointerController) {
                    window.__cfPointerController = {
                        clickAt: function(x, y) {
                            var scale = 1;
                            try {
                                if (window.visualViewport && typeof window.visualViewport.scale === 'number' && window.visualViewport.scale > 0) {
                                    scale = window.visualViewport.scale;
                                } else if (window.devicePixelRatio && window.devicePixelRatio > 0) {
                                    scale = window.devicePixelRatio;
                                }
                            } catch (e) {}
                            x = x / scale;
                            y = y / scale;

                            var target = deepElementFromPoint(document, x, y) || document.activeElement || document.body;
                            if (!target) return false;

                            try {
                                if (typeof target.focus === 'function') {
                                    target.focus({ preventScroll: true });
                                }
                            } catch (e) {}

                            fireMouseSequence(target, x, y);
                            this.showClickHighlight(x, y);
                            return true;
                        },
                        showClickHighlight: function(x, y) {
                            var layer = ensureClickHighlightLayer();
                            layer.style.left = x + 'px';
                            layer.style.top = y + 'px';
                            layer.style.opacity = '1';
                            layer.style.transform = 'scale(1)';
                            clearTimeout(layer.__cfHideTimer);
                            layer.__cfHideTimer = setTimeout(function() {
                                layer.style.opacity = '0';
                                layer.style.transform = 'scale(1.25)';
                            }, 120);
                        }
                    };
                } else if (typeof window.__cfPointerController.showClickHighlight !== 'function') {
                    window.__cfPointerController.showClickHighlight = function(x, y) {
                        var layer = ensureClickHighlightLayer();
                        layer.style.left = x + 'px';
                        layer.style.top = y + 'px';
                        layer.style.opacity = '1';
                        layer.style.transform = 'scale(1)';
                        clearTimeout(layer.__cfHideTimer);
                        layer.__cfHideTimer = setTimeout(function() {
                            layer.style.opacity = '0';
                            layer.style.transform = 'scale(1.25)';
                        }, 120);
                    };
                }

                if (!navigator.getGamepads) {
                    console.warn('Gamepad API not supported');
                    return;
                }

                window.addEventListener('gamepadconnected', function(e) {
                    console.log('Gamepad connected:', e.gamepad.id);
                });

                window.addEventListener('gamepaddisconnected', function(e) {
                    console.log('Gamepad disconnected:', e.gamepad.id);
                });

                var gamepadPollInterval = setInterval(function() {
                    var gamepads = navigator.getGamepads();
                    for (var i = 0; i < gamepads.length; i++) {
                        if (gamepads[i]) {
                            console.log('Gamepad ' + i + ' is active');
                        }
                    }
                }, 100);
            })();
            """.trimIndent(),
            null
        )
    }
}
