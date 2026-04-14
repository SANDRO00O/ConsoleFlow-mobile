package space.karrarnazim.ConsoleFlow

import android.Manifest
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.recyclerview.widget.LinearLayoutManager
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

// ── Data class for tabs ────────────────────────────────────────────────────
data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var webBackForwardList: WebBackForwardList? = null
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
    private val client = OkHttpClient.Builder().followRedirects(true).build()

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

    // ── Tab management ─────────────────────────────────────────────────────────
    private val tabs = mutableListOf<TabState>()
    private var activeTabId = 0
    private var nextTabId = 1
    private lateinit var tabAdapter: TabAdapter

    // ── Permission launcher ────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    // ── QR scan launcher ──────────────────────────────────────────────────────
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned ->
            val url = when {
                scanned.startsWith("http://") || scanned.startsWith("https://") -> scanned
                Patterns.WEB_URL.matcher(scanned).matches() -> "https://$scanned"
                else -> prefsManager.searchEngine + URLEncoder.encode(scanned, "utf-8")
            }
            webView.loadUrl(url)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)

        initViews()
        setupWebView()
        setupListeners()

        // Init first tab
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
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        btnBookmark = findViewById(R.id.btnBookmark)
        imgSearchEngine = findViewById(R.id.imgSearchEngine)
        findBar = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        tabsOverlay = findViewById(R.id.tabsOverlay)
        tabsRecycler = findViewById(R.id.tabsRecycler)
        tabCount = findViewById(R.id.tabCount)

        tabAdapter = TabAdapter(tabs,
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = LinearLayoutManager(this)
        tabsRecycler.adapter = tabAdapter

        updateSearchEngineIcon()
    }

    // ── Tab operations ─────────────────────────────────────────────────────────

    private fun switchToTab(tab: TabState) {
        // Save current state
        val current = tabs.find { it.id == activeTabId }
        current?.let {
            it.title = webView.title ?: "Tab"
            it.url = webView.url ?: HOME_URL
        }
        activeTabId = tab.id
        tabsOverlay.visibility = View.GONE
        if (tab.url.isBlank() || tab.url == HOME_URL) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.loadUrl(tab.url)
        }
        tabAdapter.setActive(tab.id)
        updateTabCount()
    }

    private fun openNewTab() {
        val id = nextTabId++
        val newTab = TabState(id = id, title = "New Tab", url = HOME_URL)
        tabs.add(newTab)
        tabAdapter.notifyItemInserted(tabs.size - 1)
        switchToTab(newTab)
        updateTabCount()
    }

    private fun closeTab(tab: TabState) {
        val idx = tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0) return
        tabs.removeAt(idx)
        tabAdapter.notifyItemRemoved(idx)
        if (tabs.isEmpty()) {
            openNewTab()
        } else if (tab.id == activeTabId) {
            val newActive = tabs.getOrNull(maxOf(0, idx - 1)) ?: tabs.first()
            switchToTab(newActive)
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
        // Update title
        val title = if (tabsOverlay.visibility == View.VISIBLE) "Tabs (${tabs.size})" else "Tabs"
        findViewById<TextView?>(R.id.tabsTitle)?.text = title
    }

    // ─────────────────────────────────────────────────────────────────────────
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
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true } catch (e: Exception) { true }
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
                url?.let {
                    if (it != HOME_URL) prefsManager.addHistory(view?.title ?: "Unknown", it)
                    // Update active tab state
                    tabs.find { t -> t.id == activeTabId }?.let { tab ->
                        tab.title = view?.title ?: "Tab"
                        tab.url = it
                    }
                }
                view?.evaluateJavascript(
                    "(function(){if(window.__erudaLoaded)return;window.__erudaLoaded=true;var x=new XMLHttpRequest();x.open('GET','https://eruda.local/eruda.js',true);x.onload=function(){try{eval(x.responseText);eruda.init();}catch(e){}};x.send();})()",
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
                            html = html.replace(
                                Regex("""<meta[^>]+http-equiv=["']Content-Security-Policy["'][^>]*>""", RegexOption.IGNORE_CASE), ""
                            )
                            val injection = "<script src=\"https://eruda.local/eruda.js\"></script>" +
                                "<script>eruda.init();window.__erudaLoaded=true;</script>"
                            val customJs = if (prefsManager.customJs.isNotEmpty()) "<script>${prefsManager.customJs}</script>" else ""
                            html = html.replaceFirst("<head>", "<head>$injection$customJs", ignoreCase = true)
                            val filteredHeaders = response.headers.toMap().toMutableMap()
                            filteredHeaders.remove("Content-Security-Policy")
                            filteredHeaders.remove("content-security-policy")
                            filteredHeaders.remove("X-Content-Security-Policy")
                            filteredHeaders.remove("X-WebKit-CSP")
                            val inputStream = ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            return WebResourceResponse("text/html", response.header("Content-Encoding", "utf-8"), response.code, "OK", filteredHeaders, inputStream)
                        }
                    } catch (e: Exception) { return null }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadUrl("$ERROR_URL?url=${URLEncoder.encode(request.url.toString(), "UTF-8")}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
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

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
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
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        textUrl.setOnEditorActionListener { _, _, _ ->
            val input = textUrl.text.toString().trim()
            val finalUrl = when {
                input.startsWith("http://") || input.startsWith("https://") -> input
                Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
                else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
            }
            webView.loadUrl(finalUrl)
            hideKeyboard()
            true
        }

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

        // Navigation
        findViewById<View>(R.id.goBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.goForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }

        // Bookmark (top bar)
        btnBookmark.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            val title = webView.title ?: "Bookmark"
            if (url == HOME_URL || url.startsWith(ERROR_URL)) return@setOnClickListener
            val added = prefsManager.toggleBookmark(title, url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        // QR Scanner
        findViewById<View>(R.id.btnQr).setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a QR code")
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        // Tabs
        findViewById<View>(R.id.btnTabsArea).setOnClickListener {
            if (tabsOverlay.visibility == View.VISIBLE) {
                tabsOverlay.visibility = View.GONE
            } else {
                // Update current tab info before showing
                tabs.find { it.id == activeTabId }?.let {
                    it.title = webView.title ?: "Tab"
                    it.url = webView.url ?: HOME_URL
                }
                tabAdapter.setActive(activeTabId)
                tabAdapter.notifyDataSetChanged()
                updateTabCount()
                tabsOverlay.visibility = View.VISIBLE
            }
        }

        findViewById<View>(R.id.btnNewTab).setOnClickListener { openNewTab() }

        // Bookmarks (bottom bar)
        findViewById<View>(R.id.btnBottomBookmarksArea).setOnClickListener { showBookmarksDialog() }

        // Menu bottom sheet
        findViewById<View>(R.id.btnMenuArea).setOnClickListener { showMenuSheet() }

        // Find in page
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
            findBar.visibility = View.GONE
            webView.clearMatches()
            hideKeyboard()
        }
    }

    // ── Bottom sheet menu ──────────────────────────────────────────────────────
    private fun showMenuSheet() {
        val sheet = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.layout_main_menu, null)
        sheet.setContentView(view)

        view.findViewById<View>(R.id.menuNightMode).setOnClickListener {
            sheet.dismiss()
            webView.evaluateJavascript(
                """(function(){
                    var el=document.getElementById('__cf_night_style');
                    if(el){el.remove();}else{
                        var s=document.createElement('style');
                        s.id='__cf_night_style';
                        s.textContent='html{filter:invert(1) hue-rotate(180deg)!important}img,video{filter:invert(1) hue-rotate(180deg)!important}';
                        document.head.appendChild(s);
                    }
                })()""", null
            )
        }

        // Desktop mode label
        val desktopLabel = view.findViewById<TextView>(R.id.menuDesktopModeLabel)
        desktopLabel.text = if (prefsManager.desktopMode) "Desktop On" else "Desktop"
        desktopLabel.setTextColor(if (prefsManager.desktopMode) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())

        view.findViewById<View>(R.id.menuBookmarks).setOnClickListener { sheet.dismiss(); showBookmarksDialog() }
        view.findViewById<View>(R.id.menuHistory).setOnClickListener { sheet.dismiss(); showHistoryDialog() }
        view.findViewById<View>(R.id.menuShare).setOnClickListener {
            sheet.dismiss()
            val url = webView.url ?: return@setOnClickListener
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, webView.title ?: "")
            }
            startActivity(Intent.createChooser(share, "Share URL"))
        }
        view.findViewById<View>(R.id.menuFindInPage).setOnClickListener { sheet.dismiss(); findBar.visibility = View.VISIBLE }
        view.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            sheet.dismiss()
            prefsManager.desktopMode = !prefsManager.desktopMode
            updateUserAgent()
            val currentUrl = webView.url
            if (!currentUrl.isNullOrEmpty() && currentUrl != HOME_URL) webView.loadUrl(currentUrl)
        }
        view.findViewById<View>(R.id.menuSettings).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.menuClearData).setOnClickListener {
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
        val bookmarks = prefsManager.getBookmarks()
        if (bookmarks.isEmpty()) { Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show(); return }
        val titles = bookmarks.map { it.first.ifEmpty { it.second } }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Bookmarks")
            .setItems(titles) { _, which -> webView.loadUrl(bookmarks[which].second) }
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
        val titles = history.map { it.first.ifEmpty { it.second } }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("History")
            .setItems(titles) { _, which -> webView.loadUrl(history[which].second) }
            .setNeutralButton("Clear") { _, _ -> prefsManager.clearHistory(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show() }
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

    private fun updateUserAgent() {
        val s = webView.settings
        if (prefsManager.desktopMode) {
            s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        } else {
            s.userAgentString = WebSettings.getDefaultUserAgent(this)
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
        }
    }

    private fun updateSearchEngineIcon() {
        val engine = prefsManager.searchEngine
        val iconRes = when {
            engine.contains("google")     -> R.drawable.ic_engine_google
            engine.contains("duckduckgo") -> R.drawable.ic_engine_duckduckgo
            engine.contains("bing")       -> R.drawable.ic_engine_bing
            engine.contains("brave")      -> R.drawable.ic_engine_brave
            else                          -> R.drawable.ic_engine_google
        }
        imgSearchEngine.setImageResource(iconRes)
        imgSearchEngine.colorFilter = null
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha = if (prefsManager.isBookmarked(url)) 1.0f else 0.45f
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
                val finalUrl = when {
                    input.startsWith("http://") || input.startsWith("https://") -> input
                    Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
                    else -> prefsManager.searchEngine + input
                }
                webView.loadUrl(finalUrl)
            }
        }
    }
}

// ── Tab RecyclerView Adapter ───────────────────────────────────────────────
class TabAdapter(
    private val tabs: MutableList<TabState>,
    private val onTabClick: (TabState) -> Unit,
    private val onTabClose: (TabState) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private var activeId: Int = -1

    fun setActive(id: Int) { activeId = id }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tabTitle)
        val url: TextView = v.findViewById(R.id.tabUrl)
        val close: View = v.findViewById(R.id.tabClose)
        val favicon: ImageView = v.findViewById(R.id.tabFavicon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifEmpty { "New Tab" }
        holder.url.text = if (tab.url == "file:///android_asset/home.html") "" else tab.url
        holder.itemView.background = if (tab.id == activeId)
            holder.itemView.context.getDrawable(R.drawable.tab_card_active)
        else
            holder.itemView.context.getDrawable(R.drawable.tab_card_bg)
        holder.itemView.setOnClickListener { onTabClick(tab) }
        holder.close.setOnClickListener { onTabClose(tab) }
    }

    override fun getItemCount() = tabs.size
}
