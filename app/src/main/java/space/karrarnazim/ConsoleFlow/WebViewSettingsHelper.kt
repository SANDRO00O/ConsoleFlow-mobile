package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewSettingsHelper {
    fun getUserAgentString(context: Context, desktopMode: Boolean): String {
        val defaultUA = WebSettings.getDefaultUserAgent(context)
        if (!desktopMode) return defaultUA

        return try {
            val chromeVersion = Regex("Chrome/([0-9.]+)").find(defaultUA)?.value
                ?: "Chrome/124.0.0.0"
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chromeVersion Safari/537.36"
        } catch (_: Exception) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
    }

    fun applyUserAgentToWebView(context: Context, webView: WebView, desktopMode: Boolean) {
        webView.settings.apply {
            userAgentString = getUserAgentString(context, desktopMode)
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
    }
}
