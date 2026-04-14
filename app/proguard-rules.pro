# Add project specific ProGuard rules here.

# Keep JS interface for WebView
-keepclassmembers class space.karrarnazim.ConsoleFlow.MainActivity$SearchBridge {
   public *;
}

# Keep ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

# Uncomment to preserve line numbers for debugging
#-keepattributes SourceFile,LineNumberTable