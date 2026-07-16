// ── ConsoleFlow Bridge: content script ──────────────────────────────────────
// يفتح منفذ رسائل native messaging عند تحميل كل صفحة، وينتظر أوامر من
// التطبيق.
//
// ⚠️ اكتشاف حرج بمراجعة عميقة خامسة: eval() من داخل content script في
// Firefox/GeckoView يُحجَب فعلياً بواسطة CSP الصارم للصفحة نفسها (script-src
// بلا 'unsafe-eval') — هذا مؤكَّد بتقرير خلل رسمي من Mozilla
// (bugzilla 1591983) مع نتيجة اختبار حقيقية على github.com بالضبط، وهي
// مشكلة معروفة وما زالت غير محلولة بالكامل (bug 1267027 meta-bug مفتوح).
// هذا بالضبط سبب وجود آلية اعتراض واستبدال CSP في الكود القديم أصلاً —
// فقدناها بترحيل GeckoView.
//
// الحل: الوضع الليلي وviewport لا يحتاجان eval إطلاقاً — مجرد تلاعب DOM
// عادي (appendChild لعنصر style/meta)، وهذا غير خاضع لقيود script-src.
// حوّلتهما لأوامر محدَّدة الاسم بدل eval عام. eruda وحدها تبقى تحتاج eval
// (تحميل مكتبة كاملة) وستفشل على مواقع CSP صارمة — قيد حقيقي موثَّق، ذكرته
// بوضوح بدل التظاهر أنه محلول.
(function () {
  if (window.__cfBridgeInit) return;
  window.__cfBridgeInit = true;

  var port;
  try {
    port = browser.runtime.connectNative("consoleflow");
  } catch (e) {
    return;
  }

  function toggleNightMode() {
    var el = document.getElementById("__cf_night");
    if (el) { el.remove(); return; }
    var s = document.createElement("style");
    s.id = "__cf_night";
    s.textContent = "html{filter:invert(1) hue-rotate(180deg)!important}" +
      "img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}";
    (document.head || document.documentElement).appendChild(s);
  }

  function hideConsole() {
    try { if (window.eruda && eruda.hide) eruda.hide(); } catch (e) {}
    var el = document.getElementById("eruda");
    if (el) { el.style.display = "none"; }
  }

  port.onMessage.addListener(function (msg) {
    if (!msg) return;
    try {
      if (msg.action === "toggleNightMode") { toggleNightMode(); return; }
      if (msg.action === "consoleHide") { hideConsole(); return; }
      // ⚠️ المسار الوحيد المتبقي الذي يحتاج eval فعلياً (تحميل مكتبة eruda
      // لأول مرة، أو إظهارها بعد تحميل سابق — ConsoleToolsInjector يتحقق
      // من window.__erudaInited داخل نص السكربت نفسه). سيفشل بصمت على أي
      // موقع بسياسة CSP تمنع 'unsafe-eval' — قيد حقيقي في Firefox/GeckoView
      // نفسه، ليس خطأ في هذا الكود.
      if (typeof msg.script === "string") {
        try { (0, eval)(msg.script); } catch (e) {}
      }
    } catch (e) {}
  });
})();
