package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefsManager = PrefsManager(this)

        // Back button
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        overridePendingTransition(0, 0)

        // Search engine row
        updateEngineDisplay()
        findViewById<android.view.View>(R.id.rowSearchEngine).setOnClickListener {
            showEngineChooser()
        }

        // Desktop mode toggle
        val switchDesktop = findViewById<Switch>(R.id.switchDesktop)
        switchDesktop.isChecked = prefsManager.desktopMode
        findViewById<android.view.View>(R.id.rowDesktopMode).setOnClickListener {
            prefsManager.desktopMode = !prefsManager.desktopMode
            switchDesktop.isChecked = prefsManager.desktopMode
        }
        switchDesktop.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.desktopMode = isChecked
        }

        // Clear data
        findViewById<android.view.View>(R.id.rowClearData).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Data")
                .setMessage("This will clear cache, cookies and browsing history.")
                .setPositiveButton("Clear") { _, _ ->
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    prefsManager.clearHistory()
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateEngineDisplay() {
        val engine = prefsManager.searchEngine
        val (name, iconRes) = when {
            engine.contains("google")     -> Pair("Google",     R.drawable.ic_engine_google)
            engine.contains("duckduckgo") -> Pair("DuckDuckGo", R.drawable.ic_engine_ddg)
            engine.contains("bing")       -> Pair("Bing",       R.drawable.ic_engine_bing)
            engine.contains("brave")      -> Pair("Brave",      R.drawable.ic_engine_brave)
            else                          -> Pair("Google",     R.drawable.ic_engine_google)
        }
        findViewById<TextView>(R.id.settingsEngineName).text = name
        findViewById<ImageView>(R.id.settingsEngineIcon).setImageResource(iconRes)
    }

    private fun showEngineChooser() {
        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
        val urls = arrayOf(
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=",
            "https://search.brave.com/search?q="
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Search Engine")
            .setItems(engines) { _, which ->
                prefsManager.searchEngine = urls[which]
                updateEngineDisplay()
                // Notify MainActivity to update its icon
                setResult(RESULT_OK)
                Toast.makeText(this, "${engines[which]} selected", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
