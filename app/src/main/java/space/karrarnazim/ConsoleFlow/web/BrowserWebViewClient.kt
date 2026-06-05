package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

class BrowserWebViewClient(
    private val activity: Activity,
    private val tabId: Int,
    private val prefsManager: PrefsManager,
    private val okClient: OkHttpClient,
    private val currentWebViewProvider: () -> WebView?,
    private val isHomeUrl: (String?) -> Boolean,
    private val isLocalhostHost: (String?) -> Boolean,
    private val noInterceptDomains: List<String>,
    private val onPageStartedUi: (Int, WebView, String?) -> Unit,
    private val onPageFinishedUi: (Int, WebView, String?) -> Unit,
    private val onReceivedErrorUi: (String?) -> Unit,
    private val onApplyConsoleTools: (WebView) -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val url = req.url.toString()
        if (url.startsWith("http") || url.startsWith("file:")) return false
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (view == currentWebViewProvider()) {
            onPageStartedUi(tabId, view, url)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinishedUi(tabId, view, url)
        onApplyConsoleTools(view)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()

        if (url == "https://eruda.local/eruda.js") {
            return try {
                WebResourceResponse("application/javascript", "utf-8", activity.assets.open("eruda.js"))
            } catch (_: Exception) {
                null
            }
        }

        if (prefsManager.getBoolean("disable_intercept", false)) return null

        val host = request.url.host ?: ""
        if (isLocalhostHost(host)) return null
        if (noInterceptDomains.any { host == it || host.endsWith(".$it") }) return null

        if (request.isForMainFrame && request.method == "GET" && url.startsWith("http")) {
            try {
                val ua = WebViewSettingsHelper.getUserAgentString(activity, prefsManager.desktopMode)
                val reqBuilder = Request.Builder().url(url)
                request.requestHeaders.forEach { (k, v) ->
                    if (k.lowercase() != "user-agent") reqBuilder.addHeader(k, v)
                }
                reqBuilder.header("User-Agent", ua)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) reqBuilder.header("Cookie", cookie)

                okClient.newCall(reqBuilder.build()).execute().use { response ->
                    val contentType = response.header("Content-Type", "") ?: ""
                    if (contentType.contains("text/html")) {
                        var html = response.body?.string() ?: ""

                        html = html.replace(
                            Regex("""<meta[^>]+http-equiv=[\"']Content-Security-Policy[\"'][^>]*>""", RegexOption.IGNORE_CASE),
                            ""
                        )
                        val injectedScripts = UserScriptsManager.buildInjectedScripts(prefsManager.consoleEnabled, prefsManager.customJs)
                        html = html.replaceFirst("<head>", "<head>$injectedScripts", ignoreCase = true)
                        val hdrs = response.headers.toMap().toMutableMap()
                        hdrs.remove("Content-Security-Policy")
                        hdrs.remove("content-security-policy")

                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            response.code,
                            "OK",
                            hdrs,
                            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                        )
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
        if (req.isForMainFrame) {
            activity.runOnUiThread { onReceivedErrorUi(req.url.toString()) }
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        AlertDialog.Builder(activity, R.style.DarkDialog)
            .setTitle("SSL Certificate Error")
            .setMessage("The site's security certificate is not trusted. Continue anyway?")
            .setPositiveButton("Continue") { _, _ -> handler.proceed() }
            .setNegativeButton("Go Back") { _, _ -> handler.cancel() }
            .show()
    }
}
