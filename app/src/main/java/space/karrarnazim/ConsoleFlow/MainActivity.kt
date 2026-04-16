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
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
//  بيانات التبويب والمجموعة
// ─────────────────────────────────────────────────────────────────────────────

data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var hasThumbnail: Boolean = false
) : Serializable {
    @Transient var ramThumbnail: Bitmap? = null
}

data class TabGroup(
    val id: Int,
    var name: String,
    val tabs: MutableList<TabState> = mutableListOf()
) : Serializable

// ─────────────────────────────────────────────────────────────────────────────
//  النشاط الرئيسي
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    // ── واجهة المستخدم ──────────────────────────────────────────────────────
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
    private var tabGroupsContainer: LinearLayout? = null

    // ── الإعدادات والمديرون ────────────────────────────────────────────────
    private lateinit var prefsManager: PrefsManager
    private val okClient = OkHttpClient.Builder().followRedirects(true).build()
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
    private val HOME_URL  = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"

    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com", "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    // ── مجموعات التبويبات والتبويب النشط ───────────────────────────────────
    private var tabGroups = mutableListOf<TabGroup>()
    private var activeGroupId = 0
    private var activeTabId = 0
    private var nextTabId = 1
    private var nextGroupId = 1
    private lateinit var tabAdapter: TabAdapter

    private val currentGroup: TabGroup? get() = tabGroups.find { it.id == activeGroupId }

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

    // ─────────────────────────────────────────────────────────────────────────
    //  دورة حياة النشاط
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)
        ioExecutor = Executors.newCachedThreadPool()   // تحسين الأداء

        initViews()
        setupListeners()

        val intentUrl = intent?.data?.toString()

        if (savedInstanceState != null) {
            // استعادة المجموعات والتبويبات عند تدوير الشاشة
            val savedGroups = savedInstanceState.getSerializable("GROUPS_LIST") as? ArrayList<TabGroup>
            if (savedGroups != null && savedGroups.isNotEmpty()) {
                tabGroups.clear()
                tabGroups.addAll(savedGroups)
                activeGroupId = savedInstanceState.getInt("ACTIVE_GROUP_ID", tabGroups.first().id)
                activeTabId = savedInstanceState.getInt("ACTIVE_TAB_ID", tabGroups.first().tabs.firstOrNull()?.id ?: 0)
                nextTabId = savedInstanceState.getInt("NEXT_TAB_ID", 100)
                nextGroupId = savedInstanceState.getInt("NEXT_GROUP_ID", 100)

                tabGroups.forEach { group ->
                    group.tabs.forEach { tab ->
                        val wv = createNewWebView(tab.id)
                        // استعادة حالة WebView من Bundle مخصص
                        savedInstanceState.getBundle("webview_${tab.id}")?.let { wv.restoreState(it) }
                        webViews[tab.id] = wv
                        if (tab.id == activeTabId) webViewContainer.addView(wv)
                    }
                }
                updateGroupsUI()
                refreshTabsRecycler()
            } else {
                createNewGroup("Default")
            }
        } else {
            loadPersistentTabs(intentUrl)
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
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
        outState.putInt("ACTIVE_TAB_ID", activeTabId)
        outState.putInt("NEXT_TAB_ID", nextTabId)
        outState.putInt("NEXT_GROUP_ID", nextGroupId)

        // حفظ حالة كل WebView في Bundle مستقل لتجنب التعارض
        webViews.forEach { (tabId, wv) ->
            val bundle = Bundle()
            wv.saveState(bundle)
            outState.putBundle("webview_$tabId", bundle)
        }
    }

    override fun onPause() {
        super.onPause()
        // حفظ الحالة الدائمة عند الخروج المؤقت (حماية من قتل التطبيق)
        savePersistentTabs()
    }

    override fun onDestroy() {
        // تنظيف جميع WebView وتحرير الموارد
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

        // قد لا يكون هذا العنصر موجودًا في التخطيط القديم، نتحقق منه
        tabGroupsContainer = findViewById<LinearLayout>(R.id.tabGroupsContainer)

        tabAdapter = TabAdapter(this, mutableListOf(),
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter       = tabAdapter

        updateSearchEngineIcon()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  الحفظ الدائم (SharedPreferences)
    // ─────────────────────────────────────────────────────────────────────────

    private fun savePersistentTabs() {
        ioExecutor.execute {
            try {
                val groupsArray = JSONArray()
                for (group in tabGroups) {
                    val groupObj = JSONObject()
                    groupObj.put("id", group.id)
                    groupObj.put("name", group.name)

                    val tabsArray = JSONArray()
                    for (tab in group.tabs) {
                        val tabObj = JSONObject()
                        tabObj.put("id", tab.id)
                        tabObj.put("title", tab.title)
                        tabObj.put("url", tab.url)
                        tabObj.put("hasThumb", tab.hasThumbnail)
                        tabsArray.put(tabObj)
                    }
                    groupObj.put("tabs", tabsArray)
                    groupsArray.put(groupObj)
                }
                getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE).edit()
                    .putString("SAVED_GROUPS", groupsArray.toString())
                    .putInt("ACTIVE_GROUP", activeGroupId)
                    .putInt("ACTIVE_TAB", activeTabId)
                    .putInt("NEXT_TAB_ID", nextTabId)
                    .putInt("NEXT_GROUP_ID", nextGroupId)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadPersistentTabs(intentUrl: String?) {
        val prefs = getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("SAVED_GROUPS", null)

        if (savedJson != null) {
            try {
                val groupsArray = JSONArray(savedJson)
                if (groupsArray.length() > 0) {
                    for (i in 0 until groupsArray.length()) {
                        val gObj = groupsArray.getJSONObject(i)
                        val group = TabGroup(gObj.getInt("id"), gObj.getString("name"))

                        val tabsArray = gObj.getJSONArray("tabs")
                        for (j in 0 until tabsArray.length()) {
                            val tObj = tabsArray.getJSONObject(j)
                            val t = TabState(
                                tObj.getInt("id"),
                                tObj.getString("title"),
                                tObj.getString("url"),
                                tObj.getBoolean("hasThumb")
                            )
                            group.tabs.add(t)
                            val wv = createNewWebView(t.id)
                            webViews[t.id] = wv
                            wv.loadUrl(t.url)
                        }
                        tabGroups.add(group)
                    }
                    activeGroupId = prefs.getInt("ACTIVE_GROUP", tabGroups.first().id)
                    nextTabId = prefs.getInt("NEXT_TAB_ID", 100)
                    nextGroupId = prefs.getInt("NEXT_GROUP_ID", 100)

                    // الآن نحدد activeTabId من المجموعة النشطة
                    val activeGroupTabs = currentGroup?.tabs
                    activeTabId = prefs.getInt("ACTIVE_TAB", activeGroupTabs?.firstOrNull()?.id ?: 0)

                    val activeWv = webViews[activeTabId]
                    if (activeWv != null) webViewContainer.addView(activeWv)

                    updateGroupsUI()
                    refreshTabsRecycler()

                    if (!intentUrl.isNullOrEmpty()) openNewTab(intentUrl)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                    refreshTabsRecycler()
                    updateGroupsUI()
                }

                setOnLongClickListener {
                    showModernPopup(group.name, listOf("Rename Group", "Delete Group")) { index ->
                        when (index) {
                            0 -> {
                                val input = EditText(this@MainActivity).apply {
                                    setText(group.name)
                                    setTextColor(Color.WHITE)
                                }
                                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
                                    .setTitle("Rename Group")
                                    .setView(input)
                                    .setPositiveButton("Save") { _, _ ->
                                        group.name = input.text.toString()
                                        updateGroupsUI()
                                        savePersistentTabs()
                                    }
                                    .show()
                            }
                            1 -> {
                                if (tabGroups.size == 1) {
                                    Toast.makeText(this@MainActivity, "Cannot delete the last group", Toast.LENGTH_SHORT).show()
                                    return@showModernPopup
                                }
                                // حذف ملفات الصور المصغرة وتدمير WebView للتبويبات
                                group.tabs.forEach { t ->
                                    ioExecutor.execute { File(cacheDir, "thumb_${t.id}.webp").delete() }
                                    webViews[t.id]?.destroy()
                                    webViews.remove(t.id)
                                }
                                tabGroups.remove(group)
                                if (activeGroupId == group.id) {
                                    activeGroupId = tabGroups.first().id
                                    activeTabId = currentGroup?.tabs?.firstOrNull()?.id ?: 0
                                }
                                updateGroupsUI()
                                refreshTabsRecycler()
                                savePersistentTabs()
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
        val group = TabGroup(nextGroupId++, name)
        tabGroups.add(group)
        activeGroupId = group.id
        openNewTab(url)
        updateGroupsUI()
    }

    private fun refreshTabsRecycler() {
        currentGroup?.let {
            tabAdapter.updateTabs(it.tabs)
            tabAdapter.setActive(activeTabId)
            tabAdapter.notifyDataSetChanged()
        }
        updateTabCount()
    }

    private fun openNewTab(url: String = HOME_URL) {
        captureAndStoreThumbnail {
            val id = nextTabId++
            val newTab = TabState(id = id, title = "New Tab", url = url)
            currentGroup?.tabs?.add(newTab)

            val wv = createNewWebView(id)
            webViews[id] = wv
            wv.loadUrl(url)

            switchToTab(newTab)
            refreshTabsRecycler()
            savePersistentTabs()
        }
    }

    private fun switchToTab(tab: TabState) {
        // إخفاء لوحة المفاتيح عند التبديل
        hideKeyboard()

        val executeSwitch = {
            activeTabId = tab.id
            tabsOverlay.visibility = View.GONE
            tabAdapter.setActive(tab.id)

            webViewContainer.removeAllViews()
            currentWebView?.let { wv ->
                webViewContainer.addView(wv)
                updateUIForCurrentWebView(wv)
            }
            updateTabCount()
            savePersistentTabs()
        }

        if (activeTabId != tab.id && currentWebView != null) {
            captureAndStoreThumbnail { executeSwitch() }
        } else {
            executeSwitch()
        }
    }

    private fun closeTab(tab: TabState) {
        val group = currentGroup ?: return
        val idx = group.tabs.indexOfFirst { it.id == tab.id }
        if (idx < 0) return

        // تدوير الصورة النقطية يدويًا لمنع تسريب الذاكرة
        tab.ramThumbnail?.recycle()
        tab.ramThumbnail = null
        ioExecutor.execute { File(cacheDir, "thumb_${tab.id}.webp").delete() }

        webViews[tab.id]?.let { wv ->
            webViewContainer.removeView(wv)
            wv.destroy()
            webViews.remove(tab.id)
        }

        group.tabs.removeAt(idx)
        tabAdapter.notifyItemRemoved(idx)

        if (group.tabs.isEmpty()) {
            openNewTab(HOME_URL)
        } else if (tab.id == activeTabId) {
            val fallbackTab = group.tabs.getOrNull(maxOf(0, idx - 1)) ?: group.tabs.first()
            switchToTab(fallbackTab)
        } else {
            updateTabCount()
            savePersistentTabs()
        }
    }

    private fun updateTabCount() {
        val totalTabs = tabGroups.sumOf { it.tabs.size }
        tabCount.text = totalTabs.toString()
    }

    private fun updateUIForCurrentWebView(wv: WebView) {
        val url = wv.url ?: HOME_URL
        textUrl.setText(if (url == HOME_URL || url.startsWith(ERROR_URL)) "" else url)
        updateBookmarkIcon(url)
        progressBar.progress = wv.progress
        progressBar.visibility = if (wv.progress < 100) View.VISIBLE else View.INVISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  التقاط صور مصغرة للتبويبات
    // ─────────────────────────────────────────────────────────────────────────

    private fun captureAndStoreThumbnail(onComplete: (() -> Unit)? = null) {
        val wv = currentWebView
        if (wv == null || wv.width <= 0 || wv.height <= 0) {
            onComplete?.invoke()
            return
        }

        val tabId = activeTabId
        try {
            val scale = 0.3f
            val w = (wv.width * scale).toInt()
            val h = (wv.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)

            currentGroup?.tabs?.find { it.id == tabId }?.let {
                it.hasThumbnail = true
                it.ramThumbnail = bitmap
            }
            onComplete?.invoke()

            ioExecutor.execute {
                try {
                    val file = File(cacheDir, "thumb_$tabId.webp")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            onComplete?.invoke()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  إنشاء WebView جديد مع جميع الإعدادات والمستمعين
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewWebView(tabId: Int): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
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
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        applyUserAgentToWebView(wv)
        wv.addJavascriptInterface(SearchBridge(), "Android")

        // قائمة السياق الحديثة للروابط
        wv.setOnCreateContextMenuListener { _, _, _ ->
            val result = wv.hitTestResult
            if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val url = result.extra ?: return@setOnCreateContextMenuListener

                showModernPopup(url, listOf("Open in New Tab", "Copy Link", "Bookmark Link", "Share")) { index ->
                    when (index) {
                        0 -> { openNewTab(url); Toast.makeText(this@MainActivity, "Opened in new tab", Toast.LENGTH_SHORT).show() }
                        1 -> {
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
                            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            prefsManager.toggleBookmark("Bookmark", url)
                            Toast.makeText(this@MainActivity, "Bookmarked", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }, "Share"))
                        }
                    }
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("http") || url.startsWith("file:")) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
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

                    currentGroup?.tabs?.find { t -> t.id == tabId }?.let { tab ->
                        tab.title = view.title ?: "Tab"
                        tab.url = it
                    }
                    savePersistentTabs()
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
                    } catch (_: Exception) {
                        null
                    }
                }

                if (prefsManager.sharedPreferences.getBoolean("disable_intercept", false)) return null

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
                    } catch (_: Exception) {
                        return null
                    }
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
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webViewContainer.visibility = View.GONE
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webViewContainer.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                setFullscreen(false)
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
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    allowScanningByMediaScanner()
                    @Suppress("DEPRECATION")
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                } else {
                    // Android 10+ يستخدم Scoped Storage
                    setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }

        return wv
    }

    private fun setFullscreen(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
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
            navigateTo(textUrl.text.toString().trim())
            hideKeyboard()
            true
        }

        textUrl.setOnLongClickListener {
            showModernPopup("URL Options", listOf("Copy URL", "Share URL")) { index ->
                when (index) {
                    0 -> {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", currentWebView?.url ?: ""))
                        Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentWebView?.url ?: "")
                        }, "Share URL"))
                    }
                }
            }
            true
        }

        findViewById<View>(R.id.btnBackArea).setOnClickListener    { currentWebView?.let { if (it.canGoBack()) it.goBack() } }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { currentWebView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { loadUrlInstantly(HOME_URL) }

        findViewById<View>(R.id.btnTabsArea).setOnClickListener {
            if (tabsOverlay.visibility == View.VISIBLE) {
                tabsOverlay.visibility = View.GONE
            } else {
                captureAndStoreThumbnail {
                    refreshTabsRecycler()
                    tabsOverlay.visibility = View.VISIBLE
                }
            }
        }

        findViewById<View>(R.id.btnNewTab).setOnClickListener { openNewTab() }

        findViewById<View?>(R.id.btnNewGroup)?.setOnClickListener {
            val input = EditText(this).apply { setTextColor(Color.WHITE); setPadding(32, 32, 32, 32) }
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("New Group Name")
                .setView(input)
                .setPositiveButton("Create") { _, _ -> createNewGroup(input.text.toString().ifEmpty { "Group" }) }
                .show()
        }

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

    // ─────────────────────────────────────────────────────────────────────────
    //  التنقل والبحث
    // ─────────────────────────────────────────────────────────────────────────

    private fun navigateTo(input: String) {
        val finalUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
            else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
        }
        loadUrlInstantly(finalUrl)
    }

    private fun loadUrlInstantly(url: String) {
        textUrl.setText(if (url == HOME_URL || url.startsWith(ERROR_URL)) "" else url)
        progressBar.progress = 5
        progressBar.visibility = View.VISIBLE
        currentWebView?.loadUrl(url)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  قائمة منبثقة حديثة
    // ─────────────────────────────────────────────────────────────────────────

    private fun showModernPopup(title: String, items: List<String>, onSelect: (Int) -> Unit) {
        val sheet = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // fallback للموارد إذا لم تكن موجودة
            try {
                setBackgroundResource(R.drawable.bg_bottom_sheet)
            } catch (e: Exception) {
                setBackgroundColor(Color.parseColor("#2C2C2C"))
            }
            setPadding(0, 32, 0, 32)
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 12).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 48
            }
            try {
                setBackgroundResource(R.drawable.bg_menu_item)
            } catch (e: Exception) {
                setBackgroundColor(Color.WHITE)
            }
        }
        container.addView(handle)

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(48, 0, 48, 24)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(titleView)

        items.forEachIndexed { index, itemText ->
            val itemView = TextView(this).apply {
                text = itemText
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(48, 36, 48, 36)

                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)

                setOnClickListener {
                    sheet.dismiss()
                    onSelect(index)
                }
            }
            container.addView(itemView)
        }

        sheet.setContentView(container)
        sheet.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  القائمة الرئيسية (القائمة السفلية)
    // ─────────────────────────────────────────────────────────────────────────

    private fun showMenuSheet() {
        if (cachedMenuSheet == null) {
            cachedMenuSheet = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
            cachedMenuSheetView = layoutInflater.inflate(R.layout.layout_main_menu, null)
            cachedMenuSheet?.setContentView(cachedMenuSheetView!!)

            cachedMenuSheetView?.findViewById<View>(R.id.menuNightMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                currentWebView?.evaluateJavascript(
                    "(function(){var el=document.getElementById('__cf_night');if(el){el.remove();}else{var s=document.createElement('style');s.id='__cf_night';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important}img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}';document.head.appendChild(s);}})()",
                    null
                )
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuBookmarks)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                showBookmarksDialog()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                showHistoryDialog()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuShare)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                val url = currentWebView?.url ?: return@setOnClickListener
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                    putExtra(Intent.EXTRA_SUBJECT, currentWebView?.title ?: "")
                }, "Share"))
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuFindInPage)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                findBar.visibility = View.VISIBLE
                currentWebView?.setFindListener { ord, total, _ ->
                    findViewById<TextView>(R.id.findMatches).text = if (total > 0) "${ord + 1}/$total" else "0/0"
                }
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuDesktopMode)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                prefsManager.desktopMode = !prefsManager.desktopMode
                webViews.values.forEach { applyUserAgentToWebView(it) }
                // نعيد تحميل الصفحة الحالية مع مسح الكاش لتطبيق الـ UA الجديد
                currentWebView?.apply {
                    clearCache(true)
                    reload()
                }
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                startActivity(Intent(this, SettingsActivity::class.java))
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

        cachedMenuSheet?.show()
    }

    private fun showBookmarksDialog() {
        val bks = prefsManager.getBookmarks()
        if (bks.isEmpty()) {
            Toast.makeText(this, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        showModernPopup("Bookmarks", bks.map { it.first.ifEmpty { it.second } }) { index ->
            loadUrlInstantly(bks[index].second)
        }
    }

    private fun showHistoryDialog() {
        val hist = prefsManager.getHistory()
        if (hist.isEmpty()) {
            Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show()
            return
        }
        showModernPopup("History", hist.map { it.first.ifEmpty { it.second } }) { index ->
            loadUrlInstantly(hist[index].second)
        }
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webViews.values.forEach { it.clearCache(true); it.clearHistory() }
        prefsManager.clearHistory()

        getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE).edit().remove("SAVED_GROUPS").apply()

        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  إعدادات User‑Agent ووضع سطح المكتب
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    //  تحديث أيقونة محرك البحث والعلامة المرجعية
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    //  أدوات مساعدة
    // ─────────────────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(textUrl.windowToken, 0)
    }

    private fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        fullscreenContainer.visibility = View.GONE
        webViewContainer.visibility = View.VISIBLE
        customView = null
        setFullscreen(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  جسر JavaScript
    // ─────────────────────────────────────────────────────────────────────────

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) {
            runOnUiThread { navigateTo(input) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  محول عرض التبويبات (RecyclerView Adapter)
// ─────────────────────────────────────────────────────────────────────────────

class TabAdapter(
    private val context: Context,
    private var tabs: MutableList<TabState>,
    private val onTabClick: (TabState) -> Unit,
    private val onTabClose: (TabState) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private var activeId: Int = -1
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun updateTabs(newTabs: MutableList<TabState>) {
        this.tabs = newTabs
    }

    fun setActive(id: Int) {
        activeId = id
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tabTitle)
        val favicon: ImageView = v.findViewById(R.id.tabFavicon)
        val thumbnail: ImageView = v.findViewById(R.id.tabThumbnail)
        val close: ImageView = v.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab = tabs[position]
        val isActive = tab.id == activeId

        h.title.text = tab.title.ifEmpty { "New Tab" }
        h.favicon.setImageResource(R.drawable.home)
        h.favicon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt()
        )
        h.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

        if (tab.ramThumbnail != null) {
            h.thumbnail.setImageBitmap(tab.ramThumbnail)
        } else if (tab.hasThumbnail) {
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
                    mainHandler.post {
                        h.thumbnail.setImageResource(android.R.color.transparent)
                    }
                }
            }
        } else {
            h.thumbnail.setImageResource(android.R.color.transparent)
        }

        h.itemView.background = context.getDrawable(
            if (isActive) R.drawable.tab_card_active else R.drawable.tab_card_bg
        )
        h.title.setTextColor(if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt())
        h.close.setColorFilter(if (isActive) 0xFF003366.toInt() else 0xFFAAAAAA.toInt())

        h.itemView.setOnClickListener { onTabClick(tab) }
        h.close.setOnClickListener { onTabClose(tab) }
    }

    override fun getItemCount(): Int = tabs.size
}