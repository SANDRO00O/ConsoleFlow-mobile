package space.karrarnazim.ConsoleFlow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import space.karrarnazim.ConsoleFlow.R
import space.karrarnazim.ConsoleFlow.logging.LogEntry
import space.karrarnazim.ConsoleFlow.logging.LogParser
import space.karrarnazim.ConsoleFlow.logging.LogRepository
import space.karrarnazim.ConsoleFlow.ui.adapters.LogEntryAdapter
import java.util.concurrent.Executors

/**
 * ── شاشة عرض السجلات ─────────────────────────────────────────────────────
 * الجزء المرئي من نظام التسجيل الاحترافي: يعرض محتوى ملفات FileLoggingTree
 * داخل التطبيق مباشرة، بفلترة حسب المستوى وبحث نصي، وزر مشاركة يُصدّر ملف
 * السجل الفعلي (عبر FileProvider) — بديل مباشر لمطالبة المستخدم بتشغيل
 * `adb logcat` يدوياً في كل مرة يُطلَب منه سجل تشخيصي.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var adapter: LogEntryAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var searchInput: EditText

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var allEntries: List<LogEntry> = emptyList()
    private var activeLevelFilter: String? = null // null = الكل
    private val levels = listOf("E", "W", "I", "D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        recycler = findViewById(R.id.logsRecycler)
        emptyState = findViewById(R.id.logsEmptyState)
        searchInput = findViewById(R.id.logsSearchInput)

        adapter = LogEntryAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.btnLogsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnLogsShare).setOnClickListener { shareLogs() }
        findViewById<View>(R.id.btnLogsClear).setOnClickListener { confirmClearLogs() }

        buildFilterChips()

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = applyFilters()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        // يُحدَّث تلقائياً عند العودة للشاشة — مفيد لو تركتها مفتوحة
        // بينما تُعيد إنتاج مشكلة بتبويب آخر ثم رجعت لمراجعة السجل الجديد.
        loadEntries()
    }

    private fun buildFilterChips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.logsFilterChipsContainer)
        val allChip = makeChip("All", selected = true) { chipView ->
            activeLevelFilter = null
            highlightSelectedChip(container, chipView)
            applyFilters()
        }
        container.addView(allChip)
        for (level in levels) {
            val chip = makeChip(levelLabel(level)) { chipView ->
                activeLevelFilter = level
                highlightSelectedChip(container, chipView)
                applyFilters()
            }
            container.addView(chip)
        }
    }

    private fun levelLabel(level: String) = when (level) {
        "E" -> "Error"; "W" -> "Warn"; "I" -> "Info"; "D" -> "Debug"; else -> level
    }

    private fun makeChip(label: String, selected: Boolean = false, onClick: (View) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setPadding(28, 14, 28, 14)
            textSize = 13f
            setTextColor(if (selected) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            setBackgroundColor(
                if (selected) android.graphics.Color.parseColor("#FFFFFF")
                else android.graphics.Color.parseColor("#2A2A2A")
            )
            val marginEnd = 12
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, marginEnd, 0) }
            setOnClickListener { onClick(this) }
        }
    }

    private fun highlightSelectedChip(container: android.widget.LinearLayout, selectedView: View) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? TextView ?: continue
            val isSelected = child === selectedView
            child.setTextColor(if (isSelected) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            child.setBackgroundColor(
                if (isSelected) android.graphics.Color.parseColor("#FFFFFF")
                else android.graphics.Color.parseColor("#2A2A2A")
            )
        }
    }

    private fun loadEntries() {
        ioExecutor.execute {
            val tree = LogRepository.get()
            val entries = tree?.let { LogParser.parseAll(it.allLogFiles()) } ?: emptyList()
            runOnUiThread {
                allEntries = entries
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = allEntries.filter { entry ->
            (activeLevelFilter == null || entry.level == activeLevelFilter) &&
            (query.isEmpty() || entry.tag.lowercase().contains(query) || entry.message.lowercase().contains(query))
        }
        adapter.submit(filtered)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shareLogs() {
        val tree = LogRepository.get() ?: return
        val files = tree.allLogFiles()
        if (files.isEmpty()) {
            android.widget.Toast.makeText(this, "No logs to share yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uris = files.map { file ->
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share logs"))
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Clear logs")
            .setMessage("This deletes all stored log files. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                LogRepository.get()?.clearAll()
                allEntries = emptyList()
                applyFilters()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
