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
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

class CursorController(private val activity: Activity) {

    private val d      = activity.resources.displayMetrics.density
    private val RADIUS = 12f * d
    private val SIZE   = (RADIUS * 2f + 0.5f).toInt()

    val view = CursorView(activity)

    private val contentRoot: View
        get() = activity.findViewById(android.R.id.content)

    private var posX    = 0f
    private var posY    = 0f
    private var screenW = 0f
    private var screenH = 0f

    private var axisX = 0f
    private var axisY = 0f

    private val choreo      = Choreographer.getInstance()
    private var framePosted = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTask    = Runnable { fadeOut() }

    fun attach() {
        val decor = activity.window.decorView as ViewGroup
        decor.addView(view, FrameLayout.LayoutParams(SIZE, SIZE))
        decor.post {
            screenW = decor.width.toFloat()
            screenH = decor.height.toFloat()
            posX    = screenW / 2f
            posY    = screenH / 2f
            applyPosition()
        }
    }

    fun detach() {
        choreo.removeFrameCallback(frameCallback)
        hideHandler.removeCallbacks(hideTask)
        (activity.window.decorView as? ViewGroup)?.removeView(view)
    }

    fun updateStick(x: Float, y: Float) {
        axisX = x
        axisY = y
        if (x != 0f || y != 0f) {
            revealCursor()
            scheduleFrame()
        }
    }

    fun performClick(): Boolean {
        if (!isVisible) return false

        hideHandler.removeCallbacks(hideTask)
        view.animate().cancel()

        val wasVisible = view.visibility == View.VISIBLE
        if (wasVisible) {
            view.alpha = 0f
            view.visibility = View.INVISIBLE
        }

        val handled = dispatchSystemTap(contentRoot, posX, posY)

        if (wasVisible) {
            view.visibility = View.VISIBLE
            view.alpha = 1f
        }
        view.animateClick()
        hideHandler.postDelayed(hideTask, HIDE_DELAY_MS)
        return handled
    }

    private fun dispatchSystemTap(root: View, screenX: Float, screenY: Float): Boolean {
        val target = findTargetView(root, screenX, screenY) ?: return false

        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val localX = screenX - loc[0]
        val localY = screenY - loc[1]
        if (target.width <= 0 || target.height <= 0) return false
        if (localX < 0f || localY < 0f || localX > target.width.toFloat() || localY > target.height.toFloat()) {
            return false
        }

        if (target.isFocusable) {
            target.requestFocus()
        }

        val t  = SystemClock.uptimeMillis()
        val dn = MotionEvent.obtain(t, t,        MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(t, t + 80L,  MotionEvent.ACTION_UP,   localX, localY, 0)

        val downHandled = target.dispatchTouchEvent(dn)
        val upHandled = target.dispatchTouchEvent(up)
        dn.recycle()
        up.recycle()

        return downHandled || upHandled || target.performClick()
    }

    private fun findTargetView(view: View, screenX: Float, screenY: Float): View? {
        if (view === this.view) return null
        if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) return null

        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + view.width.toFloat()
        val bottom = top + view.height.toFloat()

        if (screenX < left || screenX > right || screenY < top || screenY > bottom) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val hit = findTargetView(child, screenX, screenY)
                if (hit != null) return hit
            }
        }

        return if (isTargetable(view)) view else null
    }

    private fun isTargetable(view: View): Boolean {
        return view is WebView ||
            view.isClickable ||
            view.isLongClickable ||
            view.hasOnClickListeners() ||
            view is android.widget.EditText
    }

    fun keepVisible() {
        revealCursor()
    }

    fun screenPosition(): Pair<Float, Float> = posX to posY

    val isVisible: Boolean
        get() = view.visibility == View.VISIBLE && view.alpha > 0.05f

    private val frameCallback = Choreographer.FrameCallback {
        framePosted = false
        if (axisX != 0f || axisY != 0f) {
            advance()
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (!framePosted) {
            framePosted = true
            choreo.postFrameCallback(frameCallback)
        }
    }

    private fun advance() {
        val maxPx = 20f * d
        posX = (posX + axisX * abs(axisX) * maxPx).coerceIn(RADIUS, screenW - RADIUS)
        posY = (posY + axisY * abs(axisY) * maxPx).coerceIn(RADIUS, screenH - RADIUS)
        applyPosition()
    }

    private fun applyPosition() {
        view.translationX = posX - RADIUS
        view.translationY = posY - RADIUS
    }

    private fun revealCursor() {
        hideHandler.removeCallbacks(hideTask)
        if (view.visibility != View.VISIBLE) {
            view.alpha      = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(150L).start()
        hideHandler.postDelayed(hideTask, HIDE_DELAY_MS)
    }

    private fun fadeOut() {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(400L)
            .withEndAction { view.visibility = View.INVISIBLE }
            .start()
    }

    inner class CursorView(ctx: Context) : View(ctx) {
        private val den = ctx.resources.displayMetrics.density
        private val r   = 12f * den
        private val sw  = 2.2f * den

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = sw
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
        }

        private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 0
        }
        private var flashAlpha = 0
        private var flashAnim: android.animation.ValueAnimator? = null

        init {
            isClickable = false
            isFocusable = false
            visibility  = View.INVISIBLE
            alpha       = 0f
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun animateClick() {
            flashAnim?.cancel()
            flashAnim = android.animation.ValueAnimator.ofInt(220, 0).apply {
                duration = 260L
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    flashAlpha = anim.animatedValue as Int
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        flashAlpha = 0
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx     = width  / 2f
            val cy     = height / 2f
            val innerR = r - sw / 2f

            if (flashAlpha > 0) {
                flashPaint.alpha = flashAlpha
                canvas.drawCircle(cx, cy, innerR, flashPaint)
            }

            canvas.drawCircle(cx, cy, innerR, borderPaint)

            canvas.drawCircle(cx, cy, 2.4f * den, dotPaint)
        }

        override fun onTouchEvent(e: MotionEvent) = false
    }

    private companion object {
        const val HIDE_DELAY_MS = 3500L
    }
}
