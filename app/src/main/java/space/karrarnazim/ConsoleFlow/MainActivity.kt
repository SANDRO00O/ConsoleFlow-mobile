package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.net.URLEncoder
import java.util.concurrent.Executors

// تم التعديل إلى Serializable لتجنب مشاكل Parcelize في الجرادل
data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var hasThumbnail: Boolean = false
) : Serializable

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private val webViews = mutableMapOf<Int, WebView>()
    
    private val currentWebView: WebView? get() = webViews[activeTabId]

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
    
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null

    private val HOME_URL  = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"

    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com", "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    private var tabs = mutableListOf<TabState>()
    private var activeTabId = 0
    private var nextTabId   = 1
    private lateinit var tabAdapter: TabAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned -> navigateTo(scanned) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)
        
        initViews()
        setupListeners()

        val intentUrl = intent?.data?.toString()

        if (savedInstanceState != null) {
            val savedTabs = savedInstanceState.getSerializable("TABS_LIST") as? ArrayList<TabState>
            if (savedTabs != null && savedTabs.isNotEmpty()) {
                tabs.clear()
                tabs.addAll(savedTabs)
                activeTabId = savedInstanceState.getInt("ACTIVE_TAB_ID", tabs.first().id)
                nextTabId = savedInstanceState.getInt("NEXT_TAB_ID", tabs.maxOf { it.id } + 1)
                
                tabs.forEach { tab ->
                    val wv = createNewWebView(tab.id)
                    wv.restoreState(savedInstanceState)
                    webViews[tab.id] = wv
                    if (tab.id == activeTabId) {
                        webViewContainer.addView(wv)
                    }
                }
                tabAdapter.setActive(activeTabId)
                tabAdapter.notifyDataSetChanged()
                updateTabCount()
            } else {
                openNewTab(HOME_URL)
            }
        } else {
            val startUrl = if (!intentUrl.isNullOrEmpty()) intentUrl else HOME_URL
            openNewTab(startUrl)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                tabsOverlay.visibility == View.VISIBLE -> tabsOverlay.visibility = View.GONE
                customView != null -> hideCustomView()
                findBar.visibility == View.VISIBLE -> {
                    findBar.visibility = View.GONE
                    currentWebView?.clearMatches()
                }
                currentWebView?.canGoBack() == true -> currentWebView?.goBack()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("TABS_LIST", ArrayList(tabs))
        outState.putInt("ACTIVE_TAB_ID", activeTabId)
        outState.putInt("NEXT_TAB_ID", nextTabId)
        webViews.values.forEach { it.saveState(outState) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.data?.toString()
        if (!url.isNullOrEmpty()) {
            tabsOverlay.visibility = View.GONE
            openNewTab(url)
        }
    }

    override fun onDestroy() {
        webViews.values.forEach { wv ->
            webViewContainer.removeView(wv)
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
        webViews.clear()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        webViewContainer    = findViewById(R.id.webViewContainer)
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

        tabAdapter = TabAdapter(this, tabs,
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter       = tabAdapter

        updateSearchEngineIcon()
    }
    
    private fun openNewTab(url: String = HOME_URL) {
        captureAndStoreThumbnail()
        
        val id = nextTabId++
        val newTab = TabState(id = id, title = "New Tab", url = url)
        tabs.add(newTab)
        
        val wv = createNewWebView(id)
        webViews[id] = wv
        wv.loadUrl(url)
        
        switchToTab(newTab)
        tabAdapter.notifyItemInserted(tabs.size - 1)
    }

    private fun switchToTab(tab: TabState) {
        if (activeTabId != tab.id && currentWebView != null) {
            captureAndStoreThumbnail()
        }
        
        activeTabId = tab.id
        tabsOverlay.visibility = View.GONE
        tabAdapter.setActive(tab.id)
        
        webViewContainer.removeAllViews()
        currentWebView?.let { wv ->
            webViewContainer.addView(wv)
            updateUIForCurrentWebView(wv)
        }
        
        updateTabCount()
    }

    private fun closeTab(tab: TabState) {
        val idx = tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0) return
        
        ioExecutor.execute { File(cacheDir, "thumb_${tab.id}.webp").delete() }
        
        webViews[tab.id]?.let { wv ->
            webViewContainer.removeView(wv)
            wv.destroy()
            webViews.remove(tab.id)
        }
        
        tabs.removeAt(idx)
        tabAdapter.notifyItemRemoved(idx)
        
        if (tabs.isEmpty()) {
            openNewTab(HOME_URL)
        } else if (tab.id == activeTabId) {
            val fallbackTab = tabs.getOrNull(maxOf(0, idx - 1)) ?: tabs.first()
            switchToTab(fallbackTab)
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
        val lbl = if (tabsOverlay.visibility == View.VISIBLE) "Tabs (${tabs.size})" else "Tabs"
        findViewById<TextView?>(R.id.tabsTitle)?.text = lbl
    }

    private fun updateUIForCurrentWebView(wv: WebView) {
        val url = wv.url ?: HOME_URL
        textUrl.setText(if (url == HOME_URL || url.startsWith(ERROR_URL)) "" else url)
        updateBookmarkIcon(url)
        progressBar.progress = wv.progress
        progressBar.visibility = if (wv.progress < 100) View.VISIBLE else View.INVISIBLE
    }

    // تم إصلاح نظام التقاط الصور ليستخدم الطريقة الصحيحة للمتصفح
    private fun captureAndStoreThumbnail() {
        val wv = currentWebView ?: return
        val tabId = activeTabId
        
        if (wv.width <= 0 || wv.height <= 0) return

        try {
            // استخدام Canvas لرسم المتصفح بأمان وبحجم صغير لتوفير الرام
            val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)

            ioExecutor.execute {
                try {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 360, 225, true)
                    val file = File(cacheDir, "thumb_$tabId.webp")
                    FileOutputStream(file).use { out ->
                        scaled.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                    bitmap.recycle()
                    if (scaled !== bitmap) scaled.recycle()
                    
                    mainHandler.post {
                        tabs.find { it.id == tabId }?.hasThumbnail = true
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewWebView(tabId: Int): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled   = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        
        applyUserAgentToWebView(wv)
        wv.addJavascriptInterface(SearchBridge(), "Android")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http") || url.startsWith("file:")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (_: Exception) { true }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view == currentWebView) {
                    progressBar.visibility = View.VISIBLE
                    textUrl.setText(if (url == HOME_URL || url?.startsWith(ERROR_URL) == true) "" else url)
                    updateBookmarkIcon(url ?: "")
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view == currentWebView) {
                    swipeRefresh.isRefreshing = false
                    progressBar.visibility = View.INVISIBLE
                }
                
                url?.let {
                    if (it != HOME_URL && !it.startsWith(ERROR_URL))
                        prefsManager.addHistory(view.title ?: "Unknown", it)
                    
                    tabs.find { t -> t.id == tabId }?.let { tab ->
                        tab.title = view.title ?: "Tab"
                        tab.url   = it
                    }
                }

                if (prefsManager.desktopMode) {
                    view.evaluateJavascript(
                        "(function() { " +
                        "var meta = document.querySelector('meta[name=\"viewport\"]');" +
                        "if (meta) { meta.setAttribute('content', 'width=1024'); } " +
                        "else { var nm = document.createElement('meta'); nm.name='viewport'; nm.content='width=1024'; document.head.appendChild(nm); }" +
                        "})();", null
                    )
                }

                view.evaluateJavascript(
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
                    "})()", null
                )
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                if (url == "https://eruda.local/eruda.js") {
                    return try {
                        WebResourceResponse("application/javascript", "utf-8", assets.open("eruda.js"))
                    } catch (_: Exception) { null }
                }

                val host = request.url.host ?: ""
                if (NO_INTERCEPT_DOMAINS.any { host == it || host.endsWith(".$it") }) return null

                if (request.isForMainFrame && request.method == "GET" && url.startsWith("http")) {
                    try {
                        val ua = getUserAgentString() 
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

                            html = html.replace(Regex("""<meta[^>]+http-equiv=["']Content-Security-Policy["'][^>]*>""", RegexOption.IGNORE_CASE), "")

                            val erudaTag  = "<script src=\"https://eruda.local/eruda.js\"></script>"
                            val erudaInit = "<script>(function(){if(window.__erudaInited)return;try{eruda.init();window.__erudaInited=true;}catch(e){}})()</script>"
                            val customJsTag = prefsManager.customJs.takeIf { it.isNotEmpty() }?.let { "<script>$it</script>" } ?: ""

                            html = html.replaceFirst("<head>", "<head>$erudaTag$erudaInit$customJsTag", ignoreCase = true)

                            val hdrs = response.headers.toMap().toMutableMap()
                            hdrs.remove("Content-Security-Policy")
                            hdrs.remove("content-security-policy")
                            
                            return WebResourceResponse(
                                "text/html", "utf-8", response.code, "OK", hdrs,
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

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view == currentWebView) progressBar.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webViewContainer.visibility = View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webViewContainer.visibility = View.VISIBLE
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

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                @Suppress("DEPRECATION") allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
        
        return wv
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> (currentWebView?.scrollY ?: 0) > 0 }
        swipeRefresh.setOnRefreshListener { currentWebView?.reload() }

        textUrl.setOnEditorActionListener { _, _, _ ->
            navigateTo(textUrl.text.toString().trim()); hideKeyboard(); true
        }
        textUrl.setOnLongClickListener {
            PopupMenu(this, textUrl).apply {
                menu.add("Copy URL").setOnMenuItemClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", currentWebView?.url ?: ""))
                    Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show(); true
                }
                menu.add("Share URL").setOnMenuItemClickListener {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, currentWebView?.url ?: "")
                    }, "Share URL")); true
                }
                show()
            }; true
        }

        findViewById<View>(R.id.btnBackArea).setOnClickListener    { currentWebView?.let { if (it.canGoBack()) it.goBack() } }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { currentWebView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { currentWebView?.loadUrl(HOME_URL) }

        findViewById<View>(R.id.btnTabsArea).setOnClickListener {
            if (tabsOverlay.visibility == View.VISIBLE) {
                tabsOverlay.visibility = View.GONE
            } else {
                captureAndStoreThumbnail()
                tabAdapter.setActive(activeTabId)
                tabAdapter.notifyDataSetChanged()
                updateTabCount()
                tabsOverlay.visibility = View.VISIBLE
            }
        }
        findViewById<View>(R.id.btnNewTab).setOnClickListener  { openNewTab() }
        findViewById<View>(R.id.btnMenuArea).setOnClickListener { showMenuSheet() }

        btnBookmark.setOnClickListener {
            val url = currentWebView?.url ?: return@setOnClickListener
            if (url == HOME_URL || url.startsWith(ERROR_URL)) return@setOnClickListener
            val added = prefsManager.toggleBookmark(currentWebView?.title ?: "Bookmark", url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

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

        val inputFind = findViewById<EditText>(R.id.findInput)
        val tvMatches = findViewById<TextView>(R.id.findMatches)
        inputFind.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { currentWebView?.findAllAsync(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.btnFindNext).setOnClickListener  { currentWebView?.findNext(true)  }
        findViewById<View>(R.id.btnFindPrev).setOnClickListener  { currentWebView?.findNext(false) }
        findViewById<View>(R.id.btnFindClose).setOnClickListener {
            findBar.visibility = View.GONE
            currentWebView?.clearMatches()
            hideKeyboard()
        }
    }

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
            currentWebView?.evaluateJavascript(
                "(function(){var el=document.getElementById('__cf_night');if(el){el.remove();}else{var s=document.createElement('style');s.id='__cf_night';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important}img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}';document.head.appendChild(s);}})()", null
            )
        }
        v.findViewById<View>(R.id.menuBookmarks).setOnClickListener  { sheet.dismiss(); showBookmarksDialog() }
        v.findViewById<View>(R.id.menuHistory).setOnClickListener    { sheet.dismiss(); showHistoryDialog()   }
        v.findViewById<View>(R.id.menuShare).setOnClickListener {
            sheet.dismiss()
            val url = currentWebView?.url ?: return@setOnClickListener
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, currentWebView?.title ?: "")
            }, "Share"))
        }
        v.findViewById<View>(R.id.menuFindInPage).setOnClickListener {
            sheet.dismiss(); findBar.visibility = View.VISIBLE
            currentWebView?.setFindListener { ord, total, _ ->
                findViewById<TextView>(R.id.findMatches).text = if (total > 0) "${ord + 1}/$total" else "0/0"
            }
        }
        v.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            sheet.dismiss()
            prefsManager.desktopMode = !prefsManager.desktopMode
            webViews.values.forEach { applyUserAgentToWebView(it) } 
            val cur = currentWebView?.url
            if (!cur.isNullOrEmpty() && cur != HOME_URL) {
                currentWebView?.clearCache(false)
                currentWebView?.reload()
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
        if (bks.isEmpty()) { Toast.makeText(this, "No bookmarks", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog).setTitle("Bookmarks")
            .setItems(bks.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, w -> currentWebView?.loadUrl(bks[w].second) }
            .setNeutralButton("Clear All") { _, _ ->
                getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE).edit().remove("bookmarks").apply()
                updateBookmarkIcon(currentWebView?.url ?: "")
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showHistoryDialog() {
        val hist = prefsManager.getHistory()
        if (hist.isEmpty()) { Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this, R.style.DarkDialog).setTitle("History")
            .setItems(hist.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, w -> currentWebView?.loadUrl(hist[w].second) }
            .setNeutralButton("Clear") { _, _ -> prefsManager.clearHistory() }
            .setNegativeButton("Close", null).show()
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webViews.values.forEach { it.clearCache(true); it.clearHistory() }
        prefsManager.clearHistory()
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    private fun getUserAgentString(): String {
        val defaultUA = WebSettings.getDefaultUserAgent(this)
        return if (prefsManager.desktopMode) {
            try {
                val chromeVersion = Regex("Chrome/([0-9.]+)").find(defaultUA)?.value ?: "Chrome/124.0.0.0"
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chromeVersion Safari/537.36"
            } catch (e: Exception) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            }
        } else {
            defaultUA
        }
    }

    private fun applyUserAgentToWebView(wv: WebView) {
        val isDesktop = prefsManager.desktopMode
        wv.settings.apply {
            userAgentString = getUserAgentString()
            useWideViewPort = isDesktop
            loadWithOverviewMode = isDesktop
        }
    }

    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
            else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
        }
        currentWebView?.loadUrl(url)
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
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(textUrl.windowToken, 0)
    }

    private fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        fullscreenContainer.visibility = View.GONE
        webViewContainer.visibility = View.VISIBLE
        customView = null
    }

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) { runOnUiThread { navigateTo(input) } }
    }
}

class TabAdapter(
    private val context: Context,
    private val tabs: MutableList<TabState>,
    private val onTabClick: (TabState) -> Unit,
    private val onTabClose: (TabState) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private var activeId: Int = -1
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setActive(id: Int) { activeId = id }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:     TextView  = v.findViewById(R.id.tabTitle)
        val favicon:   ImageView = v.findViewById(R.id.tabFavicon)
        val thumbnail: ImageView = v.findViewById(R.id.tabThumbnail)
        val close:     ImageView = v.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = tabs[position]
        val isActive = tab.id == activeId

        h.title.text = tab.title.ifEmpty { "New Tab" }
        h.favicon.setImageResource(R.drawable.home)
        val defaultColor = if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt()
        h.favicon.imageTintList = android.content.res.ColorStateList.valueOf(defaultColor)

        if (tab.hasThumbnail) {
            ioExecutor.execute {
                val file = File(context.cacheDir, "thumb_${tab.id}.webp")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    mainHandler.post {
                        if (h.adapterPosition == position) { 
                            h.thumbnail.setImageBitmap(bitmap)
                        }
                    }
                } else {
                    mainHandler.post { h.thumbnail.setImageResource(android.R.color.transparent) }
                }
            }
        } else {
            h.thumbnail.setImageResource(android.R.color.transparent)
        }

        h.itemView.background = context.getDrawable(if (isActive) R.drawable.tab_card_active else R.drawable.tab_card_bg)
        val textColor  = if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt()
        val closeColor = if (isActive) 0xFF003366.toInt() else 0xFFAAAAAA.toInt()
        h.title.setTextColor(textColor)
        h.close.setColorFilter(closeColor)

        h.itemView.setOnClickListener { onTabClick(tab) }
        h.close.setOnClickListener   { onTabClose(tab) }
    }

    override fun getItemCount() = tabs.size
}