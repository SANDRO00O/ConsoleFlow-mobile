package space.karrarnazim.ConsoleFlow

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

/**
 * ConsoleFlow — Central Input Controller
 *
 * Single source of truth for ALL physical input. Zero duplicate logic.
 *
 * Covered input sources
 * ─────────────────────
 *  • TV remotes (Fire TV, Android TV, Google TV, etc.)
 *      D-pad up/down/left/right · OK/Select · Back · Menu · Channel+/- · Page Up/Down
 *  • Gamepads / joysticks (Xbox, PlayStation, generic Bluetooth/USB)
 *      A/B/X/Y · L1/R1 (shoulder) · L2/R2 (triggers) · Start/Select
 *      Left analog stick → smooth page scroll
 *      Right analog stick → additional scroll
 *  • Physical keyboards
 *      Ctrl+L/T/W/R/F/Tab · Alt+←/→ · F5/F12 · Escape · Arrow keys · Page Up/Down
 *  • Mice
 *      Scroll wheel (vertical + horizontal)
 *
 * Design: no special-cases. Every source maps to the same small set of
 * browser actions exposed through [Handlers]. The Activity wires these up.
 */
class InputController(private val h: Handlers) {

    // ── Right-stick: continuous scroll (Handler loop) ─────────────────────
    private val loopHandler = Handler(Looper.getMainLooper())
    private var scrollLoop: Runnable? = null
    private var stickX = 0f
    private var stickY = 0f

    // ── Left-stick: virtual cursor (Choreographer inside CursorController) ─
    private var cursor: CursorController? = null

    /** Wire the CursorController. Left analog stick will drive it. */
    fun setCursorController(c: CursorController) { cursor = c }

    // ─────────────────────────────────────────────────────────────────────
    //  Handlers interface — MainActivity implements this
    // ─────────────────────────────────────────────────────────────────────

    interface Handlers {
        fun getWebView(): WebView?
        fun isTopBarVisible(): Boolean
        fun isTabsOverlayVisible(): Boolean
        fun showTopBar()
        fun hideTopBar()
        fun focusUrlBar()
        fun navigateBack()
        fun navigateForward()
        fun navigateHome()
        fun openNewTab()
        fun closeCurrentTab()
        fun reload()
        fun showMenu()
        fun showTabs()
        fun nextTab()
        fun prevTab()
        fun toggleConsole()
        fun toggleFind()
        /** Dismiss the topmost overlay (tabs → home → topBar → findBar).
         *  Returns true if something was actually dismissed. */
        fun dismissTopOverlay(): Boolean
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Public entry points (called from Activity)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Call from Activity.dispatchKeyEvent().
     * Only processes ACTION_DOWN; returns false for everything else so the
     * system and focused views handle the remaining half of key pairs.
     */
    fun onKeyDown(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val src = event.source

        return when {
            // Gamepad non-D-pad buttons: own mapping
            src.isGamepad && event.keyCode !in DPAD_KEYS -> handleGamepadButton(event)
            // Ctrl-shortcuts: keyboard + some remotes
            event.isCtrlPressed -> handleCtrl(event)
            // Alt-shortcuts: keyboard
            event.isAltPressed  -> handleAlt(event)
            // D-pad / arrow / function / media / page keys
            else                -> handleGeneric(event)
        }
    }

    /**
     * Call from Activity.onGenericMotionEvent().
     * Handles joystick axes and mouse scroll wheel.
     */
    fun onMotion(event: MotionEvent): Boolean = when {
        event.source.isJoystick &&
            event.action == MotionEvent.ACTION_MOVE -> handleJoystick(event)
        event.source.isMouse &&
            event.action == MotionEvent.ACTION_SCROLL -> handleMouseWheel(event)
        else -> false
    }

    /** Must be called from Activity.onDestroy() to stop the scroll loop. */
    fun release() {
        scrollLoop?.let { loopHandler.removeCallbacks(it) }
        scrollLoop = null
        cursor = null   // CursorController.detach() is called separately by Activity
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Gamepad button mapping
    // ─────────────────────────────────────────────────────────────────────

    private fun handleGamepadButton(event: KeyEvent): Boolean = when (event.keyCode) {
        // A  — click at cursor if visible; otherwise activate focused web element
        KeyEvent.KEYCODE_BUTTON_A -> {
            val clicked = cursor?.performClick() ?: false
            if (!clicked && !h.isTopBarVisible() && !h.isTabsOverlayVisible()) {
                activateFocusedWebElement()
            }
            true
        }
        // B  — intentionally NOT consumed; goes to BackPressedDispatcher
        KeyEvent.KEYCODE_BUTTON_B -> false

        // X  — show tab switcher
        KeyEvent.KEYCODE_BUTTON_X -> { h.showTabs(); true }
        // Y  — show menu
        KeyEvent.KEYCODE_BUTTON_Y -> { h.showMenu(); true }

        // Shoulder buttons — prev / next tab
        KeyEvent.KEYCODE_BUTTON_L1 -> { h.prevTab(); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { h.nextTab(); true }

        // Triggers — browser back / forward
        KeyEvent.KEYCODE_BUTTON_L2 -> { h.navigateBack(); true }
        KeyEvent.KEYCODE_BUTTON_R2 -> { h.navigateForward(); true }

        // Start  — main menu
        KeyEvent.KEYCODE_BUTTON_START  -> { h.showMenu(); true }
        // Select — toggle DevTools console
        KeyEvent.KEYCODE_BUTTON_SELECT -> { h.toggleConsole(); true }

        // Thumb-sticks pressed — home / URL bar
        KeyEvent.KEYCODE_BUTTON_THUMBL -> { h.navigateHome(); true }
        KeyEvent.KEYCODE_BUTTON_THUMBR -> { h.focusUrlBar(); true }

        // Note: DPAD_* from gamepad are routed to handleGeneric() by onKeyDown()
        // because DPAD_KEYS is excluded from this function. No cases needed here.
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Keyboard Ctrl-shortcuts
    // ─────────────────────────────────────────────────────────────────────

    private fun handleCtrl(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_L -> { h.focusUrlBar(); true }
        KeyEvent.KEYCODE_T -> { h.openNewTab(); true }
        KeyEvent.KEYCODE_W -> { h.closeCurrentTab(); true }
        KeyEvent.KEYCODE_R -> { h.reload(); true }
        KeyEvent.KEYCODE_F -> { h.toggleFind(); true }
        KeyEvent.KEYCODE_TAB -> {
            if (event.isShiftPressed) h.prevTab() else h.nextTab()
            true
        }
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Keyboard Alt-shortcuts
    // ─────────────────────────────────────────────────────────────────────

    private fun handleAlt(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT  -> { h.navigateBack(); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { h.navigateForward(); true }
        KeyEvent.KEYCODE_HOME       -> { h.navigateHome(); true }
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Generic keys: D-pad · TV remote · F-keys · page/media keys
    // ─────────────────────────────────────────────────────────────────────

    private fun handleGeneric(event: KeyEvent): Boolean = when (event.keyCode) {

        KeyEvent.KEYCODE_DPAD_UP    -> dpadUp()
        KeyEvent.KEYCODE_DPAD_DOWN  -> dpadDown()
        KeyEvent.KEYCODE_DPAD_LEFT  -> dpadLeft()
        KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight()

        // OK / Select / remote center — activate focused element
        KeyEvent.KEYCODE_DPAD_CENTER -> {
            if (h.isTopBarVisible() || h.isTabsOverlayVisible()) false
            else { activateFocusedWebElement(); true }
        }

        // Function keys
        KeyEvent.KEYCODE_F5  -> { h.reload(); true }
        KeyEvent.KEYCODE_F12 -> { h.toggleConsole(); true }

        // Universal dismiss
        KeyEvent.KEYCODE_ESCAPE -> h.dismissTopOverlay()

        // Menu key (most TV remotes have this)
        KeyEvent.KEYCODE_MENU -> { h.showMenu(); true }

        // Page scroll
        KeyEvent.KEYCODE_PAGE_UP   -> { h.getWebView()?.scrollBy(0, -PAGE_SCROLL); true }
        KeyEvent.KEYCODE_PAGE_DOWN -> { h.getWebView()?.scrollBy(0,  PAGE_SCROLL); true }

        // Channel +/– (Fire TV, cable remotes) → prev/next tab
        KeyEvent.KEYCODE_CHANNEL_UP   -> { h.nextTab(); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> { h.prevTab(); true }

        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  D-pad logic (shared between remote, gamepad-dpad, keyboard arrows)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * UP:
     *  • Tabs overlay visible   → let focus system traverse the overlay
     *  • Top bar visible        → let focus system traverse the bar
     *  • Page at top (scrollY=0) → show top bar
     *  • Otherwise              → let WebView scroll (return false)
     */
    private fun dpadUp(): Boolean {
        if (h.isTabsOverlayVisible() || h.isTopBarVisible()) return false
        val wv = h.getWebView() ?: return false
        return if (wv.scrollY <= 0) { h.showTopBar(); true } else false
    }

    /**
     * DOWN:
     *  • Top bar visible → dismiss it
     *  • Otherwise       → let WebView scroll
     */
    private fun dpadDown(): Boolean {
        if (h.isTabsOverlayVisible()) return false
        return if (h.isTopBarVisible()) { h.hideTopBar(); true } else false
    }

    /**
     * LEFT:
     *  • Overlays visible       → focus system handles
     *  • Page has horizontal scroll → scroll left
     *  • Can go back            → browser back
     *  • Otherwise              → pass through
     */
    private fun dpadLeft(): Boolean {
        if (h.isTopBarVisible() || h.isTabsOverlayVisible()) return false
        val wv = h.getWebView() ?: return false
        return when {
            wv.canScrollHorizontally(-1) -> { wv.scrollBy(-STEP, 0); true }
            wv.canGoBack()               -> { h.navigateBack(); true }
            else                         -> false
        }
    }

    private fun dpadRight(): Boolean {
        if (h.isTopBarVisible() || h.isTabsOverlayVisible()) return false
        val wv = h.getWebView() ?: return false
        return when {
            wv.canScrollHorizontally(1) -> { wv.scrollBy(STEP, 0); true }
            wv.canGoForward()           -> { h.navigateForward(); true }
            else                        -> false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Activate focused element in WebView (for A button / OK key)
    // ─────────────────────────────────────────────────────────────────────

    private fun activateFocusedWebElement() {
        val wv = h.getWebView() ?: return
        val now = SystemClock.uptimeMillis()
        // KEYCODE_DPAD_CENTER triggers click on whatever element has focus in the page
        wv.dispatchKeyEvent(KeyEvent(now, now,      KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        wv.dispatchKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Analog-stick handling
    //  Left  stick → virtual cursor (CursorController + Choreographer)
    //  Right stick → page scroll   (Handler loop at ~60 fps)
    // ─────────────────────────────────────────────────────────────────────

    private fun handleJoystick(event: MotionEvent): Boolean {
        // ── Left stick → cursor ───────────────────────────────────────────
        val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
        val ly = getCenteredAxis(event, MotionEvent.AXIS_Y)
        cursor?.updateStick(lx, ly)

        // ── Right stick → scroll ──────────────────────────────────────────
        var rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
        var ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)
        // Some controllers report the D-pad as AXIS_HAT_X/Y in motion events
        if (abs(rx) < DEADZONE && abs(ry) < DEADZONE) {
            rx = getCenteredAxis(event, MotionEvent.AXIS_HAT_X)
            ry = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y)
        }
        updateStickValues(rx, ry)
        armScrollLoop()

        val cursorActive = abs(lx) >= DEADZONE || abs(ly) >= DEADZONE
        val scrollActive = abs(rx) >= DEADZONE || abs(ry) >= DEADZONE
        return cursorActive || scrollActive
    }

    private fun updateStickValues(x: Float, y: Float) {
        stickX = x; stickY = y
    }

    private fun armScrollLoop() {
        scrollLoop?.let { loopHandler.removeCallbacks(it) }
        if (abs(stickX) < DEADZONE && abs(stickY) < DEADZONE) {
            scrollLoop = null
            return
        }
        scrollLoop = object : Runnable {
            override fun run() {
                val wv = h.getWebView() ?: return
                wv.scrollBy((stickX * STICK_PX).toInt(), (stickY * STICK_PX).toInt())
                if (abs(stickX) >= DEADZONE || abs(stickY) >= DEADZONE) {
                    loopHandler.postDelayed(this, LOOP_MS)
                }
            }
        }.also { loopHandler.post(it) }
    }

    /**
     * Returns axis value outside the deadzone, or 0 if the stick is centered.
     * Uses the hardware-reported flat value from MotionRange for accurate deadzones.
     */
    private fun getCenteredAxis(event: MotionEvent, axis: Int, histIdx: Int = -1): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val raw = if (histIdx < 0) event.getAxisValue(axis)
                  else event.getHistoricalAxisValue(axis, histIdx)
        val threshold = range.flat.coerceAtLeast(DEADZONE)
        return if (abs(raw) > threshold) raw else 0f
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Mouse scroll wheel
    //  (Clicks/pointer events are handled natively by WebView)
    // ─────────────────────────────────────────────────────────────────────

    private fun handleMouseWheel(event: MotionEvent): Boolean {
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h_ = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f && h_ == 0f) return false
        val wv = h.getWebView() ?: return false
        wv.scrollBy((-h_ * MOUSE_PX).toInt(), (-v * MOUSE_PX).toInt())
        return true
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val STEP       = 180     // px per discrete D-pad left/right press
        const val PAGE_SCROLL = 700    // px for Page Up / Page Down
        const val STICK_PX   = 20f    // px per frame for analog-stick scroll
        const val MOUSE_PX   = 100f   // px per mouse-wheel notch
        const val DEADZONE   = 0.15f  // analog stick dead-zone (min; actual from hardware)
        const val LOOP_MS    = 16L    // ~60 fps scroll loop interval

        /** Key codes that belong to D-pad — not handled as "gamepad buttons". */
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
    }
}

// ── Extension helpers (file-private) ─────────────────────────────────────────

private val Int.isGamepad  get() = this and InputDevice.SOURCE_GAMEPAD  == InputDevice.SOURCE_GAMEPAD
private val Int.isJoystick get() = this and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
private val Int.isMouse    get() = this and InputDevice.SOURCE_MOUSE    == InputDevice.SOURCE_MOUSE
