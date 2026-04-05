package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
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
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var prefsManager: PrefsManager
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null

    private val HOME_URL = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"

    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com", "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    // Settings activity launcher — refresh engine icon on return
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK || result.resultCode == Activity.RESULT_CANCELED) {
            updateSearchEngineIcon()
            // Apply potentially changed desktop mode
            updateUserAgent()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

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
        imgSearchEngine = findViewById(R.id.imgSearchEngine)
        findBar = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        updateSearchEngineIcon()
    }

    @SuppressLint("SetJavaScriptEnabled")
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
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (e: Exception) { true }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                if (url == HOME_URL || url?.startsWith(ERROR_URL) == true) {
                    textUrl.setText("")
                } else {
                    textUrl.setText(url)
                }
                updateBookmarkIcon(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.INVISIBLE
                url?.let { if (it != HOME_URL) prefsManager.addHistory(view?.title ?: "Unknown", it) }
                view?.evaluateJavascript(
                    "if(!window.eruda){var s=document.createElement('script');s.src='https://eruda.local/eruda.js';document.head.appendChild(s);s.onload=function(){eruda.init();};}",
                    null
                )
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url == "https://eruda.local/eruda.js") {
                    return try {
                        val stream = assets.open("eruda.js")
                        WebResourceResponse("application/javascript", "utf-8", stream)
                    } catch (e: Exception) { null }
                }
                val host = request.url.host ?: ""
                if (NO_INTERCEPT_DOMAINS.any { host.endsWith(it) }) return null
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
                    } catch (e: Exception) { return null }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame)
                    view.loadUrl("$ERROR_URL?url=${URLEncoder.encode(request.url.toString(), "UTF-8")}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
                    .setTitle("SSL Certificate Error")
                    .setMessage("The site's certificate is not trusted. Continue anyway?")
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Go Back") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                webPermissionRequest = request
                val androidPerms = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPerms.add(Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPerms.add(Manifest.permission.CAMERA)
                    }
                }
                if (androidPerms.isNotEmpty()) requestPermissionLauncher.launch(androidPerms.toTypedArray())
                else request.grant(request.resources)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val req = DownloadManager.Request(Uri.parse(url)).apply {
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
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener { webView.reload() }
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = webView.scrollY == 0
        }

        textUrl.setOnEditorActionListener { _, _, _ ->
            navigateFromInput(textUrl.text.toString().trim())
            hideKeyboard()
            true
        }

        textUrl.setOnLongClickListener {
            val popup = PopupMenu(this, textUrl)
            popup.menu.add("Copy URL").setOnMenuItemClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("URL", webView.url))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show(); true
            }
            popup.menu.add("Share URL").setOnMenuItemClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, webView.url)
                }, "Share URL")); true
            }
            popup.show(); true
        }

        findViewById<View>(R.id.goBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.goForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.goHome).setOnClickListener { webView.loadUrl(HOME_URL) }

        btnBookmark.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            val added = prefsManager.toggleBookmark(webView.title ?: "Bookmark", url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        val inputFind = findViewById<EditText>(R.id.findInput)
        val tvMatches = findViewById<TextView>(R.id.findMatches)
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
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
            findBar.visibility = View.GONE; webView.clearMatches(); hideKeyboard()
        }
    }

    // ── Navigate helper — fixes double https:// prefix bug ────────────────────
    private fun navigateFromInput(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
            else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
        }
        webView.loadUrl(url)
    }

    // ── Menu — PopupWindow (no animation delay) ────────────────────────────────
    @SuppressLint("InflateParams")
    private fun showMenu(anchor: View) {
        val menuView = layoutInflater.inflate(R.layout.layout_main_menu, null)

        val popup = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 16f
        popup.isOutsideTouchable = true
        popup.animationStyle = 0   // no animation → instant open

        // Desktop Mode checkmark
        val checkView = menuView.findViewById<TextView>(R.id.menuDesktopCheck)
        checkView.visibility = if (prefsManager.desktopMode) View.VISIBLE else View.GONE

        menuView.findViewById<View>(R.id.menuBookmarks).setOnClickListener {
            popup.dismiss(); showBookmarksDialog()
        }
        menuView.findViewById<View>(R.id.menuHistory).setOnClickListener {
            popup.dismiss(); showHistoryDialog()
        }
        menuView.findViewById<View>(R.id.menuFindInPage).setOnClickListener {
            popup.dismiss(); findBar.visibility = View.VISIBLE
        }
        menuView.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            popup.dismiss()
            prefsManager.desktopMode = !prefsManager.desktopMode
            updateUserAgent()
            val cur = webView.url
            if (!cur.isNullOrEmpty() && cur != HOME_URL) webView.loadUrl(cur)
        }
        menuView.findViewById<View>(R.id.menuSettings).setOnClickListener {
            popup.dismiss()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        menuView.findViewById<View>(R.id.menuClearData).setOnClickListener {
            popup.dismiss(); clearData()
        }

        // Show anchored to top-right corner below the menu button
        popup.showAsDropDown(anchor, 0, 4)
    }

    private fun showBookmarksDialog() {
        val bookmarks = prefsManager.getBookmarks()
        if (bookmarks.isEmpty()) { Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Bookmarks")
            .setItems(bookmarks.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, i ->
                webView.loadUrl(bookmarks[i].second)
            }
            .setNeutralButton("Clear All") { _, _ ->
                getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE).edit().remove("bookmarks").apply()
                updateBookmarkIcon(webView.url ?: "")
                Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHistoryDialog() {
        val history = prefsManager.getHistory()
        if (history.isEmpty()) { Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("History")
            .setItems(history.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, i ->
                webView.loadUrl(history[i].second)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webView.clearCache(true)
        webView.clearHistory()
        prefsManager.clearHistory()
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    // ── User Agent ─────────────────────────────────────────────────────────────
    private fun updateUserAgent() {
        val settings = webView.settings
        if (prefsManager.desktopMode) {
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        } else {
            settings.userAgentString = WebSettings.getDefaultUserAgent(this)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
        }
    }

    // ── Search Engine Icon (local drawable, no network needed) ────────────────
    private fun updateSearchEngineIcon() {
        val iconRes = when {
            prefsManager.searchEngine.contains("google")     -> R.drawable.ic_engine_google
            prefsManager.searchEngine.contains("duckduckgo") -> R.drawable.ic_engine_ddg
            prefsManager.searchEngine.contains("bing")       -> R.drawable.ic_engine_bing
            prefsManager.searchEngine.contains("brave")      -> R.drawable.ic_engine_brave
            else                                             -> R.drawable.ic_engine_google
        }
        imgSearchEngine.setImageResource(iconRes)
        imgSearchEngine.colorFilter = null
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha = if (prefsManager.isBookmarked(url)) 1.0f else 0.45f
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(textUrl.windowToken, 0)
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
            runOnUiThread { navigateFromInput(input) }
        }
    }
}
