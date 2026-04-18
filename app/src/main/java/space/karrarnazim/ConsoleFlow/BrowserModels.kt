package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import java.io.Serializable

const val HOME_URL = "about:blank"
const val PREFS_NAME = "ConsoleFlowPrefs"

const val KEY_DESKTOP_MODE = "desktop_mode"
const val KEY_SEARCH_ENGINE = "search_engine"
const val KEY_CUSTOM_JS = "custom_js"
const val KEY_CONSOLE_ENABLED = "console_enabled"
const val KEY_ALLOW_JAVASCRIPT = "allow_javascript"
const val KEY_TRACKING_PROTECTION = "tracking_protection"
const val KEY_HTTPS_ONLY = "https_only_mode"
const val KEY_REMOTE_DEBUGGING = "remote_debugging"
const val KEY_WEB_EXTENSIONS = "web_extensions"
const val KEY_BOOKMARKS = "bookmarks"
const val KEY_HISTORY = "history"


data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = HOME_URL,
    var hasThumbnail: Boolean = false,
) : Serializable {
    @Transient var ramThumbnail: Bitmap? = null
    @Transient var faviconBitmap: Bitmap? = null
}


data class TabGroup(
    val id: Int,
    var name: String,
    val tabs: MutableList<TabState> = mutableListOf(),
) : Serializable


data class BrowserFeatureFlags(
    var allowJavascript: Boolean = true,
    var useTrackingProtection: Boolean = true,
    var httpsOnlyMode: Boolean = true,
    var remoteDebuggingEnabled: Boolean = false,
    var webExtensionsEnabled: Boolean = true,
)
