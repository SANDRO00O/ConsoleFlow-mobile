package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Patterns
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var fabEruda: View
    private lateinit var findBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    
    private lateinit var prefsManager: PrefsManager
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null

    private val HOME_URL = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"
    
    // Permission launcher للميكروفون والكاميرا
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)
        
        initViews()
        setupWebView()
        setupListeners()
        setupFabDrag()
        
        // استعادة الحالة عند تغيير اتجاه الشاشة
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

        // التعامل مع زر الرجوع في النظام
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        btnBookmark = findViewById(R.id.btnBookmark)
        fabEruda = findViewById(R.id.fabEruda)
        findBar = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        updateUserAgent()

        webView.addJavascriptInterface(SearchBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("file:")) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) { true }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                textUrl.setText(url)
                updateBookmarkIcon(url ?: "")
                fabEruda.visibility = if (url == HOME_URL || url?.startsWith(ERROR_URL) == true) View.GONE else View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.INVISIBLE
                url?.let {
                    if (it != HOME_URL) prefsManager.addHistory(view?.title ?: "Unknown", it)
                }
                // Fallback لتشغيل Eruda للمواقع التي تم تجاوزها في Intercept (مثل POST)
                view?.evaluateJavascript("if(!window.eruda) { var script = document.createElement('script'); script.src = 'https://eruda.local/eruda.js'; document.head.appendChild(script); script.onload = function() { eruda.init(); }; }", null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                
                // تقديم Eruda محلياً كأنه قادم من سيرفر لضمان تجنب مشاكل الـ CORS
                if (url == "https://eruda.local/eruda.js") {
                    return try {
                        val stream = assets.open("eruda.js")
                        WebResourceResponse("application/javascript", "utf-8", stream)
                    } catch (e: Exception) { null }
                }

                // حقن ذكي: نحقن سكريبت Eruda في بداية صفحات الـ HTML لتسجيل الأخطاء المبكرة
                if (request.isForMainFrame && request.method == "GET" && url.startsWith("http")) {
                    try {
                        val reqBuilder = Request.Builder().url(url)
                        request.requestHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (cookie != null) reqBuilder.addHeader("Cookie", cookie)

                        val response = client.newCall(reqBuilder.build()).execute()
                        val contentType = response.header("Content-Type", "") ?: ""
                        
                        if (contentType.contains("text/html")) {
                            var html = response.body?.string() ?: ""
                            val injection = "<script src=\"https://eruda.local/eruda.js\"></script><script>eruda.init();</script>"
                            val customJs = if (prefsManager.customJs.isNotEmpty()) "<script>${prefsManager.customJs}</script>" else ""
                            
                            html = html.replaceFirst("<head>", "<head>$injection$customJs", ignoreCase = true)
                            val inputStream = ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            
                            return WebResourceResponse("text/html", response.header("Content-Encoding", "utf-8"), response.code, "OK", response.headers.toMap(), inputStream)
                        }
                    } catch (e: Exception) {
                        return null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadUrl("$ERROR_URL?url=${URLEncoder.encode(request.url.toString(), "UTF-8")}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("SSL Certificate Error")
                    .setMessage("The site's security certificate is not trusted. Continue anyway?")
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Go Back") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // دعم الـ Fullscreen (فيديو يوتيوب وغيرها)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            // طلبات الصلاحيات (كاميرا، ميكروفون، موقع)
            override fun onPermissionRequest(request: PermissionRequest) {
                webPermissionRequest = request
                val androidPerms = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPerms.add(Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPerms.add(Manifest.permission.CAMERA)
                    }
                }
                if (androidPerms.isNotEmpty()) {
                    requestPermissionLauncher.launch(androidPerms.toTypedArray())
                } else {
                    request.grant(request.resources)
                }
            }
        }

        // دعم التنزيلات
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // إيقاف التحديث عند السحب إذا كان المستخدم لم يصل لأعلى الصفحة
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = webView.scrollY == 0
        }

        textUrl.setOnEditorActionListener { _, _, _ ->
            val input = textUrl.text.toString().trim()
            val finalUrl = if (Patterns.WEB_URL.matcher(input).matches()) {
                if (!input.startsWith("http")) "https://$input" else input
            } else {
                prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
            }
            webView.loadUrl(finalUrl)
            hideKeyboard()
            true
        }

        // نسخ الرابط عند الضغط مطولاً
        textUrl.setOnLongClickListener {
            val popup = PopupMenu(this, textUrl)
            popup.menu.add("Copy URL").setOnMenuItemClickListener {
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("URL", webView.url))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                true
            }
            popup.menu.add("Share URL").setOnMenuItemClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, webView.url)
                }
                startActivity(Intent.createChooser(share, "Share URL"))
                true
            }
            popup.show()
            true
        }

        findViewById<View>(R.id.goBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.goForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.goHome).setOnClickListener { webView.loadUrl(HOME_URL) }

        btnBookmark.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            val title = webView.title ?: "Bookmark"
            val added = prefsManager.toggleBookmark(title, url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        // Find in Page Logic
        val inputFind = findViewById<EditText>(R.id.findInput)
        val tvMatches = findViewById<TextView>(R.id.findMatches)
        
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            tvMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }

        inputFind.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { webView.findAllAsync(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<View>(R.id.btnFindNext).setOnClickListener { webView.findNext(true) }
        findViewById<View>(R.id.btnFindPrev).setOnClickListener { webView.findNext(false) }
        findViewById<View>(R.id.btnFindClose).setOnClickListener { 
            findBar.visibility = View.GONE
            webView.clearMatches()
            hideKeyboard()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabDrag() {
        var dX = 0f
        var dY = 0f
        var lastAction = 0

        fabEruda.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                }
                MotionEvent.ACTION_MOVE -> {
                    view.y = event.rawY + dY
                    view.x = event.rawX + dX
                    lastAction = MotionEvent.ACTION_MOVE
                }
                MotionEvent.ACTION_UP -> {
                    if (lastAction == MotionEvent.ACTION_DOWN) {
                        // Toggle Eruda
                        webView.evaluateJavascript("if(window.eruda) { var e = document.querySelector('.eruda-container'); if(e) e.style.display = e.style.display==='none'?'block':'none'; }", null)
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        
        menu.add("Find in Page").setOnMenuItemClickListener { findBar.visibility = View.VISIBLE; true }
        menu.add("Desktop Mode").apply { 
            isCheckable = true
            isChecked = prefsManager.desktopMode 
        }.setOnMenuItemClickListener {
            prefsManager.desktopMode = !it.isChecked
            updateUserAgent()
            webView.reload()
            true
        }
        menu.add("Settings").setOnMenuItemClickListener { showSettingsDialog(); true }
        menu.add("Clear Data").setOnMenuItemClickListener {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            webView.clearCache(true)
            webView.clearHistory()
            prefsManager.clearHistory()
            Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun showSettingsDialog() {
        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
        val urls = arrayOf("https://www.google.com/search?q=", "https://duckduckgo.com/?q=", "https://www.bing.com/search?q=", "https://search.brave.com/search?q=")
        
        AlertDialog.Builder(this)
            .setTitle("Select Search Engine")
            .setItems(engines) { _, which ->
                prefsManager.searchEngine = urls[which]
                Toast.makeText(this, "Search engine updated", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateUserAgent() {
        val defaultUserAgent = WebSettings.getDefaultUserAgent(this)
        webView.settings.userAgentString = if (prefsManager.desktopMode) {
            defaultUserAgent.replace("Mobile", "eliboM").replace("Android", "diordnA") // خدعة بسيطة لتغيير الـ UA
        } else {
            defaultUserAgent
        }
    }

    private fun updateBookmarkIcon(url: String) {
        if (prefsManager.isBookmarked(url)) {
            // غير لون الأيقونة أو ضع أيقونة نجمة ممتلئة (يجب إضافة drawable)
            btnBookmark.alpha = 1.0f 
        } else {
            btnBookmark.alpha = 0.5f
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textUrl.windowToken, 0)
    }

    private fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
    }

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) {
            runOnUiThread {
                val finalUrl = if (Patterns.WEB_URL.matcher(input).matches()) "https://$input" else prefsManager.searchEngine + input
                webView.loadUrl(finalUrl)
            }
        }
    }
}