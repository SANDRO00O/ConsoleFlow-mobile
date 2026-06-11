package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Central input manager that coordinates TV Remote, Gamepad, Mouse, and Keyboard input.
 * Provides a unified interface for handling all types of input devices.
 */
class InputManager(
    private val activity: Activity,
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

    private val tvRemoteHandler = TVRemoteHandler(
        activity = activity,
        webViewContainer = webViewContainer,
        topBar = topBar,
        bottomBar = bottomBar,
        textUrl = textUrl,
        onNavigateBack = onNavigateBack,
        onNavigateForward = onNavigateForward,
        onReload = onReload,
        onToggleMenu = onToggleMenu
    )

    private var joystickCursorX = 0f
    private var joystickCursorY = 0f
    private var joystickCursorInitialized = false

    private val gamepadHandler = GamepadHandler(
        activity = activity,
        webViewContainer = webViewContainer,
        onButtonA = { handleGamepadButtonA() },
        onButtonB = { handleGamepadButtonB() },
        onButtonX = { handleGamepadButtonX() },
        onButtonY = { handleGamepadButtonY() },
        onMenuButton = onToggleMenu,
        onRightStickMove = { x, y -> moveJoystickCursor(x, y) }
    )

    private val mouseKeyboardHandler = MouseKeyboardHandler(
        activity = activity,
        webViewContainer = webViewContainer,
        topBar = topBar,
        textUrl = textUrl,
        onNewTab = onNewTab,
        onCloseTab = onCloseTab,
        onReload = onReload,
        onFind = onFind,
        onFocusUrlBar = onFocusUrlBar,
        onToggleMenu = onToggleMenu,
        onNavigateBack = onNavigateBack,
        onNavigateForward = onNavigateForward,
        onToggleFullscreen = onToggleFullscreen,
        onToggleDarkMode = onToggleDarkMode
    )

    /**
     * Handles all key events from various input devices.
     * Prioritizes input in this order: Keyboard shortcuts > Gamepad buttons > TV Remote > Special keys
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (mouseKeyboardHandler.handleKeyboardShortcut(keyCode, event)) {
            return true
        }

        if (gamepadHandler.handleGamepadButtonEvent(keyCode, event)) {
            return true
        }

        if (tvRemoteHandler.handleKeyEvent(keyCode, event)) {
            return true
        }

        if (mouseKeyboardHandler.handleSpecialKeys(keyCode)) {
            return true
        }

        return false
    }

    /**
     * Handles all motion events from gamepads and joysticks.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        return gamepadHandler.handleMotionEvent(event)
    }

    /**
     * Initializes all input handlers for a WebView.
     */
    fun initializeWebView(webView: WebView) {
        tvRemoteHandler.enableSpatialNavigation(webView)
        gamepadHandler.injectGamepadAPISupport(webView)
        mouseKeyboardHandler.injectKeyboardAccessibilityCSS(webView)
        mouseKeyboardHandler.injectContextMenuSupport(webView)
        mouseKeyboardHandler.enableSmartKeyboardHandling(webView)
    }

    /**
     * Handles Gamepad Button A (typically "Select" or "Click").
     */
    private fun handleGamepadButtonA() {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return

        if (!joystickCursorInitialized) {
            val width = webViewContainer.width.takeIf { it > 0 } ?: return
            val height = webViewContainer.height.takeIf { it > 0 } ?: return
            joystickCursorX = width / 2f
            joystickCursorY = height / 2f
            joystickCursorInitialized = true
        }

        val x = joystickCursorX.roundToInt()
        val y = joystickCursorY.roundToInt()

        onCursorClickFlash()
        if (onCursorClickAt(x.toFloat(), y.toFloat())) {
            return
        }

        webView.evaluateJavascript(
            """
            (function() {
                var x = $x;
                var y = $y;

                if (window.__cfPointerController && typeof window.__cfPointerController.clickAt === 'function') {
                    if (window.__cfPointerController.clickAt(x, y)) {
                        return;
                    }
                }

                var target = document.elementFromPoint(x, y) || document.activeElement;
                if (!target) return;

                try {
                    if (typeof target.focus === 'function') {
                        target.focus({ preventScroll: true });
                    }
                } catch (e) {
                    try { if (typeof target.focus === 'function') target.focus(); } catch (e2) {}
                }

                ['pointermove', 'mousemove', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
                    try {
                        var evt = (type.indexOf('pointer') === 0 && typeof PointerEvent !== 'undefined')
                            ? new PointerEvent(type, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, pointerId: 1, pointerType: 'mouse', isPrimary: true, buttons: 1 })
                            : new MouseEvent(type, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, buttons: 1 });
                        target.dispatchEvent(evt);
                    } catch (e) {}
                });

                if (typeof target.click === 'function') {
                    try { target.click(); } catch (e) {}
                }
            })();
            """.trimIndent(),
            null
        )
        onCursorClickHighlight(x.toFloat(), y.toFloat())
    }

    /**
     * Handles Gamepad Button B (typically "Back").
     */
    private fun handleGamepadButtonB() {
        onNavigateBack()
    }

    /**
     * Handles Gamepad Button X (typically "Menu" or "Options").
     */
    private fun handleGamepadButtonX() {
        onToggleMenu()
    }

    /**
     * Handles Gamepad Button Y (typically "Special Action").
     */
    private fun handleGamepadButtonY() {
        onReload()
    }

    private fun moveJoystickCursor(dx: Float, dy: Float) {
        val width = webViewContainer.width.takeIf { it > 0 } ?: return
        val height = webViewContainer.height.takeIf { it > 0 } ?: return

        if (!joystickCursorInitialized) {
            joystickCursorX = width / 2f
            joystickCursorY = height / 2f
            joystickCursorInitialized = true
        }

        val maxX = (width - joystickCursor.width).coerceAtLeast(0).toFloat()
        val maxY = (height - joystickCursor.height).coerceAtLeast(0).toFloat()

        joystickCursorX = (joystickCursorX + dx).coerceIn(0f, maxX)
        joystickCursorY = (joystickCursorY + dy).coerceIn(0f, maxY)

        if (joystickCursor.visibility != View.VISIBLE) {
            joystickCursor.visibility = View.VISIBLE
        }
        joystickCursor.translationX = webViewContainer.x + joystickCursorX - joystickCursor.width / 2f
        joystickCursor.translationY = webViewContainer.y + joystickCursorY - joystickCursor.height / 2f
    }

    /**
     * Checks if a device is a gamepad/joystick based on its source.
     */
    fun isGamepadDevice(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Checks if a device is a keyboard.
     */
    fun isKeyboardDevice(source: Int): Boolean {
        return (source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    }

    /**
     * Gets a human-readable name for the input device.
     */
    fun getInputDeviceName(keyCode: Int): String {
        return when {
            keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE -> "Gamepad"
            keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad"
            keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z -> "Keyboard"
            else -> "Unknown"
        }
    }
}
