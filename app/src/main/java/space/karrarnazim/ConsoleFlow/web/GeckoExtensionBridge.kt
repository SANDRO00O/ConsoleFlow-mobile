package space.karrarnazim.ConsoleFlow

import android.content.Context
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * ── لماذا هذا الملف موجود ──────────────────────────────────────────────────
 *
 * اكتشفت عبر مراجعة عميقة أن `GeckoTabSession.evaluateJs()` القديم (المبني
 * على `session.loadUri("javascript:...")`) على الأغلب لا يعمل إطلاقاً —
 * GeckoView ليس فيه أي evaluateJavascript()، ولا وجدت أي دليل موثّق أن
 * javascript: URI تُنفَّذ عبر loadUri/load. الطريقة الموثّقة رسمياً الوحيدة
 * لتنفيذ جافاسكربت داخل صفحة هي WebExtension + native messaging.
 *
 * هذا الملف يسجّل إضافة مدمجة صغيرة (assets/consoleflow-bridge/) مرة واحدة
 * لكل GeckoRuntime، ثم يربط منفذ رسائل (Port) بكل GeckoTabSession على حدة
 * فور اتصال content.js من تلك الصفحة تحديداً — بحيث `evaluateJs()` يصبح
 * إرسال رسالة عبر ذلك المنفذ بدل استدعاء ملاحي وهمي.
 *
 * تحقّقت من هذا التصميم بالكامل عبر توثيق Mozilla الرسمي مباشرة، بما في
 * ذلك اسم نوع الـdelegate الدقيق (WebExtension.MessageDelegate — كلاس
 * علوي، وليس متداخلاً تحت SessionController كما ظننت في محاولة أولى
 * صحّحتها بعد تحقق أعمق) وتوقيع WebExtension.Port.postMessage(JSONObject).
 */
object GeckoExtensionBridge {
    private const val EXTENSION_LOCATION = "resource://android/assets/consoleflow-bridge/"
    private const val EXTENSION_ID = "bridge@consoleflow.karrarnazim.space"
    private const val NATIVE_APP = "consoleflow"

    @Volatile private var extension: WebExtension? = null
    @Volatile private var installing = false
    private val pendingCallbacks = mutableListOf<(WebExtension) -> Unit>()

    @Synchronized
    private fun ensureInstalled(context: Context, onReady: (WebExtension) -> Unit) {
        extension?.let { onReady(it); return }
        pendingCallbacks.add(onReady)
        if (installing) return
        installing = true

        // ensureBuiltIn (بدل installBuiltIn) يتجنّب إعادة التثبيت في كل
        // إقلاع — الإضافة تبقى مسجَّلة طالما التطبيق مثبَّت.
        GeckoRuntimeManager.get(context).webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        synchronized(this) {
                            extension = ext
                            pendingCallbacks.forEach { it(ext) }
                            pendingCallbacks.clear()
                        }
                    }
                    installing = false
                },
                { installing = false }
            )
    }

    /**
     * يربط جلسة تبويب معيّنة بالإضافة، بحيث أي اتصال يفتحه content.js لتلك
     * الصفحة تحديداً يُخزَّن كـ Port داخل GeckoTabSession نفسها.
     *
     * ✅ صُحِّح بعد تحقق أعمق: النوع الصحيح هو WebExtension.MessageDelegate
     * (كلاس علوي)، وليس WebExtension.SessionController.MessageDelegate كما
     * كتبته في المحاولة الأولى — SessionController مجرد كلاس تحكّم يملك
     * دالة setMessageDelegate، وليس مجالاً يحوي نوع delegate خاصاً به.
     * تأكّدت من هذا عبر توثيق Mozilla الرسمي مباشرة (WebExtension.
     * MessageDelegate javadoc + مثال web-extensions.html الحرفي).
     */
    fun attachSession(context: Context, tab: GeckoTabSession) {
        ensureInstalled(context) { ext ->
            tab.session.webExtensionController.setMessageDelegate(
                ext,
                object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        tab.bridgePort = port
                    }
                },
                NATIVE_APP
            )
        }
    }

    /** يرسل نص سكربت لتنفيذه داخل الصفحة الحالية لهذا التبويب، إن وُجد منفذ متصل.
     *  ⚠️ يعتمد على eval داخل content script — سيفشل بصمت على مواقع بـCSP
     *  صارم يمنع 'unsafe-eval' (قيد حقيقي في Firefox موثَّق عبر bugzilla
     *  1591983، وليس خللاً في هذا الكود). استخدم toggleNightMode/
     *  setDesktopViewport أدناه للميزات التي لا تحتاج eval أصلاً. */
    fun runScript(tab: GeckoTabSession, script: String) {
        val port = tab.bridgePort ?: return
        runCatching {
            port.postMessage(JSONObject().put("script", script))
        }
    }

    /** لا يحتاج eval — استدعاء مباشر لدالة eruda.hide() المعرَّفة أصلاً، غير خاضع لقيود CSP. */
    fun hideConsole(tab: GeckoTabSession) {
        val port = tab.bridgePort ?: return
        runCatching { port.postMessage(JSONObject().put("action", "consoleHide")) }
    }

    /** لا يحتاج eval — تلاعب DOM بحت (appendChild لعنصر style)، غير خاضع لقيود CSP. */
    fun toggleNightMode(tab: GeckoTabSession) {
        val port = tab.bridgePort ?: return
        runCatching { port.postMessage(JSONObject().put("action", "toggleNightMode")) }
    }
}
