package space.karrarnazim.ConsoleFlow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.ClipboardManager
import android.content.pm.*
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.net.*
import android.os.*
import android.provider.*
import android.text.*
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.*
import android.webkit.*
import android.webkit.CookieManager
import android.widget.*
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.*
import androidx.fragment.app.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.util.Patterns
import android.speech.RecognizerIntent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import androidx.lifecycle.*
import com.google.android.material.tabs.*
import androidx.appcompat.app.*
import androidx.appcompat.widget.*
import androidx.core.content.*
import androidx.core.view.*
import androidx.recyclerview.widget.*
import androidx.swiperefreshlayout.widget.*
import space.karrarnazim.ConsoleFlow.ui.adapters.SuggestionsAdapter
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
    // BUG-AA FIX: ensureWebViewForTab's eviction picks
    // `webViews.keys.firstOrNull { it != activeTabId }` and the comment there
    // calls it "LRU eviction" — but a plain mutableMapOf() (insertion-order
    // LinkedHashMap) made that pick the OLDEST-CREATED tab, not the least
    // recently used one. A tab opened early but revisited constantly was the
    // first eviction candidate, while one opened recently and never touched
    // again stayed alive forever. accessOrder=true bumps an entry to the end
    // on every get()/put(), so the entry actually at the front is genuinely
    // the least-recently-used one — matching what the eviction code assumes.
    private val webViews = LinkedHashMap<Int, GeckoTabSession>(16, 0.75f, true)
    private val currentWebView: GeckoTabSession? get() = webViews[activeTabId]

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    // ── Search Overlay (full-screen, shown on URL bar tap) ──────────────────
    private val suggestionsManager = SearchSuggestionsManager()
    private lateinit var suggestionsAdapter: SuggestionsAdapter
    private lateinit var suggestionsRecycler: RecyclerView
    /** Root container of the full-screen search overlay, added programmatically. */
    private var searchOverlayContainer: FrameLayout? = null
    private lateinit var overlaySearchInput: EditText
    private lateinit var overlayClearBtn: ImageView

    /**
     * True on Android TV (leanback). Used to pick wider grids and scaled
     * programmatic views. The values-television/ resource qualifier handles
     * all XML-defined dimensions automatically — this flag covers Kotlin code only.
     */
    private val isTV: Boolean by lazy { TvUtils.isTelevision(this) }

    // Guards the one-time overscan-safe padding pass applied to the home
    // overlay (see onResume). Runs once; TV overscan doesn't change at runtime.
    private var homeOverlayOverscanApplied = false

    private fun px(resId: Int) = resources.getDimensionPixelSize(resId)
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
    // GeckoView renders fullscreen video in-place — no CustomView swap needed,
    // unlike the old WebChromeClient API. Just track whether we're in it.
    private var isFullscreenActive = false
    // BUG-V FIX (preserved): holds the pending callback while the Android
    // runtime permission dialog is open, so getUserMedia() actually resolves.
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null
    // BUG-N FIX (preserved): holds the pending callback while the system file
    // picker is open, for <input type="file">.
    private var pendingFileChooserResult: ((List<Uri>?) -> Unit)? = null

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
    private lateinit var webViewFactory: GeckoSessionFactory

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
        pendingPermissionResult?.invoke(perms.values.all { it })
        pendingPermissionResult = null
    }

    // POST_NOTIFICATIONS permission for download completion alerts (API 33+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — downloads work either way */ }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned -> navigateTo(scanned) }
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        transcript?.let { query -> if (query.isNotBlank()) navigateTo(query) }
    }

    // BUG-N FIX: completes the <input type="file"> flow started by onShowFileChooser
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: List<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> List(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
            data.data != null -> listOf(data.data!!)
            else -> null
        }
        pendingFileChooserResult?.invoke(uris)
        pendingFileChooserResult = null
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

        // ⚠️ إصلاح خطأ حقيقي: ErudaAssetCache.preload() كانت مُعرَّفة في
        // ConsoleToolsInjector.kt لكن ما انحطّت باستدعائها بأي مكان —
        // يعني الكونسول كان سيفشل بصمت (eruda غير معرَّف، الخطأ يُبتلَع
        // بصمت داخل try/catch الخاص بحقن السكربت). قراءة الملف I/O من
        // assets فتُنفَّذ في خيط خلفي بدل حجب onCreate.
        ioExecutor.execute {
            runCatching {
                assets.open("eruda.js").bufferedReader().use { it.readText() }
            }.getOrNull()?.let { ErudaAssetCache.preload(it) }
        }

        sessionManager = BrowserSessionManager()
        webViewFactory = GeckoSessionFactory(
            activity = this,
            prefsManager = prefsManager,
            isHomeUrl = { isHomeUrl(it) },
            bookmarkRepository = bookmarkRepository,
            onOpenNewTab = { openNewTab(it) },
            onMarkHomeOverlayDirty = { homeOverlayDirty = true },
            onInvalidateHomePreviewCache = { invalidateHomePreviewCache() },
            onSetSwipeRefresh = { enabled -> swipeRefresh.isEnabled = enabled && swipeRefreshAllowed() },
            onPageStartedUi = { tab ->
                // BUG-L FIX (preserved): only touch shared chrome UI when this
                // is the tab currently on screen — a background tab starting a
                // new navigation must never overwrite the visible address bar.
                if (tab.tabId == activeTabId) {
                    keepCursorAlive()
                    progressBar.visibility = View.VISIBLE
                    textUrl.setText(if (isHomeUrl(tab.url)) "" else tab.url)
                    updateBookmarkIcon(tab.url ?: "")
                    if (isHomeUrl(tab.url)) setTopBarVisible(false) else setTopBarVisible(true)
                }
            },
            onProgressChangedUi = { tab ->
                // BUG-K FIX (preserved): a background tab loading must not
                // move the active tab's progress bar.
                if (tab.tabId == activeTabId) progressBar.progress = tab.progress
            },
            onFullScreenUi = { fullScreen ->
                isFullscreenActive = fullScreen
                setFullscreen(fullScreen)
            },
            onAndroidPermissionsNeededUi = { perms, onResult ->
                // BUG-V FIX (preserved): getUserMedia() (camera/mic — video
                // calls, WebRTC, voice input) must reach a real system prompt,
                // not hang forever.
                pendingPermissionResult = onResult
                requestPermissionLauncher.launch(perms)
            },
            onShowFileChooserUi = { mimeTypes, onResult ->
                // BUG-N FIX (preserved): <input type="file"> must open the
                // system file/image/camera picker.
                pendingFileChooserResult = onResult
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeTypes.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "*/*"
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                val launched = runCatching { fileChooserLauncher.launch(intent) }.isSuccess
                if (!launched) {
                    pendingFileChooserResult = null
                    Toast.makeText(this, "No file picker app available", Toast.LENGTH_SHORT).show()
                    onResult(null)
                }
            },
            onPageFinishedUi = { tab ->
                // BUG-2 FIX (preserved): only touch UI state for the currently
                // visible tab; a background tab finishing load must not hide
                // the active tab's progress bar or its pull-to-refresh spinner.
                val isActiveTab = tab.tabId == activeTabId

                if (isActiveTab) {
                    keepCursorAlive()
                    swipeRefresh.isRefreshing = false
                    progressBar.visibility = View.INVISIBLE
                }

                val pageUrl = tab.url
                if (pageUrl != null) {
                    if (!isHomeUrl(pageUrl)) {
                        val title = tab.title ?: "Unknown"
                        ioExecutor.execute { historyRepository.addHistory(title, pageUrl) }
                    }

                    currentGroup?.tabs?.find { it.id == tab.tabId }?.let { tabState ->
                        tabState.title = tab.title ?: "Tab"
                        tabState.url = if (isHomeUrl(pageUrl)) HOME_URL else pageUrl
                        if (isHomeUrl(pageUrl) && tabState.ramThumbnail == null) {
                            tabState.ramThumbnail = getHomePreviewBitmap()
                            tabState.hasThumbnail = true
                            tabState.thumbnailUrl = HOME_URL
                        }
                    }

                    // BUG-1 FIX (preserved): returning to about:blank/home must
                    // show the home overlay instead of a blank screen.
                    if (isActiveTab && isHomeUrl(pageUrl)) showHomeOverlay()

                    refreshTabsRecycler()
                    savePersistentTabs()
                }

                // ⚠️ حُذف حقن viewport meta اليدوي القديم من هنا — كان
                // ضرورياً في WebView لأنه ما عنده مكافئ أصلي. GeckoView عنده
                // GeckoSessionSettings.VIEWPORT_MODE_DESKTOP (مُطبَّق في
                // GeckoTabSession.setDesktopMode()) الذي يغني عن هذا تماماً.
                // إبقاؤه كان سيعني منطقاً مكرَّراً بلا داعٍ.
            },
            onReceivedIconUi = { tab ->
                // NOTE: GeckoView's ContentDelegate has no onReceivedIcon
                // equivalent to WebView's favicon callback — this currently
                // only refreshes the tab title in the recycler. Favicon
                // fetching needs a separate manual request (e.g. reading
                // <link rel="icon"> after page load) — tracked as a follow-up,
                // not silently faked here.
                currentGroup?.tabs?.find { it.id == tab.tabId }?.let {
                    refreshTabsRecycler()
                }
            },
            onReceivedErrorUi = { url -> showErrorOverlay(url) },
            onApplyConsoleTools = { tab -> applyConsoleTools(tab) },
            onDownloadStart = { url, contentType, contentLength, filename ->
                handleDownloadRequest(url, contentType ?: "application/octet-stream", contentLength, filename)
            }
        )

        initViews()
        cursorController = CursorController(this).also { it.attach() }
        inputController  = buildInputController().also { it.setCursorController(cursorController) }

        // Refresh menu mini-list while Downloads tab is open
        DownloadTracker.downloads.observe(this) {
            if (cachedMenuSheet?.isShowing == true) {
                val pageDl = cachedMenuSheetView?.findViewById<View>(R.id.menuPageDownloads)
                if (pageDl?.visibility == View.VISIBLE) populateMenuDownloadsList()
            }
        }
        setupListeners()
        window.decorView.setOnGenericMotionListener { _, event ->
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
                cursorArmed = true
            }
            keepCursorAlive()
            inputController.onGenericMotion(event)
        }
        setTopBarVisible(false, immediate = true)

        val intentUrl = intent?.data?.toString()

        if (savedInstanceState != null) {
            val savedGroups = savedInstanceState.getSerializable("GROUPS_LIST") as? ArrayList<TabGroup>
            if (savedGroups != null && savedGroups.isNotEmpty()) {
                tabGroups.clear()
                tabGroups.addAll(savedGroups)
                activeGroupId = savedInstanceState.getInt("ACTIVE_GROUP_ID", tabGroups.first().id)
                // BUG-W FIX: fall back to a real group if the saved id doesn't
                // match any restored group (defensive — currentGroup would
                // otherwise be null and the rest of this block silently no-ops).
                if (tabGroups.none { it.id == activeGroupId }) activeGroupId = tabGroups.first().id
                activeTabId   = savedInstanceState.getInt("ACTIVE_TAB_ID",   tabGroups.first().tabs.firstOrNull()?.id ?: 0)
                nextTabId     = savedInstanceState.getInt("NEXT_TAB_ID", 100)
                nextGroupId   = savedInstanceState.getInt("NEXT_GROUP_ID", 100)

                // BUG-W FIX: mirrors loadPersistentTabs' self-heal (BUG-M) for a
                // restored active group with zero tabs — without this, no
                // WebView is ever created/attached below, leaving only the
                // home overlay floating over an empty webViewContainer.
                if (currentGroup?.tabs.isNullOrEmpty()) sessionManager.createNewTab(HOME_URL)

                val activeTab = currentGroup?.tabs?.find { it.id == activeTabId }
                    ?: currentGroup?.tabs?.firstOrNull()
                if (activeTab != null) {
                    val wv = ensureWebViewForTab(activeTab)
                    wv.setActive(true)
                    webViewContainer.addView(wv.geckoView)
                    wv.requestFocus()
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
                tabsOverlay.visibility == View.VISIBLE -> { tabsOverlay.visibility = View.GONE; keepCursorAlive() }
                nativeOverlayContainer.visibility == View.VISIBLE -> hideNativeOverlays()
                // Dismiss the search overlay before any deeper back action.
                searchOverlayContainer?.visibility == View.VISIBLE -> hideSearchOverlay()
                topBar.visibility == View.VISIBLE && isHomeUrl(currentWebView?.url) -> setTopBarVisible(false)
                isFullscreenActive -> hideCustomView()
                findBar.visibility == View.VISIBLE -> {
                    findBar.visibility = View.GONE
                    currentWebView?.clearMatches()
                    keepCursorAlive()
                }
                currentWebView?.canGoBack == true -> currentWebView?.goBack()
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
        // ⚠️ فجوة مُغلقة: TabState.sessionStateJson (يُحدَّث تلقائياً عبر
        // GeckoSession.ProgressDelegate.onSessionStateChange — نمط دفع، لا
        // استطلاع يدوي غير متزامن) هو مجرد حقل String عادي على tabGroups
        // القابل للتسلسل أصلاً — فيُحفَظ هنا تلقائياً مع putSerializable
        // بلا أي كود إضافي. هذا يستعيد تاريخ التصفح الفعلي داخل كل تبويب،
        // وليس فقط الرابط الحالي.
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineIcon()
        keepCursorAlive()
        currentWebView?.setActive(true)

        // BUG-5 FIX: SettingsActivity's "Clear Data" can't access the live WebViews,
        // so it sets a flag. We honour it here where we have full access.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("pending_full_clear", false)) {
            prefs.edit().remove("pending_full_clear").apply()
            clearData()
        }

        // BUG-R FIX: the user-agent / viewport settings are applied to a WebView
        // once, at creation time (BrowserWebViewFactory). Toggling "Desktop Mode"
        // from SettingsActivity only wrote a SharedPreferences value — any tab
        // that was already open kept its old user-agent forever (the toggle
        // looked like it simply didn't work). Re-sync every live WebView here;
        // applyUserAgentToWebView is idempotent so this is a no-op when nothing
        // changed.
        webViews.values.forEach { it.setDesktopMode(prefsManager.desktopMode) }

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

        // TV FIX: window insets only describe system bars (status/nav), not
        // panel overscan. Most TV screens crop ~5% off every edge, so the
        // home overlay's logo/tiles were rendered partly outside the visible
        // picture. Apply once — no-op on phones.
        if (!homeOverlayOverscanApplied && isTV) {
            homeOverlayOverscanApplied = true
            TvUtils.applyOverscanSafePadding(this, nativeOverlayContainer)
        }
    }

    override fun onPause() {
        super.onPause()
        currentWebView?.setActive(false)
        savePersistentTabs()
    }

    override fun onDestroy() {
        webViews.values.forEach { wv ->
            webViewContainer.removeView(wv.geckoView)
            wv.destroy()
        }
        webViews.clear()
        ioExecutor.shutdown()
        // Cancel any in-flight / debounced suggestion fetch so the OkHttp
        // callback can't post to the main handler after the Activity is gone.
        suggestionsManager.cancel()
        cachedMenuSheet?.dismiss()
        inputController.release()
        cursorController.detach()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // BUG-Q FIX: MainActivity declares configChanges for orientation/screenSize
        // so it is never recreated on rotation (intentional — avoids reloading
        // every open WebView). But nothing was refreshing the virtual cursor's
        // screen bounds, which were captured once in CursorController.attach().
        // Wait for the new layout pass so decorView.width/height are accurate.
        if (::cursorController.isInitialized) {
            window.decorView.post { cursorController.refreshScreenBounds() }
        }
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

        // ── Search Overlay: adapter initialised here; the View tree is built
        //    lazily in buildSearchOverlay() the first time showSearchOverlay() fires,
        //    because at this point the root ConstraintLayout isn't fully measured yet.
        suggestionsAdapter = SuggestionsAdapter(
            context    = this,
            onNavigate = { suggestion ->
                hideSearchOverlay()
                navigateTo(suggestion)
            },
            onFill = { suggestion ->
                overlaySearchInput.setText(suggestion)
                overlaySearchInput.setSelection(suggestion.length)
            }
        )

        buildNativeOverlays()
        setTopBarVisible(false, immediate = true)

        // FIX #5 — نمرر ioExecutor للـ Adapter بدلاً من أن ينشئ هو thread pool خاص به
        tabAdapter = TabAdapter(
            context    = this,
            ioExecutor = ioExecutor,
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, if (isTV) 4 else 2)
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
        // BUG-7 FIX: also clear stale home-preview bitmaps cached inside TabState
        // objects so the tabs overlay shows the refreshed home preview immediately.
        tabGroups.forEach { group ->
            group.tabs.filter { isHomeUrl(it.url) }.forEach { it.ramThumbnail = null }
        }
    }

    fun getHomePreviewBitmap(force: Boolean = false): Bitmap {
        // FIX #4 — أولاً: تحقق من الـ in-memory cache لتجنب File I/O على main thread
        if (!force) {
            homePreviewBitmapCache?.let { return it }
        }

        val width  = resources.displayMetrics.widthPixels.coerceAtLeast(360)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(640)
        val key        = BrowserCacheFiles.homePreviewCacheKey(width, height)
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
        keepCursorAlive()
        // Search overlay belongs to the URL bar — close it when the bar hides.
        if (!visible) hideSearchOverlay()
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
        fun px(r: Int) = resources.getDimensionPixelSize(r)
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
            setImageResource(R.drawable.logo_consoleflow)
            layoutParams = LinearLayout.LayoutParams(
                (130 * dp).toInt(),
                (25 * dp).toInt()
            ).apply { marginEnd = (8 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_START
            alpha = 0.95f
        }
        topRow.addView(topAction)
        topRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        val settingsBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.home_settings_btn), px(R.dimen.home_settings_btn))
            alpha = 0.85f
            setPadding((9*dp).toInt(), (9*dp).toInt(), (9*dp).toInt(), (9*dp).toInt())
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        topRow.addView(settingsBtn)

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_search)
            setPadding((14*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(R.dimen.home_bar_height)
            ).apply { bottomMargin = (26*dp).toInt() }
        }
        content.addView(searchBar)

        val searchIcon = ImageView(this).apply {
            tag = "home_search_engine_icon"
            setImageResource(currentSearchEngineIconRes())
            setColorFilter(Color.parseColor("#7E7E7E"))
            val sz = px(R.dimen.home_search_icon)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
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
            // Display-only for typing; tapping or D-pad focusing opens the search overlay.
            isFocusableInTouchMode = false
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showSearchOverlay("") }
            setOnClickListener { showSearchOverlay("") }
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

        fun bookmarkGrid(items: List<kotlin.Pair<String, String>>, loadRemoteIcons: Boolean) {
            if (items.isEmpty()) return
            val tileSz   = px(R.dimen.home_bookmark_tile)
            val textSzSp = resources.getDimension(R.dimen.home_bookmark_text_size) /
                           resources.displayMetrics.scaledDensity
            val grid = GridLayout(this).apply {
                columnCount = if (isTV) 6 else 4
                useDefaultMargins = true
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
                    layoutParams = LinearLayout.LayoutParams(tileSz, tileSz)
                    setBackgroundResource(R.drawable.tab_card_bg)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
                    setImageResource(R.drawable.ic_favicon_fallback)
                }
                val label = TextView(this).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = textSzSp; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((2*dp).toInt(), (6*dp).toInt(), (2*dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(tileSz + (10*dp).toInt(),
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
        buttonRow.addView(makeButton("Home")  { goHome() })
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

    /**
     * ⚠️ يستبدل JsBridge المحذوف بالخطأ (انظر التوضيح المفصّل في ملخص هذه
     * الجولة). القديم كان يعطّل swipe-refresh فقط أثناء اللمس الفعلي على
     * لوحة eruda (دقة عالية عبر JS). GeckoView لا يملك addJavascriptInterface
     * فبنيت الحل الصحيح (WebExtension) مهمة كبيرة منفصلة — كحل عملي فوري
     * أدق تقريب ممكن بلا جسر: تعطيل "اسحب للتحديث" بالكامل طالما الكونسول
     * ظاهر، بدل فقط أثناء اللمس الفعلي. تقريب أخشن لكنه يحل نفس المشكلة
     * الأصلية (تعارض السحب مع سحب لوحة eruda) بلا أي جسر جافاسكربت.
     */
    private fun swipeRefreshAllowed(): Boolean = !prefsManager.consoleEnabled

    private fun hideNativeOverlays(immediate: Boolean = false) {
        keepCursorAlive()
        nativeHomeOverlay?.let  { fadeOverlay(it, false, immediate) }
        nativeErrorOverlay?.let { fadeOverlay(it, false, immediate) }
        nativeOverlayContainer.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled    = swipeRefreshAllowed()
    }

    // FIX #7 — showHomeOverlay تتحقق من dirty flag وتُعيد البناء عند الحاجة فقط
    private fun showHomeOverlay() {
        keepCursorAlive()
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
        keepCursorAlive()
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
        // ⚠️ فجوة مُغلقة: نُزامن sessionStateJson من كل جلسة حيّة إلى TabState
        // المقابل لها قبل الكتابة، حتى يحمل الملف المحفوظ آخر حالة معروفة —
        // هذا يستبدل onSaveInstanceState اليدوي غير الممكن مزامنته (انظر
        // GeckoTabSession.sessionStateJson).
        webViews.forEach { (id, wv) ->
            wv.sessionStateJson?.let { json ->
                tabGroups.forEach { g -> g.tabs.find { it.id == id }?.sessionStateJson = json }
            }
        }
        sessionManager.saveToStorage(this)
    }

    fun loadPersistentTabs(intentUrl: String?) {
        if (sessionManager.restoreFromStorage(this) && sessionManager.tabGroups.isNotEmpty()) {
            // BUG-M FIX: self-heal a corrupted/stale save where the active
            // group ended up with zero tabs (e.g. an old app version, or a
            // manually edited prefs file). Without this, activeTab below is
            // null, ensureWebViewForTab is never called, and the app boots
            // into a fully blank screen — no WebView, no overlay, nothing.
            if (currentGroup?.tabs.isNullOrEmpty()) {
                sessionManager.createNewTab(HOME_URL)
            }

            val activeGroupTabs = currentGroup?.tabs
            val preferredTabId = activeTabId
            val activeTab = activeGroupTabs?.find { it.id == preferredTabId } ?: activeGroupTabs?.firstOrNull()
            activeTabId = activeTab?.id ?: 0

            if (activeTab != null) {
                val wv = ensureWebViewForTab(activeTab)
                wv.setActive(true)
                if (webViewContainer.indexOfChild(wv.geckoView) == -1) webViewContainer.addView(wv.geckoView)
                // BUG-3 FIX: request focus so TV remote / keyboard / gamepad events
                // are delivered to the WebView immediately on cold start.
                wv.requestFocus()
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
                    BrowserDialogHelpers.showModernPopup(context, group.name, listOf("Rename Group", "Delete Group")) { index ->
                        when (index) {
                            0 -> {
                                val input = EditText(context).apply {
                                    setText(group.name); setTextColor(Color.WHITE)
                                }
                                AlertDialog.Builder(context, R.style.DarkDialog)
                                    .setTitle("Rename Group")
                                    .setView(input)
                                    .setPositiveButton("Save") { _, _ ->
                                        group.name = input.text.toString()
                                        updateGroupsUI(); savePersistentTabs()
                                    }.show()
                            }
                            1 -> {
                                if (tabGroups.size == 1) {
                                    Toast.makeText(context, "Cannot delete the last group", Toast.LENGTH_SHORT).show()
                                    return@showModernPopup
                                }
                                val wasActiveGroup = activeGroupId == group.id
                                group.tabs.forEach { t ->
                                    ioExecutor.execute { File(cacheDir, "thumb_${t.id}.webp").delete() }
                                    webViews[t.id]?.destroy()
                                    webViews.remove(t.id)
                                }
                                tabGroups.remove(group)

                                if (wasActiveGroup) {
                                    // BUG-H FIX: previously only activeGroupId/activeTabId
                                    // were updated here. The just-destroyed WebView stayed
                                    // attached as the visible child of webViewContainer —
                                    // a frozen screen, and any later goBack()/loadUrl() on it
                                    // throws "WebView cannot be used after destroy()".
                                    // switchToTab() does the full job: creates/attaches the
                                    // new tab's WebView and refreshes the whole UI.
                                    activeGroupId = tabGroups.first().id
                                    val newActiveTab = currentGroup?.tabs?.firstOrNull()
                                    if (newActiveTab != null) switchToTab(newActiveTab) else openNewTab(HOME_URL)
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
        keepCursorAlive()
        sanitizeActiveTabSelection()
        captureAndStoreThumbnail {
            refreshTabsRecycler()
            tabsOverlay.visibility = View.VISIBLE
        }
    }

    private fun openNewTab(url: String = HOME_URL) {
        keepCursorAlive()
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
            if (wv.geckoView.parent == null && webViewContainer.childCount == 0) {
                webViewContainer.addView(wv.geckoView)
            }
            switchToTab(newTab)
            refreshTabsRecycler()
            savePersistentTabs()
        }
    }

    private fun switchToTab(tab: TabState) {
        keepCursorAlive()
        hideKeyboard()

        val shouldAnimateOverlay = tabsOverlay.visibility == View.VISIBLE

        val executeSwitch = {
            val previousWebView = currentWebView
            val targetWebView = ensureWebViewForTab(tab)
            activeTabId = tab.id
            keepCursorAlive()

            // ⚠️ يُغلق فجوة setActive المكتشفة بالمراجعة الرابعة: التبويب
            // المغادَر يتوقف عن استهلاك موارد الخلفية، والتبويب الجديد
            // يُفعَّل.
            if (previousWebView !== targetWebView) previousWebView?.setActive(false)
            targetWebView.setActive(true)

            webViewContainer.removeAllViews()
            webViewContainer.addView(targetWebView.geckoView)
            targetWebView.requestFocus()   // keyboard / TV-remote / gamepad focus
            if (::inputController.isInitialized) inputController.stopScrollLoop()
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
        keepCursorAlive()
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
                    if (webViewContainer.indexOfChild(wv.geckoView) >= 0) {
                        webViewContainer.removeView(wv.geckoView)
                    }
                }
                runCatching { wv.stopLoading() }
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
    private fun ensureWebViewForTab(tab: TabState): GeckoTabSession {
        webViews[tab.id]?.let { return it }

        // طرد الجلسة الأقل استخداماً إذا تجاوزنا الحد الأقصى
        if (webViews.size >= MAX_LIVE_WEBVIEWS) {
            val evictId = webViews.keys.firstOrNull { it != activeTabId }
            if (evictId != null) {
                webViews[evictId]?.let { session ->
                    if (webViewContainer.indexOfChild(session.geckoView) >= 0) {
                        webViewContainer.removeView(session.geckoView)
                    }
                    session.destroy()
                }
                webViews.remove(evictId)
            }
        }

        val wv = webViewFactory.create(tab.id)
        webViews[tab.id] = wv
        // ⚠️ فجوة مُغلقة: نستعيد تاريخ التصفح/موضع التمرير/بيانات النماذج من
        // sessionStateJson المحفوظ (يُحدَّث تلقائياً — انظر GeckoTabSession
        // وGeckoSessionDelegates.onSessionStateChange). هذا يستعيد فعلياً زر
        // "الرجوع" داخل التبويب بعد إعادة إنشاء العملية، وليس فقط الرابط
        // الحالي كما كان الحال في المرحلة 2.
        val savedStateJson = tab.sessionStateJson
        if (savedStateJson != null) {
            val restored = runCatching {
                org.mozilla.geckoview.GeckoSession.SessionState.fromString(savedStateJson)
            }.getOrNull()
            if (restored != null) {
                wv.session.restoreState(restored)
            } else if (!isHomeUrl(tab.url)) {
                wv.loadUrl(tab.url)
            } else {
                wv.loadUrl(HOME_URL)
            }
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

    private fun updateUIForCurrentWebView(wv: GeckoTabSession) {
        keepCursorAlive()
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
        if (wv == null || wv.geckoView.width <= 0 || wv.geckoView.height <= 0 || !wv.geckoView.isAttachedToWindow) {
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

        val homeLike = isHomeUrl(currentUrl)
        if (homeLike) {
            val bitmap = getHomePreviewBitmap()
            tabRef?.let {
                it.hasThumbnail = true
                it.thumbnailUrl = currentUrl
                it.ramThumbnail = bitmap
            }
            onComplete?.invoke()
            ioExecutor.execute {
                try {
                    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out) }
                } catch (e: Exception) { e.printStackTrace() }
            }
            return
        }

        // BUG-taste note: capturePixels() is async — onComplete now fires from
        // inside this callback instead of synchronously, which is why this
        // function already took a callback parameter to begin with.
        wv.capturePixels { fullBitmap ->
            try {
                val bitmap = if (fullBitmap != null) {
                    val scale = 0.3f
                    val w = maxOf(1, (fullBitmap.width * scale).toInt())
                    val h = maxOf(1, (fullBitmap.height * scale).toInt())
                    Bitmap.createScaledBitmap(fullBitmap, w, h, true)
                } else {
                    null
                }

                if (bitmap != null) {
                    tabRef?.let {
                        it.hasThumbnail = true
                        it.thumbnailUrl = currentUrl
                        it.ramThumbnail = bitmap
                    }
                    ioExecutor.execute {
                        try {
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                onComplete?.invoke()
            } catch (e: Exception) {
                onComplete?.invoke()
            }
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
            // textUrl is now display-only; typing happens inside the search overlay.
            // If somehow an action fires here, open the overlay.
            showSearchOverlay(textUrl.text.toString().trim())
            true
        }

        // textUrl is display-only for typing; tapping/D-pad focusing it opens
        // the full-screen search overlay pre-filled with the current URL.
        // isFocusableInTouchMode=false: touch triggers onClick, not focus.
        // isFocusable=true: TV D-pad can land here and gain focus → overlay.
        textUrl.isFocusableInTouchMode = false
        textUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showSearchOverlay(textUrl.text.toString().trim())
        }
        textUrl.setOnClickListener {
            showSearchOverlay(textUrl.text.toString().trim())
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

        findViewById<View>(R.id.btnBackArea).setOnClickListener    { currentWebView?.let { if (it.canGoBack)    it.goBack()    } }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { currentWebView?.let { if (it.canGoForward) it.goForward() } }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { goHome() }

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

    /**
     * BUG-Z FIX: single source of truth for "go home". Previously
     * showHomeOverlay() was called directly from btnHomeArea and the
     * gamepad/remote Home shortcut WITHOUT navigating the underlying
     * WebView — the overlay appeared, but the old page stayed loaded
     * underneath and reappeared unchanged on Back (and the tab's
     * title/thumbnail never updated to reflect "home"). Only
     * loadUrlInstantly's home branch paired the two correctly; this makes
     * that the one place that defines what "home" actually means.
     */
    private fun goHome() {
        showHomeOverlay()
        currentWebView?.loadUrl(HOME_URL)
        textUrl.setText("")
    }

    private fun loadUrlInstantly(url: String) {
        if (isHomeUrl(url)) {
            goHome()
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
        keepCursorAlive()
        if (cachedMenuSheet == null) {
            cachedMenuSheet     = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme).apply {
                setOnShowListener { keepCursorAlive() }
                setOnDismissListener { keepCursorAlive() }
            }
            cachedMenuSheetView = layoutInflater.inflate(R.layout.layout_main_menu, null)
            cachedMenuSheet?.setContentView(cachedMenuSheetView!!)

            // TV FIX: BottomSheetBehavior defaults to STATE_COLLAPSED, showing
            // only peekHeight (~half the sheet) until the user drags it open.
            // That drag gesture doesn't exist on a D-pad/remote — there is no
            // touch input — so on TV the menu was permanently stuck showing
            // only its first quarter with no way to reach the rest. Force it
            // fully expanded immediately; gated to TV only so phone behavior
            // (which nobody complained about) is untouched.
            if (isTV) {
                (cachedMenuSheetView!!.parent as? View)?.let { sheetInternal ->
                    BottomSheetBehavior.from(sheetInternal).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                    }
                }
            }

            cachedMenuSheetView?.findViewById<View>(R.id.menuNightMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                // ⚠️ يستخدم الآن مسار DOM آمن من CSP (GeckoExtensionBridge.
                // toggleNightMode) بدل eval عام — انظر التوضيح الحرج بمراجعة
                // عميقة خامسة في content.js.
                currentWebView?.let { GeckoExtensionBridge.toggleNightMode(it) }
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
                currentWebView?.setFindListener { current, total ->
                    findViewById<TextView>(R.id.findMatches).text =
                        if (total > 0) "${current + 1}/$total" else "0/0"
                }
                // BUG-O FIX: focus + show keyboard immediately, matching the
                // behaviour of the keyboard/gamepad "find" shortcut
                // (InputController.toggleFind) — previously this menu entry
                // left the bar visible but required an extra manual tap.
                val fi = findViewById<EditText>(R.id.findInput)
                fi.requestFocus()
                fi.post {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(fi, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuDesktopMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                prefsManager.desktopMode = !prefsManager.desktopMode
                webViews.values.forEach { it.setDesktopMode(prefsManager.desktopMode) }
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

            // ── Menu page tab chips ──────────────────────────────────────
            cachedMenuSheetView?.findViewById<View>(R.id.menuTabTools)?.setOnClickListener {
                activateMenuTab(isTools = true)
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuTabDownloads)?.setOnClickListener {
                activateMenuTab(isTools = false)
            }

            // ── Downloads mini page: open full activity ──────────────────
            cachedMenuSheetView?.findViewById<View>(R.id.menuDlOpenPage)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                startActivity(Intent(this, DownloadsActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }

        // Always reset to Tools tab on open
        activateMenuTab(isTools = true)

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Menu page switching
    // ─────────────────────────────────────────────────────────────────────────

    private fun activateMenuTab(isTools: Boolean) {
        val v = cachedMenuSheetView ?: return
        val tabTools   = v.findViewById<TextView>(R.id.menuTabTools)
        val tabDl      = v.findViewById<TextView>(R.id.menuTabDownloads)
        val pageTools  = v.findViewById<View>(R.id.menuPageTools)
        val pageDl     = v.findViewById<View>(R.id.menuPageDownloads)
        val density    = resources.displayMetrics.density

        val activeChip = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#2B2930"))
            cornerRadius = 10 * density
        }

        if (isTools) {
            tabTools.setTextColor(Color.WHITE)
            tabTools.setTypeface(null, android.graphics.Typeface.BOLD)
            tabTools.background = activeChip
            tabDl.setTextColor(Color.parseColor("#888888"))
            tabDl.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabDl.background = null
            pageTools.visibility = View.VISIBLE
            pageDl.visibility   = View.GONE
        } else {
            tabDl.setTextColor(Color.WHITE)
            tabDl.setTypeface(null, android.graphics.Typeface.BOLD)
            tabDl.background = activeChip
            tabTools.setTextColor(Color.parseColor("#888888"))
            tabTools.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabTools.background = null
            pageTools.visibility = View.GONE
            pageDl.visibility   = View.VISIBLE
            populateMenuDownloadsList()
        }
    }

    private fun populateMenuDownloadsList() {
        val listContainer = cachedMenuSheetView
            ?.findViewById<LinearLayout>(R.id.menuDlList) ?: return
        listContainer.removeAllViews()

        val items = DownloadTracker.downloads.value.orEmpty()

        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No downloads yet"
                textSize = 14f
                setTextColor(Color.parseColor("#555555"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, menuDp(24), 0, menuDp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            return
        }

        // Show last 4 downloads, newest first
        items.reversed().take(4).forEach { item ->
            listContainer.addView(buildMenuDlRow(item))
        }
    }

    private fun buildMenuDlRow(item: DownloadItem): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(menuDp(12), menuDp(10), menuDp(12), menuDp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = menuDp(2) }
        }

        // Coloured state dot
        val dot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(menuStateColor(item.state))
            }
            layoutParams = LinearLayout.LayoutParams(menuDp(8), menuDp(8)).apply {
                marginEnd = menuDp(12)
            }
        }

        // Text column: filename + status
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        col.addView(TextView(this).apply {
            text = item.fileName
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val stateText = when (item.state) {
            DownloadState.RUNNING   -> {
                val pct = if (item.totalBytes > 0)
                    " · ${(item.downloadedBytes * 100 / item.totalBytes).toInt()}%" else ""
                "Downloading$pct"
            }
            DownloadState.QUEUED    -> "Waiting…"
            DownloadState.COMPLETED -> "Complete  ·  ${menuFmtBytes(item.downloadedBytes)}"
            DownloadState.FAILED    -> "Failed"
            DownloadState.CANCELLED -> "Cancelled"
        }
        col.addView(TextView(this).apply {
            text = stateText
            textSize = 11f
            setTextColor(menuStateColor(item.state))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = menuDp(2) }
        })

        row.addView(dot)
        row.addView(col)
        return row
    }

    private fun menuDp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun menuStateColor(state: DownloadState): Int = Color.parseColor(when (state) {
        DownloadState.RUNNING   -> "#6EA8DC"
        DownloadState.COMPLETED -> "#5DB075"
        DownloadState.FAILED    -> "#F2B8B5"
        DownloadState.CANCELLED -> "#555555"
        DownloadState.QUEUED    -> "#888888"
    })

    private fun menuFmtBytes(b: Long): String = when {
        b < 1_024L         -> "$b B"
        b < 1_048_576L     -> "${"%.1f".format(b / 1_024.0)} KB"
        b < 1_073_741_824L -> "${"%.1f".format(b / 1_048_576.0)} MB"
        else               -> "${"%.2f".format(b / 1_073_741_824.0)} GB"
    }




    private fun clearData() {
        // BUG (migration): android.webkit.WebStorage/CookieManager operate on
        // the SYSTEM WebView's storage — GeckoView keeps its own completely
        // separate cookie jar and cache via GeckoRuntime.storageController.
        // Calling only the old APIs here would make "Clear Data" a no-op that
        // silently leaves every cookie and cached asset behind post-migration.
        // ✅ تأكّد StorageController.ClearFlags.ALL فعلياً من توثيق Mozilla
        // الرسمي (index-all.html: "ALL - Static variable in class
        // org.mozilla.geckoview.StorageController.ClearFlags").
        runCatching {
            GeckoRuntimeManager.get(this).storageController
                .clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Full-Screen Search Overlay
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the full-screen search overlay once, add it to the window's
     * content view (above everything else) and store the reference.
     *
     * Called lazily the first time showSearchOverlay() runs so the
     * root ConstraintLayout is already attached and measured.
     */
    private fun buildSearchOverlay() {
        if (searchOverlayContainer != null) return
        val dp  = resources.displayMetrics.density
        fun px(r: Int) = resources.getDimensionPixelSize(r)
        val ctx = this

        // ── RecyclerView for suggestions ─────────────────────────────────
        suggestionsRecycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter       = suggestionsAdapter
            itemAnimator  = null
            overScrollMode = View.OVER_SCROLL_NEVER
            // TV: the RecyclerView itself must NOT be focusable.
            // D-pad focus goes directly to individual item rows (isFocusable=true
            // in SuggestionsAdapter). If the RecyclerView itself is focusable,
            // D-pad stops here and never enters the list.
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // Wire TV "UP on first row → back to search bar" callback now that
        // overlaySearchInput will exist by the time the callback fires.
        suggestionsAdapter.onBackToSearch = { overlaySearchInput.requestFocus() }

        // ── Header row — height/sizes from dimens.xml (TV gets larger values) ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0D0D0D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                px(R.dimen.search_overlay_header)
            )
        }

        val btnBack = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back_arrow)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            scaleType     = ImageView.ScaleType.CENTER
            layoutParams  = LinearLayout.LayoutParams(
                px(R.dimen.search_overlay_btn),
                LinearLayout.LayoutParams.MATCH_PARENT)
            background    = ContextCompat.getDrawable(ctx, R.drawable.bottom_btn_ripple)
            setOnClickListener { hideSearchOverlay() }
        }

        val searchIconInBar = ImageView(ctx).apply {
            val sz = px(R.dimen.search_overlay_icon)
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(0xFF666666.toInt())
            scaleType     = ImageView.ScaleType.CENTER_INSIDE
            layoutParams  = LinearLayout.LayoutParams(sz, sz).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
        }

        overlaySearchInput = EditText(ctx).apply {
            hint      = "Search or type URL"
            setHintTextColor(0xFF555555.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize  = if (isTV) 18f else 16f
            setSingleLine(true)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                         android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType  = android.text.InputType.TYPE_CLASS_TEXT or
                         android.text.InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.gravity    = Gravity.CENTER_VERTICAL
                it.marginStart = (10 * dp).toInt()
                it.marginEnd   = (4 * dp).toInt()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) { hideSearchOverlay(); navigateTo(q) }
                    true
                } else false
            }
            // TV: D-pad DOWN from the search bar moves focus to the first suggestion.
            // Android's default traversal would land on the RecyclerView container
            // (if focusable), never on the items. We do it explicitly instead.
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    && suggestionsAdapter.itemCount > 0) {
                    suggestionsRecycler.getChildAt(0)?.requestFocus() ?: false
                    true
                } else false
            }
        }

        overlayClearBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_clear)
            imageTintList = ColorStateList.valueOf(0xFF888888.toInt())
            scaleType     = ImageView.ScaleType.CENTER
            layoutParams  = LinearLayout.LayoutParams(
                px(R.dimen.search_overlay_btn),
                LinearLayout.LayoutParams.MATCH_PARENT)
            background    = ContextCompat.getDrawable(ctx, R.drawable.bottom_btn_ripple)
            visibility    = View.INVISIBLE
            setOnClickListener {
                overlaySearchInput.setText("")
                overlaySearchInput.requestFocus()
            }
        }

        header.addView(btnBack)
        header.addView(searchIconInBar)
        header.addView(overlaySearchInput)
        header.addView(overlayClearBtn)

        // ── Thin divider ─────────────────────────────────────────────────
        val divider = View(ctx).apply {
            setBackgroundColor(0xFF1C1C1C.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)
            )
        }

        // ── Content stack ────────────────────────────────────────────────
        val vstack = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(header)
            addView(divider)
            addView(suggestionsRecycler)
        }

        // ── Root container ───────────────────────────────────────────────
        val container = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
            addView(vstack)
        }

        // Add as the highest-z child of the Activity's root view
        val rootContent = window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
        rootContent.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        searchOverlayContainer = container

        // ── TextWatcher: fetch suggestions while typing ──────────────────
        overlaySearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                overlayClearBtn.visibility =
                    if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (!prefsManager.suggestionsEnabled || query.length < 2) {
                    suggestionsAdapter.items = emptyList(); return
                }
                val kind = resolveSearchEngineKind(prefsManager.searchEngine)
                suggestionsManager.fetchDebounced(query, kind) { list ->
                    suggestionsAdapter.items = list
                }
            }
        })
    }

    /**
     * Show the full-screen search overlay, optionally pre-filling with [prefill].
     * The overlay slides up (40 dp) and fades in over 200 ms.
     */
    fun showSearchOverlay(prefill: String = "") {
        buildSearchOverlay()
        val container = searchOverlayContainer ?: return

        // Cancel any in-progress hide animation before starting the show.
        // Without this, the hide's withEndAction { visibility = GONE } fires
        // AFTER we set visibility = VISIBLE, collapsing the overlay instantly.
        container.animate().cancel()

        container.visibility = View.VISIBLE
        container.alpha      = 0f
        container.translationY = resources.displayMetrics.density * 40f
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        overlaySearchInput.setText(prefill)
        overlaySearchInput.requestFocus()
        overlaySearchInput.setSelection(prefill.length)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(overlaySearchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSearchOverlay() {
        suggestionsManager.cancel()
        if (::suggestionsAdapter.isInitialized) suggestionsAdapter.items = emptyList()
        val container = searchOverlayContainer ?: return
        if (container.visibility != View.VISIBLE) return
        container.animate().cancel()
        hideKeyboard()
        container.animate()
            .alpha(0f)
            .translationY(resources.displayMetrics.density * 40f)
            .setDuration(160)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                container.visibility = View.GONE
                // TV: move focus to WebView so D-pad focus does NOT drift back to
                // textUrl/searchInput (which would immediately re-open the overlay).
                currentWebView?.requestFocus()
            }
            .start()
    }

    private fun hideCustomView() {
        keepCursorAlive()
        isFullscreenActive = false
        setFullscreen(false)
    }

    private fun startSettingsActivity() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Download handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called from BrowserWebViewFactory when WebView can't render a URL.
     * Requests POST_NOTIFICATIONS on API 33+ (once), then fires the service.
     */
    private fun handleDownloadRequest(
        url: String,
        mimeType: String,
        contentLength: Long,
        suggestedFilename: String?
    ) {
        // Request notification permission on Android 13+ so completion
        // notifications are visible. Downloads work either way.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val fileName = suggestedFilename?.takeIf { it.isNotBlank() }
            ?: android.webkit.URLUtil.guessFileName(url, null, mimeType)

        // ⚠️ فجوة مُغلقة: DownloadService لم يعد يحتاج كوكيز/UA مُستخرَجين
        // يدوياً عبر android.webkit.CookieManager (الذي لا يرى كوكيز
        // GeckoView إطلاقاً) — أصبح يجلب الملف عبر GeckoWebExecutor مباشرة،
        // والذي يستخدم مخزن كوكيز GeckoView الحقيقي تلقائياً. انظر
        // DownloadService.kt.
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
                putExtra(DownloadService.EXTRA_URL,      url)
                putExtra(DownloadService.EXTRA_FILENAME, fileName)
                putExtra(DownloadService.EXTRA_MIME,     mimeType)
            }
        )

        Toast.makeText(this, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Console (Eruda) — سكريبتات الحقن
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleConsoleForCurrentPage() {
        val enable = !prefsManager.consoleEnabled
        prefsManager.consoleEnabled = enable
        swipeRefresh.isEnabled = swipeRefreshAllowed()
        currentWebView?.let { tab -> ConsoleToolsInjector.apply(tab, enable, "") }
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

    private fun applyConsoleTools(tab: GeckoTabSession) {
        ConsoleToolsInjector.apply(tab, prefsManager.consoleEnabled, "")
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Input Controller — TV remote · Gamepad · Keyboard · Mouse
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var inputController: InputController
    private lateinit var cursorController: CursorController
    private var cursorArmed = false

    private fun keepCursorAlive() {
        if (::cursorController.isInitialized && cursorArmed) {
            cursorController.keepVisible()
        }
    }

    /**
     * Builds the InputController, wiring all browser actions via lambdas.
     * Called once from onCreate() after initViews().
     */
    private fun buildInputController(): InputController {
        return InputController(object : InputController.Handlers {

            override fun getActiveSession(): GeckoTabSession? = currentWebView

            override fun isTopBarVisible() = topBar.visibility == View.VISIBLE

            override fun isTabsOverlayVisible() = tabsOverlay.visibility == View.VISIBLE

            override fun showTopBar() = setTopBarVisible(true)

            override fun hideTopBar() = setTopBarVisible(false)

            override fun focusUrlBar() = showSearchTopBar()

            override fun navigateBack() {
                currentWebView?.let { if (it.canGoBack) it.goBack() }
            }

            override fun navigateForward() {
                currentWebView?.let { if (it.canGoForward) it.goForward() }
            }

            override fun navigateHome() = goHome()

            override fun openNewTab() = this@MainActivity.openNewTab()

            override fun closeCurrentTab() {
                val tab = currentGroup?.tabs?.find { it.id == activeTabId } ?: return
                closeTab(tab)
            }

            override fun reload() { currentWebView?.reload() }

            override fun showMenu() = showMenuSheet()

            override fun showTabs() = showTabsOverlay()

            override fun nextTab() = this@MainActivity.nextTab()

            override fun prevTab() = this@MainActivity.prevTab()

            override fun toggleConsole() = toggleConsoleForCurrentPage()

            override fun toggleFind() {
                if (findBar.visibility == View.VISIBLE) {
                    findBar.visibility = View.GONE
                    currentWebView?.clearMatches()
                    hideKeyboard()
                } else {
                    findBar.visibility = View.VISIBLE
                    currentWebView?.setFindListener { current, total ->
                        findViewById<TextView>(R.id.findMatches).text =
                            if (total > 0) "${current + 1}/$total" else "0/0"
                    }
                    val fi = findViewById<EditText>(R.id.findInput)
                    fi.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(fi, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            override fun dismissTopOverlay(): Boolean {
                return when {
                    tabsOverlay.visibility == View.VISIBLE -> {
                        tabsOverlay.visibility = View.GONE
                        keepCursorAlive()
                        true
                    }
                    nativeOverlayContainer.visibility == View.VISIBLE -> {
                        hideNativeOverlays(); true
                    }
                    topBar.visibility == View.VISIBLE -> {
                        setTopBarVisible(false); hideKeyboard(); true
                    }
                    findBar.visibility == View.VISIBLE -> {
                        findBar.visibility = View.GONE
                        currentWebView?.clearMatches()
                        hideKeyboard()
                        keepCursorAlive()
                        true
                    }
                    else -> false
                }
            }
        })
    }

    /** Cycle to the next tab in the active group (wraps around). */
    private fun nextTab() {
        keepCursorAlive()
        if (::inputController.isInitialized) inputController.stopScrollLoop()
        val tabs = currentGroup?.tabs ?: return
        if (tabs.size < 2) return
        val idx  = tabs.indexOfFirst { it.id == activeTabId }
        switchToTab(tabs[(idx + 1) % tabs.size])
    }

    /** Cycle to the previous tab in the active group (wraps around). */
    private fun prevTab() {
        keepCursorAlive()
        if (::inputController.isInitialized) inputController.stopScrollLoop()
        val tabs = currentGroup?.tabs ?: return
        if (tabs.size < 2) return
        val idx  = tabs.indexOfFirst { it.id == activeTabId }
        switchToTab(tabs[(idx - 1 + tabs.size) % tabs.size])
    }

    // ── dispatchKeyEvent ──────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK is always handled by onBackPressedDispatcher — never intercept it.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_DPAD)) {
                cursorArmed = true
            }

            // When the search overlay is visible, ALL key events go directly to
            // the focused view (EditText or suggestion row). InputController must
            // not intercept them — DPAD_CENTER on a focused suggestion row must
            // navigate to that suggestion, not call activateWebElement().
            if (searchOverlayContainer?.visibility == View.VISIBLE) {
                return super.dispatchKeyEvent(event)
            }

            // When an EditText (URL bar, find bar) owns focus, only intercept:
            //   • Escape   → dismiss overlay
            //   • Ctrl+*   → browser shortcuts still work while typing
            //   • F5/F12   → function keys
            // Everything else (typing, Enter, arrows inside the field) → super.
            val textFocused = currentFocus is EditText
            val isSpecialKey = event.isCtrlPressed
                            || event.keyCode == KeyEvent.KEYCODE_ESCAPE
                            || event.keyCode == KeyEvent.KEYCODE_F5
                            || event.keyCode == KeyEvent.KEYCODE_F12

            if (textFocused && !isSpecialKey) return super.dispatchKeyEvent(event)

            if (inputController.onKeyDown(event)) return true
        }

        return super.dispatchKeyEvent(event)
    }

    // ── dispatchGenericMotionEvent ────────────────────────────────────────────

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            cursorArmed = true
        }
        keepCursorAlive()
        if (inputController.onGenericMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
