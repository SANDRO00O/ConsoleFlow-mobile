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

/**
 * CursorController — مؤشر افتراضي تحركه عصا التحكم اليسار.
 *
 * آلية العمل:
 *  1. InputController يُغذّيه بقيم المحور عبر updateStick(x, y).
 *  2. Choreographer.FrameCallback يُحدّث posX/posY مرة لكل VSYNC
 *     بمنحنى سرعة تربيعي: بطيء عند المركز، سريع عند الحافة.
 *  3. CursorView حجم ثابت، يُحرَّك بـ translationX/Y فقط —
 *     محتواه يُرسم مرة واحدة ويُركَّب على GPU بدون أي تكلفة CPU.
 *  4. performClick() يُرسل ACTION_DOWN + ACTION_UP حقيقيَّين عبر
 *     Activity.dispatchTouchEvent بإحداثيات المؤشر، فيصل الحدث
 *     لأي View أو WebView يقع تحت المؤشر تماماً كالنقر الحقيقي.
 *  5. CursorView يُعيد false من onTouchEvent فلا يحجب أي نقرة.
 *
 * يختفي تلقائياً بعد 3.5 ثانية من توقف العصا.
 */
class CursorController(private val activity: Activity) {

    private val d      = activity.resources.displayMetrics.density
    private val RADIUS = 12f * d                      // نصف قطر الدائرة بالبكسل
    private val SIZE   = (RADIUS * 2f + 0.5f).toInt() // حجم الـ View بالبكسل

    val view = CursorView(activity)

    private val contentRoot: View
        get() = activity.findViewById(android.R.id.content)

    private var posX    = 0f
    private var posY    = 0f
    private var screenW = 0f
    private var screenH = 0f

    // قيم المحور الحالية (تُكتب وتُقرأ على Main thread فقط)
    private var axisX = 0f
    private var axisY = 0f

    private val choreo      = Choreographer.getInstance()
    private var framePosted = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTask    = Runnable { fadeOut() }

    // ── دورة الحياة ───────────────────────────────────────────────────────

    fun attach() {
        val decor = activity.window.decorView as ViewGroup
        decor.addView(view, FrameLayout.LayoutParams(SIZE, SIZE))
        // نُحدّد أبعاد الشاشة بعد انتهاء layout pass
        decor.post { refreshScreenBounds(centerCursor = true) }
    }

    /**
     * يُعاد قياس أبعاد الشاشة وتحديث حدود حركة المؤشر.
     *
     * BUG-Q FIX: MainActivity يُعلن configChanges لـ orientation/screenSize
     * (لتجنّب إعادة إنشاء الـ Activity وفقدان حالة WebView عند الدوران)،
     * لكن screenW/screenH كانا يُضبطان مرة واحدة فقط في attach() ولا
     * يُحدَّثان أبداً بعد ذلك. نتيجة الدوران: المؤشر يبقى محصوراً بأبعاد
     * الاتجاه القديم — قد يخرج فعلياً عن حدود الشاشة الجديدة أو يُحرَم من
     * الوصول لمساحة الشاشة الجديدة. هذه الدالة تُستدعى من
     * onConfigurationChanged في MainActivity.
     */
    fun refreshScreenBounds(centerCursor: Boolean = false) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val w = decor.width.toFloat()
        val h = decor.height.toFloat()
        if (w <= 0f || h <= 0f) return
        screenW = w
        screenH = h
        posX = if (centerCursor) screenW / 2f else posX.coerceIn(RADIUS, screenW - RADIUS)
        posY = if (centerCursor) screenH / 2f else posY.coerceIn(RADIUS, screenH - RADIUS)
        applyPosition()
    }

    fun detach() {
        choreo.removeFrameCallback(frameCallback)
        hideHandler.removeCallbacks(hideTask)
        (activity.window.decorView as? ViewGroup)?.removeView(view)
    }

    // ── واجهة المدخلات ────────────────────────────────────────────────────

    /** يُستدعى من InputController بقيم عصا اليسار بعد معالجة dead zone. */
    fun updateStick(x: Float, y: Float) {
        axisX = x
        axisY = y
        if (x != 0f || y != 0f) {
            revealCursor()
            scheduleFrame()
        }
    }

    /** يُرسل نقرة حقيقية على موضع المؤشر. يُعيد false إن كان المؤشر مخفياً. */
    fun performClick(): Boolean {
        if (!isVisible) return false

        // نجعل المؤشر غير مرئي فقط أثناء إرسال النقرة حتى لا يلتقط
        // هو نفسه اللمسة، ثم نعيده مباشرةً بعد الإرسال.
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

    /** يرسل نقرة نظامية مباشرة إلى العنصر الواقع تحت المؤشر. */
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

    /** يبحث عن أعمق View قابلة للتفاعل تحت إحداثيات الشاشة المعطاة. */
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

    /** ينعش المؤشر ويمنعه من الاختفاء عند تبديل التبويبات أو القوائم. */
    fun keepVisible() {
        revealCursor()
    }

    /** مركز المؤشر الحالي على الشاشة، بوحدة البكسل. */
    fun screenPosition(): Pair<Float, Float> = posX to posY

    val isVisible: Boolean
        get() = view.visibility == View.VISIBLE && view.alpha > 0.05f

    // ── حلقة Choreographer ────────────────────────────────────────────────

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

    /** يُقدّم المؤشر خطوة واحدة بناءً على قيم العصا الحالية. */
    private fun advance() {
        val maxPx = 20f * d   // أقصى سرعة بالبكسل/إطار عند قيمة ±1.0
        // منحنى تربيعي: sign(v)·v²·maxPx
        posX = (posX + axisX * abs(axisX) * maxPx).coerceIn(RADIUS, screenW - RADIUS)
        posY = (posY + axisY * abs(axisY) * maxPx).coerceIn(RADIUS, screenH - RADIUS)
        applyPosition()
    }

    /** يضع الـ View بحيث يكون مركز الدائرة عند (posX, posY). */
    private fun applyPosition() {
        view.translationX = posX - RADIUS
        view.translationY = posY - RADIUS
    }

    // ── الظهور والاختفاء ──────────────────────────────────────────────────

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

    // ── CursorView ────────────────────────────────────────────────────────
    //
    //  • حجم ثابت (SIZE × SIZE px).
    //  • يُحرَّك بـ translationX/Y — لا invalidate() لكل إطار.
    //  • onTouchEvent يُعيد false → النقرات تمر للـ Views التي تحته.

    inner class CursorView(ctx: Context) : View(ctx) {
        private val den = ctx.resources.displayMetrics.density
        private val r   = 12f * den           // نصف قطر الدائرة
        private val sw  = 2.2f * den          // سماكة البوردر

        // دائرة شفافة ببوردر أبيض
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = sw
        }

        // نقطة مركز بيضاء للدقة في التوجيه
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
        }

        // ومضة داخلية عند النقر
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

        /** ومضة بيضاء داخلية سريعة تؤكد النقر بصرياً. */
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

            // 1. ومضة داخلية (شفاف عادةً، أبيض لحظة النقر)
            if (flashAlpha > 0) {
                flashPaint.alpha = flashAlpha
                canvas.drawCircle(cx, cy, innerR, flashPaint)
            }

            // 2. بوردر أبيض
            canvas.drawCircle(cx, cy, innerR, borderPaint)

            // 3. نقطة مركز للدقة
            canvas.drawCircle(cx, cy, 2.4f * den, dotPaint)
        }

        override fun onTouchEvent(e: MotionEvent) = false
    }

    // ── ثوابت ─────────────────────────────────────────────────────────────

    private companion object {
        const val HIDE_DELAY_MS = 3500L
    }
}
