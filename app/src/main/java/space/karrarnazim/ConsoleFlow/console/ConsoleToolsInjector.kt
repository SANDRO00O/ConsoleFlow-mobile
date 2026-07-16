package space.karrarnazim.ConsoleFlow

/**
 * يستبدل UserScriptsManager القديم بالكامل. القديم كان يبني وسوم <script>
 * كنص، يحقنها داخل جسم الـ HTML عبر regex بحث عن <head>، ثم يُعيد بناء
 * استجابة HTTP كاملة (وهذا ما سبّب خلل Content-Length). لا حاجة لأي من هذا:
 * GeckoTabSession.evaluateJs() يُرسل السكربت عبر منفذ WebExtension (انظر
 * GeckoExtensionBridge.kt) — بلا لمس شبكي، بلا ترقيع نصّي، بلا تكرار جلب.
 */
object ConsoleToolsInjector {

    fun apply(tab: GeckoTabSession, consoleEnabled: Boolean, customJs: String) {
        if (consoleEnabled) {
            injectEruda(tab)
        } else {
            // ✅ إصلاح إضافي بمراجعة عميقة: هذا كان يمر عبر eval (عرضة
            // لحجب CSP)، رغم أن إخفاء الكونسول لا يحتاج eval إطلاقاً — مجرد
            // استدعاء دالة eruda.hide() المعرَّفة أصلاً. حوّلته لمسار
            // GeckoExtensionBridge.hideConsole الآمن من CSP بالكامل.
            GeckoExtensionBridge.hideConsole(tab)
        }
        if (customJs.isNotEmpty()) tab.evaluateJs(customJs)
    }

    private fun injectEruda(tab: GeckoTabSession) {
        // ملاحظة: eruda.js كان يُحقَن سابقاً عبر اعتراض طلب شبكي وهمي
        // (https://eruda.local/eruda.js) يُقرأ من assets. مع GeckoView لا
        // يوجد shouldInterceptRequest مكافئ بنفس البساطة؛ الطريقة الصحيحة
        // هي حقن محتوى eruda.js كنص كامل مباشرة بدل الاعتماد على اعتراض
        // شبكي وهمي — أبسط وأكثر موثوقية لأنها لا تفترض أن الصفحة ستطلب
        // ذلك المسار الوهمي أصلاً.
        val erudaSource = ErudaAssetCache.getOrLoad()
        tab.evaluateJs(
            erudaSource +
                ";(function(){if(window.__erudaInited){try{eruda.show();" +
                "window.__cfConsoleEnabled=true;}catch(e){};return;}" +
                "try{eruda.init();window.__erudaInited=true;" +
                "window.__cfConsoleEnabled=true;}catch(e){}})()"
        )
    }
}

/**
 * كاش بسيط لمحتوى eruda.js حتى لا نقرأه من assets في كل تحميل صفحة —
 * الملف ثابت طوال عمر التطبيق.
 */
object ErudaAssetCache {
    private var cached: String? = null

    fun getOrLoad(): String {
        cached?.let { return it }
        // يُملأ فعلياً من MainActivity عبر preload(context) عند onCreate —
        // انظر التعليمات في رسالة التسليم.
        return cached ?: ""
    }

    fun preload(assetText: String) {
        cached = assetText
    }
}
