package space.karrarnazim.ConsoleFlow

import java.io.File

object ThumbnailManager {
    fun tabThumbnailFile(cacheDir: File, tabId: Int): File = BrowserCacheFiles.tabThumbnailFile(cacheDir, tabId)
    fun homePreviewFile(cacheDir: File, width: Int, height: Int): File = BrowserCacheFiles.homePreviewFile(cacheDir, width, height)
    fun homePreviewSigFile(cacheDir: File, width: Int, height: Int): File = BrowserCacheFiles.homePreviewSigFile(cacheDir, width, height)
    fun hasCachedTabThumbnail(cacheDir: File, tabId: Int): Boolean = BrowserCacheFiles.hasCachedTabThumbnail(cacheDir, tabId)
}
