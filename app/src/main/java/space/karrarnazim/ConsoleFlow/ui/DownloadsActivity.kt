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
    private lateinit var topCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#111111")
        setContentView(buildRootLayout())

        DownloadTracker.downloads.observe(this) { items ->
            render(items)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root layout (built programmatically — no XML needed)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#000000"))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top bar ──────────────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(0), dp(16), dp(0))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
        }

        val backBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        }

        val titleTv = TextView(this).apply {
            text = "Downloads"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }

        topCount = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            text = ""
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
        topBar.addView(topCount)
        topBar.addView(clearBtn)
        root.addView(topBar)

        // ── Scroll container ─────────────────────────────────────────────────
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
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
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(emptyView)

        return root
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List rendering — rebuild on every LiveData update
    // ─────────────────────────────────────────────────────────────────────────

    private fun render(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            scrollView.visibility = View.GONE
            emptyView.visibility  = View.VISIBLE
            topCount.text = ""
            return
        }

        scrollView.visibility = View.VISIBLE
        emptyView.visibility  = View.GONE

        val active = items.count {
            it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
        }
        topCount.text = if (active > 0) " $active active" else ""

        container.removeAllViews()
        // Newest first
        items.reversed().forEach { item ->
            container.addView(buildItemCard(item))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Card builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildItemCard(item: DownloadItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
            // Rounded corners via background — simple rect for API compat
        }

        // ── Row 1: icon · filename · action button ────────────────────────
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(this).apply {
            setImageResource(iconForMime(item.mimeType))
            setColorFilter(colorForState(item.state))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(12)
            }
        }

        val nameTv = TextView(this).apply {
            text = item.fileName
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val actionBtn = buildActionButton(item)

        row1.addView(icon)
        row1.addView(nameTv)
        if (actionBtn != null) row1.addView(actionBtn)
        card.addView(row1)

        // ── Row 2: progress + status ───────────────────────────────────────
        when (item.state) {
            DownloadState.RUNNING -> {
                // Progress bar
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                    ).apply { topMargin = dp(10) }
                    max = 100
                    if (item.totalBytes > 0) {
                        isIndeterminate = false
                        progress = ((item.downloadedBytes * 100) / item.totalBytes).toInt()
                        progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A90D9"))
                    } else {
                        isIndeterminate = true
                    }
                }
                card.addView(pb)

                // Speed / ETA row
                val statusText = buildString {
                    append(formatBytes(item.downloadedBytes))
                    if (item.totalBytes > 0) {
                        append(" / ${formatBytes(item.totalBytes)}")
                        val pct = (item.downloadedBytes * 100 / item.totalBytes).toInt()
                        append("  ($pct%)")
                    }
                    if (item.speedBytesPerSec > 0) {
                        append("  •  ${formatSpeed(item.speedBytesPerSec)}")
                    }
                    if (item.etaSeconds > 0) {
                        append("  •  ${formatEta(item.etaSeconds)} left")
                    }
                }
                card.addView(subText(statusText, "#888888"))
            }

            DownloadState.QUEUED ->
                card.addView(subText("Waiting in queue…", "#666666"))

            DownloadState.COMPLETED ->
                card.addView(subText(
                    "${formatBytes(item.downloadedBytes)}  •  Completed",
                    "#4CAF50"
                ))

            DownloadState.FAILED ->
                card.addView(subText(
                    "Failed: ${item.error ?: "Unknown error"}",
                    "#FF5252"
                ))

            DownloadState.CANCELLED ->
                card.addView(subText("Cancelled", "#555555"))
        }

        return card
    }

    private fun buildActionButton(item: DownloadItem): View? {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) }

        return when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> TextView(this).apply {
                text = "Cancel"
                textSize = 12f
                setTextColor(Color.parseColor("#FF5252"))
                setPadding(dp(6), dp(4), dp(0), dp(4))
                layoutParams = lp
                setOnClickListener {
                    // Mark cancelled locally; service checks this flag
                    DownloadTracker.update(item.id) { state = DownloadState.CANCELLED }
                    startService(Intent(this@DownloadsActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_CANCEL
                        putExtra(DownloadService.EXTRA_DL_ID, item.id)
                    })
                }
            }

            DownloadState.COMPLETED -> TextView(this).apply {
                text = "Open"
                textSize = 12f
                setTextColor(Color.parseColor("#4CAF50"))
                setPadding(dp(6), dp(4), dp(0), dp(4))
                layoutParams = lp
                setOnClickListener { openFile(item) }
            }

            DownloadState.FAILED, DownloadState.CANCELLED -> TextView(this).apply {
                text = "Remove"
                textSize = 12f
                setTextColor(Color.parseColor("#555555"))
                setPadding(dp(6), dp(4), dp(0), dp(4))
                layoutParams = lp
                setOnClickListener { DownloadTracker.remove(item.id) }
            }
        }
    }

    private fun subText(text: String, hexColor: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor(hexColor))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
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
            val uri: Uri = if (path.startsWith("content://")) {
                // API 29+: content URI stored directly
                Uri.parse(path)
            } else {
                // Pre-Q: query MediaStore for the content URI
                queryMediaStoreUri(path) ?: Uri.parse("content://downloads/public_downloads")
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryMediaStoreUri(filePath: String): Uri? {
        return try {
            val cursor = contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(filePath), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri("external"), id
                    )
                } else null
            }
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun iconForMime(mime: String): Int = when {
        mime.startsWith("image/")   -> android.R.drawable.ic_menu_gallery
        mime.startsWith("video/")   -> android.R.drawable.ic_media_play
        mime.startsWith("audio/")   -> android.R.drawable.ic_media_play
        mime == "application/pdf"   -> android.R.drawable.ic_menu_view
        mime.contains("zip")
            || mime.contains("archive")
            || mime.contains("compressed") -> android.R.drawable.ic_menu_save
        else -> android.R.drawable.ic_menu_save
    }

    private fun colorForState(state: DownloadState): Int = when (state) {
        DownloadState.RUNNING    -> Color.parseColor("#4A90D9")
        DownloadState.COMPLETED  -> Color.parseColor("#4CAF50")
        DownloadState.FAILED     -> Color.parseColor("#FF5252")
        DownloadState.CANCELLED  -> Color.parseColor("#555555")
        DownloadState.QUEUED     -> Color.parseColor("#888888")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024L         -> "$bytes B"
        bytes < 1_048_576L     -> "${"%.1f".format(bytes / 1_024.0)} KB"
        bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        else                   -> "${"%.2f".format(bytes / 1_073_741_824.0)} GB"
    }

    private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

    private fun formatEta(sec: Long): String = when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}
