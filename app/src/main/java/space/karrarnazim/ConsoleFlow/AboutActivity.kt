package space.karrarnazim.ConsoleFlow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        applyInsets()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        loadBanner()

        findViewById<TextView>(R.id.tvVersion).text = "v${UpdateManager.currentVersion(this)}"

        mapOf(
            R.id.linkWebsite   to "https://consoleflow.karrarnazim.space",
            R.id.linkDeveloper to "https://karrarnazim.space",
            R.id.linkPrivacy   to "https://consoleflow.karrarnazim.space/privacy",
            R.id.linkGithub    to "https://github.com/ConsoleFlow-Group/ConsoleFlow-mobile"
        ).forEach { (viewId, url) ->
            findViewById<View>(viewId).setOnClickListener { openUrl(url) }
        }

        startUpdateCheck(forceRefresh = false)

        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            startUpdateCheck(forceRefresh = true)
        }
    }

    private fun loadBanner() {
        val wv = findViewById<WebView>(R.id.bannerWebView)

        val horizontalPaddingPx = (32 * resources.displayMetrics.density).toInt()
        val availableWidth      = resources.displayMetrics.widthPixels - horizontalPaddingPx
        val bannerHeight        = (availableWidth / 7.112f).toInt()

        wv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, bannerHeight)

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

    private fun showState(targetId: Int) {
        listOf(R.id.updateLoading, R.id.updateUpToDate, R.id.updateAvailable, R.id.updateError)
            .forEach { id ->
                findViewById<View>(id).visibility =
                    if (id == targetId) View.VISIBLE else View.GONE
            }
    }

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

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        val topBar = findViewById<View>(R.id.aboutTopBar)
        val scroll = findViewById<View>(R.id.aboutScroll)
        val scrollBaseBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            topBar.setPadding(topBar.paddingLeft, statusBarTop, topBar.paddingRight, topBar.paddingBottom)
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, scrollBaseBottom + navBarBottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
