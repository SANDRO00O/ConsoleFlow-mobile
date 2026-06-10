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
    private val onToggleDarkMode: () -> Unit
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
        // First, try keyboard shortcuts (highest priority)
        if (mouseKeyboardHandler.handleKeyboardShortcut(keyCode, event)) {
            return true
        }

        // Then, try gamepad buttons
        if (gamepadHandler.handleGamepadButtonEvent(keyCode, event)) {
            return true
        }

        // Then, try TV remote D-Pad and buttons
        if (tvRemoteHandler.handleKeyEvent(keyCode, event)) {
            return true
        }

        // Finally, try special keys (Home, End, Page Up, Page Down, Escape)
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
        // Enable TV remote support
        tvRemoteHandler.enableSpatialNavigation(webView)

        // Inject gamepad API support
        gamepadHandler.injectGamepadAPISupport(webView)

        // Inject keyboard accessibility CSS
        mouseKeyboardHandler.injectKeyboardAccessibilityCSS(webView)

        // Inject context menu support
        mouseKeyboardHandler.injectContextMenuSupport(webView)

        // Enable smart keyboard handling
        mouseKeyboardHandler.enableSmartKeyboardHandling(webView)
    }

    /**
     * Handles Gamepad Button A (typically "Select" or "Click").
     */
    private fun handleGamepadButtonA() {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return

        if (!joystickCursorInitialized) {
            webView.evaluateJavascript(
                """
                (function() {
                    var focused = document.activeElement;
                    if (focused && typeof focused.click === 'function') {
                        focused.click();
                    }
                })();
                """.trimIndent(),
                null
            )
            return
        }

        val x = joystickCursorX.roundToInt()
        val y = joystickCursorY.roundToInt()

        webView.evaluateJavascript(
            """
            (function() {
                var x = $x;
                var y = $y;
                var target = document.elementFromPoint(x, y) || document.activeElement;
                if (!target) return;

                ['mousemove', 'mousedown', 'mouseup', 'click'].forEach(function(type) {
                    target.dispatchEvent(new MouseEvent(type, {
                        bubbles: true,
                        cancelable: true,
                        view: window,
                        clientX: x,
                        clientY: y
                    }));
                });

                if (typeof target.click === 'function') {
                    target.click();
                }
            })();
            """.trimIndent(),
            null
        )
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

        val speed = 18f
        val maxX = (width - joystickCursor.width).coerceAtLeast(0).toFloat()
        val maxY = (height - joystickCursor.height).coerceAtLeast(0).toFloat()

        joystickCursorX = (joystickCursorX + (dx * speed)).coerceIn(0f, maxX)
        joystickCursorY = (joystickCursorY + (dy * speed)).coerceIn(0f, maxY)

        if (joystickCursor.visibility != View.VISIBLE) {
            joystickCursor.visibility = View.VISIBLE
        }
        joystickCursor.translationX = joystickCursorX - joystickCursor.width / 2f
        joystickCursor.translationY = joystickCursorY - joystickCursor.height / 2f
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
