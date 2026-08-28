package space.karrarnazim.ConsoleFlow

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

class InputController(private val h: Handlers) {

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
        fun dismissTopOverlay(): Boolean
    }

    private val handler    = Handler(Looper.getMainLooper())
    private var scrollX    = 0f
    private var scrollY    = 0f
    private var scrollJob: Runnable? = null

    private var cursor: CursorController? = null
    fun setCursorController(c: CursorController) { cursor = c }

    private fun overlayActive() = h.isTopBarVisible() || h.isTabsOverlayVisible()

    fun onKeyDown(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false

        return when {
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                && event.keyCode !in DPAD_KEYS -> handleGamepad(event)

            event.isCtrlPressed -> handleCtrl(event)
            event.isAltPressed  -> handleAlt(event)
            else                -> handleGenericKey(event)
        }
    }

    fun onGenericMotion(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            && event.action == MotionEvent.ACTION_MOVE  -> handleJoystick(event)

        event.isFromSource(InputDevice.SOURCE_MOUSE)
            && event.action == MotionEvent.ACTION_SCROLL -> handleMouseWheel(event)

        else -> false
    }

    fun release() {
        stopScrollLoop()
        cursor = null
    }

    fun stopScrollLoop() {
        scrollJob?.let { handler.removeCallbacks(it) }
        scrollJob = null
        scrollX = 0f
        scrollY = 0f
    }

    private fun handleGamepad(event: KeyEvent): Boolean = when (event.keyCode) {

        KeyEvent.KEYCODE_BUTTON_A -> {
            when {
                cursor?.isVisible == true -> {
                    val handled = performCursorClick()
                    cursor?.keepVisible()
                    handled
                }
                !overlayActive()          -> {
                    activateWebElement()
                    cursor?.keepVisible()
                    true
                }
                else -> true
            }
        }

        KeyEvent.KEYCODE_BUTTON_B      -> false

        KeyEvent.KEYCODE_BUTTON_X      -> { h.showTabs();       true }
        KeyEvent.KEYCODE_BUTTON_Y      -> { h.showMenu();       true }
        KeyEvent.KEYCODE_BUTTON_L1     -> { h.prevTab();        true }
        KeyEvent.KEYCODE_BUTTON_R1     -> { h.nextTab();        true }
        KeyEvent.KEYCODE_BUTTON_L2     -> { h.navigateBack();   true }
        KeyEvent.KEYCODE_BUTTON_R2     -> { h.navigateForward();true }
        KeyEvent.KEYCODE_BUTTON_START  -> { h.showMenu();       true }
        KeyEvent.KEYCODE_BUTTON_SELECT -> { h.toggleConsole();  true }
        KeyEvent.KEYCODE_BUTTON_THUMBL -> { h.navigateHome();   true }
        KeyEvent.KEYCODE_BUTTON_THUMBR -> { h.focusUrlBar();    true }

        else -> false
    }

    private fun handleCtrl(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_L   -> { h.focusUrlBar();     true }
        KeyEvent.KEYCODE_T   -> { h.openNewTab();      true }
        KeyEvent.KEYCODE_W   -> { h.closeCurrentTab(); true }
        KeyEvent.KEYCODE_R   -> { h.reload();          true }
        KeyEvent.KEYCODE_F   -> { h.toggleFind();      true }
        KeyEvent.KEYCODE_TAB -> {
            if (event.isShiftPressed) h.prevTab() else h.nextTab()
            true
        }
        else -> false
    }

    private fun handleAlt(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT  -> { h.navigateBack();    true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { h.navigateForward(); true }
        else -> false
    }

    private fun handleGenericKey(event: KeyEvent): Boolean {
        val wv by lazy { h.getWebView() }

        return when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_UP -> when {
                overlayActive()                              -> false
                wv != null && !wv!!.canScrollVertically(-1) -> { h.showTopBar(); true }
                else                                        -> false
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                h.isTopBarVisible() -> { h.hideTopBar(); true }
                else                -> false
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (overlayActive()) return false
                val v = wv ?: return false
                when {
                    v.canScrollHorizontally(-1) -> { v.scrollBy(-DPAD_SCROLL, 0); true }
                    v.canGoBack()               -> { h.navigateBack();            true }
                    else                        -> false
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (overlayActive()) return false
                val v = wv ?: return false
                when {
                    v.canScrollHorizontally(1) -> { v.scrollBy(DPAD_SCROLL, 0); true }
                    v.canGoForward()           -> { h.navigateForward();         true }
                    else                       -> false
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (overlayActive()) false else { activateWebElement(); true }
            }

            KeyEvent.KEYCODE_F5           -> { h.reload();           true }
            KeyEvent.KEYCODE_F12          -> { h.toggleConsole();    true }
            KeyEvent.KEYCODE_ESCAPE       -> h.dismissTopOverlay()
            KeyEvent.KEYCODE_MENU         -> { h.showMenu();         true }
            KeyEvent.KEYCODE_PAGE_UP      -> { wv?.scrollBy(0, -PAGE_SCROLL); true }
            KeyEvent.KEYCODE_PAGE_DOWN    -> { wv?.scrollBy(0,  PAGE_SCROLL); true }
            KeyEvent.KEYCODE_CHANNEL_UP   -> { h.nextTab();          true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { h.prevTab();          true }

            else -> false
        }
    }

    private fun handleJoystick(event: MotionEvent): Boolean {
        val device = event.device ?: return false

        for (i in 0 until event.historySize) {
            processJoystickInput(event, device, i)
        }
        processJoystickInput(event, device, -1)
        return true
    }

    private fun processJoystickInput(event: MotionEvent, device: InputDevice, histPos: Int) {
        val lx = getCenteredAxis(event, device, MotionEvent.AXIS_X,  histPos)
        val ly = getCenteredAxis(event, device, MotionEvent.AXIS_Y,  histPos)
        cursor?.updateStick(lx, ly)

        cursor?.keepVisible()

        var rx = getCenteredAxis(event, device, MotionEvent.AXIS_Z,      histPos)
        var ry = getCenteredAxis(event, device, MotionEvent.AXIS_RZ,     histPos)
        if (rx == 0f && ry == 0f) {
            rx   = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X, histPos)
            ry   = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y, histPos)
        }
        setScrollAxes(rx, ry)
    }

    private fun getCenteredAxis(
        event: MotionEvent, device: InputDevice, axis: Int, histPos: Int
    ): Float {
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = if (histPos < 0) event.getAxisValue(axis)
                    else             event.getHistoricalAxisValue(axis, histPos)
        return if (abs(value) > range.flat) value else 0f
    }

    private fun setScrollAxes(x: Float, y: Float) {
        scrollX = x
        scrollY = y
        if ((x != 0f || y != 0f) && scrollJob == null) startScrollLoop()
    }

    private fun startScrollLoop() {
        scrollJob = object : Runnable {
            override fun run() {
                if (scrollX == 0f && scrollY == 0f) {
                    scrollJob = null
                    return
                }

                h.getWebView()?.scrollBy(
                    (scrollX * SCROLL_SPEED).toInt(),
                    (scrollY * SCROLL_SPEED).toInt()
                )

                handler.postDelayed(this, SCROLL_TICK_MS)
            }
        }
        handler.post(scrollJob!!)
    }

    private fun handleMouseWheel(event: MotionEvent): Boolean {
        val v  = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val hz = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f && hz == 0f) return false
        h.getWebView()?.scrollBy((-hz * MOUSE_SPEED).toInt(), (-v * MOUSE_SPEED).toInt())
        return true
    }

    private fun performCursorClick(): Boolean {
        val c = cursor ?: return false
        return c.performClick()
    }

    private fun activateWebElement() {
        val wv = h.getWebView() ?: return
        stopScrollLoop()
        wv.requestFocus()
        val t  = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(t, t,       KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        wv.dispatchKeyEvent(KeyEvent(t, t + 50L, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }

    private companion object {
        const val DPAD_SCROLL    = 200
        const val PAGE_SCROLL    = 720
        const val SCROLL_SPEED   = 18f
        const val MOUSE_SPEED    = 80f
        const val SCROLL_TICK_MS = 16L

        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
    }
}
