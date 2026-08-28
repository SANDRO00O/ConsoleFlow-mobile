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

    private val cBg           = Color.BLACK
    private val cSurface      = Color.parseColor("#111111")
    private val cSurfaceHigh  = Color.parseColor("#1C1C1C")
    private val cOnBg         = Color.parseColor("#E6E1E5")
    private val cOnSurfaceVar = Color.parseColor("#AAAAAA")
    private val cPrimary      = Color.parseColor("#6EA8DC")
    private val cPrimaryDim   = Color.parseColor("#0D2033")
    private val cError        = Color.parseColor("#F2B8B5")
    private val cSuccess      = Color.parseColor("#5DB075")
    private val cOutline      = Color.parseColor("#333333")

    private lateinit var container: LinearLayout
    private lateinit var emptyLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var clearBtn: TextView
    private lateinit var topBar: LinearLayout
    private var clearResetRunnable: Runnable? = null
    private val clearHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildRoot()
        setContentView(root)
        applyInsets(root)
        DownloadTracker.downloads.observe(this) { render(it) }
    }

    private fun applyInsets(root: View) {
        val scrollBaseBottom = scrollView.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            topBar.setPadding(topBar.paddingLeft, statusBarTop, topBar.paddingRight, topBar.paddingBottom)
            scrollView.setPadding(scrollView.paddingLeft, scrollView.paddingTop, scrollView.paddingRight, scrollBaseBottom + navBarBottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        root.addView(buildTopBar())
        root.addView(buildDivider())

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

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(64))
        }
        topBar = bar

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

        clearBtn = TextView(this).apply {
            text = "Clear"
            textSize = 13f
            setTextColor(cPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(cSurface, 20f)
            visibility = View.GONE
            setOnClickListener {
                this.text = "Clear done"
                setTextColor(cSuccess)

                clearResetRunnable?.let { clearHandler.removeCallbacks(it) }

                DownloadTracker.clearFinished()

                clearResetRunnable = Runnable {
                    animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            text = "Clear"
                            setTextColor(cPrimary)
                            alpha = 1f
                        }
                        .start()
                    clearResetRunnable = null
                }
                clearHandler.postDelayed(clearResetRunnable!!, 2000)
            }
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

    private fun buildEmptyState(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)

            addView(ImageView(this@DownloadsActivity).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                alpha = 0.6f
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "No downloads yet"
                textSize = 18f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) }
            })
            addView(TextView(this@DownloadsActivity).apply {
                text = "Files you download will appear here"
                textSize = 13f
                setTextColor(Color.parseColor("#444444"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            })
        }
    }

    private fun render(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            scrollView.visibility  = View.GONE
            emptyLayout.visibility = View.VISIBLE
            clearBtn.visibility    = View.GONE
            return
        }

        scrollView.visibility  = View.VISIBLE
        emptyLayout.visibility = View.GONE

        val hasFinished = items.any {
            it.state == DownloadState.COMPLETED ||
            it.state == DownloadState.FAILED    ||
            it.state == DownloadState.CANCELLED
        }
        if (clearBtn.visibility == View.GONE || clearBtn.text == "Clear") {
            clearBtn.visibility = if (hasFinished) View.VISIBLE else View.GONE
        }

        container.removeAllViews()
        items.reversed().forEach { container.addView(buildCard(it)) }
    }

    private fun buildCard(item: DownloadItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundedBg(cSurface, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4))
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val iconWrap = android.widget.FrameLayout(this).apply {
            background = roundedBg(cPrimaryDim, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
        }
        iconWrap.addView(ImageView(this).apply {
            setImageResource(iconForMime(item.mimeType))
            setColorFilter(stateColor(item.state))
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        info.addView(TextView(this).apply {
            text = item.fileName
            textSize = 14f
            setTextColor(cOnBg)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val badge = when (item.state) {
            DownloadState.QUEUED    -> "Waiting…" to cOnSurfaceVar
            DownloadState.COMPLETED -> "Complete  ·  ${formatBytes(item.downloadedBytes)}" to cSuccess
            DownloadState.FAILED    -> "Failed: ${item.error ?: "Unknown error"}" to cError
            DownloadState.CANCELLED -> "Cancelled" to cOnSurfaceVar
            DownloadState.RUNNING   -> null
        }
        if (badge != null) {
            info.addView(TextView(this).apply {
                text = badge.first
                textSize = 12f
                setTextColor(badge.second)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }
            })
        }

        row1.addView(iconWrap)
        row1.addView(info)
        buildActionChip(item)?.let { row1.addView(it) }
        card.addView(row1)

        if (item.state == DownloadState.RUNNING) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(4)).apply { topMargin = dp(12) }
                max = 100
                if (item.totalBytes > 0) {
                    isIndeterminate = false
                    progress = ((item.downloadedBytes * 100) / item.totalBytes).toInt()
                    progressTintList           = android.content.res.ColorStateList.valueOf(cPrimary)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(cSurfaceHigh)
                } else {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(cPrimary)
                }
            }
            card.addView(pb)

            card.addView(TextView(this).apply {
                text = buildString {
                    append(formatBytes(item.downloadedBytes))
                    if (item.totalBytes > 0) {
                        append(" of ${formatBytes(item.totalBytes)}")
                        append("  (${(item.downloadedBytes * 100 / item.totalBytes).toInt()}%)")
                    }
                    if (item.speedBytesPerSec > 0) append("   ${formatSpeed(item.speedBytesPerSec)}")
                    if (item.etaSeconds > 0)        append("  ·  ${formatEta(item.etaSeconds)} left")
                }
                textSize = 11f
                setTextColor(cOnSurfaceVar)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
            })
        }

        return card
    }

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
                chip("Cancel", cError, Color.parseColor("#2A0A0A")) {
                    DownloadTracker.update(item.id) { state = DownloadState.CANCELLED }
                    startService(Intent(this, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_CANCEL
                        putExtra(DownloadService.EXTRA_DL_ID, item.id)
                    })
                }
            DownloadState.COMPLETED ->
                chip("Open", cSuccess, Color.parseColor("#0A2010")) { openFile(item) }
            DownloadState.FAILED, DownloadState.CANCELLED ->
                chip("Remove", cOnSurfaceVar, cSurfaceHigh) { DownloadTracker.remove(item.id) }
        }
    }

    private fun openFile(item: DownloadItem) {
        val path = item.filePath ?: return
        try {
            val uri: Uri = if (path.startsWith("content://")) Uri.parse(path)
                           else queryMediaStoreUri(path) ?: return
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            AppLogger.w("DownloadsActivity", "No app found to open $path (mime=${item.mimeType})", e)
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

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundedBg(color: Int, cornerDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = cornerDp * resources.displayMetrics.density
        }

    private fun iconForMime(mime: String): Int = when {
        mime.startsWith("image/")  -> android.R.drawable.ic_menu_gallery
        mime.startsWith("video/")  -> android.R.drawable.ic_media_play
        mime.startsWith("audio/")  -> android.R.drawable.ic_media_play
        mime == "application/pdf"  -> android.R.drawable.ic_menu_view
        mime.contains("zip") || mime.contains("archive") -> android.R.drawable.ic_menu_save
        else                       -> android.R.drawable.ic_menu_save
    }

    private fun stateColor(s: DownloadState): Int = when (s) {
        DownloadState.RUNNING    -> cPrimary
        DownloadState.COMPLETED  -> cSuccess
        DownloadState.FAILED     -> cError
        DownloadState.CANCELLED,
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
