package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * يستبدل BrowserWebViewFactory. الفرق البنيوي الوحيد المهم: GeckoView
 * (الـ View) و GeckoSession (منطق التصفح/الحالة) كائنان منفصلان — الجلسة لا
 * تحتاج View لتعيش (مفيد لاحقاً لو أردنا تحميل تبويبات في الخلفية بلا رسم).
 */
class GeckoSessionFactory(
    private val activity: Activity,
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
) {
    fun create(tabId: Int): GeckoTabSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            // وضع سطح المكتب — يعادل UA switching القديم؛ يُطبَّق أيضاً
            // ديناميكياً لاحقاً عبر tab.session.settings عند تبديل الإعداد
            .userAgentMode(
                if (prefsManager.desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .build()

        val session = GeckoSession(settings)
        session.open(GeckoRuntimeManager.get(activity))

        val geckoView = GeckoView(activity)
        geckoView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        geckoView.setSession(session)
        // مقابل isFocusable/isFocusableInTouchMode القديمة — GeckoView
        // يحتاج نفس الشيء ليستقبل أحداث الريموت/الكيبورد.
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true

        val tab = GeckoTabSession(tabId, session, geckoView)
        GeckoExtensionBridge.attachSession(activity, tab)

        val delegates = GeckoSessionDelegates(
            activity = activity,
            tab = tab,
            prefsManager = prefsManager,
            isHomeUrl = isHomeUrl,
            bookmarkRepository = bookmarkRepository,
            onOpenNewTab = onOpenNewTab,
            onMarkHomeOverlayDirty = onMarkHomeOverlayDirty,
            onInvalidateHomePreviewCache = onInvalidateHomePreviewCache,
            onSetSwipeRefresh = onSetSwipeRefresh,
            onPageStartedUi = onPageStartedUi,
            onPageFinishedUi = onPageFinishedUi,
            onProgressChangedUi = onProgressChangedUi,
            onReceivedIconUi = onReceivedIconUi,
            onReceivedErrorUi = onReceivedErrorUi,
            onApplyConsoleTools = onApplyConsoleTools,
            onDownloadStart = onDownloadStart,
            onAndroidPermissionsNeededUi = onAndroidPermissionsNeededUi,
            onFullScreenUi = onFullScreenUi,
            onShowFileChooserUi = onShowFileChooserUi
        )

        session.navigationDelegate = delegates
        session.progressDelegate = delegates
        session.contentDelegate = delegates
        session.permissionDelegate = delegates
        session.promptDelegate = delegates
        session.scrollDelegate = delegates

        return tab
    }
}
