package space.karrarnazim.ConsoleFlow

class HistoryRepository(private val prefsManager: PrefsManager) {
    fun addHistory(title: String, url: String) = prefsManager.addHistory(title, url)

    fun clearHistory() = prefsManager.clearHistory()

    fun getHistory(): List<Pair<String, String>> = prefsManager.getHistory()
}
