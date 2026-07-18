# Add project specific ProGuard rules here.

# ⚠️ حُذفت قاعدة @android.webkit.JavascriptInterface القديمة هنا — كانت
# ضرورية لـJsBridge القديم (addJavascriptInterface)، وهذا النمط بالكامل لم
# يعد موجوداً بعد ترحيل GeckoView (الاستبدال: WebExtension + native
# messaging، انظر GeckoExtensionBridge.kt). إبقاؤها كان سيصبح قاعدة ميتة
# بلا أثر فعلي.

# ✅ إضافة احترازية: release_yml.yml هو أول مكان تُفعَّل فيه فعلياً
# minifyEnabled (assembleDebug لا يُفعّلها). GeckoView يُفترض أنه يحمل
# consumer-rules.pro خاصة به داخل الـAAR (نمط قياسي للمكتبات الحديثة)، لكن
# هذه شبكة أمان إضافية — لا ضرر من الإبقاء عليها حتى لو كانت القواعد
# المرفقة كافية وحدها.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Keep ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

# Uncomment to preserve line numbers for debugging
#-keepattributes SourceFile,LineNumberTable