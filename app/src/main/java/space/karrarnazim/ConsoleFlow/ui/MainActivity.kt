package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.*
import android.graphics.*
import android.graphics.drawable.*
import android.net.*
import android.os.*
import android.provider.*
import android.text.*
import android.util.*
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.*
import android.webkit.*
import android.widget.*
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.*
import androidx.fragment.app.*
import androidx.webkit.*
import androidx.lifecycle.*
import com.google.android.material.tabs.*
import androidx.appcompat.app.*
import androidx.appcompat.widget.*
import androidx.core.content.*
import androidx.core.view.*
import androidx.recyclerview.widget.*
import androidx.swiperefreshlayout.widget.*
import com.google.android.material.bottomsheet.*
import com.google.android.material.dialog.*
import com.google.android.material.floatingactionbutton.*
import com.google.android.material.textfield.*
import okhttp3.*
import org.json.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*

class MainActivity : AppCompatActivity() {

    // ── واجهة المستخدم ──────────────────────────────────────────────────────
    private lateinit var webViewContainer: FrameLayout
    private val webViews = mutableMapOf<Int, WebView>()
    private val currentWebView: WebView? get() = webViews[activeTabId]

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tabsOverlay: FrameLayout
    private lateinit var tabsRecycler: RecyclerView
    private lateinit var tabCount: TextView
    private lateinit var nativeOverlayContainer: FrameLayout
    private var nativeHomeOverlay: View? = null
    private var nativeErrorOverlay: View? = null
    private var homeSearchEngineIcon: ImageView? = null
    private var lastErrorUrl: String? = null
    private var tabGroupsContainer: LinearLayout? = null

    // FIX #3 — dirty flag للـ home overlay بدلاً من rebuild فوري
    private var homeOverlayDirty = false

    // FIX #4 — cache الـ home preview في الذاكرة لتجنب I/O على main thread
    private var homePreviewBitmapCache: Bitmap? = null

    // ── الإعدادات والمديرون ────────────────────────────────────────────────
    private lateinit var prefsManager: PrefsManager
    private lateinit var bookmarkRepository: BookmarkRepository
    private lateinit var historyRepository: HistoryRepository

    // FIX #9 — إضافة timeouts لـ OkHttp لمنع block لا نهائي
    private val okClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    // FIX #10 — fixed thread pool بدلاً من newCachedThreadPool اللانهائي
    private lateinit var ioExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── الفيديو بملء الشاشة والأذونات ───────────────────────────────────────
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null

    // ── القائمة السفلية المخزنة مؤقتاً ─────────────────────────────────────
    private var cachedMenuSheet: BottomSheetDialog? = null
    private var cachedMenuSheetView: View? = null

    // ── الثوابت ─────────────────────────────────────────────────────────────
    private val HOME_URL = HOME_URL_CONST

    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com", "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    private fun isLocalhostHost(host: String?): Boolean {
        val normalized = host?.lowercase().orEmpty()
        return normalized in LOCALHOST_HOSTS
    }

    // ── إدارة الجلسات والتبويبات ───────────────────────────────────────────
    private lateinit var sessionManager: BrowserSessionManager
    private lateinit var webViewFactory: BrowserWebViewFactory

    private var tabGroups: MutableList<TabGroup>
        get() = sessionManager.tabGroups
        set(value) {
            sessionManager.tabGroups.clear()
            sessionManager.tabGroups.addAll(value)
        }

    private var activeGroupId: Int
        get() = sessionManager.activeGroupId
        set(value) { sessionManager.activeGroupId = value }

    private var activeTabId: Int
        get() = sessionManager.activeTabId
        set(value) { sessionManager.activeTabId = value }

    private var nextTabId: Int
        get() = sessionManager.nextTabId
        set(value) { sessionManager.nextTabId = value }

    private var nextGroupId: Int
        get() = sessionManager.nextGroupId
        set(value) { sessionManager.nextGroupId = value }

    private val currentGroup: TabGroup? get() = sessionManager.currentGroup
    private lateinit var tabAdapter: TabAdapter
    // ── عقود نتائج الأذونات ومسح QR ────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned -> navigateTo(scanned) }
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        transcript?.let { query -> if (query.isNotBlank()) navigateTo(query) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  دورة حياة النشاط
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)
        bookmarkRepository = BookmarkRepository(prefsManager)
        historyRepository = HistoryRepository(prefsManager)

        // FIX #10 — fixed thread pool محدود بعدد cores
        ioExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )

        sessionManager = BrowserSessionManager()
        webViewFactory = BrowserWebViewFactory(
            activity = this,
            prefsManager = prefsManager,
            okClient = okClient,
            currentWebViewProvider = { currentWebView },
            isHomeUrl = { isHomeUrl(it) },
            isLocalhostHost = { isLocalhostHost(it) },
            noInterceptDomains = NO_INTERCEPT_DOMAINS,
            bookmarkRepository = bookmarkRepository,
            onOpenNewTab = { openNewTab(it) },
            onMarkHomeOverlayDirty = { homeOverlayDirty = true },
            onInvalidateHomePreviewCache = { invalidateHomePreviewCache() },
            onNavigate = { navigateTo(it) },
            onSetSwipeRefresh = { enabled -> swipeRefresh.isEnabled = enabled },
            onPageStartedUi = { _, _, url ->
                progressBar.visibility = View.VISIBLE
                textUrl.setText(if (isHomeUrl(url)) "" else url)
                updateBookmarkIcon(url ?: "")
                if (isHomeUrl(url)) setTopBarVisible(false) else setTopBarVisible(true)
            },
            onProgressChangedUi = { _, progress ->
                progressBar.progress = progress
            },
            onShowCustomViewUi = { view, callback ->
                customView = view
                customViewCallback = callback
                fullscreenContainer.removeAllViews()
                if (view != null) fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webViewContainer.visibility = View.GONE
                setFullscreen(true)
            },
            onHideCustomViewUi = {
                hideCustomView()
            },
            onPermissionRequestUi = { request ->
                webPermissionRequest = request
            },
            onPageFinishedUi = { tabId, view, url ->
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.INVISIBLE

                url?.let { pageUrl ->
                    if (!isHomeUrl(pageUrl)) {
                        val title = view.title ?: "Unknown"
                        ioExecutor.execute { historyRepository.addHistory(title, pageUrl) }
                    }

                    currentGroup?.tabs?.find { it.id == tabId }?.let { tab ->
                        tab.title = view.title ?: "Tab"
                        tab.url = if (isHomeUrl(pageUrl)) HOME_URL else pageUrl
                        if (isHomeUrl(pageUrl) && tab.ramThumbnail == null) {
                            tab.ramThumbnail = getHomePreviewBitmap()
                            tab.hasThumbnail = true
                            tab.thumbnailUrl = HOME_URL
                        }
                    }
                    refreshTabsRecycler()
                    savePersistentTabs()
                }

                if (prefsManager.desktopMode) {
                    view.evaluateJavascript(
                        "(function(){" +
                            "var meta=document.querySelector('meta[name="viewport"]');" +
                            "if(meta){meta.setAttribute('content','width=1024');}" +
                            "else{var nm=document.createElement('meta');nm.name='viewport';" +
                            "nm.content='width=1024';document.head.appendChild(nm);" +
                            "}})()",
                        null
                    )
                }
            },
            onReceivedIconUi = { tabId, icon ->
                currentGroup?.tabs?.find { it.id == tabId }?.let { tab ->
                    tab.faviconBitmap = icon
                    refreshTabsRecycler()
                }
            },
            onReceivedErrorUi = { url -> showErrorOverlay(url) },
            onApplyConsoleTools = { view -> applyConsoleTools(view) }
        )

        initViews()
        setupListeners()
        setTopBarVisible(false, immediate = true)

        val intentUrl = intent?.data?.toString()

        if (savedInstanceState != null) {
            val savedGroups = savedInstanceState.getSerializable("GROUPS_LIST") as? ArrayList<TabGroup>
            if (savedGroups != null && savedGroups.isNotEmpty()) {
                tabGroups.clear()
                tabGroups.addAll(savedGroups)
                activeGroupId = savedInstanceState.getInt("ACTIVE_GROUP_ID", tabGroups.first().id)
                activeTabId   = savedInstanceState.getInt("ACTIVE_TAB_ID",   tabGroups.first().tabs.firstOrNull()?.id ?: 0)
                nextTabId     = savedInstanceState.getInt("NEXT_TAB_ID", 100)
                nextGroupId   = savedInstanceState.getInt("NEXT_GROUP_ID", 100)

                val activeTab = currentGroup?.tabs?.find { it.id == activeTabId }
                    ?: currentGroup?.tabs?.firstOrNull()
                if (activeTab != null) {
                    val restoredState = savedInstanceState.getBundle("active_webview_state")
                    val wv = ensureWebViewForTab(activeTab, restoredState)
                    webViewContainer.addView(wv)
                }
                updateGroupsUI()
                refreshTabsRecycler()
                val activeTabUrl = currentGroup?.tabs?.find { it.id == activeTabId }?.url
                if (isHomeUrl(activeTabUrl) && intentUrl.isNullOrEmpty()) showHomeOverlay()
                else hideNativeOverlays(immediate = true)
            } else {
                createNewGroup("Default")
            }
        } else {
            loadPersistentTabs(intentUrl)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                tabsOverlay.visibility == View.VISIBLE -> tabsOverlay.visibility = View.GONE
                nativeOverlayContainer.visibility == View.VISIBLE -> hideNativeOverlays()
                topBar.visibility == View.VISIBLE && isHomeUrl(currentWebView?.url) -> setTopBarVisible(false)
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        val url = intent?.data?.toString()
        if (!url.isNullOrEmpty()) {
            tabsOverlay.visibility = View.GONE
            openNewTab(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("GROUPS_LIST", ArrayList(tabGroups))
        outState.putInt("ACTIVE_GROUP_ID", activeGroupId)
        outState.putInt("ACTIVE_TAB_ID",   activeTabId)
        outState.putInt("NEXT_TAB_ID",     nextTabId)
        outState.putInt("NEXT_GROUP_ID",   nextGroupId)
        currentWebView?.let { wv ->
            val bundle = Bundle()
            wv.saveState(bundle)
            outState.putBundle("active_webview_state", bundle)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineIcon()

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val extraTop = (resources.displayMetrics.density * 4f).toInt()

            topBar.setPadding(
                topBar.paddingLeft,
                statusBarTop + extraTop,
                topBar.paddingRight,
                topBar.paddingBottom
            )

            tabsOverlay.setPadding(
                tabsOverlay.paddingLeft,
                statusBarTop,
                tabsOverlay.paddingRight,
                tabsOverlay.paddingBottom
            )

            (bottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != navBarBottom) {
                    lp.bottomMargin = navBarBottom
                    bottomBar.layoutParams = lp
                }
            }

            insets
        }
        root.requestApplyInsets()
    }

    override fun onPause() {
        super.onPause()
        savePersistentTabs()
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
        cachedMenuSheet?.dismiss()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  التهيئة الأولية للواجهة
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        webViewContainer       = findViewById(R.id.webViewContainer)
        swipeRefresh           = findViewById(R.id.swipeRefresh)
        progressBar            = findViewById(R.id.progressBar)
        topBar                 = findViewById(R.id.topBar)
        textUrl                = findViewById(R.id.textUrl)
        btnBookmark            = findViewById(R.id.btnBookmark)
        imgSearchEngine        = findViewById(R.id.imgSearchEngine)
        findBar                = findViewById(R.id.findBar)
        bottomBar              = findViewById(R.id.bottomBar)
        fullscreenContainer    = findViewById(R.id.fullscreenContainer)
        tabsOverlay            = findViewById(R.id.tabsOverlay)
        tabsRecycler           = findViewById(R.id.tabsRecycler)
        tabCount               = findViewById(R.id.tabCount)
        nativeOverlayContainer = findViewById(R.id.nativeOverlayContainer)
        tabGroupsContainer     = findViewById(R.id.tabGroupsContainer)

        buildNativeOverlays()
        setTopBarVisible(false, immediate = true)

        // FIX #5 — نمرر ioExecutor للـ Adapter بدلاً من أن ينشئ هو thread pool خاص به
        tabAdapter = TabAdapter(
            context    = this,
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter       = tabAdapter

        updateSearchEngineIcon()

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val extraTop = (resources.displayMetrics.density * 4f).toInt()

            topBar.setPadding(
                topBar.paddingLeft,
                statusBarTop + extraTop,
                topBar.paddingRight,
                topBar.paddingBottom
            )

            tabsOverlay.setPadding(
                tabsOverlay.paddingLeft,
                statusBarTop,
                tabsOverlay.paddingRight,
                tabsOverlay.paddingBottom
            )

            (bottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != navBarBottom) {
                    lp.bottomMargin = navBarBottom
                    bottomBar.layoutParams = lp
                }
            }

            insets
        }
        root.requestApplyInsets()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  بناء الـ Overlays (مرة واحدة عند التهيئة)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNativeOverlays() {
        nativeOverlayContainer.removeAllViews()
        nativeHomeOverlay  = buildHomeOverlay()
        nativeErrorOverlay = buildErrorOverlay()
        nativeHomeOverlay?.let  { nativeOverlayContainer.addView(it) }
        nativeErrorOverlay?.let { nativeOverlayContainer.addView(it) }
        hideNativeOverlays(immediate = true)
        // FIX #3 — حفظ مرجع أيقونة محرك البحث من الـ View الجديد عبر tag
        homeSearchEngineIcon = nativeHomeOverlay?.findViewWithTag("home_search_engine_icon")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Home Preview Bitmap — مع cache في الذاكرة
    // ─────────────────────────────────────────────────────────────────────────

    // FIX #4 — invalidate يُستدعى عند تغيير البيانات (bookmarks / search engine)
    private fun invalidateHomePreviewCache() {
        homePreviewBitmapCache = null
    }

    fun getHomePreviewBitmap(force: Boolean = false): Bitmap {
        // FIX #4 — أولاً: تحقق من الـ in-memory cache لتجنب File I/O على main thread
        if (!force) {
            homePreviewBitmapCache?.let { return it }
        }

        val width  = resources.displayMetrics.widthPixels.coerceAtLeast(360)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(640)
        val key        = homePreviewCacheKey(width, height)
        val cacheFile  = BrowserCacheFiles.homePreviewFile(cacheDir, width, height)
        val sigFile    = BrowserCacheFiles.homePreviewSigFile(cacheDir, width, height)

        if (!force && cacheFile.exists() && sigFile.exists()) {
            runCatching { sigFile.readText() }.getOrNull()?.let { stored ->
                if (stored == key) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { cached ->
                        homePreviewBitmapCache = cached   // FIX #4 — خزّن في الذاكرة
                        return cached
                    }
                }
            }
        }

        val rendered = renderHomePreviewBitmap(width, height)
        homePreviewBitmapCache = rendered   // FIX #4 — خزّن في الذاكرة
        ioExecutor.execute {
            try {
                FileOutputStream(cacheFile).use { out ->
                    rendered.compress(Bitmap.CompressFormat.WEBP, 80, out)
                }
                sigFile.writeText(key)
            } catch (_: Exception) { }
        }
        return rendered
    }

private fun homePreviewCacheKey(width: Int, height: Int): String =
    BrowserCacheFiles.homePreviewCacheKey(width, height)


    private fun renderHomePreviewBitmap(width: Int, height: Int): Bitmap {
        val view  = buildHomeOverlay(loadFavicons = false)
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun currentSearchEngineIconRes(): Int {
        return if (prefsManager.searchEngineIsCustom) R.drawable.ic_find else searchEngineIconRes(prefsManager.searchEngine)
    }

    private fun setTopBarVisible(visible: Boolean, immediate: Boolean = false) {
        if (immediate) {
            topBar.alpha      = if (visible) 1f else 0f
            topBar.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            topBar.visibility = View.VISIBLE
            topBar.translationY = -topBar.height.toFloat() * 0.2f
            topBar.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            topBar.animate().alpha(0f).translationY(-topBar.height.toFloat() * 0.2f)
                .setDuration(120).withEndAction { topBar.visibility = View.GONE }.start()
        }
    }

    private fun showSearchTopBar(initialQuery: String = "") {
        setTopBarVisible(true)
        textUrl.setText(initialQuery)
        textUrl.setSelection(textUrl.text?.length ?: 0)
        textUrl.requestFocus()
        textUrl.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(textUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun launchQrScanner() {
        qrScanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan a QR code")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
        })
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or speak a URL")
        }
        runCatching { voiceSearchLauncher.launch(intent) }
            .onFailure { Toast.makeText(this, "Voice search unavailable", Toast.LENGTH_SHORT).show() }
    }

    private fun loadBookmarkFavicon(url: String, target: ImageView) {
        ioExecutor.execute {
            try {
                val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
                val faviconUrl = if (host.isNotEmpty())
                    "https://www.google.com/s2/favicons?sz=64&domain=$host"
                else
                    "https://www.google.com/s2/favicons?sz=64&domain_url=${URLEncoder.encode(url, "utf-8")}"
                val request = Request.Builder().url(faviconUrl).build()
                okClient.newCall(request).execute().use { response ->
                    val body   = response.body ?: throw IllegalStateException("No body")
                    val bitmap = BitmapFactory.decodeStream(body.byteStream())
                        ?: throw IllegalStateException("Bad image")
                    mainHandler.post {
                        target.setImageBitmap(bitmap)
                        target.imageTintList = null
                    }
                }
            } catch (_: Exception) {
                mainHandler.post { target.setImageResource(R.drawable.ic_favicon_fallback) }
            }
        }
    }

    private fun buildHomeOverlay(loadFavicons: Boolean = true): View {
        val dp   = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha      = 0f
            visibility = View.GONE
            isClickable = true
        }

        val scroll = ScrollView(this).apply {
            isFillViewport  = true
            overScrollMode  = View.OVER_SCROLL_NEVER
            layoutParams    = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scroll)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val w = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            w.marginStart = (18 * dp).toInt(); w.marginEnd = (18 * dp).toInt()
            layoutParams = w
            setPadding(0, (18 * dp).toInt(), 0, (24 * dp).toInt())
        }
        scroll.addView(content)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18 * dp).toInt() }
        }
        content.addView(topRow)

        val topAction = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((40*dp).toInt(), (40*dp).toInt())
            alpha = 0.95f
            setPadding((9*dp).toInt(), (9*dp).toInt(), (9*dp).toInt(), (9*dp).toInt())
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        topRow.addView(topAction)
        topRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_search)
            setPadding((14*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (26*dp).toInt() }
        }
        content.addView(searchBar)

        // FIX #3 — نضع tag على الـ icon لنتمكن من re-resolve بعد أي rebuild
        val searchIcon = ImageView(this).apply {
            tag = "home_search_engine_icon"
            setImageResource(currentSearchEngineIconRes())
            setColorFilter(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams((20*dp).toInt(), (20*dp).toInt())
        }
        homeSearchEngineIcon = searchIcon
        searchBar.addView(searchIcon)

        val searchInput = EditText(this).apply {
            hint = "Search or type URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7C7C7C"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12*dp).toInt(), 0, (8*dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions  = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType   = android.text.InputType.TYPE_CLASS_TEXT or
                          android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) navigateTo(query)
                    hideKeyboard(); true
                } else false
            }
        }
        searchBar.addView(searchInput)

        fun searchActionButton(iconRes: Int? = null, bitmap: Bitmap? = null,
                                sizeDp: Int = 40, onClick: () -> Unit): ImageView {
            return ImageView(this).apply {
                if (bitmap != null) setImageBitmap(bitmap)
                else if (iconRes != null) setImageResource(iconRes)
                setColorFilter(Color.parseColor("#ECECEC"))
                setBackgroundResource(R.drawable.bottom_btn_ripple)
                layoutParams = LinearLayout.LayoutParams((sizeDp*dp).toInt(), (sizeDp*dp).toInt())
                    .apply { marginStart = (6*dp).toInt() }
                setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onClick() }
            }
        }

        searchBar.addView(searchActionButton(iconRes = R.drawable.ic_qr, sizeDp = 40) { launchQrScanner() })
        searchBar.addView(searchActionButton(iconRes = R.drawable.ic_mic, sizeDp = 40) { launchVoiceSearch() })

        val fixedHeader = TextView(this).apply {
            text = "DEV BOOKMARKS"
            setTextColor(Color.parseColor("#7B7B7B"))
            textSize = 11f; letterSpacing = 0.08f
            setPadding((4*dp).toInt(), 0, 0, (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(fixedHeader)

        fun bookmarkGrid(items: List<Pair<String, String>>, loadRemoteIcons: Boolean) {
            if (items.isEmpty()) return
            val grid = GridLayout(this).apply {
                columnCount = 4; useDefaultMargins = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            items.forEach { (title, url) ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins((3*dp).toInt(), (3*dp).toInt(), (3*dp).toInt(), (10*dp).toInt())
                    }
                }
                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt())
                    setBackgroundResource(R.drawable.tab_card_bg)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
                    setImageResource(R.drawable.ic_favicon_fallback)
                }
                val label = TextView(this).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 10.5f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((2*dp).toInt(), (6*dp).toInt(), (2*dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams((54*dp).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                item.addView(icon); item.addView(label)
                item.setOnClickListener { navigateTo(url) }
                icon.setOnClickListener { navigateTo(url) }
                grid.addView(item)
                if (loadRemoteIcons) loadBookmarkFavicon(url, icon)
            }
            content.addView(grid)
        }

        val fixedSites = listOf(
            "GitHub"       to "https://github.com",
            "Stack Overflow" to "https://stackoverflow.com",
            "MDN"          to "https://developer.mozilla.org",
            "Kotlin"       to "https://kotlinlang.org"
        )
        bookmarkGrid(fixedSites, loadFavicons)

        val userBookmarks = bookmarkRepository.getBookmarks().take(12)
        if (userBookmarks.isNotEmpty()) {
            val userHeader = TextView(this).apply {
                text = "MY BOOKMARKS"
                setTextColor(Color.parseColor("#7B7B7B"))
                textSize = 11f; letterSpacing = 0.08f
                setPadding((4*dp).toInt(), (10*dp).toInt(), 0, (10*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            content.addView(userHeader)
            bookmarkGrid(userBookmarks, loadFavicons)
        }

        root.setOnClickListener { }
        return root
    }

    private fun buildErrorOverlay(): View {
        val dp   = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK); alpha = 0f; visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            lp.marginStart = (24*dp).toInt(); lp.marginEnd = (24*dp).toInt()
            layoutParams = lp
            setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
        }
        root.addView(content)

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_clear)
            setColorFilter(Color.parseColor("#A6C8FF"))
            layoutParams = LinearLayout.LayoutParams((80*dp).toInt(), (80*dp).toInt())
                .apply { bottomMargin = (12*dp).toInt() }
        }
        content.addView(icon)

        val title = TextView(this).apply {
            text = "Webpage not available"
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
        }
        content.addView(title)

        val desc = TextView(this).apply {
            text = "Could not load the requested page."
            setTextColor(Color.parseColor("#BBBBBB")); textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (8*dp).toInt(), 0, (10*dp).toInt())
        }
        content.addView(desc)

        val urlText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#777777")); textSize = 12f
            gravity = Gravity.CENTER
            setPadding((12*dp).toInt(), 0, (12*dp).toInt(), (20*dp).toInt())
            maxLines = 2
        }
        content.addView(urlText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        content.addView(buttonRow)

        fun makeButton(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 15f
                setPadding((18*dp).toInt(), (12*dp).toInt(), (18*dp).toInt(), (12*dp).toInt())
                setBackgroundResource(R.drawable.bg_menu_item)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (6*dp).toInt(); marginEnd = (6*dp).toInt() }
            }
        }

        buttonRow.addView(makeButton("Retry") { hideNativeOverlays(); currentWebView?.reload() })
        buttonRow.addView(makeButton("Home")  { showHomeOverlay() })
        buttonRow.addView(makeButton("Close") { hideNativeOverlays() })

        val updateUrlText = {
            urlText.text = lastErrorUrl?.let { "Could not connect to:\n$it" }
                ?: "Could not connect to the requested page."
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateUrlText() }
        root.post { updateUrlText() }
        return root
    }

    private fun fadeOverlay(view: View, visible: Boolean, immediate: Boolean = false) {
        if (immediate) {
            view.alpha      = if (visible) 1f else 0f
            view.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(140).start()
        } else {
            view.animate().alpha(0f).setDuration(120)
                .withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private fun hideNativeOverlays(immediate: Boolean = false) {
        nativeHomeOverlay?.let  { fadeOverlay(it, false, immediate) }
        nativeErrorOverlay?.let { fadeOverlay(it, false, immediate) }
        nativeOverlayContainer.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled    = true
    }

    // FIX #7 — showHomeOverlay تتحقق من dirty flag وتُعيد البناء عند الحاجة فقط
    private fun showHomeOverlay() {
        if (homeOverlayDirty) {
            buildNativeOverlays()
            homeOverlayDirty = false
        }
        lastErrorUrl = null
        setTopBarVisible(false)
        nativeErrorOverlay?.let { fadeOverlay(it, false) }
        nativeHomeOverlay?.let {
            nativeOverlayContainer.visibility = View.VISIBLE
            fadeOverlay(it, true)
            nativeOverlayContainer.bringToFront()
            swipeRefresh.isRefreshing = false
            swipeRefresh.isEnabled    = false
            progressBar.visibility    = View.INVISIBLE
            textUrl.setText("")
            hideKeyboard()
        }
    }

    private fun showErrorOverlay(url: String?) {
        lastErrorUrl = url
        setTopBarVisible(false)
        nativeHomeOverlay?.let  { fadeOverlay(it, false) }
        nativeErrorOverlay?.let {
            nativeOverlayContainer.visibility = View.VISIBLE
            fadeOverlay(it, true)
            nativeOverlayContainer.bringToFront()
            swipeRefresh.isRefreshing = false
            swipeRefresh.isEnabled    = false
            progressBar.visibility    = View.INVISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  الحفظ الدائم (SharedPreferences)
    // ─────────────────────────────────────────────────────────────────────────

    // FIX #1 — أخذ snapshot كامل على الـ main thread قبل الكتابة في الخلفية
    // يمنع ConcurrentModificationException الصامت
private fun savePersistentTabs() {
        sessionManager.saveToStorage(this)
    }

    fun loadPersistentTabs(intentUrl: String?) {
        if (sessionManager.restoreFromStorage(this) && sessionManager.tabGroups.isNotEmpty()) {
            val activeGroupTabs = currentGroup?.tabs
            val preferredTabId = activeTabId
            val activeTab = activeGroupTabs?.find { it.id == preferredTabId } ?: activeGroupTabs?.firstOrNull()
            activeTabId = activeTab?.id ?: 0

            if (activeTab != null) {
                val wv = ensureWebViewForTab(activeTab)
                if (webViewContainer.indexOfChild(wv) == -1) webViewContainer.addView(wv)
            }

            updateGroupsUI()
            refreshTabsRecycler()

            val activeTabUrl = currentGroup?.tabs?.find { it.id == activeTabId }?.url
            if (isHomeUrl(activeTabUrl) && intentUrl.isNullOrEmpty()) showHomeOverlay()
            else hideNativeOverlays(immediate = true)

            if (!intentUrl.isNullOrEmpty()) openNewTab(intentUrl)
            return
        }
        createNewGroup("Default", if (!intentUrl.isNullOrEmpty()) intentUrl else HOME_URL)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  إدارة المجموعات والتبويبات
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateGroupsUI() {
        tabGroupsContainer?.removeAllViews()
        for (group in tabGroups) {
            val tv = TextView(this).apply {
                text = group.name
                setPadding(32, 16, 32, 16)
                textSize = 14f
                if (group.id == activeGroupId) {
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_menu_item)
                } else {
                    setTextColor(Color.GRAY)
                    setBackgroundColor(Color.TRANSPARENT)
                }

                setOnClickListener {
                    activeGroupId = group.id
                    sanitizeActiveTabSelection()
                    refreshTabsRecycler()
                    updateGroupsUI()
                }

                setOnLongClickListener {
                    BrowserDialogHelpers.showModernPopup(this, group.name, listOf("Rename Group", "Delete Group")) { index ->
                        when (index) {
                            0 -> {
                                val input = EditText(this@MainActivity).apply {
                                    setText(group.name); setTextColor(Color.WHITE)
                                }
                                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
                                    .setTitle("Rename Group")
                                    .setView(input)
                                    .setPositiveButton("Save") { _, _ ->
                                        group.name = input.text.toString()
                                        updateGroupsUI(); savePersistentTabs()
                                    }.show()
                            }
                            1 -> {
                                if (tabGroups.size == 1) {
                                    Toast.makeText(this@MainActivity, "Cannot delete the last group", Toast.LENGTH_SHORT).show()
                                    return@showModernPopup
                                }
                                group.tabs.forEach { t ->
                                    ioExecutor.execute { File(cacheDir, "thumb_${t.id}.webp").delete() }
                                    webViews[t.id]?.destroy()
                                    webViews.remove(t.id)
                                }
                                tabGroups.remove(group)
                                if (activeGroupId == group.id) {
                                    activeGroupId = tabGroups.first().id
                                    activeTabId   = currentGroup?.tabs?.firstOrNull()?.id ?: 0
                                }
                                sanitizeActiveTabSelection()
                                updateGroupsUI(); refreshTabsRecycler(); savePersistentTabs()
                            }
                        }
                    }
                    true
                }
            }
            tabGroupsContainer?.addView(tv)
        }
    }

    private fun createNewGroup(name: String, url: String = HOME_URL) {
        sessionManager.createNewGroup(name)
        openNewTab(url)
        updateGroupsUI()
    }

    // FIX #6 — استخدام DiffUtil عبر submitUpdate في TabAdapter
    private fun refreshTabsRecycler() {
        sanitizeActiveTabSelection()
        val newTabs = currentGroup?.tabs?.toList() ?: emptyList()
        tabAdapter.submitUpdate(newTabs, activeTabId)
        updateTabCount()
    }

    private fun sanitizeActiveTabSelection(preferredTabId: Int? = null) {
        sessionManager.sanitizeActiveTabSelection(preferredTabId)
    }

    private fun showTabsOverlay() {
        sanitizeActiveTabSelection()
        captureAndStoreThumbnail {
            refreshTabsRecycler()
            tabsOverlay.visibility = View.VISIBLE
        }
    }

    private fun openNewTab(url: String = HOME_URL) {
        captureAndStoreThumbnail {
            val id     = nextTabId++
            val newTab = TabState(id = id, title = "New Tab", url = url)
            if (isHomeUrl(url)) {
                newTab.hasThumbnail  = true
                newTab.thumbnailUrl  = url
                newTab.ramThumbnail  = getHomePreviewBitmap()
            }
            currentGroup?.tabs?.add(newTab)

            val wv = ensureWebViewForTab(newTab)
            if (wv.parent == null && webViewContainer.childCount == 0) {
                webViewContainer.addView(wv)
            }
            switchToTab(newTab)
            refreshTabsRecycler()
            savePersistentTabs()
        }
    }

    private fun switchToTab(tab: TabState) {
        hideKeyboard()

        val shouldAnimateOverlay = tabsOverlay.visibility == View.VISIBLE

        val executeSwitch = {
            val targetWebView = ensureWebViewForTab(tab)
            activeTabId = tab.id

            webViewContainer.removeAllViews()
            webViewContainer.addView(targetWebView)
            updateUIForCurrentWebView(targetWebView)

            if (isHomeUrl(tab.url)) showHomeOverlay()
            else { hideNativeOverlays(); setTopBarVisible(true) }

            updateTabCount()
            savePersistentTabs()
            refreshTabsRecycler()

            if (shouldAnimateOverlay) {
                tabsOverlay.postDelayed({
                    if (tabsOverlay.visibility == View.VISIBLE) {
                        tabsOverlay.visibility = View.GONE
                    }
                }, 120)
            } else {
                tabsOverlay.visibility = View.GONE
            }
        }

        if (activeTabId != tab.id && currentWebView != null) {
            captureAndStoreThumbnail { executeSwitch() }
        } else {
            executeSwitch()
        }
    }

    // FIX #6 — حذف notifyItemRemoved اليدوي، نستخدم refreshTabsRecycler مع DiffUtil
    private fun closeTab(tab: TabState) {
        val group = currentGroup ?: return
        val idx   = group.tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0) return

        val wasActive = tab.id == activeTabId
        val closingWebView = webViews.remove(tab.id)

        tab.ramThumbnail = null
        tab.faviconBitmap = null
        ioExecutor.execute { BrowserCacheFiles.tabThumbnailFile(cacheDir, tab.id).delete() }

        group.tabs.removeAt(idx)

        fun destroyClosedTabWebView() {
            closingWebView?.let { wv ->
                runCatching {
                    if (webViewContainer.indexOfChild(wv) >= 0) {
                        webViewContainer.removeView(wv)
                    }
                }
                runCatching { wv.stopLoading() }
                runCatching { wv.clearHistory() }
                runCatching { wv.removeAllViews() }
                runCatching { wv.destroy() }
            }
        }

        if (group.tabs.isEmpty()) {
            activeTabId = 0
            destroyClosedTabWebView()
            openNewTab(HOME_URL)
            return
        }

        if (wasActive) {
            val fallbackTab = group.tabs.getOrNull(maxOf(0, idx - 1)) ?: group.tabs.first()
            activeTabId = fallbackTab.id
            switchToTab(fallbackTab)
            destroyClosedTabWebView()
        } else {
            destroyClosedTabWebView()
            sanitizeActiveTabSelection()
            refreshTabsRecycler()  // DiffUtil يتولى الـ animation
            savePersistentTabs()
        }
    }

    // FIX #11 — LRU eviction: لا نحتفظ بأكثر من MAX_LIVE_WEBVIEWS في الذاكرة
    private fun ensureWebViewForTab(tab: TabState, restoreState: Bundle? = null): WebView {
        webViews[tab.id]?.let { return it }

        // طرد الـ WebView الأقل استخداماً إذا تجاوزنا الحد الأقصى
        if (webViews.size >= MAX_LIVE_WEBVIEWS) {
            val evictId = webViews.keys.firstOrNull { it != activeTabId }
            if (evictId != null) {
                webViews[evictId]?.let { wv ->
                    if (webViewContainer.indexOfChild(wv) >= 0) webViewContainer.removeView(wv)
                    wv.destroy()
                }
                webViews.remove(evictId)
            }
        }

        val wv = webViewFactory.create(tab.id)
        webViews[tab.id] = wv
        if (restoreState != null) {
            runCatching { wv.restoreState(restoreState) }
        } else if (!isHomeUrl(tab.url)) {
            wv.loadUrl(tab.url)
        } else {
            wv.loadUrl(HOME_URL)
        }
        return wv
    }

    private fun updateTabCount() {
        val totalTabs = tabGroups.sumOf { it.tabs.size }
        tabCount.text = totalTabs.toString()
    }

    private fun updateUIForCurrentWebView(wv: WebView) {
        val url = wv.url ?: HOME_URL
        textUrl.setText(if (isHomeUrl(url)) "" else url)
        updateBookmarkIcon(url)
        progressBar.progress   = wv.progress
        progressBar.visibility = if (wv.progress < 100) View.VISIBLE else View.INVISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  التقاط صور مصغرة للتبويبات
    // ─────────────────────────────────────────────────────────────────────────

    private fun captureAndStoreThumbnail(onComplete: (() -> Unit)? = null) {
        val wv = currentWebView
        if (wv == null || wv.width <= 0 || wv.height <= 0 || !wv.isAttachedToWindow) {
            currentGroup?.tabs?.find { it.id == activeTabId }?.let {
                if (isHomeUrl(it.url)) {
                    it.hasThumbnail = true
                    it.ramThumbnail = getHomePreviewBitmap()
                }
            }
            onComplete?.invoke()
            return
        }

        val tabId      = activeTabId
        val currentUrl = runCatching { wv.url ?: HOME_URL }.getOrDefault(HOME_URL)
        val file       = BrowserCacheFiles.tabThumbnailFile(cacheDir, tabId)
        val tabRef     = currentGroup?.tabs?.find { it.id == tabId }

        if (file.exists() && tabRef?.thumbnailUrl == currentUrl) {
            onComplete?.invoke(); return
        }

        try {
            val homeLike = isHomeUrl(currentUrl)
            val bitmap   = if (homeLike) {
                getHomePreviewBitmap()
            } else {
                val scale  = 0.3f
                val w      = maxOf(1, (wv.width * scale).toInt())
                val h      = maxOf(1, (wv.height * scale).toInt())
                val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                canvas.scale(scale, scale)
                canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                runCatching { wv.draw(canvas) }
                bmp
            }

            tabRef?.let {
                it.hasThumbnail  = true
                it.thumbnailUrl  = currentUrl
                it.ramThumbnail  = bitmap
            }
            onComplete?.invoke()

            ioExecutor.execute {
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) {
            onComplete?.invoke()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  إنشاء WebView جديد مع جميع الإعدادات والمستمعين
    // ─────────────────────────────────────────────────────────────────────────

    private fun setFullscreen(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen)
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            else
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  مستمعات الأزرار
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> (currentWebView?.scrollY ?: 0) > 0 }
        swipeRefresh.setOnRefreshListener { currentWebView?.reload() }

        textUrl.setOnEditorActionListener { _, _, _ ->
            navigateTo(textUrl.text.toString().trim()); hideKeyboard(); true
        }

        textUrl.setOnLongClickListener {
            BrowserDialogHelpers.showModernPopup(this, "URL Options", listOf("Copy URL", "Share URL")) { index ->
                when (index) {
                    0 -> {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("URL", currentWebView?.url ?: ""))
                        Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    1 -> startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentWebView?.url ?: "")
                        }, "Share URL"
                    ))
                }
            }
            true
        }

        findViewById<View>(R.id.btnBackArea).setOnClickListener    { currentWebView?.let { if (it.canGoBack())    it.goBack()    } }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { currentWebView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { showHomeOverlay() }

        findViewById<View>(R.id.btnTabsArea).setOnClickListener {
            if (tabsOverlay.visibility == View.VISIBLE) {
                tabsOverlay.visibility = View.GONE
            } else {
                showTabsOverlay()
            }
        }

        findViewById<View>(R.id.btnNewTab).setOnClickListener { openNewTab() }

        findViewById<View?>(R.id.btnNewGroup)?.setOnClickListener {
            val input = EditText(this).apply { setTextColor(Color.WHITE); setPadding(32, 32, 32, 32) }
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("New Group Name")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    createNewGroup(input.text.toString().ifEmpty { "Group" })
                }.show()
        }

        findViewById<View>(R.id.btnMenuArea).setOnClickListener { showMenuSheet() }

        // FIX #7 — إزالة buildNativeOverlays() من هنا وتعويضها بـ dirty flag
        btnBookmark.setOnClickListener {
            val url = currentWebView?.url ?: return@setOnClickListener
            if (isHomeUrl(url)) return@setOnClickListener
            val added = bookmarkRepository.toggleBookmark(currentWebView?.title ?: "Bookmark", url)
            updateBookmarkIcon(url)
            homeOverlayDirty = true       // FIX #7 — سيُعيد البناء عند الظهور فقط
            invalidateHomePreviewCache()  // FIX #4 — إبطال cache الـ preview
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnQr).setOnClickListener { launchQrScanner() }

        val inputFind  = findViewById<EditText>(R.id.findInput)
        val tvMatches  = findViewById<TextView>(R.id.findMatches)
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

    // ─────────────────────────────────────────────────────────────────────────
    //  التنقل والبحث
    // ─────────────────────────────────────────────────────────────────────────

    private fun navigateTo(input: String) {
        val trimmed = input.trim()
        val finalUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file:") -> trimmed
            isLocalhostUrl(trimmed) -> "http://$trimmed"
            Patterns.WEB_URL.matcher(trimmed).matches() -> "https://$trimmed"
            else -> buildSearchUrl(prefsManager.searchEngine, trimmed)
        }
        loadUrlInstantly(finalUrl)
    }

    private fun loadUrlInstantly(url: String) {
        if (isHomeUrl(url)) {
            showHomeOverlay()
            currentWebView?.loadUrl(HOME_URL)
            textUrl.setText("")
            return
        }
        setTopBarVisible(true)
        hideNativeOverlays()
        textUrl.setText(url)
        progressBar.progress   = 5
        progressBar.visibility = View.VISIBLE
        currentWebView?.loadUrl(url)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  قوائم منبثقة حديثة
    // ─────────────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────────────
    //  القائمة الرئيسية (القائمة السفلية)
    // ─────────────────────────────────────────────────────────────────────────

    private fun showMenuSheet() {
        if (cachedMenuSheet == null) {
            cachedMenuSheet     = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
            cachedMenuSheetView = layoutInflater.inflate(R.layout.layout_main_menu, null)
            cachedMenuSheet?.setContentView(cachedMenuSheetView!!)

            cachedMenuSheetView?.findViewById<View>(R.id.menuNightMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                currentWebView?.evaluateJavascript(
                    "(function(){var el=document.getElementById('__cf_night');" +
                    "if(el){el.remove();}else{var s=document.createElement('style');" +
                    "s.id='__cf_night';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important}" +
                    "img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
                    "document.head.appendChild(s);}})()", null
                )
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuBookmarks)?.setOnClickListener {
                cachedMenuSheet?.dismiss(); BrowserDialogHelpers.showBookmarksDialog(
                context = this,
                getBookmarks = { bookmarkRepository.getBookmarks() },
                openUrl = { loadUrlInstantly(it) },
                loadFavicon = { url, imageView -> loadBookmarkFavicon(url, imageView) }
            )
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
                cachedMenuSheet?.dismiss(); BrowserDialogHelpers.showHistoryDialog(
                context = this,
                getHistory = { historyRepository.getHistory() },
                openUrl = { loadUrlInstantly(it) },
                loadFavicon = { url, imageView -> loadBookmarkFavicon(url, imageView) }
            )
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuConsoleToggle)?.setOnClickListener {
                cachedMenuSheet?.dismiss(); toggleConsoleForCurrentPage()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuFindInPage)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                findBar.visibility = View.VISIBLE
                currentWebView?.setFindListener { ord, total, _ ->
                    findViewById<TextView>(R.id.findMatches).text =
                        if (total > 0) "${ord + 1}/$total" else "0/0"
                }
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuDesktopMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                prefsManager.desktopMode = !prefsManager.desktopMode
                webViews.values.forEach { WebViewSettingsHelper.applyUserAgentToWebView(this, it, prefsManager.desktopMode) }
                // FIX #12 — حذف clearCache(true) الذي كان يمسح cache كل المواقع
                currentWebView?.reload()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
                cachedMenuSheet?.dismiss(); startSettingsActivity()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuClearData)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                AlertDialog.Builder(this, R.style.DarkDialog)
                    .setTitle("Clear Browsing Data")
                    .setMessage("This will delete cache, cookies, and history.")
                    .setPositiveButton("Clear") { _, _ -> clearData() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        val desktopLabel = cachedMenuSheetView?.findViewById<TextView>(R.id.menuDesktopModeLabel)
        if (prefsManager.desktopMode) {
            desktopLabel?.text = "Desktop On"
            desktopLabel?.setTextColor(Color.WHITE)
        } else {
            desktopLabel?.text = "Desktop"
            desktopLabel?.setTextColor(Color.parseColor("#CCCCCC"))
        }
        updateMenuConsoleState()
        cachedMenuSheet?.show()
    }




    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webViews.values.forEach { it.clearCache(true); it.clearHistory() }
        historyRepository.clearHistory()
        invalidateHomePreviewCache()  // FIX #4 — أبطل الـ cache عند مسح البيانات

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("SAVED_GROUPS").remove("ACTIVE_GROUP")
            .remove("ACTIVE_TAB").remove("NEXT_TAB_ID").remove("NEXT_GROUP_ID")
            .apply()
        cacheDir.listFiles()?.forEach { file ->
            if ((file.name.startsWith("thumb_") && file.name.endsWith(".webp")) ||
                (file.name.startsWith("home_preview_") &&
                    (file.name.endsWith(".webp") || file.name.endsWith(".sig")))) {
                file.delete()
            }
        }
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  إعدادات User-Agent ووضع سطح المكتب
    // ─────────────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────────────
    //  تحديث أيقونة محرك البحث والعلامة المرجعية
    // ─────────────────────────────────────────────────────────────────────────

    // FIX #3 — يُعيد resolve مرجع الأيقونة من الـ View الحي عبر tag
    private fun updateSearchEngineIcon() {
        val res = currentSearchEngineIconRes()
        imgSearchEngine.setImageResource(res)
        imgSearchEngine.colorFilter = null
        // دائماً نحل المرجع من الـ hierarchy الحالي لتجنب المرجع الصوري
        val liveIcon = nativeHomeOverlay?.findViewWithTag<View>("home_search_engine_icon") as? ImageView
        if (liveIcon != null) homeSearchEngineIcon = liveIcon
        homeSearchEngineIcon?.setImageResource(res)
        homeSearchEngineIcon?.colorFilter = null
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha =
            if (!isHomeUrl(url) && prefsManager.isBookmarked(url)) 1.0f else 0.4f
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  أدوات مساعدة
    // ─────────────────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val token = currentFocus?.windowToken ?: textUrl.windowToken
        if (token != null) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
    }

    private fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        fullscreenContainer.visibility = View.GONE
        webViewContainer.visibility    = View.VISIBLE
        customView = null
        setFullscreen(false)
    }

    private fun startSettingsActivity() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Console (Eruda) — سكريبتات الحقن
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleConsoleForCurrentPage() {
        val enable = !prefsManager.consoleEnabled
        prefsManager.consoleEnabled = enable
        currentWebView?.let { view ->
            if (enable) {
                view.evaluateJavascript(ConsoleScripts.initScript(), null)
                view.evaluateJavascript(ConsoleScripts.touchHookScript(), null)
            } else {
                view.evaluateJavascript(ConsoleScripts.disableScript(), null)
            }
        }
        updateMenuConsoleState()
        Toast.makeText(this, if (enable) "Console enabled" else "Console disabled", Toast.LENGTH_SHORT).show()
    }

    private fun updateMenuConsoleState() {
        val enabled = prefsManager.consoleEnabled
        cachedMenuSheetView?.findViewById<TextView>(R.id.menuConsoleLabel)?.apply {
            text = if (enabled) "Console On" else "Console Off"
            setTextColor(if (enabled) Color.WHITE else Color.parseColor("#CCCCCC"))
        }
    }

    private fun applyConsoleTools(view: WebView) {
        if (prefsManager.consoleEnabled) {
            view.evaluateJavascript(ConsoleScripts.initScript(), null)
            view.evaluateJavascript(ConsoleScripts.touchHookScript(), null)
        } else {
            view.evaluateJavascript(ConsoleScripts.disableScript(), null)
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  جسر JavaScript
    // ─────────────────────────────────────────────────────────────────────────

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) { runOnUiThread { navigateTo(input) } }

        @JavascriptInterface
        fun setSwipeRefresh(enabled: Boolean) {
            mainHandler.post { swipeRefresh.isEnabled = enabled }
        }
    }
}
