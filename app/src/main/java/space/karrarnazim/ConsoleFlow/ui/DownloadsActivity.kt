package space.karrarnazim.ConsoleFlow

import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class DownloadsActivity : AppCompatActivity() {

    // ── M3 Dark colour tokens ─────────────────────────────────────────────
    private val cBg            = Color.parseColor("#1C1B1F")
    private val cSurface       = Color.parseColor("#2B2930")
    private val cSurfaceHigh   = Color.parseColor("#36343B")
    private val cOnBg          = Color.parseColor("#E6E1E5")
    private val cOnSurfaceVar  = Color.parseColor("#CAC4D0")
    private val cPrimary       = Color.parseColor("#6EA8DC")   // app blue
    private val cPrimaryDim    = Color.parseColor("#1A3A5C")   // tonal container
    private val cError         = Color.parseColor("#F2B8B5")
    private val cSuccess       = Color.parseColor("#5DB075")
    private val cOutline       = Color.parseColor("#49454F")

    private lateinit var container: LinearLayout
    private lateinit var emptyLayout: LinearLayout
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(buildRoot())
        DownloadTracker.downloads.observe(this) { render(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        root.addView(buildTopBar())
        root.addView(buildDivider())

        // Scroll area + empty state share the same space
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(32))
        }
        scrollView.addView(container)

        emptyLayout = buildEmptyState()

        frame.addView(scrollView)
        frame.addView(emptyLayout)
        root.addView(frame)
        return root
    }

    // ── Top app bar ───────────────────────────────────────────────────────────

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(64))
        }

        val back = ImageView(this).apply {
            setImageResource(R.drawable.arrow_left)
            setBackgroundResource(R.drawable.bottom_btn_ripple)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = "Downloads"
            textSize = 22f
            setTextColor(cOnBg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(4)
            }
        }

        val clearBtn = TextView(this).apply {
            text = "Clear done"
            textSize = 13f
            setTextColor(cPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(cSurface, 20f)
            setOnClickListener { DownloadTracker.clearFinished() }
        }

        bar.addView(back)
        bar.addView(title)
        bar.addView(clearBtn)
        return bar
    }

    private fun buildDivider() = View(this).apply {
        setBackgroundColor(cOutline)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    private fun buildEmptyState(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)

            val icon = ImageView(this@DownloadsActivity).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(cOutline)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                alpha = 0.5f
            }

            val title = TextView(this@DownloadsActivity).apply {
                text = "No downloads yet"
                textSize = 18f
                setTextColor(cOnSurfaceVar)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(20)
                }
            }

            val sub = TextView(this@DownloadsActivity).apply {
                text = "Files you download will appear here"
                textSize = 13f
                setTextColor(cOutline)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(8)
                }
            }

            addView(icon); addView(title); addView(sub)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    private fun render(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            scrollView.visibility  = View.GONE
            emptyLayout.visibility = View.VISIBLE
            return
        }
        scrollView.visibility  = View.VISIBLE
        emptyLayout.visibility = View.GONE
        container.removeAllViews()
        items.reversed().forEach { container.addView(buildCard(it)) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // M3 download card
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCard(item: DownloadItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(cSurface, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4))
            }
        }

        // ── Row 1: tonal icon · info column · action ──────────────────────
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        // Tonal icon container (M3 style)
        val iconContainer = android.widget.FrameLayout(this).apply {
            background = roundedBg(cPrimaryDim, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(14)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForMime(item.mimeType))
            setColorFilter(stateColor(item.state))
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        iconContainer.addView(icon)

        // Info column
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = item.fileName
            textSize = 14f
            setTextColor(cOnBg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // State badge
        val badgeText = when (item.state) {
            DownloadState.QUEUED   -> "Waiting"
            DownloadState.RUNNING  -> null   // replaced by progress bar
            DownloadState.COMPLETED -> "Complete  •  ${formatBytes(item.downloadedBytes)}"
            DownloadState.FAILED   -> "Failed: ${item.error ?: "Unknown error"}"
            DownloadState.CANCELLED -> "Cancelled"
        }
        val badgeColor = when (item.state) {
            DownloadState.COMPLETED -> cSuccess
            DownloadState.FAILED    -> cError
            else                    -> cOnSurfaceVar
        }
        if (badgeText != null) {
            infoCol.addView(TextView(this).apply {
                text = badgeText
                textSize = 12f
                setTextColor(badgeColor)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) }
            })
        }

        // Action button (right side)
        val action = buildActionChip(item)

        row1.addView(iconContainer)
        row1.addView(infoCol)
        if (action != null) row1.addView(action)
        card.addView(row1)

        // ── Progress section (RUNNING only) ────────────────────────────────
        if (item.state == DownloadState.RUNNING) {
            // Progress bar — M3 style (4dp height, rounded, primary color)
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(4)).apply {
                    topMargin = dp(12)
                }
                max = 100
                if (item.totalBytes > 0) {
                    isIndeterminate = false
                    progress = ((item.downloadedBytes * 100) / item.totalBytes).toInt()
                    progressTintList =
                        android.content.res.ColorStateList.valueOf(cPrimary)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(cSurfaceHigh)
                } else {
                    isIndeterminate = true
                    indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(cPrimary)
                }
            }
            card.addView(pb)

            // Speed / ETA row
            val statsText = buildString {
                append(formatBytes(item.downloadedBytes))
                if (item.totalBytes > 0) {
                    val pct = (item.downloadedBytes * 100 / item.totalBytes).toInt()
                    append(" of ${formatBytes(item.totalBytes)}  ($pct%)")
                }
                if (item.speedBytesPerSec > 0) append("   ${formatSpeed(item.speedBytesPerSec)}")
                if (item.etaSeconds > 0)        append("  •  ${formatEta(item.etaSeconds)} left")
            }
            card.addView(TextView(this).apply {
                text = statsText
                textSize = 11f
                setTextColor(cOnSurfaceVar)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            })
        }

        return card
    }

    // ── Action chip ───────────────────────────────────────────────────────────

    private fun buildActionChip(item: DownloadItem): View? {
        val lp = LinearLayout.LayoutParams(WRAP, dp(32)).apply { marginStart = dp(10) }

        fun chip(label: String, fg: Int, bg: Int, action: () -> Unit) =
            TextView(this).apply {
                text = label; textSize = 12f; setTextColor(fg)
                background = roundedBg(bg, 8f)
                setPadding(dp(10), 0, dp(10), 0)
                gravity = Gravity.CENTER
                layoutParams = lp
                setOnClickListener { action() }
            }

        return when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED ->
                chip("Cancel", cError, Color.parseColor("#3B1F1F")) {
                    DownloadTracker.update(item.id) { state = DownloadState.CANCELLED }
                    startService(Intent(this, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_CANCEL
                        putExtra(DownloadService.EXTRA_DL_ID, item.id)
                    })
                }
            DownloadState.COMPLETED ->
                chip("Open", cSuccess, Color.parseColor("#1A3B26")) {
                    openFile(item)
                }
            DownloadState.FAILED, DownloadState.CANCELLED ->
                chip("Remove", cOnSurfaceVar, cSurfaceHigh) {
                    DownloadTracker.remove(item.id)
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File open
    // ─────────────────────────────────────────────────────────────────────────

    private fun openFile(item: DownloadItem) {
        val path = item.filePath ?: return
        try {
            val uri: Uri = if (path.startsWith("content://")) Uri.parse(path)
                           else queryMediaStoreUri(path) ?: return
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryMediaStoreUri(path: String): Uri? = runCatching {
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
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

    private fun roundedBg(color: Int, cornerDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape         = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius  = cornerDp * resources.displayMetrics.density
        }

    private fun iconForMime(mime: String): Int = when {
        mime.startsWith("image/")  -> android.R.drawable.ic_menu_gallery
        mime.startsWith("video/")  -> android.R.drawable.ic_media_play
        mime.startsWith("audio/")  -> android.R.drawable.ic_media_play
        mime == "application/pdf"  -> android.R.drawable.ic_menu_view
        mime.contains("zip") || mime.contains("archive") -> android.R.drawable.ic_menu_save
        else -> android.R.drawable.ic_menu_save
    }

    private fun stateColor(s: DownloadState): Int = when (s) {
        DownloadState.RUNNING    -> cPrimary
        DownloadState.COMPLETED  -> cSuccess
        DownloadState.FAILED     -> cError
        DownloadState.CANCELLED  -> cOutline
        DownloadState.QUEUED     -> cOnSurfaceVar
    }

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
