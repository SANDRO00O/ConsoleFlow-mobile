package space.karrarnazim.ConsoleFlow

import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class DownloadsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#111111")
        setContentView(buildRootLayout())
        DownloadTracker.downloads.observe(this) { items -> render(items) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // ── Top bar ──────────────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
        }

        // Same back button style as SettingsActivity
        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.arrow_left)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { finish() }
        }

        val titleTv = TextView(this).apply {
            text = "Downloads"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(10)
            }
        }

        val clearBtn = TextView(this).apply {
            text = "Clear done"
            textSize = 13f
            setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(12), dp(8), dp(0), dp(8))
            setOnClickListener { DownloadTracker.clearFinished() }
        }

        topBar.addView(backBtn)
        topBar.addView(titleTv)
        topBar.addView(clearBtn)
        root.addView(topBar)

        // Thin divider
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        })

        // ── List ─────────────────────────────────────────────────────────────
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scrollView.addView(container)
        root.addView(scrollView)

        // ── Empty state ───────────────────────────────────────────────────────
        emptyView = TextView(this).apply {
            text = "No downloads yet"
            textSize = 16f
            setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(emptyView)

        return root
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    private fun render(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            scrollView.visibility = View.GONE
            emptyView.visibility  = View.VISIBLE
            return
        }
        scrollView.visibility = View.VISIBLE
        emptyView.visibility  = View.GONE

        container.removeAllViews()
        items.reversed().forEach { container.addView(buildCard(it)) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Card builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCard(item: DownloadItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
        }

        // ── Row 1: icon · filename · action ──────────────────────────────────
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(this).apply {
            setImageResource(iconForMime(item.mimeType))
            setColorFilter(colorForState(item.state))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) }
        }

        val nameTv = TextView(this).apply {
            text = item.fileName
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        row1.addView(icon)
        row1.addView(nameTv)
        buildActionBtn(item)?.let { row1.addView(it) }
        card.addView(row1)

        // ── Row 2: progress / status ──────────────────────────────────────────
        when (item.state) {
            DownloadState.RUNNING -> {
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(10) }
                    max = 100
                    if (item.totalBytes > 0) {
                        isIndeterminate = false
                        progress = ((item.downloadedBytes * 100) / item.totalBytes).toInt()
                        progressTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#4A90D9"))
                    } else {
                        isIndeterminate = true
                    }
                }
                card.addView(pb)

                val txt = buildString {
                    append(formatBytes(item.downloadedBytes))
                    if (item.totalBytes > 0) {
                        append(" / ${formatBytes(item.totalBytes)}")
                        append("  (${(item.downloadedBytes * 100 / item.totalBytes).toInt()}%)")
                    }
                    if (item.speedBytesPerSec > 0) append("  •  ${formatSpeed(item.speedBytesPerSec)}")
                    if (item.etaSeconds > 0) append("  •  ${formatEta(item.etaSeconds)} left")
                }
                card.addView(sub(txt, "#888888"))
            }

            DownloadState.QUEUED     -> card.addView(sub("Waiting in queue…", "#555555"))
            DownloadState.COMPLETED  -> card.addView(sub("${formatBytes(item.downloadedBytes)}  •  Complete", "#4CAF50"))
            DownloadState.FAILED     -> card.addView(sub("Failed: ${item.error ?: "Unknown error"}", "#FF5252"))
            DownloadState.CANCELLED  -> card.addView(sub("Cancelled", "#444444"))
        }

        return card
    }

    private fun buildActionBtn(item: DownloadItem): View? {
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) }
        return when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> TextView(this).apply {
                text = "Cancel"; textSize = 12f; setTextColor(Color.parseColor("#FF5252"))
                setPadding(dp(6), dp(4), 0, dp(4)); layoutParams = lp
                setOnClickListener {
                    DownloadTracker.update(item.id) { state = DownloadState.CANCELLED }
                    startService(Intent(this@DownloadsActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_CANCEL
                        putExtra(DownloadService.EXTRA_DL_ID, item.id)
                    })
                }
            }
            DownloadState.COMPLETED -> TextView(this).apply {
                text = "Open"; textSize = 12f; setTextColor(Color.parseColor("#4CAF50"))
                setPadding(dp(6), dp(4), 0, dp(4)); layoutParams = lp
                setOnClickListener { openFile(item) }
            }
            DownloadState.FAILED, DownloadState.CANCELLED -> TextView(this).apply {
                text = "Remove"; textSize = 12f; setTextColor(Color.parseColor("#555555"))
                setPadding(dp(6), dp(4), 0, dp(4)); layoutParams = lp
                setOnClickListener { DownloadTracker.remove(item.id) }
            }
        }
    }

    private fun sub(text: String, hex: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.parseColor(hex))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File open
    // ─────────────────────────────────────────────────────────────────────────

    private fun openFile(item: DownloadItem) {
        val path = item.filePath ?: run {
            Toast.makeText(this, "File path unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = if (path.startsWith("content://")) Uri.parse(path)
                           else queryMediaStoreUri(path)
                               ?: Uri.fromFile(java.io.File(path))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryMediaStoreUri(filePath: String): Uri? = runCatching {
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(filePath), null
        )?.use { c ->
            if (c.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0))
            else null
        }
    }.getOrNull()

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun iconForMime(mime: String): Int = when {
        mime.startsWith("image/")  -> android.R.drawable.ic_menu_gallery
        mime.startsWith("video/")  -> android.R.drawable.ic_media_play
        mime.startsWith("audio/")  -> android.R.drawable.ic_media_play
        mime == "application/pdf"  -> android.R.drawable.ic_menu_view
        mime.contains("zip") || mime.contains("archive") -> android.R.drawable.ic_menu_save
        else                       -> android.R.drawable.ic_menu_save
    }

    private fun colorForState(s: DownloadState): Int = Color.parseColor(when (s) {
        DownloadState.RUNNING   -> "#4A90D9"
        DownloadState.COMPLETED -> "#4CAF50"
        DownloadState.FAILED    -> "#FF5252"
        DownloadState.CANCELLED -> "#444444"
        DownloadState.QUEUED    -> "#666666"
    })

    private fun formatBytes(b: Long): String = when {
        b < 1_024L         -> "$b B"
        b < 1_048_576L     -> "${"%.1f".format(b / 1_024.0)} KB"
        b < 1_073_741_824L -> "${"%.1f".format(b / 1_048_576.0)} MB"
        else               -> "${"%.2f".format(b / 1_073_741_824.0)} GB"
    }
    private fun formatSpeed(bps: Long) = "${formatBytes(bps)}/s"
    private fun formatEta(sec: Long): String = when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}
