package space.karrarnazim.ConsoleFlow

import android.app.Activity

class BrowserScreenController(
    private val activity: Activity,
    private val sessionManager: BrowserSessionManager,
    private val onUiChanged: () -> Unit = {}
) {
    fun createGroup(name: String): TabGroup {
        val group = sessionManager.createNewGroup(name)
        onUiChanged()
        return group
    }

    fun openTab(url: String, title: String = "New Tab"): TabState {
        val tab = sessionManager.createNewTab(url, title)
        onUiChanged()
        return tab
    }

    fun activateTab(tabId: Int) {
        sessionManager.activeTabId = tabId
        sessionManager.sanitizeActiveTabSelection(tabId)
        onUiChanged()
    }
}
