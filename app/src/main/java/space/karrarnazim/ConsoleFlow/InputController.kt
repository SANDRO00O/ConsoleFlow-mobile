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
 * InputController — مركز إدارة كل المدخلات في ConsoleFlow.
 *
 * المصادر المدعومة:
 *  ┌────────────────┬──────────────────────────────────────────────────────┐
 *  │ ريموت تلفزيون │ D-pad · OK/Back · Menu · Channel+/− · Page Up/Down  │
 *  │ جويستيك        │ A/B/X/Y · L1/R1/L2/R2 · Start/Select · L3/R3       │
 *  │                │ عصا يسار → cursor   ·   عصا يمين → تمرير            │
 *  │ كيبورد         │ Ctrl+L/T/W/R/F/Tab · Alt+←/→ · F5/F12 · Escape     │
 *  │ ماوس           │ عجلة التمرير (عمودي + أفقي)                         │
 *  └────────────────┴──────────────────────────────────────────────────────┘
 *
 * قواعد ثابتة:
 *  • event.repeatCount != 0  → يُتجاهل (لا تكرار عند الضغط المستمر)
 *  • KEYCODE_BACK            → لا يُستهلَك أبداً (يذهب لـ BackPressedDispatcher)
 *  • getCenteredAxis         → النمط الرسمي من توثيق Android بـ range.flat
 *  • isFromSource()          → الطريقة الرسمية بدل bitwise AND اليدوي
 */
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

    // ── عصا يمين: تمرير عبر Handler loop (~60fps) ─────────────────────────
    private val handler    = Handler(Looper.getMainLooper())
    private var scrollX    = 0f
    private var scrollY    = 0f
    private var scrollJob: Runnable? = null

    // ── عصا يسار: مؤشر افتراضي ─────────────────────────────────────────────
    private var cursor: CursorController? = null
    fun setCursorController(c: CursorController) { cursor = c }

    /** هل يوجد overlay يحجب WebView حالياً؟ */
    private fun overlayActive() = h.isTopBarVisible() || h.isTabsOverlayVisible()

    // ─────────────────────────────────────────────────────────────────────
    //  نقاط الدخول (تُستدعى من Activity)
    // ─────────────────────────────────────────────────────────────────────

    /** يُستدعى من Activity.dispatchKeyEvent(). يعيد true إن استهلك الحدث. */
    fun onKeyDown(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false   // تجاهل التكرار عند الضغط المستمر

        return when {
            // أزرار جويستيك (غير D-pad): تعيين خاص
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                && event.keyCode !in DPAD_KEYS -> handleGamepad(event)

            event.isCtrlPressed -> handleCtrl(event)
            event.isAltPressed  -> handleAlt(event)
            else                -> handleGenericKey(event)
        }
    }

    /** يُستدعى من Activity.onGenericMotionEvent(). */
    fun onGenericMotion(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            && event.action == MotionEvent.ACTION_MOVE  -> handleJoystick(event)

        event.isFromSource(InputDevice.SOURCE_MOUSE)
            && event.action == MotionEvent.ACTION_SCROLL -> handleMouseWheel(event)

        else -> false
    }

    /** يُستدعى من Activity.onDestroy(). */
    fun release() {
        stopScrollLoop()
        cursor = null
    }

    /** يوقف تمرير العصا اليمنى فورًا حتى لا يظل عالقًا بعد نقرة أو تبديل واجهة. */
    fun stopScrollLoop() {
        scrollJob?.let { handler.removeCallbacks(it) }
        scrollJob = null
        scrollX = 0f
        scrollY = 0f
    }

    // ─────────────────────────────────────────────────────────────────────
    //  أزرار الجويستيك
    // ─────────────────────────────────────────────────────────────────────

    private fun handleGamepad(event: KeyEvent): Boolean = when (event.keyCode) {

        KeyEvent.KEYCODE_BUTTON_A -> {
            // إن كان المؤشر ظاهراً → انقر عنده. وإلا → نشّط عنصر الصفحة.
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

        // BUTTON_B → لا يُستهلَك. يصل إلى onBackPressedDispatcher.
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

    // ─────────────────────────────────────────────────────────────────────
    //  اختصارات الكيبورد
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    //  D-pad / ريموت / أسهم الكيبورد / مفاتيح أخرى
    // ─────────────────────────────────────────────────────────────────────

    private fun handleGenericKey(event: KeyEvent): Boolean {
        val wv by lazy { h.getWebView() }

        return when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_UP -> when {
                overlayActive()                              -> false
                wv != null && !wv!!.canScrollVertically(-1) -> { h.showTopBar(); true }
                else                                        -> false  // WebView تتمرر بنفسها
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

            KeyEvent.KEYCODE_DPAD_CENTER -> when {
                overlayActive() -> false
                // BUG-P FIX: some gamepads (notably PS-style pads through
                // generic Bluetooth HID adapters) report their confirm
                // button as DPAD_CENTER rather than BUTTON_A. Without this
                // check, pressing it while the virtual cursor was visible
                // ignored the cursor entirely and activated whatever DOM
                // element happened to have focus instead — clicking the
                // wrong thing. Mirrors KEYCODE_BUTTON_A's own logic.
                cursor?.isVisible == true -> {
                    val handled = performCursorClick()
                    cursor?.keepVisible()
                    handled
                }
                else -> { activateWebElement(); true }
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

    // ─────────────────────────────────────────────────────────────────────
    //  Joystick — نمط المعالجة الرسمي من توثيق Android
    // ─────────────────────────────────────────────────────────────────────

    private fun handleJoystick(event: MotionEvent): Boolean {
        val device = event.device ?: return false

        // الأمر الرسمي: اعالج القيم التاريخية أولاً ثم القيمة الحالية
        for (i in 0 until event.historySize) {
            processJoystickInput(event, device, i)
        }
        processJoystickInput(event, device, -1)
        return true
    }

    private fun processJoystickInput(event: MotionEvent, device: InputDevice, histPos: Int) {
        // عصا يسار → المؤشر الافتراضي
        val lx = getCenteredAxis(event, device, MotionEvent.AXIS_X,  histPos)
        val ly = getCenteredAxis(event, device, MotionEvent.AXIS_Y,  histPos)
        cursor?.updateStick(lx, ly)

        // أي حركة من الجويستيك تبقي المؤشر حياً أثناء التنقّل بين الواجهات.
        cursor?.keepVisible()

        // عصا يمين → تمرير الصفحة؛ الاحتياط بـ HAT axes إن كانت عصا اليمين في مركزها
        var rx = getCenteredAxis(event, device, MotionEvent.AXIS_Z,      histPos)
        var ry = getCenteredAxis(event, device, MotionEvent.AXIS_RZ,     histPos)
        if (rx == 0f && ry == 0f) {
            rx   = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X, histPos)
            ry   = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y, histPos)
        }
        setScrollAxes(rx, ry)
    }

    /**
     * getCenteredAxis — التطبيق الرسمي من توثيق Android.
     *
     * يستخدم قيمة flat المُبلَّغ عنها من الجهاز نفسه، لذلك تعمل dead zone
     * بشكل صحيح مع كل أنواع الكنترولرز بغض النظر عن الماركة.
     */
    private fun getCenteredAxis(
        event: MotionEvent, device: InputDevice, axis: Int, histPos: Int
    ): Float {
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = if (histPos < 0) event.getAxisValue(axis)
                    else             event.getHistoricalAxisValue(axis, histPos)
        return if (abs(value) > range.flat) value else 0f
    }

    // ─────────────────────────────────────────────────────────────────────
    //  حلقة تمرير عصا اليمين
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    //  عجلة الماوس
    // ─────────────────────────────────────────────────────────────────────

    private fun handleMouseWheel(event: MotionEvent): Boolean {
        val v  = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val hz = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f && hz == 0f) return false
        h.getWebView()?.scrollBy((-hz * MOUSE_SPEED).toInt(), (-v * MOUSE_SPEED).toInt())
        return true
    }

    // ─────────────────────────────────────────────────────────────────────
    //  تنشيط عنصر الصفحة (زر OK / A)
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    //  ثوابت
    // ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val DPAD_SCROLL    = 200     // px لكل ضغطة D-pad أفقي
        const val PAGE_SCROLL    = 720     // px لـ Page Up/Down
        const val SCROLL_SPEED   = 18f     // px/tick لتمرير عصا اليمين
        const val MOUSE_SPEED    = 80f     // px لكل نقرة عجلة ماوس
        const val SCROLL_TICK_MS = 16L     // ~60fps

        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
    }
}
