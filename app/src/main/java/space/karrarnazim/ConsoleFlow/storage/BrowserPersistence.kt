package space.karrarnazim.ConsoleFlow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RestoredBrowserTabs(
    val groups: MutableList<TabGroup>,
    val activeGroupId: Int,
    val activeTabId: Int,
    val nextTabId: Int,
    val nextGroupId: Int
)

object BrowserPersistence {
    fun save(
        context: Context,
        snapshot: List<TabGroup>,
        activeGroupId: Int,
        activeTabId: Int,
        nextTabId: Int,
        nextGroupId: Int
    ) {
        try {
            val groupsArray = JSONArray()
            for (group in snapshot) {
                val groupObj = JSONObject()
                groupObj.put("id", group.id)
                groupObj.put("name", group.name)

                val tabsArray = JSONArray()
                for (tab in group.tabs) {
                    val tabObj = JSONObject()
                    tabObj.put("id", tab.id)
                    tabObj.put("title", tab.title)
                    tabObj.put("url", tab.url)
                    tabObj.put("hasThumb", tab.hasThumbnail)
                    tabObj.put("thumbUrl", tab.thumbnailUrl)
                    // ⚠️ إصلاح خطأ حقيقي بمراجعة عميقة سابعة: هذا الحقل كان
                    // غائباً تماماً. BrowserPersistence هو مسار حفظ منفصل
                    // تماماً عن onSaveInstanceState/putSerializable (الذي
                    // يغطي فقط تدوير الشاشة) — هذا المسار هنا هو المسؤول عن
                    // نجاة حالة التبويبات عبر إغلاق التطبيق فعلياً وإعادة
                    // فتحه، وهي الحالة الأهم عملياً. بدون هذا السطر، كل ادعاء
                    // "حللنا فجوة استمرارية حالة التبويب" في مرحلة سابقة كان
                    // غير مكتمل — يعمل فقط عبر تدوير الشاشة، ليس عبر إغلاق
                    // التطبيق.
                    tabObj.put("sessionState", tab.sessionStateJson)
                    tabsArray.put(tabObj)
                }
                groupObj.put("tabs", tabsArray)
                groupsArray.put(groupObj)
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("SAVED_GROUPS", groupsArray.toString())
                .putInt("ACTIVE_GROUP", activeGroupId)
                .putInt("ACTIVE_TAB", activeTabId)
                .putInt("NEXT_TAB_ID", nextTabId)
                .putInt("NEXT_GROUP_ID", nextGroupId)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun restore(context: Context): RestoredBrowserTabs? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString("SAVED_GROUPS", null) ?: return null

        return try {
            val groups = mutableListOf<TabGroup>()
            val groupsArray = JSONArray(savedJson)
            if (groupsArray.length() == 0) return null

            for (i in 0 until groupsArray.length()) {
                val gObj = groupsArray.getJSONObject(i)
                val group = TabGroup(gObj.getInt("id"), gObj.getString("name"))

                val tabsArray = gObj.getJSONArray("tabs")
                for (j in 0 until tabsArray.length()) {
                    val tObj = tabsArray.getJSONObject(j)
                    val rawUrl = tObj.getString("url")
                    val tabId = tObj.getInt("id")
                    group.tabs.add(
                        TabState(
                            id = tabId,
                            title = tObj.getString("title"),
                            url = if (isHomeUrl(rawUrl)) HOME_URL_CONST else rawUrl,
                            hasThumbnail = tObj.optBoolean("hasThumb", false) ||
                                ThumbnailManager.hasCachedTabThumbnail(context.cacheDir, tabId),
                            thumbnailUrl = tObj.optString("thumbUrl", rawUrl),
                            sessionStateJson = tObj.optString("sessionState", null)
                        )
                    )
                }
                groups.add(group)
            }

            val activeGroupId = prefs.getInt("ACTIVE_GROUP", groups.first().id)
            val activeGroupTabs = groups.firstOrNull { it.id == activeGroupId }?.tabs
            RestoredBrowserTabs(
                groups = groups,
                activeGroupId = activeGroupId,
                activeTabId = prefs.getInt(
                    "ACTIVE_TAB",
                    activeGroupTabs?.firstOrNull()?.id ?: groups.firstOrNull()?.tabs?.firstOrNull()?.id ?: 0
                ),
                nextTabId = prefs.getInt("NEXT_TAB_ID", 100),
                nextGroupId = prefs.getInt("NEXT_GROUP_ID", 100)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
