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
                onRightStickMove(moveX, moveY)
                active = true
            }

            if (active) {
                webViewContainer.postOnAnimation(this)
            } else {
                analogLoopRunning = false
                lastFrameTimeMs = 0L
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
                if (!window.__cfPointerController) {
                    window.__cfPointerController = {
                        clickAt: function(x, y) {
                            var elements = [];
                            if (document.elementsFromPoint) {
                                elements = document.elementsFromPoint(x, y) || [];
                            } else {
                                var fallback = document.elementFromPoint(x, y);
                                if (fallback) elements = [fallback];
                            }

                            if (elements.length === 0) {
                                var fallbackTarget = document.elementFromPoint(x, y) || document.activeElement;
                                if (fallbackTarget) elements = [fallbackTarget];
                            }

                            for (var i = 0; i < elements.length; i++) {
                                var el = elements[i];
                                if (!el) continue;

                                try {
                                    if (typeof el.focus === 'function') {
                                        el.focus({ preventScroll: true });
                                    } else if (typeof el.focus === 'function') {
                                        el.focus();
                                    }
                                } catch (e) {}

                                try {
                                    ['pointermove', 'mousemove', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
                                        var evt;
                                        try {
                                            evt = new MouseEvent(type, {
                                                bubbles: true,
                                                cancelable: true,
                                                view: window,
                                                clientX: x,
                                                clientY: y,
                                                buttons: 1
                                            });
                                        } catch (e) {
                                            evt = document.createEvent('MouseEvents');
                                            evt.initMouseEvent(type, true, true, window, 1, 0, 0, x, y, false, false, false, false, 0, null);
                                        }
                                        el.dispatchEvent(evt);
                                    });
                                } catch (e) {}

                                try {
                                    if (typeof el.click === 'function') {
                                        el.click();
                                    }
                                } catch (e) {}

                                if (el !== document.body && el !== document.documentElement) {
                                    return true;
                                }
                            }

                            return false;
                        }
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
