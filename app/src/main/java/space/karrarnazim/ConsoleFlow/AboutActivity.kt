package space.karrarnazim.ConsoleFlow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // ── back button
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

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
