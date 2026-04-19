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
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
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
//  مانع الإعلانات (AdBlocker) — متوافق مع قوائم uBlock Origin
// ─────────────────────────────────────────────────────────────────────────────

object AdBlocker {
    private val blockedDomains = HashSet<String>()
    private val blockedPatterns = mutableListOf<String>()
    private val cosmeticFilters = StringBuilder()
    @Volatile private var loaded = false

    fun load(assets: android.content.res.AssetManager) {
        if (loaded) return
        try {
            assets.open("adblock_filters.txt").bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachLine
                when {
                    line.startsWith("##") -> cosmeticFilters.append(line.substring(2)).append(",")
                    line.startsWith("||") && line.endsWith("^") -> {
                        val domain = line.substring(2, line.length - 1)
                        blockedDomains.add(domain)
                    }
                    line.startsWith("/") -> blockedPatterns.add(line.trimEnd('^'))
                    else -> {}
                }
            }
            loaded = true
        } catch (_: Exception) {}
    }

    fun isBlocked(url: String, host: String): Boolean {
        if (!loaded) return false
        // domain check — exact or subdomain
        if (blockedDomains.any { host == it || host.endsWith(".$it") }) return true
        // URL pattern check
        if (blockedPatterns.any { url.contains(it, ignoreCase = true) }) return true
        return false
    }

    fun getCosmeticCss(): String {
        if (!loaded || cosmeticFilters.isEmpty()) return ""
        val selectors = cosmeticFilters.toString().trimEnd(',')
        return "try{var s=document.createElement('style');s.textContent='$selectors{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important}';document.head.appendChild(s);}catch(e){}"
    }

    val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

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
    @Transient var faviconBitmap: Bitmap? = null
}

data class TabGroup(
    val id: Int,
    var name: String,
    val tabs: MutableList<TabState> = mutableListOf()
) : Serializable

private fun isHomeStateLikeUrl(url: String?): Boolean {
    if (url.isNullOrEmpty()) return true
    return url == "about:blank" || url == "error://page" || url.startsWith("error://")
}


private fun generateHomePreviewBitmap(width: Int = 540, height: Int = 900): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawColor(Color.BLACK)

    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2D34") }
    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A") }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E5E5")
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A7A7A")
        textSize = 22f
    }

    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, paint: Paint) {
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    // Top settings button
    val settingsStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawRoundRect(RectF(width - 92f, 22f, width - 22f, 92f), 18f, 18f, accentPaint)
    canvas.drawLine(width - 72f, 42f, width - 42f, 42f, settingsStroke)
    canvas.drawLine(width - 72f, 62f, width - 52f, 62f, settingsStroke)
    canvas.drawLine(width - 72f, 82f, width - 62f, 82f, settingsStroke)
    canvas.drawLine(width - 50f, 28f, width - 50f, 34f, settingsStroke)
    canvas.drawLine(width - 30f, 48f, width - 30f, 54f, settingsStroke)
    canvas.drawLine(width - 50f, 68f, width - 50f, 74f, settingsStroke)

    // Search bar
    roundRect(34f, 168f, width - 34f, 326f, 72f, cardPaint)

    val gPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }
    val gx = 112f
    val gy = 247f
    val gr = 30f
    gPaint.color = Color.parseColor("#EA4335"); canvas.drawArc(gx - gr, gy - gr, gx + gr, gy + gr, 20f, 82f, false, gPaint)
    gPaint.color = Color.parseColor("#FBBC05"); canvas.drawArc(gx - gr, gy - gr, gx + gr, gy + gr, 102f, 78f, false, gPaint)
    gPaint.color = Color.parseColor("#34A853"); canvas.drawArc(gx - gr, gy - gr, gx + gr, gy + gr, 180f, 80f, false, gPaint)
    gPaint.color = Color.parseColor("#4285F4"); canvas.drawArc(gx - gr, gy - gr, gx + gr, gy + gr, 260f, 74f, false, gPaint)

    canvas.drawText("Search", 184f, 258f, textPaint)

    fun iconTile(x: Float, y: Float) {
        roundRect(x, y, x + 58f, y + 58f, 18f, accentPaint)
    }

    iconTile(width - 222f, 221f)
    iconTile(width - 144f, 221f)

    // QR icon
    val qrX = width - 204f
    val qrY = 237f
    val qrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    repeat(3) { row ->
        repeat(3) { col ->
            canvas.drawRect(qrX + col * 10f, qrY + row * 10f, qrX + col * 10f + 7f, qrY + row * 10f + 7f, qrPaint)
        }
    }
    canvas.drawRect(qrX + 28f, qrY + 10f, qrX + 33f, qrY + 15f, qrPaint)
    canvas.drawRect(qrX + 20f, qrY + 28f, qrX + 25f, qrY + 33f, qrPaint)

    // Mic icon
    val micX = width - 122f
    val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawRoundRect(RectF(micX + 16f, 234f, micX + 38f, 272f), 11f, 11f, micPaint)
    canvas.drawArc(RectF(micX + 11f, 232f, micX + 43f, 270f), 0f, 180f, false, micPaint)
    canvas.drawLine(micX + 27f, 272f, micX + 27f, 289f, micPaint)
    canvas.drawLine(micX + 16f, 289f, micX + 38f, 289f, micPaint)

    canvas.drawText("Bookmarks", 40f, 406f, mutedPaint)

    val sample = listOf(
        Triple("GitHub", Color.parseColor("#FFFFFF"), "GH"),
        Triple("Stack Overflow", Color.parseColor("#FFFFFF"), "SO"),
        Triple("MDN", Color.parseColor("#FFFFFF"), "MDN"),
        Triple("npm", Color.parseColor("#C63636"), "npm"),
        Triple("Docker", Color.parseColor("#2496ED"), "D"),
        Triple("Dev.to", Color.parseColor("#FFFFFF"), "DEV")
    )

    val lefts = floatArrayOf(36f, 206f, 376f)
    val tops = floatArrayOf(446f, 650f)
    var idx = 0
    for (row in tops) {
        for (col in lefts) {
            if (idx >= sample.size) break
            val (name, bg, txt) = sample[idx++]
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            roundRect(col, row, col + 96f, row + 96f, 26f, tilePaint)

            val textColor = if (name == "npm") Color.WHITE else Color.BLACK
            val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textAlign = Paint.Align.CENTER
                textSize = if (txt.length > 2) 22f else 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(txt, col + 48f, row + 58f, letterPaint)

            canvas.drawText(name, col + 48f, row + 126f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 19f
            })
        }
    }

    return bitmap
}


private fun createMicBitmap(sizePx: Int): Bitmap {
    val size = maxOf(sizePx, 48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = maxOf(4.2f, size * 0.11f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val w = size.toFloat()
    val h = size.toFloat()
    val left = w * 0.34f
    val top = h * 0.12f
    val right = w * 0.66f
    val bottom = h * 0.64f
    canvas.drawRoundRect(RectF(left, top, right, bottom), w * 0.16f, w * 0.16f, paint)
    canvas.drawLine(w * 0.5f, bottom, w * 0.5f, h * 0.78f, paint)
    canvas.drawLine(w * 0.35f, h * 0.78f, w * 0.65f, h * 0.78f, paint)
    canvas.drawArc(RectF(w * 0.28f, h * 0.10f, w * 0.72f, h * 0.66f), 200f, 140f, false, paint)
    return bitmap
}

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
    private lateinit var topBar: LinearLayout
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
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
    private val HOME_URL  = "about:blank"

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

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        transcript?.let { query ->
            if (query.isNotBlank()) navigateTo(query)
        }
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

        // تحميل قوائم منع الإعلانات في الخلفية
        ioExecutor.execute { AdBlocker.load(assets) }

        initViews()
        setupListeners()
        setTopBarVisible(false, immediate = true)

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
                nativeOverlayContainer.visibility == View.VISIBLE -> hideNativeOverlays()
                topBar.visibility == View.VISIBLE && isHomeStateUrl(currentWebView?.url) -> setTopBarVisible(false)
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

    override fun onResume() {
        super.onResume()
        updateSearchEngineIcon()
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
        topBar              = findViewById(R.id.topBar)
        textUrl             = findViewById(R.id.textUrl)
        btnBookmark         = findViewById(R.id.btnBookmark)
        imgSearchEngine     = findViewById(R.id.imgSearchEngine)
        findBar             = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        tabsOverlay         = findViewById(R.id.tabsOverlay)
        tabsRecycler        = findViewById(R.id.tabsRecycler)
        tabCount            = findViewById(R.id.tabCount)
        nativeOverlayContainer = findViewById(R.id.nativeOverlayContainer)

        // عنصر عرض المجموعات موجود مسبقًا في XML
        tabGroupsContainer = findViewById<LinearLayout>(R.id.tabGroupsContainer)
        buildNativeOverlays()
        setTopBarVisible(false, immediate = true)

        tabAdapter = TabAdapter(this, mutableListOf(),
            onTabClick = { tab -> switchToTab(tab) },
            onTabClose = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter       = tabAdapter

        updateSearchEngineIcon()
    }

    private fun isHomeStateUrl(url: String?): Boolean = isHomeStateLikeUrl(url)

    private fun buildNativeOverlays() {
        nativeOverlayContainer.removeAllViews()
        nativeHomeOverlay = buildHomeOverlay()
        nativeErrorOverlay = buildErrorOverlay()
        nativeHomeOverlay?.let { nativeOverlayContainer.addView(it) }
        nativeErrorOverlay?.let { nativeOverlayContainer.addView(it) }
        hideNativeOverlays(immediate = true)
    }

    fun getHomePreviewBitmap(force: Boolean = false): Bitmap {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(360)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(640)
        val key = homePreviewCacheKey(width, height)
        val cacheFile = File(cacheDir, "home_preview_${width}x${height}.webp")
        val sigFile = File(cacheDir, "home_preview_${width}x${height}.sig")

        if (!force && cacheFile.exists() && sigFile.exists()) {
            runCatching { sigFile.readText() }.getOrNull()?.let { stored ->
                if (stored == key) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { cached -> return cached }
                }
            }
        }

        val rendered = renderHomePreviewBitmap(width, height)
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

    private fun homePreviewCacheKey(width: Int, height: Int): String {
        val bookmarks = prefsManager.getBookmarks().joinToString("|") { (t, u) -> "$t>$u" }
        return buildString {
            append("v4|")
            append(width).append('x').append(height).append('|')
            append(prefsManager.searchEngine).append('|')
            append(bookmarks)
        }
    }

    private fun renderHomePreviewBitmap(width: Int, height: Int): Bitmap {
        val view = buildHomeOverlay(loadFavicons = false)
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun currentSearchEngineIconRes(): Int {
        return when {
            prefsManager.searchEngine.contains("google") -> R.drawable.ic_engine_google
            prefsManager.searchEngine.contains("duckduckgo") -> R.drawable.ic_engine_duckduckgo
            prefsManager.searchEngine.contains("bing") -> R.drawable.ic_engine_bing
            prefsManager.searchEngine.contains("brave") -> R.drawable.ic_engine_brave
            else -> R.drawable.ic_engine_google
        }
    }

    private fun setTopBarVisible(visible: Boolean, immediate: Boolean = false) {
        if (immediate) {
            topBar.alpha = if (visible) 1f else 0f
            topBar.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            topBar.visibility = View.VISIBLE
            topBar.translationY = -topBar.height.toFloat() * 0.2f
            topBar.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            topBar.animate().alpha(0f).translationY(-topBar.height.toFloat() * 0.2f).setDuration(120)
                .withEndAction { topBar.visibility = View.GONE }.start()
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
                val faviconUrl = if (host.isNotEmpty()) {
                    "https://www.google.com/s2/favicons?sz=64&domain=$host"
                } else {
                    "https://www.google.com/s2/favicons?sz=64&domain_url=${URLEncoder.encode(url, "utf-8")}"
                }
                val request = Request.Builder().url(faviconUrl).build()
                okClient.newCall(request).execute().use { response ->
                    val body = response.body ?: throw IllegalStateException("No body")
                    val bitmap = BitmapFactory.decodeStream(body.byteStream()) ?: throw IllegalStateException("Bad image")
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
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(
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
            w.marginStart = (18 * dp).toInt()
            w.marginEnd = (18 * dp).toInt()
            layoutParams = w
            setPadding(0, (18 * dp).toInt(), 0, (24 * dp).toInt())
        }
        scroll.addView(content)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18 * dp).toInt() }
        }
        content.addView(topRow)

        val topAction = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
            alpha = 0.95f
            setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
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
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_search)
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (26 * dp).toInt() }
        }
        content.addView(searchBar)

        val searchIcon = ImageView(this).apply {
            setImageResource(currentSearchEngineIconRes())
            setColorFilter(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
        }
        homeSearchEngineIcon = searchIcon
        searchBar.addView(searchIcon)

        val searchInput = EditText(this).apply {
            hint = "Search or type URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7C7C7C"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) navigateTo(query)
                    hideKeyboard()
                    true
                } else false
            }
        }
        searchBar.addView(searchInput)

        fun searchActionButton(iconRes: Int? = null, bitmap: Bitmap? = null, sizeDp: Int = 40, onClick: () -> Unit): ImageView {
            return ImageView(this).apply {
                if (bitmap != null) setImageBitmap(bitmap) else if (iconRes != null) setImageResource(iconRes)
                setColorFilter(Color.parseColor("#ECECEC"))
                setBackgroundResource(R.drawable.bottom_btn_ripple)
                layoutParams = LinearLayout.LayoutParams((sizeDp * dp).toInt(), (sizeDp * dp).toInt()).apply { marginStart = (6 * dp).toInt() }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onClick() }
            }
        }

        searchBar.addView(searchActionButton(iconRes = R.drawable.ic_qr, sizeDp = 40) { launchQrScanner() })
        searchBar.addView(searchActionButton(iconRes = R.drawable.ic_mic, sizeDp = 40) { launchVoiceSearch() })

        val fixedHeader = TextView(this).apply {
            text = "DEV BOOKMARKS"
            setTextColor(Color.parseColor("#7B7B7B"))
            textSize = 11f
            letterSpacing = 0.08f
            setPadding((4 * dp).toInt(), 0, 0, (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(fixedHeader)

        fun bookmarkGrid(items: List<Pair<String, String>>, loadRemoteIcons: Boolean) {
            if (items.isEmpty()) return
            val grid = GridLayout(this).apply {
                columnCount = 4
                useDefaultMargins = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            items.forEach { (title, url) ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (10 * dp).toInt())
                    }
                }

                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
                    setBackgroundResource(R.drawable.tab_card_bg)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                    setImageResource(R.drawable.ic_favicon_fallback)
                }

                val label = TextView(this).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams((54 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                }

                item.addView(icon)
                item.addView(label)
                item.setOnClickListener { navigateTo(url) }
                icon.setOnClickListener { navigateTo(url) }
                grid.addView(item)

                if (loadRemoteIcons) loadBookmarkFavicon(url, icon)
            }
            content.addView(grid)
        }

        val fixedSites = listOf(
            "GitHub" to "https://github.com",
            "Stack Overflow" to "https://stackoverflow.com",
            "MDN" to "https://developer.mozilla.org",
            "Kotlin" to "https://kotlinlang.org"
        )
        bookmarkGrid(fixedSites, loadFavicons)

        val userBookmarks = prefsManager.getBookmarks().take(12)
        if (userBookmarks.isNotEmpty()) {
            val userHeader = TextView(this).apply {
                text = "MY BOOKMARKS"
                setTextColor(Color.parseColor("#7B7B7B"))
                textSize = 11f
                letterSpacing = 0.08f
                setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            content.addView(userHeader)
            bookmarkGrid(userBookmarks, loadFavicons)
        }

        root.setOnClickListener { }
        return root
    }

    private fun buildErrorOverlay(): View {
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            lp.marginStart = (24 * dp).toInt()
            lp.marginEnd = (24 * dp).toInt()
            layoutParams = lp
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        root.addView(content)

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_clear)
            setColorFilter(Color.parseColor("#A6C8FF"))
            layoutParams = LinearLayout.LayoutParams((80 * dp).toInt(), (80 * dp).toInt()).apply {
                bottomMargin = (12 * dp).toInt()
            }
        }
        content.addView(icon)

        val title = TextView(this).apply {
            text = "Webpage not available"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        content.addView(title)

        val desc = TextView(this).apply {
            text = "Could not load the requested page."
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
        }
        content.addView(desc)

        val urlText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#777777"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (20 * dp).toInt())
            maxLines = 2
        }
        content.addView(urlText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(buttonRow)

        fun makeButton(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
                setBackgroundResource(R.drawable.bg_menu_item)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * dp).toInt()
                    marginEnd = (6 * dp).toInt()
                }
            }
        }

        buttonRow.addView(makeButton("Retry") {
            hideNativeOverlays()
            currentWebView?.reload()
        })
        buttonRow.addView(makeButton("Home") {
            showHomeOverlay()
        })
        buttonRow.addView(makeButton("Close") {
            hideNativeOverlays()
        })
        val updateUrlText = {
            urlText.text = lastErrorUrl?.let { "Could not connect to:\n$it" } ?: "Could not connect to the requested page."
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateUrlText() }
        root.post { updateUrlText() }
        return root
    }

    private fun fadeOverlay(view: View, visible: Boolean, immediate: Boolean = false) {
        if (immediate) {
            view.alpha = if (visible) 1f else 0f
            view.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(140).start()
        } else {
            view.animate().alpha(0f).setDuration(120).withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private fun hideNativeOverlays(immediate: Boolean = false) {
        nativeHomeOverlay?.let { fadeOverlay(it, false, immediate) }
        nativeErrorOverlay?.let { fadeOverlay(it, false, immediate) }
        nativeOverlayContainer.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled = true
    }

    private fun showHomeOverlay() {
        lastErrorUrl = null
        setTopBarVisible(false)
        nativeErrorOverlay?.let { fadeOverlay(it, false) }
        nativeHomeOverlay?.let {
            nativeOverlayContainer.visibility = View.VISIBLE
            fadeOverlay(it, true)
            nativeOverlayContainer.bringToFront()
            swipeRefresh.isRefreshing = false
            swipeRefresh.isEnabled = false
            progressBar.visibility = View.INVISIBLE
            textUrl.setText("")
            hideKeyboard()
        }
    }

    private fun showErrorOverlay(url: String?) {
        lastErrorUrl = url
        setTopBarVisible(false)
        nativeHomeOverlay?.let { fadeOverlay(it, false) }
        nativeErrorOverlay?.let {
            nativeOverlayContainer.visibility = View.VISIBLE
            fadeOverlay(it, true)
            nativeOverlayContainer.bringToFront()
            swipeRefresh.isRefreshing = false
            swipeRefresh.isEnabled = false
            progressBar.visibility = View.INVISIBLE
        }
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
                            val rawUrl = tObj.getString("url")
                            val t = TabState(
                                tObj.getInt("id"),
                                tObj.getString("title"),
                                if (isHomeStateUrl(rawUrl)) HOME_URL else rawUrl,
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

                    val activeTabUrl = currentGroup?.tabs?.find { it.id == activeTabId }?.url
                    if (isHomeStateUrl(activeTabUrl) && intentUrl.isNullOrEmpty()) showHomeOverlay() else hideNativeOverlays(immediate = true)

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
            if (isHomeStateUrl(url)) {
                newTab.hasThumbnail = true
                newTab.ramThumbnail = getHomePreviewBitmap()
            }
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
            if (isHomeStateUrl(tab.url)) showHomeOverlay() else { hideNativeOverlays(); setTopBarVisible(true) }
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
        textUrl.setText(if (isHomeStateUrl(url)) "" else url)
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
            currentGroup?.tabs?.find { it.id == activeTabId }?.let {
                if (isHomeStateUrl(it.url)) {
                    it.hasThumbnail = true
                    it.ramThumbnail = getHomePreviewBitmap()
                }
            }
            onComplete?.invoke()
            return
        }

        val tabId = activeTabId
        try {
            val homeLike = isHomeStateUrl(wv.url)
            val bitmap = if (homeLike) {
                getHomePreviewBitmap()
            } else {
                val scale = 0.3f
                val w = (wv.width * scale).toInt()
                val h = (wv.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                wv.draw(canvas)
                bitmap
            }

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
                    textUrl.setText(if (isHomeStateUrl(url)) "" else url)
                    updateBookmarkIcon(url ?: "")
                    if (isHomeStateUrl(url)) setTopBarVisible(false) else setTopBarVisible(true)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view == currentWebView) {
                    swipeRefresh.isRefreshing = false
                    progressBar.visibility = View.INVISIBLE
                }

                url?.let {
                    currentGroup?.tabs?.find { t -> t.id == tabId }?.let { tab ->
                        tab.title = view.title ?: "Tab"
                        tab.url = if (isHomeStateUrl(it)) HOME_URL else it
                        if (isHomeStateUrl(it) && tab.ramThumbnail == null) {
                            tab.ramThumbnail = getHomePreviewBitmap()
                            tab.hasThumbnail = true
                        }
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

                applyConsoleTools(view)
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

                // منع الإعلانات — يعمل على جميع الطلبات (صور، سكريبتات، إطارات...)
                if (prefsManager.adBlockEnabled) {
                    val host = request.url.host ?: ""
                    if (AdBlocker.isBlocked(url, host)) return AdBlocker.emptyResponse
                }

                // تم إصلاح الخطأ: استخدام الدالة getBoolean من PrefsManager بدلاً من الوصول المباشر
                if (prefsManager.getBoolean("disable_intercept", false)) return null

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

                                                        val erudaTags = if (prefsManager.consoleEnabled) {
                                "<script src=\"https://eruda.local/eruda.js\"></script>" +
                                "<script>(function(){if(window.__erudaInited){try{eruda.show();window.__cfConsoleEnabled=true;}catch(e){};return;}try{eruda.init();window.__erudaInited=true;window.__cfConsoleEnabled=true;}catch(e){}})()</script>"
                            } else {
                                ""
                            }
                            val customJsTag = prefsManager.customJs.takeIf { it.isNotEmpty() }?.let { "<script>$it</script>" } ?: ""

                            html = html.replaceFirst("<head>", "<head>$erudaTags$customJsTag", ignoreCase = true)
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
                if (req.isForMainFrame) {
                    runOnUiThread {
                        showErrorOverlay(req.url.toString())
                    }
                }
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

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                icon ?: return
                currentGroup?.tabs?.find { t -> t.id == tabId }?.let { tab ->
                    tab.faviconBitmap = icon
                    tabAdapter.updateFavicon(tabId, icon)
                }
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
        findViewById<View>(R.id.btnHomeArea).setOnClickListener    { showHomeOverlay() }

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
            if (isHomeStateUrl(url)) return@setOnClickListener
            val added = prefsManager.toggleBookmark(currentWebView?.title ?: "Bookmark", url)
            updateBookmarkIcon(url)
            buildNativeOverlays()
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnQr).setOnClickListener { launchQrScanner() }

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
        if (isHomeStateUrl(url)) {
            showHomeOverlay()
            currentWebView?.loadUrl(HOME_URL)
            textUrl.setText("")
            return
        }
        setTopBarVisible(true)
        hideNativeOverlays()
        textUrl.setText(url)
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
            cachedMenuSheetView?.findViewById<View>(R.id.menuAdBlock)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                prefsManager.adBlockEnabled = !prefsManager.adBlockEnabled
                updateMenuAdBlockState()
            }
            cachedMenuSheetView?.findViewById<View>(R.id.menuConsoleToggle)?.setOnClickListener {
                cachedMenuSheet?.dismiss()
                toggleConsoleForCurrentPage()
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
                startSettingsActivity()
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
        updateMenuAdBlockState()

        cachedMenuSheet?.show()
    }

    private fun showBookmarksDialog() {
        val bks = prefsManager.getBookmarks()
        if (bks.isEmpty()) {
            Toast.makeText(this, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        showListWithFavicons("Bookmarks", bks) { index ->
            loadUrlInstantly(bks[index].second)
        }
    }

    private fun showListWithFavicons(
        title: String,
        items: List<Pair<String, String>>,
        onSelect: (Int) -> Unit
    ) {
        val sheet = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
        val dp = resources.displayMetrics.density

        val scrollView = android.widget.ScrollView(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            try { setBackgroundResource(R.drawable.bg_bottom_sheet) }
            catch (_: Exception) { setBackgroundColor(Color.parseColor("#2C2C2C")) }
            setPadding(0, 32, 0, 32)
        }
        scrollView.addView(container)

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (100 * dp).toInt(), (12 * dp).toInt()
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
            }
            try { setBackgroundResource(R.drawable.bg_menu_item) }
            catch (_: Exception) { setBackgroundColor(Color.WHITE) }
        }
        container.addView(handle)

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding((48 * dp).toInt(), 0, (48 * dp).toInt(), (24 * dp).toInt())
        }
        container.addView(titleView)

        items.forEachIndexed { index, (itemTitle, url) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((48 * dp).toInt(), (20 * dp).toInt(), (48 * dp).toInt(), (20 * dp).toInt())
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }

            val iconSize = (28 * dp).toInt()
            val faviconView = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (16 * dp).toInt()
                }
                setImageResource(R.drawable.ic_favicon_fallback)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            row.addView(faviconView)

            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = itemTitle.ifEmpty { url }
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(textView)

            // Load favicon async from Google's service
            val domain = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
            if (domain.isNotEmpty()) {
                ioExecutor.execute {
                    try {
                        val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=64"
                        val req = okhttp3.Request.Builder().url(faviconUrl).build()
                        val resp = okClient.newCall(req).execute()
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            mainHandler.post {
                                if (bmp != null) {
                                    faviconView.setImageBitmap(bmp)
                                    faviconView.imageTintList = null
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            row.setOnClickListener {
                sheet.dismiss()
                onSelect(index)
            }
            container.addView(row)
        }

        sheet.setContentView(scrollView)
        sheet.show()
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webViews.values.forEach { it.clearCache(true); it.clearHistory() }

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
        val res = currentSearchEngineIconRes()
        imgSearchEngine.setImageResource(res)
        imgSearchEngine.colorFilter = null
        homeSearchEngineIcon?.setImageResource(res)
        homeSearchEngineIcon?.colorFilter = null
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha = if (!isHomeStateUrl(url) && prefsManager.isBookmarked(url)) 1.0f else 0.4f
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
        webViewContainer.visibility = View.VISIBLE
        customView = null
        setFullscreen(false)
    }

    private fun startSettingsActivity() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun consoleInitScript(): String =
        "(function(){" +
        "window.__cfConsoleEnabled=true;" +
        "var el=document.getElementById('eruda');" +
        "if(window.__erudaInited){" +
            "try{if(window.eruda&&eruda.show)eruda.show();}catch(e){}" +
            "if(el)el.style.display='';" +
            "return;" +
        "}" +
        "if(typeof eruda!=='undefined'){" +
            "try{eruda.init();window.__erudaInited=true;window.__cfConsoleEnabled=true;if(el)el.style.display='';}catch(e){}" +
            "return;" +
        "}" +
        "var x=new XMLHttpRequest();" +
        "x.open('GET','https://eruda.local/eruda.js',true);" +
        "x.onload=function(){" +
            "if(window.__erudaInited)return;" +
            "try{eval(x.responseText);eruda.init();window.__erudaInited=true;window.__cfConsoleEnabled=true;if(el)el.style.display='';}catch(e){}" +
        "};" +
        "x.send();" +
        "})()"

    private fun consoleDisableScript(): String =
        "(function(){" +
        "window.__cfConsoleEnabled=false;" +
        "try{if(window.eruda&&eruda.hide)eruda.hide();}catch(e){}" +
        "var el=document.getElementById('eruda');" +
        "if(el){el.style.display='none';el.classList.add('__cf_console_hidden');}" +
        "})()"

    private fun touchHookScript(): String =
        "(function(){" +
        "if(window.__erudaTouchHooked)return;" +
        "window.__erudaTouchHooked=true;" +
        "function getErudaEl(){return document.getElementById('eruda');}" +
        "function consoleEnabled(){return window.__cfConsoleEnabled!==false;}" +
        "document.addEventListener('touchstart',function(e){" +
            "if(!consoleEnabled())return;" +
            "var el=getErudaEl();" +
            "if(el&&el.contains(e.target)){try{Android.setSwipeRefresh(false);}catch(ex){}}" +
        "},{capture:true,passive:true});" +
        "document.addEventListener('touchend',function(){" +
            "if(!consoleEnabled())return;" +
            "try{Android.setSwipeRefresh(true);}catch(ex){}" +
        "},{capture:true,passive:true});" +
        "document.addEventListener('touchcancel',function(){" +
            "if(!consoleEnabled())return;" +
            "try{Android.setSwipeRefresh(true);}catch(ex){}" +
        "},{capture:true,passive:true});" +
        "})()"

    private fun toggleConsoleForCurrentPage() {
        val enable = !prefsManager.consoleEnabled
        prefsManager.consoleEnabled = enable
        currentWebView?.let { view ->
            if (enable) {
                view.evaluateJavascript(consoleInitScript(), null)
                view.evaluateJavascript(touchHookScript(), null)
            } else {
                view.evaluateJavascript(consoleDisableScript(), null)
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

    private fun updateMenuAdBlockState() {
        val enabled = prefsManager.adBlockEnabled
        cachedMenuSheetView?.findViewById<TextView>(R.id.menuAdBlockLabel)?.apply {
            text = if (enabled) "AdBlock On" else "AdBlock Off"
            setTextColor(if (enabled) Color.WHITE else Color.parseColor("#CCCCCC"))
        }
    }

    private fun applyConsoleTools(view: WebView) {
        if (prefsManager.consoleEnabled) {
            view.evaluateJavascript(consoleInitScript(), null)
            view.evaluateJavascript(touchHookScript(), null)
        } else {
            view.evaluateJavascript(consoleDisableScript(), null)
        }
        // حقن فلاتر CSS لإخفاء عناصر الإعلانات المتبقية
        if (prefsManager.adBlockEnabled) {
            val css = AdBlocker.getCosmeticCss()
            if (css.isNotEmpty()) view.evaluateJavascript(css, null)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  جسر JavaScript
    // ─────────────────────────────────────────────────────────────────────────

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) {
            runOnUiThread { navigateTo(input) }
        }

        // يُستدعى من JS عند لمس Eruda لتعطيل SwipeRefresh مؤقتاً
        @JavascriptInterface
        fun setSwipeRefresh(enabled: Boolean) {
            mainHandler.post { swipeRefresh.isEnabled = enabled }
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

    fun updateFavicon(tabId: Int, favicon: Bitmap) {
        val position = tabs.indexOfFirst { it.id == tabId }
        if (position >= 0) {
            tabs[position].faviconBitmap = favicon
            mainHandler.post { notifyItemChanged(position) }
        }
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

        // عرض الفيكون الحقيقي أو الـ fallback
        if (tab.faviconBitmap != null) {
            h.favicon.setImageBitmap(tab.faviconBitmap)
            h.favicon.imageTintList = null
        } else {
            h.favicon.setImageResource(R.drawable.ic_favicon_fallback)
            h.favicon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt()
            )
        }

        h.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

        if (tab.ramThumbnail != null) {
            h.thumbnail.setImageBitmap(tab.ramThumbnail)
        } else if (isHomeStateLikeUrl(tab.url)) {
            val homePreview = (context as? MainActivity)?.getHomePreviewBitmap() ?: generateHomePreviewBitmap()
            h.thumbnail.setImageBitmap(homePreview)
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