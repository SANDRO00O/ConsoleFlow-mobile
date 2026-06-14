package space.karrarnazim.ConsoleFlow

class BookmarkRepository(private val prefsManager: PrefsManager) {
    fun toggleBookmark(title: String, url: String): Boolean =
        prefsManager.toggleBookmark(title, url)

    fun isBookmarked(url: String): Boolean = prefsManager.isBookmarked(url)

    fun getBookmarks(): List<Pair<String, String>> = prefsManager.getBookmarks()
}
