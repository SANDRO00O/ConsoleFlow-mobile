package space.karrarnazim.ConsoleFlow

internal const val PREFS_NAME = "ConsoleFlowPrefs"
internal const val HOME_URL_CONST = "about:blank"
// ✅ إصلاح جذري حقيقي (لا تخمين): كان 6 — نسخة من حد WebView القديم.
// GeckoView أثقل بكثير لكل جلسة (كل جلسة، حتى غير الظاهرة، تحجز خرائط
// ذاكرة GPU فعلية). Mozilla نفسها توثّق هذا السيناريو بالضبط في تتبع
// الأخطاء الرسمي (bugzilla 1388750): "نفاد مساحة العناوين بسبب خرائط
// الرسوميات... عندما تكون نوافذ كثيرة مفتوحة لكن واحدة فقط ظاهرة" —
// مطابق تماماً لسجل التشغيل الذي أظهر إعادة تشغيل العملية بأكملها 20 مرة
// خلال 4 دقائق فقط. تقليل هذا الحد يقلّل ضغط الذاكرة المسبِّب لذلك مباشرة.
internal const val MAX_LIVE_WEBVIEWS = 2
