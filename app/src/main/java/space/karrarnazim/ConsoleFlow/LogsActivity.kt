package space.karrarnazim.ConsoleFlow

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEntries: TextView
    private lateinit var tvSummaryErrors: TextView
    private lateinit var summaryCard: View
    private lateinit var adapter: LogAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFilter = LogLevel.values().toList()    // ALL by default
    private var searchQuery   = ""

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = buildUI()
        setContentView(root)

        // status bar insets — نفس pattern الـ MainActivity
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  بناء الواجهة برمجياً
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun buildUI(): LinearLayout {
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Header ──────────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnBack = ImageView(this).apply {
            setImageResource(R.drawable.arrow_left)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener { finish() }
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (10 * dp).toInt()
            }
        }
        val tvTitle = TextView(this).apply {
            text      = "Logs"
            textSize  = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val tvSubtitle = TextView(this).apply {
            text     = "Captured WebView & App events"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        titleBlock.addView(tvTitle)
        titleBlock.addView(tvSubtitle)

        // copy all button
        val btnCopy = ImageView(this).apply {
            setImageResource(R.drawable.ic_copy)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            alpha = 0.85f
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener { copyAllLogs() }
        }

        // share button
        val btnShare = ImageView(this).apply {
            setImageResource(R.drawable.ic_share)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            alpha = 0.85f
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener { shareLogs() }
        }

        // more (overflow) button
        val btnMore = ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            alpha = 0.85f
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener { showOverflowMenu(it) }
        }

        header.addView(btnBack)
        header.addView(titleBlock)
        header.addView(btnCopy)
        header.addView(btnShare)
        header.addView(btnMore)

        // ── Divider ─────────────────────────────────────────────────────────
        val divider = View(this).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
        }

        // ── Scrollable content ───────────────────────────────────────────────
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }

        val contentWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Filter Card ──────────────────────────────────────────────────────
        val filterLabel = TextView(this).apply {
            text     = "Filter"
            textSize = 13f
            setTextColor(0xFF7C5BE8.toInt())  // purple accent
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }

        val filterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(0xFF111111.toInt(), (12 * dp))
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (20 * dp).toInt() }
        }

        // Level row
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }
        val icFilter = ImageView(this).apply {
            setImageResource(R.drawable.ic_find)  // reuse existing icon
            setColorFilter(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
        }
        val levelTextBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvLevelTitle = TextView(this).apply {
            text     = "Level"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val tvLevelSubtitle = TextView(this).apply {
            text     = "Filter logs by severity"
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }
        levelTextBlock.addView(tvLevelTitle)
        levelTextBlock.addView(tvLevelSubtitle)

        val levelOptions = arrayOf("ALL", "ERROR", "WARN", "INFO", "DEBUG", "VERBOSE")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levelOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val spinner = Spinner(this).apply {
            adapter     = spinnerAdapter
            background  = null
            setPopupBackgroundResource(android.R.color.black)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>?) {}
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    currentFilter = when (pos) {
                        0    -> LogLevel.values().toList()
                        else -> listOf(LogLevel.valueOf(levelOptions[pos]))
                    }
                    refreshList()
                }
            }
        }
        // style the spinner text
        (spinner.selectedView as? TextView)?.setTextColor(Color.WHITE)

        levelRow.addView(icFilter)
        levelRow.addView(levelTextBlock)
        levelRow.addView(spinner)

        // Divider in filter card
        val filterDivider = View(this).apply {
            setBackgroundColor(0xFF222222.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply { bottomMargin = (12 * dp).toInt() }
        }

        // Search row
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            background  = roundedBg(0xFF1A1A1A.toInt(), (8 * dp))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val icSearch = ImageView(this).apply {
            setImageResource(R.drawable.ic_find)
            setColorFilter(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                marginEnd = (10 * dp).toInt()
            }
        }
        val etSearch = EditText(this).apply {
            hint         = "Search logs..."
            textSize     = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF555555.toInt())
            background   = null
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    refreshList()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        searchRow.addView(icSearch)
        searchRow.addView(etSearch)

        filterCard.addView(levelRow)
        filterCard.addView(filterDivider)
        filterCard.addView(searchRow)

        // ── Entries header ───────────────────────────────────────────────────
        tvEntries = TextView(this).apply {
            text     = "Entries (0)"
            textSize = 13f
            setTextColor(0xFF7C5BE8.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp).toInt()
            }
        }

        // ── Issue Summary Card ────────────────────────────────────────────────
        summaryCard = buildSummaryCard(dp)

        // ── RecyclerView ─────────────────────────────────────────────────────
        recycler = RecyclerView(this).apply {
            itemAnimator = null
            isNestedScrollingEnabled = false
            layoutManager = LinearLayoutManager(this@LogsActivity)
            layoutParams  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        adapter = LogAdapter()
        recycler.adapter = adapter

        contentWrapper.addView(filterLabel)
        contentWrapper.addView(filterCard)
        contentWrapper.addView(tvEntries)
        contentWrapper.addView(summaryCard)
        contentWrapper.addView(recycler)

        scroll.addView(contentWrapper)

        root.addView(header)
        root.addView(divider)
        root.addView(scroll)
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun buildSummaryCard(dp: Float): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(0xFF1A0505.toInt(), (12 * dp))
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        val icWarn = TextView(this).apply {
            text     = "⚠"
            textSize = 16f
            setTextColor(0xFFEF5350.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
        }
        val tvTitle = TextView(this).apply {
            text     = "Issue Summary"
            textSize = 15f
            setTextColor(0xFFEF5350.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(icWarn)
        titleRow.addView(tvTitle)

        tvSummaryErrors = TextView(this).apply {
            text     = "Total errors: 0"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        }

        card.addView(titleRow)
        card.addView(tvSummaryErrors)
        return card
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Data & Filtering
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun refreshList() {
        val all      = AppLogger.snapshot()
        val filtered = all.filter { entry ->
            entry.level in currentFilter &&
            (searchQuery.isEmpty() ||
             entry.message.contains(searchQuery, ignoreCase = true) ||
             entry.source.contains(searchQuery, ignoreCase = true))
        }

        val errorCount = all.count { it.level == LogLevel.ERROR }
        tvEntries.text       = "Entries (${filtered.size})"
        tvSummaryErrors.text = "Total errors: $errorCount"
        summaryCard.visibility = if (errorCount > 0) View.VISIBLE else View.GONE

        adapter.submitList(filtered)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyAllLogs() {
        val text = AppLogger.exportText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ConsoleFlow Logs", text))
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val text   = AppLogger.exportText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type    = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ConsoleFlow Logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Logs"))
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Clear all logs")
        popup.menu.add(0, 2, 0, "Refresh")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    AlertDialog.Builder(this, R.style.DarkDialog)
                        .setTitle("Clear Logs")
                        .setMessage("Delete all captured log entries?")
                        .setPositiveButton("Clear") { _, _ ->
                            AppLogger.clear()
                            refreshList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                2 -> refreshList()
            }
            true
        }
        popup.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun roundedBg(color: Int, radiusPx: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  RecyclerView Adapter
    // ─────────────────────────────────────────────────────────────────────────

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

        private var items: List<LogEntry> = emptyList()

        fun submitList(newItems: List<LogEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val tvTime:    TextView = root.getChildAt(0) as TextView
            val badgeRow:  LinearLayout = root.getChildAt(1) as LinearLayout
            val tvMessage: TextView = root.getChildAt(2) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp   = resources.displayMetrics.density
            val card = LinearLayout(this@LogsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background  = roundedBg(0xFF0D0D0D.toInt(), (8 * dp))
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
            }

            // timestamp
            val tvTime = TextView(this@LogsActivity).apply {
                textSize = 10f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * dp).toInt() }
            }

            // badge row: [LEVEL] [SOURCE]
            val badgeRow = LinearLayout(this@LogsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
            }
            // level badge (placeholder — bound in onBind)
            val levelBadge = TextView(this@LogsActivity).apply {
                textSize    = 9f
                setPadding((5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
                tag = "level_badge"
            }
            // source badge
            val sourceBadge = TextView(this@LogsActivity).apply {
                textSize    = 9f
                setPadding((5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
                tag = "source_badge"
            }
            // source name
            val sourceName = TextView(this@LogsActivity).apply {
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                tag = "source_name"
            }
            badgeRow.addView(levelBadge)
            badgeRow.addView(sourceBadge)
            badgeRow.addView(sourceName)

            // message
            val tvMessage = TextView(this@LogsActivity).apply {
                textSize = 12f
                setTextColor(0xFFDDDDDD.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            card.addView(tvTime)
            card.addView(badgeRow)
            card.addView(tvMessage)
            return VH(card)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, position: Int) {
            val dp    = resources.displayMetrics.density
            val entry = items[position]

            h.tvTime.text = entry.timeString

            val levelBadge  = h.badgeRow.findViewWithTag<TextView>("level_badge")
            val sourceBadge = h.badgeRow.findViewWithTag<TextView>("source_badge")
            val sourceName  = h.badgeRow.findViewWithTag<TextView>("source_name")

            // level badge
            levelBadge.text             = entry.level.label
            levelBadge.setTextColor(entry.level.color)
            levelBadge.background       = roundedBg(entry.level.bgColor, (4 * dp))

            // source badge (green accent like the screenshot)
            val srcBgColor = when (entry.source) {
                "JS"  -> 0xFF0D3320.toInt()
                "NET" -> 0xFF0D2033.toInt()
                "APP" -> 0xFF1A1A00.toInt()
                "WV"  -> 0xFF001A33.toInt()
                else  -> 0xFF1A1A1A.toInt()
            }
            val srcFgColor = when (entry.source) {
                "JS"  -> 0xFF4CAF50.toInt()
                "NET" -> 0xFF29B6F6.toInt()
                "APP" -> 0xFFFFEE58.toInt()
                "WV"  -> 0xFF42A5F5.toInt()
                else  -> 0xFFAAAAAA.toInt()
            }
            sourceBadge.text      = entry.source
            sourceBadge.setTextColor(srcFgColor)
            sourceBadge.background = roundedBg(srcBgColor, (4 * dp))

            sourceName.text = when (entry.source) {
                "JS"  -> "JavaScriptConsole"
                "NET" -> "NetworkError"
                "APP" -> "Application"
                "WV"  -> "WebViewClient"
                else  -> entry.source
            }

            h.tvMessage.text = entry.message

            // long-press to copy single entry
            h.root.setOnLongClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = "[${entry.timeString}] ${entry.level.label} [${entry.source}] ${entry.message}"
                cm.setPrimaryClip(ClipData.newPlainText("Log Entry", txt))
                Toast.makeText(this@LogsActivity, "Entry copied", Toast.LENGTH_SHORT).show()
                true
            }

            // card background tinted for errors
            h.root.background = roundedBg(
                if (entry.level == LogLevel.ERROR) 0xFF120505.toInt() else 0xFF0D0D0D.toInt(),
                (8 * dp)
            )
        }

        override fun getItemCount() = items.size
    }
}
