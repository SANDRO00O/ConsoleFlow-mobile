
package space.karrarnazim.ConsoleFlow

import android.content.Context

class BrowserSessionManager {
    val tabGroups: MutableList<TabGroup> = mutableListOf()
    var activeGroupId: Int = 0
    var activeTabId: Int = 0
    var nextTabId: Int = 1
    var nextGroupId: Int = 1

    val currentGroup: TabGroup?
        get() = tabGroups.find { it.id == activeGroupId }

    val currentTab: TabState?
        get() = currentGroup?.tabs?.find { it.id == activeTabId }

    fun saveToStorage(context: Context) {
        BrowserPersistence.save(
            context = context,
            snapshot = tabGroups.map { it.copy(tabs = it.tabs.toMutableList()) },
            activeGroupId = activeGroupId,
            activeTabId = activeTabId,
            nextTabId = nextTabId,
            nextGroupId = nextGroupId
        )
    }

    fun restoreFromStorage(context: Context): Boolean {
        val restored = BrowserPersistence.restore(context) ?: return false
        tabGroups.clear()
        tabGroups.addAll(restored.groups)
        activeGroupId = restored.activeGroupId
        activeTabId = restored.activeTabId
        nextTabId = restored.nextTabId
        nextGroupId = restored.nextGroupId
        return tabGroups.isNotEmpty()
    }

    fun createNewGroup(name: String): TabGroup {
        val group = TabGroup(nextGroupId++, name)
        tabGroups.add(group)
        activeGroupId = group.id
        return group
    }

    fun createNewTab(url: String, title: String = "New Tab"): TabState {
        val tab = TabState(id = nextTabId++, title = title, url = url)
        currentGroup?.tabs?.add(tab)
        activeTabId = tab.id
        return tab
    }

    fun sanitizeActiveTabSelection(preferredTabId: Int? = null) {
        val group = currentGroup ?: return
        val preferred = preferredTabId?.let { wanted -> group.tabs.firstOrNull { it.id == wanted } }
        val validActive = group.tabs.firstOrNull { it.id == activeTabId }
        val fallback = preferred ?: validActive ?: group.tabs.firstOrNull()
        activeTabId = fallback?.id ?: 0
    }
}
