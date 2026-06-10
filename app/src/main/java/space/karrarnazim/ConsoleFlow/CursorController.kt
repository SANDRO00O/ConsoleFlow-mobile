package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * CursorController — virtual gamepad pointer for ConsoleFlow.
 *
 * Left analog stick moves a circular cursor. Pressing A (or L3) fires a real
 * MotionEvent at the cursor position so that any View — including WebView pages
 * — responds exactly as if the user tapped with a finger.
 *
 * ── Motion ──────────────────────────────────────────────────────────────────
 *  • Frame loop is driven by Choreographer (VSYNC-locked). One update per
 *    display refresh — zero jitter, zero Handler timer drift.
 *  • Quadratic speed curve:  Δpx = sign(v) × v² × MAX_SPEED_DP × density
 *    Gives fine control near the center and fast travel at full deflection.
 *  • Screen-edge clamping: cursor stays within [radius … screenW-radius].
 *
 * ── Click ────────────────────────────────────────────────────────────────────
 *  Activity.dispatchTouchEvent(ACTION_DOWN) + dispatchTouchEvent(ACTION_UP)
 *  at (posX, posY). This travels through the entire view hierarchy — decor
 *  view → content layout → WebView (or any Button/RecyclerView/etc.) — just
 *  like a real finger tap. CursorView intercepts nothing: its dispatchTouchEvent
 *  always returns false so the event falls through to views below.
 *
 * ── Rendering ────────────────────────────────────────────────────────────────
 *  CursorView uses LAYER_TYPE_HARDWARE. Its content (circle + dot) is rasterised
 *  once; subsequent movement is done via translationX / translationY, which the
 *  GPU composites with zero CPU cost and no invalidate() calls per frame.
 */
class CursorController(private val activity: Activity) {

    // ── Pixel measurements (computed once) ───────────────────────────────
    private val density   = activity.resources.displayMetrics.density
    private val radiusPx  = RADIUS_DP  * density          // outer circle radius in px
    private val viewSizePx = (radiusPx * 2f + 0.5f).toInt()

    // ── The visual layer ─────────────────────────────────────────────────
    private val view = CursorView(activity)

    // ── Position in window-coordinates (px) ──────────────────────────────
    private var posX    = 0f
    private var posY    = 0f
    private var screenW = 1f   // populated after first layout
    private var screenH = 1f

    // ── Stick state — written + read on main thread, no synchronisation needed ──
    private var stickX = 0f
    private var stickY = 0f

    // ── Choreographer loop ────────────────────────────────────────────────
    private val choreo       = Choreographer.getInstance()
    private var framePending = false

    // ── Auto-hide ─────────────────────────────────────────────────────────
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTask    = Runnable { fadeOut() }

    private var attached = false

    // ─────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /** Add the cursor overlay to the window. Call once from Activity.onCreate(). */
    fun attach() {
        if (attached) return
        attached = true
        val decor = activity.window.decorView as ViewGroup
        decor.addView(view, FrameLayout.LayoutParams(viewSizePx, viewSizePx))
        // Resolve screen dimensions after layout pass
        decor.post {
            screenW = decor.width.toFloat()
            screenH = decor.height.toFloat()
            // Start in the middle but stay invisible until first stick input
            posX = screenW * 0.5f
            posY = screenH * 0.5f
            applyTranslation()
        }
    }

    /** Remove the overlay. Call from Activity.onDestroy(). */
    fun detach() {
        if (!attached) return
        attached = false
        framePending = false
        choreo.removeFrameCallback(frameCallback)
        hideHandler.removeCallbacks(hideTask)
        (activity.window.decorView as? ViewGroup)?.removeView(view)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Input API  (called by InputController on the main thread)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Feed current left-stick axes (each in [-1 .. +1], already dead-zone filtered).
     * Pass (0f, 0f) when the stick returns to rest.
     */
    fun updateStick(x: Float, y: Float) {
        stickX = x
        stickY = y
        if (abs(x) > DEADZONE || abs(y) > DEADZONE) {
            revealCursor()
            scheduleFrame()
        }
    }

    /**
     * Fire a real tap at the cursor's current position.
     * Returns true if the cursor was visible and the event was dispatched.
     */
    fun performClick(): Boolean {
        if (!isVisible) return false
        view.playClickAnim()
        val t    = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t,        MotionEvent.ACTION_DOWN, posX, posY, 0)
        val up   = MotionEvent.obtain(t, t + 80L,  MotionEvent.ACTION_UP,   posX, posY, 0)
        try {
            activity.dispatchTouchEvent(down)
            activity.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        return true
    }

    /** True when the cursor is currently visible and interactive. */
    val isVisible: Boolean
        get() = attached && view.visibility == View.VISIBLE && view.alpha > 0.05f

    // ─────────────────────────────────────────────────────────────────────
    //  Choreographer frame loop
    // ─────────────────────────────────────────────────────────────────────

    private val frameCallback = Choreographer.FrameCallback { _ ->
        framePending = false
        if (abs(stickX) > DEADZONE || abs(stickY) > DEADZONE) {
            stepCursor()
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (!framePending) {
            framePending = true
            choreo.postFrameCallback(frameCallback)
        }
    }

    /** Advance cursor position by one frame based on current stick values. */
    private fun stepCursor() {
        val maxPx = MAX_SPEED_DP * density
        // Quadratic response: sign(v)·v²·maxPx → slow near center, fast at edges
        posX = (posX + stickX * abs(stickX) * maxPx).coerceIn(radiusPx, screenW - radiusPx)
        posY = (posY + stickY * abs(stickY) * maxPx).coerceIn(radiusPx, screenH - radiusPx)
        applyTranslation()
    }

    /** Move the view without touching its content or triggering invalidate(). */
    private fun applyTranslation() {
        view.translationX = posX - radiusPx
        view.translationY = posY - radiusPx
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Visibility helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun revealCursor() {
        hideHandler.removeCallbacks(hideTask)
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(120L).start()
        hideHandler.postDelayed(hideTask, HIDE_MS)
    }

    private fun fadeOut() {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(400L)
            .withEndAction { view.visibility = View.INVISIBLE }
            .start()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CursorView
    //
    //  • Fixed size (diameter × diameter).  Positioned with translationX/Y.
    //  • LAYER_TYPE_HARDWARE: content rasterised once on GPU; subsequent
    //    translationX/Y changes are free matrix operations — no CPU redraw.
    //  • dispatchTouchEvent always returns false so real taps fall through.
    // ─────────────────────────────────────────────────────────────────────

    private inner class CursorView(context: Context) : View(context) {

        private val d  = context.resources.displayMetrics.density
        private val r  = RADIUS_DP * d        // outer radius
        private val ir = r * 0.27f            // inner precision-dot radius
        private val cx get() = width  * 0.5f  // recomputed after layout
        private val cy get() = height * 0.5f

        // All paints created once — never recreated during rendering
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 15, 15, 15)
            style  = Paint.Style.STROKE
            strokeWidth = 1.7f * d
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 20, 20, 20)
            style = Paint.Style.FILL
        }
        // Soft glow ring — slightly larger, very transparent, for depth on dark bg
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            style  = Paint.Style.STROKE
            strokeWidth = 4f * d
        }

        init {
            isClickable  = false
            isFocusable  = false
            alpha        = 0f               // invisible until first stick input
            visibility   = View.INVISIBLE
            // Hardware layer: rasterise once, move for free via translation
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        /** Brief pulse to give click feedback. */
        fun playClickAnim() {
            animate().cancel()
            animate().scaleX(0.58f).scaleY(0.58f).setDuration(60L)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }.start()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = cx; val cy = cy          // cache to avoid repeated calls
            // 1. Outer glow ring (gives depth on dark backgrounds)
            canvas.drawCircle(cx, cy, r + glowPaint.strokeWidth * 0.5f, glowPaint)
            // 2. White filled circle
            canvas.drawCircle(cx, cy, r, fillPaint)
            // 3. Dark border ring (contrast on white backgrounds)
            canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth * 0.5f, ringPaint)
            // 4. Precision dot at center
            canvas.drawCircle(cx, cy, ir, dotPaint)
        }

        // ── Touch passthrough ─────────────────────────────────────────────
        // These two overrides ensure the cursor never steals a real tap.
        // dispatchTouchEvent returning false tells the parent ViewGroup:
        // "I didn't handle this — keep trying your other children."
        override fun onTouchEvent(event: MotionEvent)     = false
        override fun dispatchTouchEvent(event: MotionEvent) = false
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val RADIUS_DP    = 14f      // visual cursor radius in dp
        const val MAX_SPEED_DP = 24f      // max px/frame at full stick deflection (dp unit)
        const val DEADZONE     = 0.08f    // minimum axis magnitude to act on
        const val HIDE_MS      = 3500L    // ms of idle before cursor fades out
    }
}
