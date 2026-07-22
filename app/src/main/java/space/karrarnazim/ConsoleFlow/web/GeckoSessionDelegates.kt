package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * ── ما حل محله هذا الملف ───────────────────────────────────────────────────
 *
 * BrowserWebViewClient القديم كان يعترض *كل* طلب GET من الصفحة الرئيسية،
 * يُعيد جلبه يدوياً عبر OkHttp، يُعدّل نص الـ HTML (حذف CSP، حقن سكربتات)،
 * ثم يُعيد بناء استجابة كاملة يدوياً — وهذا بالضبط ما سبّب Content-Length
 * القديم المُكتشَف سابقاً، وأيضاً يعني أن كل صفحة تُجلَب مرتين (مرة عبر
 * OkHttp، ومرة عبر شبكة WebView الفعلية للموارد الفرعية).
 *
 * مع GeckoView لا حاجة لأي من هذا: الحقن يتم بعد اكتمال تحميل الصفحة عبر
 * `evaluateJs()` (انظر GeckoTabSession) — بلا لمس شبكي، بلا ترقيع نصّي،
 * بلا تكرار جلب. هذا تبسيط حقيقي وليس مجرد نقل نفس المنطق لملف آخر.
 */
class GeckoSessionDelegates(
    private val activity: Activity,
    private val tab: GeckoTabSession,
    private val prefsManager: PrefsManager,
    private val isHomeUrl: (String?) -> Boolean,
    private val bookmarkRepository: BookmarkRepository,
    private val onOpenNewTab: (String) -> Unit,
    private val onMarkHomeOverlayDirty: () -> Unit,
    private val onInvalidateHomePreviewCache: () -> Unit,
    private val onSetSwipeRefresh: (Boolean) -> Unit,
    private val onPageStartedUi: (GeckoTabSession) -> Unit,
    private val onPageFinishedUi: (GeckoTabSession) -> Unit,
    private val onProgressChangedUi: (GeckoTabSession) -> Unit,
    private val onReceivedIconUi: (GeckoTabSession) -> Unit,
    private val onReceivedErrorUi: (String?) -> Unit,
    private val onApplyConsoleTools: (GeckoTabSession) -> Unit,
    private val onDownloadStart: (url: String, contentType: String?, contentLength: Long, filename: String?) -> Unit,
    private val onAndroidPermissionsNeededUi: (Array<String>, (Boolean) -> Unit) -> Unit,
    private val onFullScreenUi: (Boolean) -> Unit,
    private val onShowFileChooserUi: (Array<String>, (List<android.net.Uri>?) -> Unit) -> Unit
) : GeckoSession.NavigationDelegate,
    GeckoSession.ProgressDelegate,
    GeckoSession.ContentDelegate,
    GeckoSession.PermissionDelegate,
    GeckoSession.PromptDelegate,
    GeckoSession.ScrollDelegate {

    // ── NavigationDelegate ──────────────────────────────────────────────────

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
    ) {
        timber.log.Timber.tag("ConsoleFlowNav").i("onLocationChange tab=%s url=%s", tab.tabId, url)
        tab.url = url
        onPageStartedUi(tab)
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        tab.canGoBack = canGoBack
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        tab.canGoForward = canGoForward
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        val url = request.uri
        timber.log.Timber.tag("ConsoleFlowNav").i(
            "onLoadRequest tab=%s url=%s isHome=%s triggeredByRedirect=%s",
            tab.tabId, url, isHomeUrl(url), request.isRedirect
        )
        // ✅ إصلاح خطأ حقيقي: about:blank (رابط الشاشة الرئيسية الداخلي)
        // كان يقع ضمن هذا الشرط بالغلط — لا يبدأ بـ"http"، فكان يُعامَل
        // بالضبط مثل intent://‏ أو market://‏ ويُسلَّم للنظام في كل مرة
        // تُحمَّل فيها الشاشة الرئيسية (بدء التطبيق، إغلاق تبويب، الرجوع
        // للرئيسية) — هذا بالضبط ما كان يُظهر معرض "اختر متصفحاً" بشكل
        // متكرر. about:blank يجب معالجته داخلياً دائماً، مثله مثل http(s).
        if (isHomeUrl(url)) {
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }
        // روابط غير http(s) (مثل intent://, market://, mailto:) نُسلّمها
        // للنظام مباشرة — نفس منطق shouldOverrideUrlLoading القديم تماماً.
        if (!url.startsWith("http")) {
            return try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                GeckoResult.fromValue(AllowOrDeny.DENY)
            } catch (_: Exception) {
                GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        // رابط target="_blank" أو window.open() — يفتح تبويباً جديداً بدل
        // إنشاء جلسة معزولة لا يمتلكها أحد.
        onOpenNewTab(uri)
        return GeckoResult.fromValue(null)
    }

    /**
     * ✅ إصلاح خطأ معماري حقيقي اكتُشف بعد بلاغات "وميض/تخربط عند فشل
     * التحميل": onLoadError لم يكن مُطبَّقاً إطلاقاً هنا. هذه هي نقطة
     * التحكّم الموثّقة رسمياً الوحيدة لما يحدث عند فشل تحميل صفحة —
     * وبدونها، GeckoView كان يتصرّف بسلوكه الداخلي الافتراضي (غير معروف
     * بالضبط ماذا يعرض) في نفس الوقت الذي يعرض فيه تطبيقنا شاشة الخطأ
     * النيتف الخاصة به (عبر onPageStop success=false → onReceivedErrorUi).
     * نظامان منفصلان يتعاملان مع نفس الفشل بلا تنسيق بينهما — تفسير منطقي
     * قوي لما وُصِف من ظهور/اختفاء متكرر لواجهة الخطأ. الإرجاع الصريح لـ
     * null هنا (موثَّق رسمياً بأنه "يوقف التحميل تماماً بلا محتوى بديل")
     * يجعل شاشة الخطأ النيتف لدينا هي المصدر الوحيد والحصري لعرض الفشل.
     */
    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: org.mozilla.geckoview.WebRequestError
    ): GeckoResult<String>? {
        // 🔎 تسجيل تشخيصي مقصود: لا نعرف بعد السبب *الحقيقي* لفشل التحميل
        // المتقطّع أثناء البحث — بلا هذا، أي إصلاح إضافي سيكون تخميناً.
        // WebRequestError.category/code هما المصدر الرسمي الوحيد لمعرفة
        // نوع الفشل الفعلي (شبكة؟ أمان/شهادة؟ رابط مشوَّه؟). صرِّح للمستخدم
        // بتشغيل `adb logcat -s ConsoleFlowLoadError` أثناء إعادة إنتاج
        // المشكلة، وأرسل النتيجة — عندها الإصلاح يكون مبنياً على الحقيقة
        // لا التخمين.
        timber.log.Timber.tag("ConsoleFlowLoadError").e(
            "uri=%s category=%s code=%s", uri, error.category, error.code
        )
        return GeckoResult.fromValue(null)
    }

    // ── ProgressDelegate ────────────────────────────────────────────────────

    override fun onPageStart(session: GeckoSession, url: String) {
        timber.log.Timber.tag("ConsoleFlowNav").i("onPageStart tab=%s url=%s", tab.tabId, url)
        tab.isLoading = true
        tab.progress = 0
        tab.hasConfirmedScrollPosition = false
        onProgressChangedUi(tab)
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        timber.log.Timber.tag("ConsoleFlowNav").i("onPageStop tab=%s success=%s url=%s", tab.tabId, success, tab.url)
        tab.isLoading = false
        tab.progress = 100
        onSetSwipeRefresh(false)
        onProgressChangedUi(tab)
        onPageFinishedUi(tab)
        if (success) {
            onApplyConsoleTools(tab)
        } else {
            onReceivedErrorUi(tab.url)
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        tab.progress = progress
        onProgressChangedUi(tab)
    }

    override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
        // نمط الدفع الموثّق رسمياً لاستمرارية الحالة — بديل saveState()
        // اليدوي غير المتزامن. يُستدعى تلقائياً كلما تغيّرت الحالة الفعلية.
        tab.sessionStateJson = state.toString()
    }

    override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
    ) {
        // GeckoView تحقق من الشهادة عبر مخزن NSS الخاص به (لا يعتمد على
        // مخزن ثقة النظام القديم) — هذا هو الإصلاح الفعلي لمشكلة "SSL غير
        // موثوق رغم أنه موثوق" على أجهزة Android 5.1.1. لا حاجة لأي حوار
        // "المتابعة رغم الخطر" يدوي هنا كما كان في BrowserWebViewClient —
        // GeckoView يعرض تحذيره الأصلي تلقائياً عند securityInfo.isException.
    }

    // ── ContentDelegate ─────────────────────────────────────────────────────

    override fun onTitleChange(session: GeckoSession, title: String?) {
        tab.title = title
        onReceivedIconUi(tab) // يُحدّث عناصر واجهة التبويب (نفس تجميع onPageFinishedUi سابقاً)
    }

    override fun onCloseRequest(session: GeckoSession) {
        // صفحة نفّذت window.close() على نفسها — أقرب مكافئ لعدم فعل شيء في
        // WebView (لم يكن مُعالَجاً سابقاً، نتركه كذلك عمداً).
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        onFullScreenUi(fullScreen)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
    ) {
        val url = element.linkUri ?: element.srcUri ?: return
        BrowserDialogHelpers.showModernPopup(
            activity, url, listOf("Open in New Tab", "Copy Link", "Bookmark Link", "Share")
        ) { index ->
            when (index) {
                0 -> onOpenNewTab(url)
                1 -> (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("URL", url))
                2 -> {
                    bookmarkRepository.toggleBookmark("Bookmark", url)
                    onMarkHomeOverlayDirty()
                    onInvalidateHomePreviewCache()
                }
                3 -> activity.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
                    }, "Share"
                ))
            }
        }
    }

    override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
        // ⚠️ إصلاح خطأ حقيقي: كنت أدّعي في رد سابق أني صحّحت هذا لاستخدام
        // WebResponse بدل WebResponseInfo (المُهمَل، وأصلاً غير موجود بهذا
        // الاسم) — لكن التعديل ما طُبِّق فعلياً بالكود. WebResponse الحقيقي
        // لا يمنحنا contentType/filename جاهزين كحقول مباشرة كما توهّمت —
        // لازم نستخرجهما من خريطة headers يدوياً، بنفس أسلوب URLUtil.
        // guessFileName القديم الذي كنت حذفته من MainActivity على أساس أنه
        // لم يعد ضرورياً (كان هذا خطأ مبني على الافتراض غير الصحيح أعلاه).
        val contentType = response.headers["Content-Type"]?.substringBefore(";")?.trim()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        val contentDisposition = response.headers["Content-Disposition"]
        val filename = android.webkit.URLUtil.guessFileName(response.uri, contentDisposition, contentType)
        onDownloadStart(response.uri, contentType, contentLength, filename)
    }

    override fun onCrash(session: GeckoSession) {
        onReceivedErrorUi(tab.url)
    }

    // ── PermissionDelegate ──────────────────────────────────────────────────

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        val needed = permissions?.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.orEmpty()

        if (needed.isEmpty()) {
            callback.grant()
            return
        }
        onAndroidPermissionsNeededUi(needed.toTypedArray()) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int>? {
        // أذونات الموقع نفسه (كاميرا/مايك على مستوى origin، لا أذونات
        // Android) — نسمح بها بشكل افتراضي مطابق لسلوك WebView القديم
        // الذي لم يكن يميّز بين الاثنين أصلاً.
        return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
    }

    // ── PromptDelegate ───────────────────────────────────────────────────────

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        // ✅ تأكّد التوقيع فعلياً: FilePrompt.confirm(Context, Uri[]) موجود
        // بالضبط بهذا الشكل في توثيق Mozilla الرسمي.
        // ✅ إصلاح خطأ تصريف حقيقي: الاسم الصحيح mimeTypes (جمع)، وليس
        // mimeType — تأكّدت منه من كود اختبارات GeckoView المصدري نفسه.
        onShowFileChooserUi(prompt.mimeTypes ?: emptyArray()) { uris ->
            if (uris.isNullOrEmpty()) {
                result.complete(prompt.dismiss())
            } else {
                result.complete(prompt.confirm(activity, uris.toTypedArray()))
            }
        }
        return result
    }

    // ── ScrollDelegate ───────────────────────────────────────────────────────

    override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
        // ✅ تحديث: هذا التوقيع صرّف بنجاح فعلياً على GeckoView 150.x
        // المثبَّتة — خطر الانجراف المذكور سابقاً لم يتحقق، الواجهة الحقيقية
        // لهذه النسخة لا تزال تستقبل (scrollX, scrollY) منفصلين.
        tab.scrollX = scrollX
        tab.scrollY = scrollY
        tab.hasConfirmedScrollPosition = true
        tab.scrollX = scrollX
        tab.scrollY = scrollY
    }
}
