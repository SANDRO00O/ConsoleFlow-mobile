package space.karrarnazim.ConsoleFlow

class TabManager(private val sessionManager: BrowserSessionManager) {
    fun createGroup(name: String): TabGroup = sessionManager.createNewGroup(name)
    fun createTab(url: String, title: String = "New Tab"): TabState = sessionManager.createNewTab(url, title)
    fun currentGroup(): TabGroup? = sessionManager.currentGroup
    fun currentTab(): TabState? = sessionManager.currentTab
    fun activate(tabId: Int) {
        sessionManager.activeTabId = tabId
        sessionManager.sanitizeActiveTabSelection(tabId)
    }
}
