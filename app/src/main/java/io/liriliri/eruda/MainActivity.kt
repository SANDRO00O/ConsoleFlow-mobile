package io.liriliri.eruda

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

// https://github.com/mengkunsoft/MkBrowser
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnStart: ImageView
    private lateinit var btnGoBack: ImageView
    private lateinit var btnGoForward: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var favicon: ImageView
    private lateinit var manager: InputMethodManager
    private val TAG = "Eruda.MainActivity"
    private val HOME_URL = "file:///android_asset/home.html"
    var mFilePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        initView()
        initWebView()
    }

    private fun initView() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        favicon = findViewById(R.id.webIcon)
        btnStart = findViewById(R.id.btnStart)
        btnGoBack = findViewById(R.id.goBack)
        btnGoForward = findViewById(R.id.goForward)
        btnHome = findViewById(R.id.goHome)

        btnStart.setOnClickListener {
            if (textUrl.hasFocus()) {
                dismissKeyboard()
                var input = textUrl.text.toString()
                if (!isHttpUrl(input)) {
                    if (mayBeUrl(input)) {
                        input = "https://${input}"
                    } else {
                        try {
                            input = URLEncoder.encode(input, "utf-8")
                        } catch (e: UnsupportedEncodingException) {
                            Log.e(TAG, e.message.toString())
                        }
                        input = "https://www.google.com/search?q=${input}"
                    }
                }
                webView.loadUrl(input)
                textUrl.clearFocus()
            } else {
                webView.reload()
            }
        }

        btnGoBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnGoForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        btnHome.setOnClickListener {
            dismissKeyboard()
            webView.loadUrl(HOME_URL)
        }

        textUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val currentUrl = webView.url ?: ""
                if (currentUrl != HOME_URL && isHttpUrl(currentUrl)) {
                    textUrl.setText(currentUrl)
                } else {
                    textUrl.setText("")
                }
                textUrl.setSelection(textUrl.text.length)
                btnStart.setImageResource(R.drawable.arrow_right)
            } else {
                if (webView.url == HOME_URL) {
                    textUrl.setText("")
                } else {
                    textUrl.setText(webView.title)
                }
                btnStart.setImageResource(R.drawable.refresh)
            }
        }

        textUrl.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                btnStart.callOnClick()
                textUrl.clearFocus()
            }
            return@setOnKeyListener false
        }
    }

    private fun dismissKeyboard() {
        textUrl.clearFocus()
        if (manager.isActive) {
            manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun initWebView() {
        webView.addJavascriptInterface(SearchBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isHttpUrl(url)) return false
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    false
                } catch (e: Exception) {
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.isForMainFrame) {
                    val url = request.url.toString()
                    if (!isHttpUrl(url)) return null
                    Log.i(TAG, "Loading url: $url")
                    var headers = request.requestHeaders.toHeaders()
                    val contentType = headers["content-type"]
                    if (contentType == "application/x-www-form-urlencoded") return null
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) {
                        headers = (headers.toMap() + Pair("cookie", cookie)).toHeaders()
                    }
                    val client = OkHttpClient.Builder().followRedirects(false).build()
                    val req = Request.Builder().url(url).headers(headers).build()
                    return try {
                        val response = client.newCall(req).execute()
                        if (response.headers["content-security-policy"] == null) return null
                        val resHeaders =
                            response.headers.toMap().filter { it.key != "content-security-policy" }
                        WebResourceResponse(
                            "text/html",
                            response.header("content-encoding", "utf-8"),
                            response.code,
                            "ok",
                            resHeaders,
                            response.body?.byteStream()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, e.message.toString())
                        null
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                if (url == HOME_URL) {
                    setTextUrl("")
                } else {
                    setTextUrl("Loading...")
                    this@MainActivity.favicon.setImageResource(R.drawable.tool)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.INVISIBLE
                title = view.title

                if (url == HOME_URL) {
                    setTextUrl("")
                    return
                }

                setTextUrl(view.title)

                val script = """
                    (function () {
                        if (window.eruda) return;
                        var define;
                        if (window.define) {
                            define = window.define;
                            window.define = null;
                        }
                        var script = document.createElement('script');
                        script.src = '//cdn.jsdelivr.net/npm/eruda';
                        document.body.appendChild(script);
                        script.onload = function () {
                            eruda.init();
                            if (define) window.define = define;
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(script) {}
            }
        }

        val selectFileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    )
                    mFilePathCallback = null
                }
            }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                super.onReceivedIcon(view, icon)
                favicon.setImageBitmap(icon)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(null)
                    mFilePathCallback = null
                }
                mFilePathCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                return try {
                    selectFileLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    mFilePathCallback = null
                    false
                }
            }
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        if (resources.getString(R.string.mode) == "night") {
            val supportForceDarkStrategy =
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
            val supportForceDark = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
            if (supportForceDarkStrategy && supportForceDark) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }

        webView.loadUrl(HOME_URL)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    if (manager.isActive) {
                        manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setTextUrl(text: String?) {
        if (!textUrl.hasFocus() && text != null) {
            textUrl.setText(text)
        }
    }

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) {
            var url = input.trim()
            if (url.isEmpty()) return
            if (!isHttpUrl(url)) {
                url = if (mayBeUrl(url)) {
                    "https://$url"
                } else {
                    "https://www.google.com/search?q=${
                        try { URLEncoder.encode(url, "utf-8") }
                        catch (e: UnsupportedEncodingException) { url }
                    }"
                }
            }
            val finalUrl = url
            runOnUiThread { webView.loadUrl(finalUrl) }
        }
    }
}

fun isHttpUrl(url: String): Boolean {
    return url.startsWith("http:") || url.startsWith("https:")
}

fun mayBeUrl(text: String): Boolean {
    val domains = arrayOf(".com", ".io", ".me", ".org", ".net", ".tv", ".cn", ".dev", ".app")
    return domains.any { text.contains(it) }
}
