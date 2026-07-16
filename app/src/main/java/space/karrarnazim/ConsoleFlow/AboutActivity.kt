package space.karrarnazim.ConsoleFlow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        TvUtils.applyOverscanSafePadding(this, findViewById(android.R.id.content))

        // ── back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ── banner
        loadBanner()

        // ── current version
        findViewById<TextView>(R.id.tvVersion).text = "v${UpdateManager.currentVersion(this)}"

        // ── links
        mapOf(
            R.id.linkWebsite   to "https://consoleflow.karrarnazim.space",
            R.id.linkDeveloper to "https://karrarnazim.space",
            R.id.linkPrivacy   to "https://consoleflow.karrarnazim.space/privacy",
            R.id.linkGithub    to "https://github.com/SANDRO00O/ConsoleFlow-mobile"
        ).forEach { (viewId, url) ->
            findViewById<View>(viewId).setOnClickListener { openUrl(url) }
        }

        // ── update check (uses cache if fresh, hits network if stale)
        startUpdateCheck(forceRefresh = false)

        // ── manual check button
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            startUpdateCheck(forceRefresh = true)
        }
    }

    // ─── banner ──────────────────────────────────────────────────────────────

    private fun loadBanner() {
        val wv = findViewById<WebView>(R.id.bannerWebView)

        // TV FIX: this used to compute height from raw
        // resources.displayMetrics.widthPixels — the FULL screen width,
        // ignoring overscan-safe padding applied to the root container on TV
        // (see TvUtils). On a real TV panel that padding shrinks the banner's
        // actual on-screen width by ~10%, but the height was still calculated
        // from the wider, pre-padding figure — so the fixed-aspect-ratio
        // banner rendered taller than the space actually available and got
        // clipped. Measure the real parent width after layout instead of
        // guessing from screen metrics.
        val parentRow = wv.parent as View
        parentRow.post {
            val availableWidth = parentRow.width.takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density).toInt())
            val bannerHeight = (availableWidth / 7.112f).toInt()
            wv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, bannerHeight)
            finishLoadingBanner(wv)
        }
    }

    private fun finishLoadingBanner(wv: WebView) {

        wv.settings.loadWithOverviewMode    = true
        wv.settings.useWideViewPort         = true
        wv.isVerticalScrollBarEnabled       = false
        wv.isHorizontalScrollBarEnabled     = false
        wv.isScrollContainer                = false
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val svg = assets.open("banner.svg").bufferedReader().readText()
        val html = """<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<style>
  html,body{margin:0;padding:0;background:#000000;overflow:hidden;}
  svg{width:100%;height:auto;display:block;}
</style>
</head><body>$svg</body></html>"""

        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ─── update logic ────────────────────────────────────────────────────────

    private fun startUpdateCheck(forceRefresh: Boolean) {
        showState(R.id.updateLoading)
        UpdateManager.check(this, forceRefresh) { release, isUpdateAvailable ->
            applyUpdateResult(release, isUpdateAvailable)
        }
    }

    private fun applyUpdateResult(release: UpdateManager.ReleaseInfo?, isUpdateAvailable: Boolean) {
        when {
            release == null  -> showState(R.id.updateError)
            isUpdateAvailable -> {
                showState(R.id.updateAvailable)
                findViewById<TextView>(R.id.tvLatestVersion).text = "v${release.latestVersion}"
                findViewById<View>(R.id.btnDownload).setOnClickListener {
                    openUrl(release.releaseUrl)
                }
            }
            else -> showState(R.id.updateUpToDate)
        }

        if (release?.changelog?.isNotBlank() == true) {
            val section = findViewById<View>(R.id.changelogSection)
            section.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvChangelog).text = formatChangelog(release.changelog)
        }
    }

    /** Shows exactly one of the four update-state views, hides the rest. */
    private fun showState(targetId: Int) {
        listOf(R.id.updateLoading, R.id.updateUpToDate, R.id.updateAvailable, R.id.updateError)
            .forEach { id ->
                findViewById<View>(id).visibility =
                    if (id == targetId) View.VISIBLE else View.GONE
            }
    }

    // ─── changelog formatter ─────────────────────────────────────────────────

    /**
     * Converts the raw GitHub Markdown release body to clean plain text
     * suitable for a standard Android TextView.
     */
    private fun formatChangelog(raw: String): String =
        raw.lines()
            .joinToString("\n") { line ->
                when {
                    line.startsWith("## ") -> "\n${line.drop(3).uppercase()}"
                    line.startsWith("# ")  -> "\n${line.drop(2).uppercase()}"
                    line.startsWith("- ")  -> "  •  ${line.drop(2)}"
                    line.startsWith("* ")  -> "  •  ${line.drop(2)}"
                    else                   -> line
                }
            }
            .replace(Regex("""\*\*(.+?)\*\*""")) { it.groupValues[1] }
            .replace(Regex("""`(.+?)`""")) { it.groupValues[1] }
            .trimStart('\n')
            .trim()

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
