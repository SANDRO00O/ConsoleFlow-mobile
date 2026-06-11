package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
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
import android.os.Bundle
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager
    private lateinit var settingsJoystickCursor: View
    private lateinit var settingsJoystickClickFlash: View
    private var settingsCursorX = 0f
    private var settingsCursorY = 0f
    private var settingsCursorInitialized = false
    private var settingsAnalogX = 0f
    private var settingsAnalogY = 0f
    private var settingsAnalogLoopRunning = false
    private var settingsLastFrameMs = 0L

    private companion object {
        private const val SETTINGS_CURSOR_DEADZONE = 0.18f
        private const val SETTINGS_CURSOR_SPEED_PX_PER_SEC = 1100f
    }


    private data class EngineOption(val name: String, val url: String, val iconRes: Int?)

    private val engineOptions = listOf(
        EngineOption("Google",     "https://www.google.com/search?q=",       R.drawable.ic_engine_google),
        EngineOption("DuckDuckGo", "https://duckduckgo.com/?q=",             R.drawable.ic_engine_duckduckgo),
        EngineOption("Bing",       "https://www.bing.com/search?q=",         R.drawable.ic_engine_bing),
        EngineOption("Brave",      "https://search.brave.com/search?q=",     R.drawable.ic_engine_brave),
        EngineOption("Custom",     "",                                        null)
    )

    // ─── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefsManager = PrefsManager(this)
        settingsJoystickCursor = findViewById(R.id.settingsJoystickCursor)
        settingsJoystickClickFlash = findViewById(R.id.settingsJoystickClickFlash)
        settingsJoystickCursor.bringToFront()
        settingsJoystickClickFlash.bringToFront()
        settingsJoystickCursor.post {
            val root = findViewById<ViewGroup>(android.R.id.content)
            if (root.width > 0 && root.height > 0) {
                settingsCursorX = root.width / 2f
                settingsCursorY = root.height / 2f
                settingsCursorInitialized = true
                settingsJoystickCursor.translationX = settingsCursorX - settingsJoystickCursor.width / 2f
                settingsJoystickCursor.translationY = settingsCursorY - settingsJoystickCursor.height / 2f
                settingsJoystickCursor.visibility = View.VISIBLE
            }
        }

        // back
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // search engine
        updateSearchEngineValue()
        findViewById<View>(R.id.settingSearchEngine).setOnClickListener { showSearchEnginePicker() }

        // desktop mode
        val switchDesktop = findViewById<SwitchCompat>(R.id.switchDesktopMode)
        switchDesktop.isChecked = prefsManager.desktopMode
        switchDesktop.setOnCheckedChangeListener { _, checked -> prefsManager.desktopMode = checked }
        findViewById<View>(R.id.settingDesktopMode).setOnClickListener {
            switchDesktop.isChecked = !switchDesktop.isChecked
        }

        // custom JS
        findViewById<View>(R.id.settingCustomJs).setOnClickListener { showCustomJsDialog() }

        // clear data
        findViewById<View>(R.id.settingClearData).setOnClickListener { showClearDataDialog() }

        // about — opens AboutActivity
        findViewById<View>(R.id.settingAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val fromJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!fromJoystick) return super.dispatchGenericMotionEvent(event)

        settingsAnalogX = centeredAxis(event, MotionEvent.AXIS_X)
        settingsAnalogY = centeredAxis(event, MotionEvent.AXIS_Y)

        if (abs(settingsAnalogX) > SETTINGS_CURSOR_DEADZONE || abs(settingsAnalogY) > SETTINGS_CURSOR_DEADZONE) {
            startSettingsCursorLoop()
            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
             event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
             event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            performSettingsCursorClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startSettingsCursorLoop() {
        if (settingsAnalogLoopRunning) return
        settingsAnalogLoopRunning = true
        settingsLastFrameMs = 0L
        settingsJoystickCursor.postOnAnimation(settingsCursorRunnable)
    }

    private val settingsCursorRunnable = object : Runnable {
        override fun run() {
            val root = findViewById<ViewGroup>(android.R.id.content)
            if (settingsJoystickCursor.width <= 0 || settingsJoystickCursor.height <= 0 || root.width <= 0 || root.height <= 0) {
                settingsJoystickCursor.postOnAnimation(this)
                return
            }

            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (settingsLastFrameMs == 0L) 1f / 60f else ((now - settingsLastFrameMs).coerceAtLeast(1L) / 1000f)
            settingsLastFrameMs = now

            if (abs(settingsAnalogX) > SETTINGS_CURSOR_DEADZONE || abs(settingsAnalogY) > SETTINGS_CURSOR_DEADZONE) {
                moveSettingsCursor(root, settingsAnalogX * SETTINGS_CURSOR_SPEED_PX_PER_SEC * dt, settingsAnalogY * SETTINGS_CURSOR_SPEED_PX_PER_SEC * dt)
                settingsJoystickCursor.postOnAnimation(this)
            } else {
                settingsAnalogLoopRunning = false
                settingsLastFrameMs = 0L
            }
        }
    }

    private fun moveSettingsCursor(root: ViewGroup, dx: Float, dy: Float) {
        if (!settingsCursorInitialized) {
            settingsCursorX = root.width / 2f
            settingsCursorY = root.height / 2f
            settingsCursorInitialized = true
        }

        val halfW = settingsJoystickCursor.width / 2f
        val halfH = settingsJoystickCursor.height / 2f
        val minX = halfW
        val minY = halfH
        val maxX = (root.width - halfW).coerceAtLeast(halfW)
        val maxY = (root.height - halfH).coerceAtLeast(halfH)
        settingsCursorX = (settingsCursorX + dx).coerceIn(minX, maxX)
        settingsCursorY = (settingsCursorY + dy).coerceIn(minY, maxY)

        settingsJoystickCursor.visibility = View.VISIBLE
        settingsJoystickCursor.translationX = settingsCursorX - halfW
        settingsJoystickCursor.translationY = settingsCursorY - halfH
        settingsJoystickCursor.bringToFront()
    }

    private fun showSettingsClickFlash() {
        if (settingsJoystickClickFlash.width <= 0 || settingsJoystickClickFlash.height <= 0) {
            settingsJoystickClickFlash.post { showSettingsClickFlash() }
            return
        }

        settingsJoystickClickFlash.animate().cancel()
        settingsJoystickClickFlash.clearAnimation()
        settingsJoystickClickFlash.bringToFront()
        settingsJoystickCursor.bringToFront()
        settingsJoystickClickFlash.visibility = View.VISIBLE
        settingsJoystickClickFlash.translationX = settingsJoystickCursor.translationX
        settingsJoystickClickFlash.translationY = settingsJoystickCursor.translationY
        settingsJoystickClickFlash.scaleX = 0.7f
        settingsJoystickClickFlash.scaleY = 0.7f
        settingsJoystickClickFlash.alpha = 1f
        settingsJoystickClickFlash.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .alpha(0f)
            .setDuration(110)
            .withEndAction {
                settingsJoystickClickFlash.visibility = View.GONE
                settingsJoystickClickFlash.scaleX = 1f
                settingsJoystickClickFlash.scaleY = 1f
                settingsJoystickClickFlash.alpha = 1f
            }
            .start()
    }

    private fun performSettingsCursorClick() {
        showSettingsClickFlash()

        val root = findViewById<ViewGroup>(android.R.id.content)
        val x = settingsCursorX.toInt()
        val y = settingsCursorY.toInt()
        val target = findClickableNativeViewUnder(root, x, y)
        if (target != null) {
            target.requestFocus()
            target.performClick()
        }
    }

    private fun findClickableNativeViewUnder(root: ViewGroup, x: Int, y: Int): View? {
        fun hitTest(view: View, pointX: Int, pointY: Int): Boolean {
            if (view.visibility != View.VISIBLE) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val left = location[0] - rootLocation[0]
            val top = location[1] - rootLocation[1]
            val right = left + view.width
            val bottom = top + view.height
            return pointX >= left && pointX < right && pointY >= top && pointY < bottom
        }

        fun recurse(view: View): View? {
            if (!hitTest(view, x, y)) return null
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) {
                    recurse(view.getChildAt(i))?.let { return it }
                }
            }
            if (view.isClickable || view.hasOnClickListeners()) return view
            return null
        }

        for (i in root.childCount - 1 downTo 0) {
            recurse(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun centeredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val flat = range.flat
        val value = event.getAxisValue(axis)
        return if (abs(value) > flat) value else 0f
    }


    override fun onResume() {
        super.onResume()
        refreshUpdateBadge()
        // Silently refresh cache in background; update badge on result
        UpdateManager.check(this) { _, _ -> refreshUpdateBadge() }
    }

    // ─── update badge ─────────────────────────────────────────────────────────

    private fun refreshUpdateBadge() {
        val dot = findViewById<View>(R.id.updateDot) ?: return
        dot.visibility = if (UpdateManager.isUpdateAvailable(this)) View.VISIBLE else View.GONE
    }

    // ─── dialogs ─────────────────────────────────────────────────────────────

    private fun showCustomJsDialog() {
        val input = EditText(this).apply {
            setText(prefsManager.customJs)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF111111.toInt())
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

    private fun showClearDataDialog() {
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
                    if (file.name.startsWith("thumb_") && file.name.endsWith(".webp"))       file.delete()
                    if (file.name.startsWith("home_preview_") &&
                        (file.name.endsWith(".webp") || file.name.endsWith(".sig")))         file.delete()
                }
                Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── search engine picker ─────────────────────────────────────────────────

    private fun updateSearchEngineValue() {
        val label = if (prefsManager.searchEngineIsCustom) "Custom"
                    else searchEngineDisplayName(prefsManager.searchEngine)
        findViewById<TextView>(R.id.settingSearchEngineValue).text = label
    }

    private fun showSearchEnginePicker() {
        val listView = ListView(this).apply {
            divider = null
            setPadding(24, 20, 24, 20)
            clipToPadding = false
        }

        val adapter = object : BaseAdapter() {
            override fun getCount()                          = engineOptions.size
            override fun getItem(position: Int)              = engineOptions[position]
            override fun getItemId(position: Int)            = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val ctx = parent.context
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.CENTER_VERTICAL
                    setPadding(24, 22, 24, 22)
                    minimumHeight = (56 * resources.displayMetrics.density).toInt()

                    addView(ImageView(ctx).apply {
                        id = 1
                        val size = (24 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size)
                    })
                    addView(TextView(ctx).apply {
                        id = 2
                        setTextColor(0xFFFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding(16, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }

                val icon  = row.findViewById<ImageView>(1)
                val title = row.findViewById<TextView>(2)
                val item  = getItem(position)

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
                prefsManager.searchEngine         = selected.url
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
            setBackgroundColor(0xFF111111.toInt())
            setPadding(32, 24, 32, 24)
            hint      = "https://example.com/search?q=%s"
            setHintTextColor(0xFF444444.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(TextView(context).apply {
                text = "Use %s where the search term should go."
                setTextColor(0xFF888888.toInt())
                textSize = 13f
            })
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
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
                prefsManager.searchEngine         = value
                prefsManager.searchEngineIsCustom = true
                updateSearchEngineValue()
                Toast.makeText(this, "Custom search engine saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── transition ──────────────────────────────────────────────────────────

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
