package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder

data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var favicon: Bitmap? = null,
    var thumbnail: Bitmap? = null
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tabsOverlay: FrameLayout
    private lateinit var tabsRecycler: RecyclerView
    private lateinit var tabCount: TextView

    private lateinit var prefsManager: PrefsManager
    private val okClient = OkHttpClient.Builder().followRedirects(true).build()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null

    private val HOME_URL  = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"

    // ★ Cache UA string for safe access from background thread (shouldInterceptRequest)
    @Volatile private var cachedUserAgent: String = ""

    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com", "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    // ── Tabs ───────────────────────────────────────────────────────────────
    private val tabs = mutableListOf<TabState>()
    private var activeTabId = 0
    private var nextTabId   = 1
    private lateinit var tabAdapter: TabAdapter

    // ── Launchers ──────────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned ->
            navigateTo(scanned)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)
        initViews()
        setupWebView()
        setupListeners()

        tabs.add(TabState(id = 0, title = "New Tab", url = HOME_URL))
        tabAdapter.notifyItemInserted(0)
        updateTabCount()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                tabsOverlay.visibility == View.VISIBLE -> tabsOverlay.visibility = View.GONE
                customView != null -> hideCustomView()
                findBar.visibility == View.VISIBLE -> {
                    findBar.visibility = View.GONE
                    webView.clearMatches()
                }
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        webView             = findViewById(R.id.webView)
        swipeRefresh        = findViewById(R.id.swipeRefresh)
        progressBar         = findViewById(R.id.progressBar)
        textUrl             = findViewById(R.id.textUrl)
        btnBookmark         = findViewById(R.id.btnBookmark)
        imgSearchEngine     = findViewById(R.id.imgSearchEngine)
        findBar             = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        tabsOverlay         = findViewById(R.id.tabsOverlay)
        tabsRecycler        = findViewById(R.id.tabsRecycler)
        tabCount            = findViewById(R.id.tabCount)

        tabAdapter = TabAdapter(tabs,
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter       = tabAdapter

        updateSearchEngineIcon()
    }

    // ── Tab operations ─────────────────────────────────────────────────────
    private fun switchToTab(tab: TabState) {
        captureAndStoreThumbnail()
        tabs.find { it.id == activeTabId }?.let {
            it.title = webView.title ?: "Tab"
            it.url   = webView.url   ?: HOME_URL
        }
        activeTabId = tab.id
        tabsOverlay.visibility = View.GONE
        webView.loadUrl(tab.url.takeIf { it.isNotBlank() } ?: HOME_URL)
        tabAdapter.setActive(tab.id)
        updateTabCount()
    }

    private fun openNewTab() {
        val id = nextTabId++
        tabs.add(TabState(id = id, title = "New Tab", url = HOME_URL))
        tabAdapter.notifyItemInserted(tabs.size - 1)
        switchToTab(tabs.last())
        updateTabCount()
    }

    private fun closeTab(tab: TabState) {
        val idx = tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0) return
        tabs[idx].thumbnail?.recycle()
        tabs[idx].thumbnail = null
        tabs.removeAt(idx)
        tabAdapter.notifyItemRemoved(idx)
        if (tabs.isEmpty()) openNewTab()
        else if (tab.id == activeTabId) switchToTab(tabs.getOrNull(maxOf(0, idx - 1)) ?: tabs.first())
        updateTabCount()
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
        val lbl = if (tabsOverlay.visibility == View.VISIBLE) "Tabs (${tabs.size})" else "Tabs"
        findViewById<TextView?>(R.id.tabsTitle)?.text = lbl
    }

    private fun captureAndStoreThumbnail() {
        try {
            val w = webView.width.coerceAtLeast(1)
            val h = (w * 0.625f).toInt().coerceAtLeast(1)
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            webView.draw(Canvas(full))
            val thumb = Bitmap.createScaledBitmap(full, 360, 225, true)
            if (thumb !== full) full.recycle()
            tabs.find { it.id == activeTabId }?.let { t ->
                t.thumbnail?.recycle()
                t.thumbnail = thumb
            }
        } catch (_: Exception) {}
    }

    // ── WebView setup ──────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled   = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        // ★ applyUserAgent also populates cachedUserAgent (safe for background thread)
        applyUserAgent()
        webView.addJavascriptInterface(SearchBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http") || url.startsWith("file:")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (_: Exception) { true }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                textUrl.setText(
                    if (url == HOME_URL || url?.startsWith(ERROR_URL) == true) "" else url
                )
                updateBookmarkIcon(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing  = false
                progressBar.visibility = View.INVISIBLE
                url?.let {
                    if (it != HOME_URL && !it.startsWith(ERROR_URL))
                        prefsManager.addHistory(view?.title ?: "Unknown", it)
                    tabs.find { t -> t.id == activeTabId }?.let { tab ->
                        tab.title = view?.title ?: "Tab"
                        tab.url   = it
                    }
                }
                // ── Eruda fallback ─────────────────────────────────────────
                // Fires for pages where OkHttp injection didn't run
                // (home.html, error.html, search-engine pages, failed OkHttp calls)
                // ★ Uses simple single-line string — no trimIndent, no multiline issues
                view?.evaluateJavascript(
                    "(function(){" +
                    "if(window.__erudaInited)return;" +
                    "if(typeof eruda!=='undefined'){" +
                        "try{eruda.init();window.__erudaInited=true;}catch(e){}" +
                        "return;" +
                    "}" +
                    "var x=new XMLHttpRequest();" +
                    "x.open('GET','https://eruda.local/eruda.js',true);" +
                    "x.onload=function(){" +
                        "if(window.__erudaInited)return;" +
                        "try{eval(x.responseText);eruda.init();window.__erudaInited=true;}catch(e){}" +
                    "};" +
                    "x.send();" +
                    "})()",
                    null
                )
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                // ── Serve eruda from local assets ──────────────────────────
                if (url == "https://eruda.local/eruda.js") {
                    return try {
                        WebResourceResponse("application/javascript", "utf-8", assets.open("eruda.js"))
                    } catch (_: Exception) { null }
                }

                // ── Skip OkHttp interception for auth/search domains ───────
                val host = request.url.host ?: ""
                if (NO_INTERCEPT_DOMAINS.any { host == it || host.endsWith(".$it") }) return null

                // ── OkHttp HTML injection ──────────────────────────────────
                if (request.isForMainFrame && request.method == "GET" && url.startsWith("http")) {
                    try {
                        // ★ USE cachedUserAgent (set on main thread) — NOT webView.settings
                        //   webView.settings must only be accessed on the main thread;
                        //   shouldInterceptRequest runs on a background thread.
                        val ua = cachedUserAgent.ifEmpty {
                            WebSettings.getDefaultUserAgent(view.context)
                        }

                        val reqBuilder = Request.Builder().url(url)
                        request.requestHeaders.forEach { (k, v) ->
                            if (k.lowercase() != "user-agent") reqBuilder.addHeader(k, v)
                        }
                        reqBuilder.header("User-Agent", ua)

                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!cookie.isNullOrEmpty()) reqBuilder.header("Cookie", cookie)

                        val response = okClient.newCall(reqBuilder.build()).execute()
                        val contentType = response.header("Content-Type", "") ?: ""

                        if (contentType.contains("text/html")) {
                            var html = response.body?.string() ?: ""

                            // Strip inline CSP meta tags
                            html = html.replace(
                                Regex("""<meta[^>]+http-equiv=["']Content-Security-Policy["'][^>]*>""",
                                    RegexOption.IGNORE_CASE), ""
                            )

                            // ★ Eruda injection — single-line, no trimIndent,
                            //   no multiline string issues.
                            // ★ Uses __erudaInited flag (set only after eruda.init() succeeds)
                            //   so the onPageFinished fallback won't double-initialize.
                            val erudaTag  = "<script src=\"https://eruda.local/eruda.js\"></script>"
                            val erudaInit = "<script>(function(){" +
                                "if(window.__erudaInited)return;" +
                                "try{eruda.init();window.__erudaInited=true;}catch(e){}" +
                                "})()</script>"
                            val customJsTag = prefsManager.customJs
                                .takeIf { it.isNotEmpty() }
                                ?.let { "<script>$it</script>" } ?: ""

                            html = html.replaceFirst(
                                "<head>",
                                "<head>$erudaTag$erudaInit$customJsTag",
                                ignoreCase = true
                            )

                            // Strip CSP response headers
                            val hdrs = response.headers.toMap().toMutableMap()
                            hdrs.remove("Content-Security-Policy")
                            hdrs.remove("content-security-policy")
                            hdrs.remove("X-Content-Security-Policy")
                            hdrs.remove("X-WebKit-CSP")

                            return WebResourceResponse(
                                "text/html", "utf-8",
                                response.code, "OK",
                                hdrs,
                                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            )
                        }
                    } catch (_: Exception) { return null }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame)
                    view.loadUrl("$ERROR_URL?url=${URLEncoder.encode(req.url.toString(), "UTF-8")}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
                    .setTitle("SSL Certificate Error")
                    .setMessage("The site's security certificate is not trusted. Continue anyway?")
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Go Back")   { _, _ -> handler.cancel()  }
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                icon ?: return
                tabs.find { it.id == activeTabId }?.favicon = icon
                if (tabsOverlay.visibility == View.VISIBLE) tabAdapter.notifyDataSetChanged()
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
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                @Suppress("DEPRECATION") allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
    }

    // ── Listeners ──────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        textUrl.setOnEditorActionListener { _, _, _ ->
            navigateTo(textUrl.text.toString().trim()); hideKeyboard(); true
        }
        textUrl.setOnLongClickListener {
            PopupMenu(this, textUrl).apply {
                menu.add("Copy URL").setOnMenuItemClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("URL", webView.url))
                    Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show(); true
                }
                menu.add("Share URL").setOnMenuItemClickListener {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, webView.url)
                        }, "Share URL")); true
                }
                show()
            }; true
        }

        // Bottom navigation
        findViewById<View>(R.id.btnBackArea).setOnClickListener    { if (webView.canGoBack())    webView.goBack()    }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { webView.loadUrl(HOME_URL) }

        // Tabs toggle
        findViewById<View>(R.id.btnTabsArea).setOnClickListener {
            if (tabsOverlay.visibility == View.VISIBLE) {
                tabsOverlay.visibility = View.GONE
            } else {
                captureAndStoreThumbnail()
                tabs.find { it.id == activeTabId }?.let {
                    it.title = webView.title ?: "Tab"
                    it.url   = webView.url   ?: HOME_URL
                }
                tabAdapter.setActive(activeTabId)
                tabAdapter.notifyDataSetChanged()
                updateTabCount()
                tabsOverlay.visibility = View.VISIBLE
            }
        }
        findViewById<View>(R.id.btnNewTab).setOnClickListener  { openNewTab() }
        findViewById<View>(R.id.btnMenuArea).setOnClickListener { showMenuSheet() }

        // Top-bar bookmark
        btnBookmark.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            if (url == HOME_URL || url.startsWith(ERROR_URL)) return@setOnClickListener
            val added = prefsManager.toggleBookmark(webView.title ?: "Bookmark", url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        // QR Scanner
        findViewById<View>(R.id.btnQr).setOnClickListener {
            qrScanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a QR code")
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            })
        }

        // Find in page
        val inputFind = findViewById<EditText>(R.id.findInput)
        val tvMatches = findViewById<TextView>(R.id.findMatches)
        webView.setFindListener { ord, total, _ ->
            tvMatches.text = if (total > 0) "${ord + 1}/$total" else "0/0"
        }
        inputFind.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { webView.findAllAsync(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.btnFindNext).setOnClickListener  { webView.findNext(true)  }
        findViewById<View>(R.id.btnFindPrev).setOnClickListener  { webView.findNext(false) }
        findViewById<View>(R.id.btnFindClose).setOnClickListener {
            findBar.visibility = View.GONE
            webView.clearMatches()
            hideKeyboard()
        }
    }

    // ── Bottom sheet menu ──────────────────────────────────────────────────
    private fun showMenuSheet() {
        val sheet = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
        val v = layoutInflater.inflate(R.layout.layout_main_menu, null)
        sheet.setContentView(v)

        val desktopLabel = v.findViewById<TextView>(R.id.menuDesktopModeLabel)
        if (prefsManager.desktopMode) {
            desktopLabel.text = "Desktop On"
            desktopLabel.setTextColor(0xFFFFFFFF.toInt())
        } else {
            desktopLabel.text = "Desktop"
            desktopLabel.setTextColor(0xFFCCCCCC.toInt())
        }

        v.findViewById<View>(R.id.menuNightMode).setOnClickListener {
            sheet.dismiss()
            webView.evaluateJavascript(
                "(function(){" +
                "var el=document.getElementById('__cf_night');" +
                "if(el){el.remove();}else{" +
                "var s=document.createElement('style');" +
                "s.id='__cf_night';" +
                "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important}" +
                "img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
                "document.head.appendChild(s);" +
                "}" +
                "})()", null
            )
        }
        v.findViewById<View>(R.id.menuBookmarks).setOnClickListener  { sheet.dismiss(); showBookmarksDialog() }
        v.findViewById<View>(R.id.menuHistory).setOnClickListener    { sheet.dismiss(); showHistoryDialog()   }
        v.findViewById<View>(R.id.menuShare).setOnClickListener {
            sheet.dismiss()
            val url = webView.url ?: return@setOnClickListener
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                    putExtra(Intent.EXTRA_SUBJECT, webView.title ?: "")
                }, "Share"))
        }
        v.findViewById<View>(R.id.menuFindInPage).setOnClickListener {
            sheet.dismiss(); findBar.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            sheet.dismiss()
            prefsManager.desktopMode = !prefsManager.desktopMode
            // ★ applyUserAgent updates BOTH webView.settings AND cachedUserAgent on main thread
            applyUserAgent()
            val cur = webView.url
            if (!cur.isNullOrEmpty() && cur != HOME_URL) {
                webView.clearCache(false)
                webView.reload()
            }
        }
        v.findViewById<View>(R.id.menuSettings).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuClearData).setOnClickListener {
            sheet.dismiss()
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Browsing Data")
                .setMessage("This will delete cache, cookies, and history.")
                .setPositiveButton("Clear") { _, _ -> clearData() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        sheet.show()
    }

    private fun showBookmarksDialog() {
        val bks = prefsManager.getBookmarks()
        if (bks.isEmpty()) { Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Bookmarks")
            .setItems(bks.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, w ->
                webView.loadUrl(bks[w].second)
            }
            .setNeutralButton("Clear All") { _, _ ->
                getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)
                    .edit().remove("bookmarks").apply()
                updateBookmarkIcon(webView.url ?: "")
                Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showHistoryDialog() {
        val hist = prefsManager.getHistory()
        if (hist.isEmpty()) { Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("History")
            .setItems(hist.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, w ->
                webView.loadUrl(hist[w].second)
            }
            .setNeutralButton("Clear") { _, _ ->
                prefsManager.clearHistory()
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webView.clearCache(true)
        webView.clearHistory()
        prefsManager.clearHistory()
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    // ── User Agent ─────────────────────────────────────────────────────────
    /**
     * Must be called on the MAIN THREAD.
     * Updates both webView.settings.userAgentString AND cachedUserAgent.
     * cachedUserAgent is @Volatile so shouldInterceptRequest (background thread)
     * can safely read it.
     */
    private fun applyUserAgent() {
        with(webView.settings) {
            if (prefsManager.desktopMode) {
                val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                userAgentString      = desktopUA
                cachedUserAgent      = desktopUA
                useWideViewPort      = true
                loadWithOverviewMode = true
            } else {
                val defaultUA = WebSettings.getDefaultUserAgent(this@MainActivity)
                userAgentString      = defaultUA
                cachedUserAgent      = defaultUA
                useWideViewPort      = true
                loadWithOverviewMode = false
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
            else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
        }
        webView.loadUrl(url)
    }

    private fun updateSearchEngineIcon() {
        val res = when {
            prefsManager.searchEngine.contains("google")     -> R.drawable.ic_engine_google
            prefsManager.searchEngine.contains("duckduckgo") -> R.drawable.ic_engine_duckduckgo
            prefsManager.searchEngine.contains("bing")       -> R.drawable.ic_engine_bing
            prefsManager.searchEngine.contains("brave")      -> R.drawable.ic_engine_brave
            else                                              -> R.drawable.ic_engine_google
        }
        imgSearchEngine.setImageResource(res)
        imgSearchEngine.colorFilter = null
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha = if (prefsManager.isBookmarked(url)) 1.0f else 0.4f
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
        fun navigate(input: String) { runOnUiThread { navigateTo(input) } }
    }
}

// ── Tab RecyclerView Adapter ────────────────────────────────────────────────
class TabAdapter(
    private val tabs: MutableList<TabState>,
    private val onTabClick: (TabState) -> Unit,
    private val onTabClose: (TabState) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private var activeId: Int = -1
    fun setActive(id: Int) { activeId = id }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:     TextView  = v.findViewById(R.id.tabTitle)
        val favicon:   ImageView = v.findViewById(R.id.tabFavicon)
        val thumbnail: ImageView = v.findViewById(R.id.tabThumbnail)
        val close:     View      = v.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = tabs[position]
        h.title.text = tab.title.ifEmpty { "New Tab" }
        if (tab.favicon != null) {
            h.favicon.setImageBitmap(tab.favicon)
            h.favicon.clearColorFilter()
        } else {
            h.favicon.setImageResource(R.drawable.home)
            h.favicon.setColorFilter(0xFF666666.toInt())
        }
        if (tab.thumbnail != null) {
            h.thumbnail.setImageBitmap(tab.thumbnail)
        } else {
            h.thumbnail.setImageResource(android.R.color.transparent)
        }
        h.itemView.background = h.itemView.context.getDrawable(
            if (tab.id == activeId) R.drawable.tab_card_active else R.drawable.tab_card_bg
        )
        h.itemView.setOnClickListener { onTabClick(tab) }
        h.close.setOnClickListener   { onTabClose(tab) }
    }

    override fun getItemCount() = tabs.size
}
