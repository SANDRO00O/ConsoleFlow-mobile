package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager

    private data class EngineOption(val name: String, val url: String, val iconRes: Int?)

    private val engineOptions = listOf(
        EngineOption("Google", "https://www.google.com/search?q=", R.drawable.ic_engine_google),
        EngineOption("DuckDuckGo", "https://duckduckgo.com/?q=", R.drawable.ic_engine_duckduckgo),
        EngineOption("Bing", "https://www.bing.com/search?q=", R.drawable.ic_engine_bing),
        EngineOption("Brave", "https://search.brave.com/search?q=", R.drawable.ic_engine_brave),
        EngineOption("Custom", "", null)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefsManager = PrefsManager(this)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.settingVersion).text = "Version ${pInfo.versionName}"
        } catch (_: Exception) {}

        updateSearchEngineValue()
        findViewById<android.view.View>(R.id.settingSearchEngine).setOnClickListener {
            showSearchEnginePicker()
        }

        val switchDesktop = findViewById<SwitchCompat>(R.id.switchDesktopMode)
        switchDesktop.isChecked = prefsManager.desktopMode
        switchDesktop.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.desktopMode = isChecked
        }
        findViewById<android.view.View>(R.id.settingDesktopMode).setOnClickListener {
            switchDesktop.isChecked = !switchDesktop.isChecked
        }

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

        findViewById<android.view.View>(R.id.settingClearData).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Clear Browsing Data")
                .setMessage("This will delete cache, cookies, and history.")
                .setPositiveButton("Clear") { _, _ ->
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    val cookies = android.webkit.CookieManager.getInstance()
                    cookies.removeAllCookies(null)
                    cookies.flush()
                    prefsManager.clearHistory()
                    getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("SAVED_GROUPS")
                        .remove("ACTIVE_GROUP")
                        .remove("ACTIVE_TAB")
                        .remove("NEXT_TAB_ID")
                        .remove("NEXT_GROUP_ID")
                        .apply()
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("thumb_") && file.name.endsWith(".webp")) file.delete()
                        if (file.name.startsWith("home_preview_") && (file.name.endsWith(".webp") || file.name.endsWith(".sig"))) file.delete()
                    }
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<android.view.View>(R.id.settingPortfolio).setOnClickListener {
            openUrl("https://karrarnazim.space")
        }

        findViewById<android.view.View>(R.id.settingAppWebsite).setOnClickListener {
            openUrl("https://consoleflow.karrarnazim.space")
        }

        findViewById<android.view.View>(R.id.settingPrivacyPolicy).setOnClickListener {
            openUrl("https://consoleflow.karrarnazim.space/privacy")
        }

        findViewById<android.view.View>(R.id.settingOpenSource).setOnClickListener {
            openUrl("https://github.com/SANDRO00O/ConsoleFlow-mobile")
        }
    }

    private fun updateSearchEngineValue() {
        findViewById<TextView>(R.id.settingSearchEngineValue).text = if (prefsManager.searchEngineIsCustom) "Custom" else searchEngineDisplayName(prefsManager.searchEngine)
    }

    private fun showSearchEnginePicker() {
        val listView = ListView(this).apply {
            divider = null
            setPadding(24, 20, 24, 20)
            clipToPadding = false
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = engineOptions.size
            override fun getItem(position: Int) = engineOptions[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val context = parent.context
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 22, 24, 22)
                    minimumHeight = (56 * resources.displayMetrics.density).toInt()

                    addView(ImageView(context).apply {
                        id = 1
                        val size = (24 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size)
                    })
                    addView(TextView(context).apply {
                        id = 2
                        setTextColor(0xFFFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding(16, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }

                val icon = row.findViewById<ImageView>(1)
                val title = row.findViewById<TextView>(2)
                val item = getItem(position)

                title.text = item.name
                if (item.iconRes != null) {
                    icon.visibility = View.VISIBLE
                    icon.setImageResource(item.iconRes)
                    icon.imageTintList = null
                } else {
                    icon.visibility = View.GONE
                }

                return row
            }
        }

        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Search Engine")
            .setView(listView)
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val selected = engineOptions[position]
            if (selected.name == "Custom") {
                showCustomSearchEngineDialog()
            } else {
                prefsManager.searchEngine = selected.url
                prefsManager.searchEngineIsCustom = false
                updateSearchEngineValue()
                Toast.makeText(this, "${selected.name} selected", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showCustomSearchEngineDialog() {
        val input = EditText(this).apply {
            val current = prefsManager.searchEngine
            setText(current.takeIf { prefsManager.searchEngineIsCustom } ?: "https://example.com/search?q=%s")
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(32, 24, 32, 24)
            hint = "https://example.com/search?q=%s"
            setHintTextColor(0xFF444444.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(TextView(context).apply {
                text = "Use %s where the search term should go."
                setTextColor(0xFF9A9A9A.toInt())
                textSize = 13f
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            })
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Custom Search Engine")
            .setView(wrapper)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    Toast.makeText(this, "Enter a valid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefsManager.searchEngine = value
                prefsManager.searchEngineIsCustom = true
                updateSearchEngineValue()
                Toast.makeText(this, "Custom search engine saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
