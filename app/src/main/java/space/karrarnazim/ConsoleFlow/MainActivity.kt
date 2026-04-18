package space.karrarnazim.ConsoleFlow

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var geckoRuntime: GeckoRuntime
    private lateinit var geckoView: GeckoView

    private lateinit var topBar: LinearLayout
    private lateinit var textUrl: EditText
    private lateinit var imgSearchEngine: ImageView
    private lateinit var btnBookmark: ImageView
    private lateinit var btnQr: ImageView
    private lateinit var progressBar: View
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findMatches: TextView
    private lateinit var tabsOverlay: FrameLayout
    private lateinit var tabGroupsContainer: LinearLayout
    private lateinit var tabsRecycler: RecyclerView
    private lateinit var tabCount: TextView
    private lateinit var btnNewGroup: ImageView
    private lateinit var btnNewTab: ImageView
    private lateinit var btnMenu: ImageView
    private lateinit var goBack: ImageView
    private lateinit var goForward: ImageView
    private lateinit var goHome: ImageView

    private val tabStates = linkedMapOf<Int, TabState>()
    private val tabSessions = linkedMapOf<Int, GeckoSession>()
    private val tabGroups = linkedMapOf<Int, TabGroup>()
    private val tabCanGoBack = mutableMapOf<Int, Boolean>()
    private val tabCanGoForward = mutableMapOf<Int, Boolean>()
    private var activeTabId = -1
    private var selectedGroupId = 1
    private var nextTabId = 1
    private var nextGroupId = 2
    private var searchMatchesText = "0/0"

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            loadInput(contents)
        }
    }

    private val tabsAdapter = TabsAdapter(
        onSelect = { tabId ->
            switchToTab(tabId)
            hideTabsOverlay()
        },
        onClose = { tabId ->
            closeTab(tabId)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.decorView.setBackgroundColor(Color.BLACK)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)
        prefs = PrefsManager(this)
        bindViews()
        initBrowserEngine()
        initTabsState()
        initUi()
        onBackPressedDispatcher.addCallback(this) { handleBackPress() }
    }

    override fun onResume() {
        super.onResume()
        syncCurrentTabUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        tabSessions.values.forEach { runCatching { it.close() } }
    }

    private fun bindViews() {
        geckoView = findViewById(R.id.webViewContainer)
        topBar = findViewById(R.id.topBar)
        textUrl = findViewById(R.id.textUrl)
        imgSearchEngine = findViewById(R.id.imgSearchEngine)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnQr = findViewById(R.id.btnQr)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findMatches = findViewById(R.id.findMatches)
        tabsOverlay = findViewById(R.id.tabsOverlay)
        tabGroupsContainer = findViewById(R.id.tabGroupsContainer)
        tabsRecycler = findViewById(R.id.tabsRecycler)
        tabCount = findViewById(R.id.tabCount)
        btnNewGroup = findViewById(R.id.btnNewGroup)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnMenu = findViewById(R.id.btnMenu)
        goBack = findViewById(R.id.goBack)
        goForward = findViewById(R.id.goForward)
        goHome = findViewById(R.id.goHome)
    }

    private fun initBrowserEngine() {
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .allowInsecureConnections(
                if (prefs.httpsOnlyMode) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL
            )
            .javaScriptEnabled(prefs.allowJavaScript)
            .remoteDebuggingEnabled(prefs.remoteDebuggingEnabled)
            .extensionsProcessEnabled(prefs.webExtensionsEnabled)
            .extensionsWebAPIEnabled(prefs.webExtensionsEnabled)
            .consoleOutput(prefs.consoleEnabled)
            .build()

        geckoRuntime = GeckoRuntime.create(this, runtimeSettings)
        BrowserRuntimeStore.setRuntime(geckoRuntime)
    }

    private fun initTabsState() {
        tabGroups.clear()
        tabGroups[1] = TabGroup(1, "Tabs")
        selectedGroupId = 1

        if (tabStates.isEmpty()) {
            createTab(groupId = selectedGroupId, url = HOME_URL, switchTo = true)
        } else {
            tabStates.values.forEach { state ->
                tabGroups.getOrPut(selectedGroupId) { TabGroup(selectedGroupId, "Tabs") }.tabs.add(state)
            }
            nextTabId = max(nextTabId, (tabStates.keys.maxOrNull() ?: 0) + 1)
            restoreAllSessions()
            if (activeTabId == -1) activeTabId = tabStates.keys.first()
            switchToTab(activeTabId)
        }
    }

    private fun initUi() {
        tabsRecycler.layoutManager = GridLayoutManager(this, 2)
        tabsRecycler.adapter = tabsAdapter

        swipeRefresh.setColorSchemeColors(Color.WHITE)
        swipeRefresh.setOnRefreshListener {
            currentSession()?.reload()
            swipeRefresh.isRefreshing = false
        }

        textUrl.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadInput(textUrl.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        textUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) textUrl.selectAll() }

        imgSearchEngine.setOnClickListener { showSearchEngineChooser() }
        btnBookmark.setOnClickListener { toggleBookmarkForCurrentTab() }
        btnQr.setOnClickListener { launchQrScanner() }

        goBack.setOnClickListener { currentSession()?.goBack() }
        goForward.setOnClickListener { currentSession()?.goForward() }
        goHome.setOnClickListener { loadInput(HOME_URL) }

        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runFindInPage()
                true
            } else {
                false
            }
        }

        findBar.findViewById<ImageView>(R.id.btnFindNext).setOnClickListener { runFindInPage() }
        findBar.findViewById<ImageView>(R.id.btnFindPrev).setOnClickListener { runFindInPage() }
        findBar.findViewById<ImageView>(R.id.btnFindClose).setOnClickListener { hideFindBar() }

        btnNewGroup.setOnClickListener { createGroupAndRefresh() }
        btnNewTab.setOnClickListener {
            createTab(groupId = selectedGroupId, url = HOME_URL, switchTo = true)
            hideTabsOverlay()
        }
        btnMenu.setOnClickListener { showMainMenu() }

        findViewById<View>(R.id.btnTabsArea).setOnClickListener { showTabsOverlay() }
        findViewById<View>(R.id.btnMenuArea).setOnClickListener { showMainMenu() }
        findViewById<View>(R.id.btnBackArea).setOnClickListener { currentSession()?.goBack() }
        findViewById<View>(R.id.btnForwardArea).setOnClickListener { currentSession()?.goForward() }
        findViewById<View>(R.id.btnHomeArea).setOnClickListener { loadInput(HOME_URL) }

        updateTabChips()
        tabsAdapter.submitList(groupTabsFor(selectedGroupId))
        syncCurrentTabUi()
    }

    private fun createGroupAndRefresh() {
        val group = TabGroup(nextGroupId, "Group $nextGroupId")
        nextGroupId += 1
        tabGroups[group.id] = group
        selectedGroupId = group.id
        updateTabChips()
        tabsAdapter.submitList(groupTabsFor(group.id))
        toast("Group created")
    }

    private fun initSessionForTab(tabId: Int): GeckoSession {
        val sessionSettings = GeckoSessionSettings.Builder()
            .allowJavascript(prefs.allowJavaScript)
            .useTrackingProtection(prefs.trackingProtectionEnabled)
            .viewportMode(
                if (prefs.desktopMode) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                }
            )
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(geckoRuntime)

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (tabId == activeTabId) {
                    progressBar.visibility = View.VISIBLE
                    swipeRefresh.isRefreshing = true
                }
                updateTabTitle(tabId, url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (tabId == activeTabId) {
                    progressBar.visibility = View.INVISIBLE
                    swipeRefresh.isRefreshing = false
                }
                syncCurrentTabUi()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.visibility = if (progress >= 100) View.INVISIBLE else View.VISIBLE
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                updateTabTitle(tabId, title ?: "")
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tabCanGoBack[tabId] = canGoBack
                if (tabId == activeTabId) refreshNavButtons()
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tabCanGoForward[tabId] = canGoForward
                if (tabId == activeTabId) refreshNavButtons()
            }
        }

        return session
    }

    private fun restoreAllSessions() {
        tabSessions.values.forEach { runCatching { it.close() } }
        tabSessions.clear()

        tabStates.values.forEach { state ->
            val session = initSessionForTab(state.id)
            tabSessions[state.id] = session
            if (state.url.isNotBlank() && state.url != HOME_URL) {
                session.loadUri(state.url)
            }
        }
    }

    private fun createTab(groupId: Int, url: String, switchTo: Boolean): Int {
        tabGroups.getOrPut(groupId) { TabGroup(groupId, "Group $groupId") }

        val tabId = nextTabId++
        val state = TabState(id = tabId, title = "New Tab", url = url)
        tabStates[tabId] = state
        tabGroups[groupId]?.tabs?.add(state)

        val session = initSessionForTab(tabId)
        tabSessions[tabId] = session
        if (url.isNotBlank() && url != HOME_URL) {
            session.loadUri(url)
        }

        if (switchTo) {
            switchToTab(tabId)
        }

        refreshTabsUi()
        updateTabCount()
        return tabId
    }

    private fun closeTab(tabId: Int) {
        val session = tabSessions.remove(tabId)
        runCatching { session?.close() }

        tabStates.remove(tabId)
        tabGroups.values.forEach { group -> group.tabs.removeAll { it.id == tabId } }

        if (tabStates.isEmpty()) {
            createTab(groupId = selectedGroupId, url = HOME_URL, switchTo = true)
            return
        }

        if (activeTabId == tabId) {
            activeTabId = tabStates.keys.first()
            switchToTab(activeTabId)
        }

        refreshTabsUi()
        updateTabCount()
    }

    private fun switchToTab(tabId: Int) {
        val session = tabSessions[tabId] ?: return
        activeTabId = tabId
        geckoView.setSession(session)
        tabsOverlay.visibility = View.GONE
        progressBar.visibility = View.INVISIBLE
        refreshToolbarForActiveTab()
        refreshTabsUi()
        updateTabCount()
    }

    private fun refreshNavButtons() {
        goBack.isEnabled = tabCanGoBack[activeTabId] == true
        goForward.isEnabled = tabCanGoForward[activeTabId] == true
        goBack.alpha = if (goBack.isEnabled) 1f else 0.45f
        goForward.alpha = if (goForward.isEnabled) 1f else 0.45f
    }

    private fun refreshToolbarForActiveTab() {
        val state = tabStates[activeTabId]
        val session = currentSession()

        textUrl.setText(state?.url.orEmpty())
        textUrl.setSelection(textUrl.text?.length ?: 0)
        btnBookmark.isSelected = state?.let { prefs.isBookmarked(it.url) } == true
        btnBookmark.alpha = if (btnBookmark.isSelected) 1f else 0.72f
        goBack.isEnabled = tabCanGoBack[activeTabId] == true
        goForward.isEnabled = tabCanGoForward[activeTabId] == true
        goBack.alpha = if (goBack.isEnabled) 1f else 0.45f
        goForward.alpha = if (goForward.isEnabled) 1f else 0.45f
        findMatches.text = searchMatchesText
        swipeRefresh.isRefreshing = false
    }

    private fun syncCurrentTabUi() {
        refreshToolbarForActiveTab()
        tabsAdapter.submitList(groupTabsFor(selectedGroupId))
    }

    private fun updateTabTitle(tabId: Int, title: String) {
        val state = tabStates[tabId] ?: return
        state.title = title.ifBlank { state.url.ifBlank { "New Tab" } }
        state.hasThumbnail = true
        refreshTabsUi()
    }

    private fun refreshTabsUi() {
        tabsAdapter.submitList(groupTabsFor(selectedGroupId))
        updateTabChips()
    }

    private fun updateTabCount() {
        tabCount.text = tabStates.size.coerceAtLeast(1).toString()
    }

    private fun updateTabChips() {
        tabGroupsContainer.removeAllViews()
        tabGroups.values.forEach { group ->
            val chip = TextView(this).apply {
                text = group.name
                setTextColor(Color.WHITE)
                setPadding(28, 16, 28, 16)
                setBackgroundColor(if (group.id == selectedGroupId) Color.parseColor("#1A1A1A") else Color.BLACK)
                setOnClickListener {
                    selectedGroupId = group.id
                    tabsAdapter.submitList(groupTabsFor(group.id))
                    updateTabChips()
                }
            }
            tabGroupsContainer.addView(chip)
        }
    }

    private fun groupTabsFor(groupId: Int): List<TabState> {
        return tabGroups[groupId]?.tabs?.toList().orEmpty()
    }

    private fun currentSession(): GeckoSession? = tabSessions[activeTabId]

    private fun loadInput(input: String) {
        val resolved = resolveInputToUrl(input)
        val session = currentSession()
        if (session == null) {
            createTab(groupId = selectedGroupId, url = resolved, switchTo = true)
            return
        }

        val state = tabStates[activeTabId]
        state?.url = resolved
        session.loadUri(resolved)
        textUrl.setText(resolved)
        textUrl.setSelection(resolved.length)
        hideKeyboard(textUrl)
        hideFindBar()
    }

    private fun resolveInputToUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return HOME_URL
        if (text.startsWith("about:") || text.startsWith("file:") || text.startsWith("data:") || text.startsWith("intent:")) {
            return text
        }
        if (text.contains(" ") || !Patterns.WEB_URL.matcher(text).matches()) {
            return prefs.searchEngine + try {
                URLEncoder.encode(text, "UTF-8")
            } catch (_: UnsupportedEncodingException) {
                text
            }
        }
        return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    }

    private fun toggleBookmarkForCurrentTab() {
        val state = tabStates[activeTabId] ?: return
        val title = state.title.ifBlank { state.url }
        val added = prefs.toggleBookmark(title, state.url)
        btnBookmark.isSelected = added
        btnBookmark.alpha = if (added) 1f else 0.72f
        toast(if (added) "Bookmark added" else "Bookmark removed")
    }

    private fun showSearchEngineChooser() {
        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
        val urls = arrayOf(
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=",
            "https://search.brave.com/search?q="
        )

        val currentIndex = urls.indexOfFirst { prefs.searchEngine == it }.coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Search Engine")
            .setSingleChoiceItems(engines, currentIndex) { dialog, which ->
                prefs.searchEngine = urls[which]
                dialog.dismiss()
                toast("${engines[which]} selected")
            }
            .show()
    }

    private fun showBookmarksDialog() {
        val items = prefs.getBookmarks()
        if (items.isEmpty()) {
            toast("No bookmarks")
            return
        }

        val labels = items.map { (title, url) ->
            "${if (title.isBlank()) url else title}\n$url"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which ->
                loadInput(items[which].second)
            }
            .show()
    }

    private fun showHistoryDialog() {
        val items = prefs.getHistory()
        if (items.isEmpty()) {
            toast("No history")
            return
        }

        val labels = items.map { (title, url) ->
            "${if (title.isBlank()) url else title}\n$url"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("History")
            .setItems(labels) { _, which ->
                loadInput(items[which].second)
            }
            .show()
    }

    private fun showMainMenu() {
        val dialog = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
        val view = LayoutInflater.from(this).inflate(R.layout.layout_main_menu, null, false)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.menuNightMode).setOnClickListener {
            toast("Pure Black theme is fixed")
        }
        view.findViewById<View>(R.id.menuBookmarks).setOnClickListener {
            dialog.dismiss()
            showBookmarksDialog()
        }
        view.findViewById<View>(R.id.menuHistory).setOnClickListener {
            dialog.dismiss()
            showHistoryDialog()
        }
        view.findViewById<View>(R.id.menuConsoleToggle).setOnClickListener {
            prefs.consoleEnabled = !prefs.consoleEnabled
            restartBrowserEngine(preserveTabs = true)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.menuFindInPage).setOnClickListener {
            dialog.dismiss()
            showFindBar()
        }
        view.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            prefs.desktopMode = !prefs.desktopMode
            restartBrowserEngine(preserveTabs = true)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.menuSettings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.menuClearData).setOnClickListener {
            dialog.dismiss()
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Browsing Data")
                .setMessage("This clears Gecko cache, cookies, local storage, and saved app data.")
                .setPositiveButton("Clear") { _, _ ->
                    BrowserRuntimeStore.clearBrowserData(this)
                    prefs.clearHistory()
                    toast("Data cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun showTabsOverlay() {
        tabsOverlay.visibility = View.VISIBLE
        tabsAdapter.submitList(groupTabsFor(selectedGroupId))
        updateTabChips()
    }

    private fun hideTabsOverlay() {
        tabsOverlay.visibility = View.GONE
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        currentSession()?.finder?.clear()
    }

    private fun runFindInPage() {
        val query = findInput.text?.toString().orEmpty()
        if (query.isBlank()) return
        currentSession()?.finder?.find(query, 0)
        searchMatchesText = "Searching"
        findMatches.text = searchMatchesText
    }

    private fun handleBackPress() {
        when {
            tabsOverlay.visibility == View.VISIBLE -> hideTabsOverlay()
            findBar.visibility == View.VISIBLE -> hideFindBar()
            tabCanGoBack[activeTabId] == true -> currentSession()?.goBack()
            else -> finish()
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan a URL or text")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrScanLauncher.launch(options)
    }

    private fun restartBrowserEngine(preserveTabs: Boolean) {
        val snapshot = if (preserveTabs) {
            tabStates.values.map { TabState(it.id, it.title, it.url, it.hasThumbnail) }
        } else {
            emptyList()
        }

        tabSessions.values.forEach { runCatching { it.close() } }
        tabSessions.clear()

        initBrowserEngine()

        if (snapshot.isEmpty()) {
            tabStates.clear()
            tabGroups.values.forEach { it.tabs.clear() }
            activeTabId = -1
            nextTabId = 1
            createTab(groupId = selectedGroupId, url = HOME_URL, switchTo = true)
            return
        }

        tabStates.clear()
        tabGroups.values.forEach { it.tabs.clear() }

        snapshot.forEach { restored ->
            tabStates[restored.id] = restored
            tabGroups.getOrPut(selectedGroupId) { TabGroup(selectedGroupId, "Tabs") }.tabs.add(restored)
            nextTabId = max(nextTabId, restored.id + 1)
            tabSessions[restored.id] = initSessionForTab(restored.id)
            if (restored.url.isNotBlank() && restored.url != HOME_URL) {
                tabSessions[restored.id]?.loadUri(restored.url)
            }
        }

        activeTabId = tabStates.keys.firstOrNull() ?: -1
        if (activeTabId == -1) {
            createTab(groupId = selectedGroupId, url = HOME_URL, switchTo = true)
        } else {
            switchToTab(activeTabId)
        }

        refreshTabsUi()
        updateTabCount()
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun applyBrowserFeatureFlags(flags: BrowserFeatureFlags) {
        prefs.allowJavaScript = flags.allowJavascript
        prefs.trackingProtectionEnabled = flags.useTrackingProtection
        prefs.httpsOnlyMode = flags.httpsOnlyMode
        prefs.remoteDebuggingEnabled = flags.remoteDebuggingEnabled
        prefs.webExtensionsEnabled = flags.webExtensionsEnabled
        restartBrowserEngine(preserveTabs = true)
    }

    fun installWebExtension(uri: Uri) = geckoRuntime.webExtensionController.install(uri.toString())

    fun listWebExtensions() = geckoRuntime.webExtensionController.list()

    fun enableWebExtension(extension: WebExtension) = geckoRuntime.webExtensionController.enable(extension, 0)

    fun disableWebExtension(extension: WebExtension) = geckoRuntime.webExtensionController.disable(extension, 0)

    fun uninstallWebExtension(extension: WebExtension) = geckoRuntime.webExtensionController.uninstall(extension)

    private inner class TabsAdapter(
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit,
    ) : RecyclerView.Adapter<TabsAdapter.Holder>() {

        private var items: List<TabState> = emptyList()

        fun submitList(list: List<TabState>) {
            items = list.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tabTitle: TextView = itemView.findViewById(R.id.tabTitle)
            private val tabClose: ImageView = itemView.findViewById(R.id.tabClose)
            private val tabThumbnail: ShapeableImageView = itemView.findViewById(R.id.tabThumbnail)
            private val tabFavicon: ImageView = itemView.findViewById(R.id.tabFavicon)

            fun bind(state: TabState) {
                tabTitle.text = if (state.title.isBlank()) state.url.ifBlank { "New Tab" } else state.title
                tabFavicon.setImageResource(R.drawable.home)
                if (state.hasThumbnail && state.ramThumbnail != null) {
                    tabThumbnail.setImageBitmap(state.ramThumbnail)
                } else {
                    tabThumbnail.setImageDrawable(ColorDrawable(Color.BLACK))
                }

                itemView.setBackgroundColor(if (state.id == activeTabId) Color.parseColor("#121212") else Color.BLACK)
                itemView.setOnClickListener { onSelect(state.id) }
                tabClose.setOnClickListener { onClose(state.id) }
            }
        }
    }
}
