package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

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
        private const val STICK_DEADZONE = 0.3f
        private const val SCROLL_SPEED = 50
    }

    private var lastScrollTime = 0L
    private val scrollDebounceMs = 50L
    private var joystickCursorX = 0f
    private var joystickCursorY = 0f

    /**
     * Handles generic motion events (analog sticks and triggers).
     * Returns true if the event was consumed, false otherwise.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!isJoystick) return false

        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false

        // Get analog stick positions
        val leftStickX = getCenteredAxis(event, MotionEvent.AXIS_X)
        val leftStickY = getCenteredAxis(event, MotionEvent.AXIS_Y)
        val rightStickX = getCenteredAxis(event, MotionEvent.AXIS_Z)
        val rightStickY = getCenteredAxis(event, MotionEvent.AXIS_RZ)

        // Handle left stick (scroll)
        if (abs(leftStickX) > STICK_DEADZONE || abs(leftStickY) > STICK_DEADZONE) {
            handleLeftStickScroll(webView, leftStickX, leftStickY)
            return true
        }

        // Handle right stick (mouse-style cursor)
        if (abs(rightStickX) > STICK_DEADZONE || abs(rightStickY) > STICK_DEADZONE) {
            onRightStickMove(rightStickX, rightStickY)
            return true
        }

        // Handle triggers (L2/R2)
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

    /**
     * Handles left stick scroll (vertical and horizontal).
     */
    private fun handleLeftStickScroll(webView: WebView, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < scrollDebounceMs) return
        lastScrollTime = now

        val scrollX = (x * SCROLL_SPEED).toInt()
        val scrollY = (y * SCROLL_SPEED).toInt()

        webView.evaluateJavascript(
            """
            (function() {
                window.scrollBy($scrollX, $scrollY);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Handles left trigger (L2) - typically for page up or zoom out.
     */
    private fun handleLeftTrigger(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                window.scrollBy(0, -300);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Handles right trigger (R2) - typically for page down or zoom in.
     */
    private fun handleRightTrigger(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                window.scrollBy(0, 300);
            })();
            """.trimIndent(),
            null
        )
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
     * Moves the on-screen joystick cursor.
     */
    private fun moveJoystickCursor(dx: Float, dy: Float) {
        val containerWidth = webViewContainer.width.takeIf { it > 0 } ?: return
        val containerHeight = webViewContainer.height.takeIf { it > 0 } ?: return

        if (joystickCursorX == 0f && joystickCursorY == 0f) {
            joystickCursorX = containerWidth / 2f
            joystickCursorY = containerHeight / 2f
        }

        val speed = 18f
        val maxX = (containerWidth - 28).coerceAtLeast(0).toFloat()
        val maxY = (containerHeight - 28).coerceAtLeast(0).toFloat()

        joystickCursorX = (joystickCursorX + (dx * speed)).coerceIn(0f, maxX)
        joystickCursorY = (joystickCursorY + (dy * speed)).coerceIn(0f, maxY)

        activity.runOnUiThread {
            if (joystickCursor.visibility != android.view.View.VISIBLE) {
                joystickCursor.visibility = android.view.View.VISIBLE
            }
            joystickCursor.translationX = joystickCursorX - 14f
            joystickCursor.translationY = joystickCursorY - 14f
        }
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
     * Injects JavaScript to enable Gamepad API support in WebView.
     */
    fun injectGamepadAPISupport(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                // Ensure Gamepad API is available
                if (!navigator.getGamepads) {
                    console.warn('Gamepad API not supported');
                    return;
                }

                // Gamepad event listeners
                window.addEventListener('gamepadconnected', function(e) {
                    console.log('Gamepad connected:', e.gamepad.id);
                });

                window.addEventListener('gamepaddisconnected', function(e) {
                    console.log('Gamepad disconnected:', e.gamepad.id);
                });

                // Polling for gamepad input (fallback)
                var gamepadPollInterval = setInterval(function() {
                    var gamepads = navigator.getGamepads();
                    for (var i = 0; i < gamepads.length; i++) {
                        if (gamepads[i]) {
                            // Gamepad is connected, can be used by web apps
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
