package space.karrarnazim.ConsoleFlow

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private val adapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        recycler  = findViewById(R.id.logsRecycler)
        emptyState = findViewById(R.id.logsEmptyState)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShareLogs).setOnClickListener { shareLogs() }
        findViewById<View>(R.id.btnClearLogs).setOnClickListener { confirmClear() }

        applyInsets()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = AppLogger.snapshot()
        adapter.submit(entries)
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility   = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isNotEmpty()) recycler.scrollToPosition(entries.size - 1)
    }

    private fun shareLogs() {
        if (AppLogger.snapshot().isEmpty()) {
            Toast.makeText(this, "No logs to share yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = AppLogger.exportToFile(this)
            val uri  = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ConsoleFlow debug log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share log"))
        } catch (e: Exception) {
            AppLogger.e("LogsActivity", "Failed to export/share log file", e)
            Toast.makeText(this, "Couldn't prepare the log file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Clear Logs")
            .setMessage("This deletes the log currently shown here. It can't be undone.")
            .setPositiveButton("Clear") { _, _ ->
                AppLogger.clear()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        val topBar = findViewById<View>(R.id.logsTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            topBar.setPadding(topBar.paddingLeft, statusBarTop, topBar.paddingRight, topBar.paddingBottom)
            recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, navBarBottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
