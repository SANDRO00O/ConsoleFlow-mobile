package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * ── لماذا هذا الملف موجود ──────────────────────────────────────────────────
 *
 * WebView كان كائناً واحداً يفعل كل شيء بشكل متزامن: `wv.canGoBack()`,
 * `wv.title`, `wv.goBack()` تُستدعى وتُرجع القيمة فوراً.
 *
 * GeckoSession مختلف بنيوياً: لا يوجد `canGoBack()` متزامن، ولا `title`
 * property. كل هذه المعلومات تصلك بشكل غير متزامن عبر الـ Delegates
 * (NavigationDelegate.onCanGoBack, ContentDelegate.onTitleChange...).
 *
 * لو حاولنا نستدعي GeckoSession مباشرة من كل مكان في MainActivity (2500+
 * سطر)، كنا سنعيد كتابة نفس منطق الـ caching في كل موقع استدعاء — هذا بالضبط
 * تعريف "الترقيع" (special-casing) الذي طلبت تجنبه.
 *
 * الحل: كائن واحد (`GeckoTabSession`) يخزّن آخر حالة معروفة (title, url,
 * canGoBack, canGoForward, favicon) ويعرض واجهة تُشبه WebView القديم قدر
 * الإمكان — لكن بأسماء صريحة تعكس أنها "آخر قيمة معروفة" وليست استعلاماً
 * حياً. هذا يجعل نقل الاستدعاءات الأربعين في MainActivity عملية آلية وآمنة
 * بدل إعادة تصميم كل موقع استدعاء على حدة.
 */
class GeckoTabSession(
    val tabId: Int,
    val session: GeckoSession,
    val geckoView: GeckoView
) {
    // ── حالة مخزّنة، تُحدَّث فقط من الـ delegates ─────────────────────────
    var title: String? = null
        internal set
    var url: String? = null
        internal set
    var favicon: Bitmap? = null
        internal set
    var canGoBack: Boolean = false
        internal set
    var canGoForward: Boolean = false
        internal set
    var progress: Int = 100
        internal set
    var isLoading: Boolean = false
        internal set

    // ── أوامر تُرسَل للجلسة (fire-and-forget، بلا حاجة لانتظار رد) ────────
    fun loadUrl(newUrl: String) = session.loadUri(newUrl)
    fun goBack() = session.goBack()
    fun goForward() = session.goForward()
    fun reload() = session.reload()
    fun stopLoading() = session.stop()
    fun requestFocus() = geckoView.requestFocus()

    /**
     * ⚠️ فجوة حقيقية اكتُشفت بمراجعة عميقة رابعة: WebView كان يتعامل مع
     * هذا تلقائياً إلى حد كبير (توقّف عرض/رسم التبويبات غير المرئية ضمنياً
     * بحكم عدم استدعاء draw() عليها). GeckoSession مختلف بنيوياً — كل جلسة
     * تبقى "نشطة" (تستهلك ذاكرة ومعالجة) افتراضياً حتى لو لم تكن ظاهرة على
     * الشاشة، ما لم تُستدعَ setActive(false) صراحة. بلا هذا، كل تبويب
     * مفتوح بالخلفية يستمر يشغّل مؤقتات JS ويستهلك موارد — خطير خصوصاً على
     * أجهزة تلفاز قديمة محدودة الذاكرة (بالضبط الجمهور المستهدف لهذا
     * التطبيق).
     */
    fun setActive(active: Boolean) = session.setActive(active)
    fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = geckoView.dispatchKeyEvent(event)

    /**
     * يستبدل WebViewSettingsHelper.applyUserAgentToWebView بالكامل. القديم
     * كان يبني نص User-Agent مزيّف عبر regex لانتحال Chrome على ويندوز.
     * GeckoView يملك دعماً أصلياً لوضع سطح المكتب (UA + viewport معاً) —
     * لا حاجة لأي انتحال نصّي يدوي.
     */
    fun setDesktopMode(enabled: Boolean) {
        session.settings.userAgentMode =
            if (enabled) org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (enabled) org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }

    /**
     * ✅ تأكّد التوقيع فعلياً من توثيق Mozilla الرسمي:
     * PanZoomController.scrollBy(ScreenLength, ScreenLength, int) موجود
     * بالضبط بهذا الشكل.
     */
    fun scrollBy(dx: Int, dy: Int) {
        runCatching {
            session.panZoomController.scrollBy(
                org.mozilla.geckoview.ScreenLength.fromPixels(dx.toDouble()),
                org.mozilla.geckoview.ScreenLength.fromPixels(dy.toDouble()),
                org.mozilla.geckoview.PanZoomController.SCROLL_BEHAVIOR_SMOOTH
            )
        }
    }

    /**
     * الاتجاه السالب (فوق/يسار) يُجاب عليه بدقة الآن عبر scrollY/scrollX
     * الحقيقيين المتتبَّعين من ScrollDelegate. الاتجاه الموجب (تحت/يمين) ما
     * زال بلا إجابة متزامنة حقيقية — هل تبقّى محتوى أسفل الصفحة يحتاج معرفة
     * ارتفاع المستند الكامل، وهذا غير متوفر بدون استعلام JS. نُبقيه true
     * كإجراء احتياطي موثّق (التمرير يُحاوَل دائماً، لا ضرر إن لم يتحرك شيء).
     */
    fun canScrollHorizontally(direction: Int): Boolean = if (direction < 0) scrollX > 0 else true
    fun canScrollVertically(direction: Int): Boolean = if (direction < 0) scrollY > 0 else true

    /**
     * ⚠️ يستبدل النمط القديم `webView.draw(canvas)` لالتقاط صورة مصغرة.
     * GeckoView مركّب عبر SurfaceView على خيط/عملية منفصلة (compositor) —
     * استدعاء draw(canvas) عليه مباشرة سينتج بتمابة فارغة/سوداء، لأن محتوى
     * الصفحة لا يُرسم عبر مسار View.draw() العادي كما كان في WebView.
     * capturePixels() هو المسار الصحيح، لكنه غير متزامن.
     */
    /**
     * ⚠️ إصلاح خطأ حقيقي اكتُشف بعد الإبلاغ عن "كاردات التابويبات UI
     * خربانة": توثيق GeckoView الرسمي يؤكد صراحة أن capturePixels() يفشل
     * بـIllegalStateException لو GeckoSession.isCompositorReady() لا تزال
     * false — حالة شائعة جداً فور فتح تبويب أو التبديل إليه مباشرة، قبل
     * اكتمال أول رسم فعلي. كان هذا الفشل يُبتلَع بصمت (onResult(null))،
     * فتبقى الصورة المصغّرة فارغة أو قديمة إلى الأبد لذلك التبويب. الحل:
     * إعادة محاولة قصيرة بدل الاستسلام من أول فشل.
     */
    /**
     * ⚠️ إصلاح خطأ حقيقي اكتُشف بعد الإبلاغ عن "كاردات التابويبات UI
     * خربانة": توثيق GeckoView الرسمي يؤكد صراحة أن capturePixels() يفشل
     * بـIllegalStateException لو compositor الجلسة لا يزال غير جاهز — حالة
     * شائعة جداً فور فتح تبويب أو التبديل إليه مباشرة، قبل اكتمال أول رسم
     * فعلي. كان هذا الفشل يُبتلَع بصمت (onResult(null))، فتبقى الصورة
     * المصغّرة فارغة أو قديمة إلى الأبد لذلك التبويب. الحل: إعادة محاولة
     * قصيرة بدل الاستسلام من أول فشل.
     *
     * ✅ إصلاح خطأ تصريف حقيقي إضافي: session.isCompositorReady غير
     * متاحة — هي package-private داخل GeckoView نفسه، لا يمكن الوصول لها
     * من كود التطبيق. الفحص المسبق حُذف بالكامل؛ الاعتماد الآن فقط على
     * إعادة المحاولة عند فشل capturePixels() الفعلي (الفرع الاحتياطي أدناه)،
     * وهذا لا يحتاج أي وصول لحالة GeckoView الداخلية.
     */
    fun capturePixels(onResult: (Bitmap?) -> Unit) {
        capturePixelsWithRetry(onResult, attemptsLeft = 5)
    }

    private fun capturePixelsWithRetry(onResult: (Bitmap?) -> Unit, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            onResult(null)
            return
        }
        geckoView.capturePixels()
            .accept(
                { bmp -> onResult(bmp) },
                {
                    // فشل (على الأغلب compositor لسا غير جاهز) — أعد
                    // المحاولة بعد فاصل قصير بدل الاستسلام الفوري.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { capturePixelsWithRetry(onResult, attemptsLeft - 1) },
                        120L
                    )
                }
            )
    }

    // ── البحث داخل الصفحة ──────────────────────────────────────────────────
    // ✅ تأكّد فعلياً من توثيق Mozilla ومصدر SessionFinder.java. اكتشفت هنا
    // خطأً حقيقياً أثناء التحقق: FinderResult.found حقل Boolean (هل تم
    // العثور على تطابق؟) وليس عدد التطابقات — العدد الفعلي في FinderResult.
    // total. صحّحته.
    private var findResultCallback: ((current: Int, total: Int) -> Unit)? = null

    fun setFindListener(callback: (current: Int, total: Int) -> Unit) {
        findResultCallback = callback
    }

    fun findAllAsync(query: String) {
        session.finder.find(query, 0).accept { result ->
            result?.let { findResultCallback?.invoke(it.current, it.total) }
        }
    }

    fun findNext(forward: Boolean) {
        val flags = if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
        session.finder.find(null, flags).accept { result ->
            result?.let { findResultCallback?.invoke(it.current, it.total) }
        }
    }

    fun clearMatches() {
        session.finder.clear()
    }

    // ── موضع التمرير ────────────────────────────────────────────────────────
    // مُحدَّث فعلياً عبر GeckoSession.ScrollDelegate.onScrollChanged (انظر
    // GeckoSessionDelegates) — ليس تخميناً، هذا هو مصدر الحقيقة الحقيقي.
    var scrollX: Int = 0
        internal set
    var scrollY: Int = 0
        internal set
    // ✅ يمنع الوثوق بالقيمة الافتراضية (0) على أنها "تأكيد فعلي بأننا
    // أعلى الصفحة" — انظر استخدامها في MainActivity.setOnChildScrollUpCallback.
    var hasConfirmedScrollPosition: Boolean = false
        internal set

    // ── استمرارية حالة التبويب ──────────────────────────────────────────────
    // يُحدَّث تلقائياً من GeckoSession.ProgressDelegate.onSessionStateChange —
    // Gecko يدفع هذا كلما تغيّرت الحالة (تاريخ، تمرير، بيانات نماذج)، بدل ما
    // نستطلعه يدوياً عبر saveState() في اللحظة الأخيرة (وهذا كان المستحيل
    // فعله بشكل متزامن في onSaveInstanceState — هذا هو الحل). Mozilla أزالت
    // دعم Parcelable في GeckoSession تحديداً لصالح هذا المسار الموثّق رسمياً.
    var sessionStateJson: String? = null
        internal set

    // ── منفذ رسائل WebExtension (يُملأ من GeckoExtensionBridge عند الاتصال) ──
    var bridgePort: org.mozilla.geckoview.WebExtension.Port? = null

    /**
     * ⚠️ إصلاح خطأ جذري: كانت هذه الدالة تستخدم
     * session.loadUri("javascript:...") على افتراض غير محقَّق منه أن
     * GeckoView يدعم javascript: URIs مثل WebView القديم. بعد بحث عميق في
     * توثيق Mozilla الرسمي، لا يوجد أي دليل على ذلك — ولا توجد أي دالة
     * evaluateJavascript في GeckoSession إطلاقاً. الطريقة الصحيحة الوحيدة
     * الموثّقة رسمياً هي WebExtension + native messaging (انظر
     * GeckoExtensionBridge.kt). التوقيع الخارجي لهذه الدالة لم يتغيّر، حتى
     * لا يحتاج أي موقع استدعاء حالي (ConsoleToolsInjector، الوضع الليلي،
     * viewport سطح المكتب) أي تعديل.
     */
    fun evaluateJs(script: String) {
        GeckoExtensionBridge.runScript(this, script)
    }

    fun destroy() {
        session.close()
    }
}
