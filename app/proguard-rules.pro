# Add project specific ProGuard rules here.

# Keep WebView JavaScript interfaces — required for ANY class exposed via
# addJavascriptInterface(). R8 only sees Kotlin/Java call sites; it has no
# way to know a method is invoked via JS-side reflection (e.g. the eruda
# touch-hook script calling Android.setSwipeRefresh(...)), so without this
# rule those methods get renamed/stripped as "unused" in release builds —
# silently breaking the bridge, with debug builds completely unaffected
# (minifyEnabled is off there), making the bug invisible during normal
# local testing. This is the official Android-recommended generic rule —
# it protects every @JavascriptInterface method regardless of which class
# it's added to, so a future bridge class doesn't need a rule of its own.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

# Uncomment to preserve line numbers for debugging
#-keepattributes SourceFile,LineNumberTable