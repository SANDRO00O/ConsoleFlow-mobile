package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop_mode", false)
        set(value) = prefs.edit().putBoolean("desktop_mode", value).apply()

    var searchEngine: String
        get() = prefs.getString("search_engine", "https://www.google.com/search?q=")!!
        set(value) = prefs.edit().putString("search_engine", value).apply()

    var customJs: String
        get() = prefs.getString("custom_js", "")!!
        set(value) = prefs.edit().putString("custom_js", value).apply()

    var consoleEnabled: Boolean
        get() = prefs.getBoolean("console_enabled", true)
        set(value) = prefs.edit().putBoolean("console_enabled", value).apply()

    // ✅ الدالة المضافة حديثًا لحل خطأ "Unresolved reference: getBoolean"
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun addHistory(title: String, url: String) {
        if (url == "about:blank" || url == "error://page" || url == "file:///android_asset/home.html" || url.startsWith("file:///android_asset/error.html")) return
        val historyArray = getList("history")
        val newItem = "{\"title\":\"$title\", \"url\":\"$url\"}"
        for (i in 0 until historyArray.length()) {
            if (historyArray.getString(i).contains(url)) {
                historyArray.remove(i)
                break
            }
        }
        historyArray.put(newItem)
        if (historyArray.length() > 100) historyArray.remove(0)
        prefs.edit().putString("history", historyArray.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove("history").apply()

    fun toggleBookmark(title: String, url: String): Boolean {
        val bookmarks = getList("bookmarks")
        var exists = false
        var indexToRemove = -1

        for (i in 0 until bookmarks.length()) {
            if (bookmarks.getString(i).contains(url)) {
                exists = true
                indexToRemove = i
                break
            }
        }

        if (exists) {
            bookmarks.remove(indexToRemove)
        } else {
            bookmarks.put("{\"title\":\"$title\", \"url\":\"$url\"}")
        }
        prefs.edit().putString("bookmarks", bookmarks.toString()).apply()
        return !exists
    }

    fun isBookmarked(url: String): Boolean {
        val bookmarks = getList("bookmarks")
        for (i in 0 until bookmarks.length()) {
            if (bookmarks.getString(i).contains(url)) return true
        }
        return false
    }

    fun getBookmarks(): List<Pair<String, String>> {
        val bookmarks = getList("bookmarks")
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until bookmarks.length()) {
            try {
                val obj = JSONObject(bookmarks.getString(i))
                list.add(Pair(obj.getString("title"), obj.getString("url")))
            } catch (e: Exception) { }
        }
        return list
    }

    fun getHistory(): List<Pair<String, String>> {
        val history = getList("history")
        val list = mutableListOf<Pair<String, String>>()
        for (i in history.length() - 1 downTo 0) {
            try {
                val obj = JSONObject(history.getString(i))
                list.add(Pair(obj.getString("title"), obj.getString("url")))
            } catch (e: Exception) { }
        }
        return list
    }

    private fun getList(key: String): JSONArray {
        val jsonStr = prefs.getString(key, "[]")
        return try { JSONArray(jsonStr) } catch (e: Exception) { JSONArray() }
    }
}