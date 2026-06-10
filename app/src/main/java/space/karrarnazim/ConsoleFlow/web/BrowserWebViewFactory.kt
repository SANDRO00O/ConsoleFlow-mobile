package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import okhttp3.OkHttpClient

class BrowserWebViewFactory(
    private val activity: Activity,
    private val prefsManager: PrefsManager,
    private val okClient: OkHttpClient,
    private val currentWebViewProvider: () -> WebView?,
    private val isHomeUrl: (String?) -> Boolean,
    private val isLocalhostHost: (String?) -> Boolean,
    private val noInterceptDomains: List<String>,
    private val bookmarkRepository: BookmarkRepository,
    private val onOpenNewTab: (String) -> Unit,
    private val onMarkHomeOverlayDirty: () -> Unit,
    private val onInvalidateHomePreviewCache: () -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onSetSwipeRefresh: (Boolean) -> Unit,
    private val onPageStartedUi: (Int, WebView, String?) -> Unit,
    private val onProgressChangedUi: (Int, Int) -> Unit,
    private val onShowCustomViewUi: (View?, WebChromeClient.CustomViewCallback?) -> Unit,
    private val onHideCustomViewUi: () -> Unit,
    private val onPermissionRequestUi: (PermissionRequest?) -> Unit,
    private val onPageFinishedUi: (Int, WebView, String?) -> Unit,
    private val onReceivedIconUi: (Int, Bitmap) -> Unit,
    private val onReceivedErrorUi: (String?) -> Unit,
    private val onApplyConsoleTools: (WebView) -> Unit
) {

    @Suppress("SetJavaScriptEnabled")
    fun create(tabId: Int): WebView {
        val wv = WebView(activity)
        wv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Enable spatial navigation so D-pad/arrow keys move focus between
            // links and form controls inside the page (TV remote / keyboard use)
            @Suppress("DEPRECATION")
            setNeedInitialFocus(true)
        }

        // WebView must be focusable so TV-remote / keyboard events reach it
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        WebViewSettingsHelper.applyUserAgentToWebView(activity, wv, prefsManager.desktopMode)
        wv.addJavascriptInterface(JsBridge(onNavigate, onSetSwipeRefresh), "Android")

        wv.setOnCreateContextMenuListener { _, _, _ ->
            val result = wv.hitTestResult
            if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val url = result.extra ?: return@setOnCreateContextMenuListener
                BrowserDialogHelpers.showModernPopup(activity, url, listOf("Open in New Tab", "Copy Link", "Bookmark Link", "Share")) { index ->
                    when (index) {
                        0 -> {
                            onOpenNewTab(url)
                            Toast.makeText(activity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("URL", url))
                            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            bookmarkRepository.toggleBookmark("Bookmark", url)
                            onMarkHomeOverlayDirty()
                            onInvalidateHomePreviewCache()
                            Toast.makeText(activity, "Bookmarked", Toast.LENGTH_SHORT).show()
                        }
                        3 -> activity.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            },
                            "Share"
                        ))
                    }
                }
            }
        }

        wv.webViewClient = BrowserWebViewClient(
            activity = activity,
            tabId = tabId,
            prefsManager = prefsManager,
            okClient = okClient,
            currentWebViewProvider = currentWebViewProvider,
            isHomeUrl = isHomeUrl,
            isLocalhostHost = isLocalhostHost,
            noInterceptDomains = noInterceptDomains,
            onPageStartedUi = onPageStartedUi,
            onPageFinishedUi = onPageFinishedUi,
            onReceivedErrorUi = onReceivedErrorUi,
            onApplyConsoleTools = onApplyConsoleTools
        )

        wv.webChromeClient = BrowserChromeClient(
            tabId = tabId,
            onProgressChangedUi = onProgressChangedUi,
            onReceivedIconUi = onReceivedIconUi,
            onShowCustomViewUi = onShowCustomViewUi,
            onHideCustomViewUi = onHideCustomViewUi,
            onPermissionRequestUi = onPermissionRequestUi
        )

        return wv
    }
}
