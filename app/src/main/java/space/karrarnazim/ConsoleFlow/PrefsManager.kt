package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, "https://www.google.com/search?q=")!!
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()

    var customJs: String
        get() = prefs.getString(KEY_CUSTOM_JS, "")!!
        set(value) = prefs.edit().putString(KEY_CUSTOM_JS, value).apply()

    var consoleEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONSOLE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CONSOLE_ENABLED, value).apply()

    var allowJavaScript: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_JAVASCRIPT, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_JAVASCRIPT, value).apply()

    var trackingProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_PROTECTION, value).apply()

    var httpsOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS_ONLY, value).apply()

    var remoteDebuggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_DEBUGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_DEBUGGING, value).apply()

    var webExtensionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_EXTENSIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_EXTENSIONS, value).apply()

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    fun addHistory(title: String, url: String) {
        if (url == HOME_URL || url.startsWith("error://")) return
        val historyArray = getList(KEY_HISTORY)
        val newItem = JSONObject().apply {
            put("title", title)
            put("url", url)
        }
        for (i in 0 until historyArray.length()) {
            val item = historyArray.optJSONObject(i)
            if (item?.optString("url") == url) {
                historyArray.remove(i)
                break
            }
        }
        historyArray.put(newItem)
        while (historyArray.length() > 100) historyArray.remove(0)
        prefs.edit().putString(KEY_HISTORY, historyArray.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    fun toggleBookmark(title: String, url: String): Boolean {
        val bookmarks = getList(KEY_BOOKMARKS)
        var removed = false
        for (i in 0 until bookmarks.length()) {
            val item = bookmarks.optJSONObject(i)
            if (item?.optString("url") == url) {
                bookmarks.remove(i)
                removed = true
                break
            }
        }
        if (!removed) {
            bookmarks.put(JSONObject().apply {
                put("title", title)
                put("url", url)
            })
        }
        prefs.edit().putString(KEY_BOOKMARKS, bookmarks.toString()).apply()
        return !removed
    }

    fun isBookmarked(url: String): Boolean {
        val bookmarks = getList(KEY_BOOKMARKS)
        for (i in 0 until bookmarks.length()) {
            if (bookmarks.optJSONObject(i)?.optString("url") == url) return true
        }
        return false
    }

    fun getBookmarks(): List<Pair<String, String>> {
        val bookmarks = getList(KEY_BOOKMARKS)
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until bookmarks.length()) {
            val obj = bookmarks.optJSONObject(i) ?: continue
            list.add(obj.optString("title") to obj.optString("url"))
        }
        return list
    }

    fun getHistory(): List<Pair<String, String>> {
        val history = getList(KEY_HISTORY)
        val list = mutableListOf<Pair<String, String>>()
        for (i in history.length() - 1 downTo 0) {
            val obj = history.optJSONObject(i) ?: continue
            list.add(obj.optString("title") to obj.optString("url"))
        }
        return list
    }

    private fun getList(key: String): JSONArray {
        val jsonStr = prefs.getString(key, "[]")
        return try {
            JSONArray(jsonStr)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
