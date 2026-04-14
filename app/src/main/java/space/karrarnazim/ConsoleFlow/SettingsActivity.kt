package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefsManager = PrefsManager(this)

        // Back button
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        // Version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.settingVersion).text = "Version ${pInfo.versionName}"
        } catch (_: Exception) {}

        // Search engine
        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
        val urls = arrayOf(
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=",
            "https://search.brave.com/search?q="
        )
        val currentLabel = when {
            prefsManager.searchEngine.contains("google")     -> "Google"
            prefsManager.searchEngine.contains("duckduckgo") -> "DuckDuckGo"
            prefsManager.searchEngine.contains("bing")       -> "Bing"
            prefsManager.searchEngine.contains("brave")      -> "Brave"
            else -> "Google"
        }
        findViewById<TextView>(R.id.settingSearchEngineValue).text = currentLabel
        findViewById<android.view.View>(R.id.settingSearchEngine).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Search Engine")
                .setItems(engines) { _, which ->
                    prefsManager.searchEngine = urls[which]
                    findViewById<TextView>(R.id.settingSearchEngineValue).text = engines[which]
                    Toast.makeText(this, "${engines[which]} selected", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // Desktop mode
        val switchDesktop = findViewById<SwitchCompat>(R.id.switchDesktopMode)
        switchDesktop.isChecked = prefsManager.desktopMode
        switchDesktop.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.desktopMode = isChecked
        }
        findViewById<android.view.View>(R.id.settingDesktopMode).setOnClickListener {
            switchDesktop.isChecked = !switchDesktop.isChecked
        }

        // Custom JS
        findViewById<android.view.View>(R.id.settingCustomJs).setOnClickListener {
            val input = EditText(this).apply {
                setText(prefsManager.customJs)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF1A1A1A.toInt())
                setPadding(32, 24, 32, 24)
                hint = "// Your JavaScript here..."
                setHintTextColor(0xFF444444.toInt())
                isSingleLine = false
                minLines = 5
            }
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Custom JavaScript")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    prefsManager.customJs = input.text.toString()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Clear data
        findViewById<android.view.View>(R.id.settingClearData).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Browsing Data")
                .setMessage("This will delete cache, cookies, and history.")
                .setPositiveButton("Clear") { _, _ ->
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    prefsManager.clearHistory()
                    getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)
                        .edit().apply()
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
