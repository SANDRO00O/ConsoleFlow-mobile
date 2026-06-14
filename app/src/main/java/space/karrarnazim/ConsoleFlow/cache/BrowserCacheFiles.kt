package space.karrarnazim.ConsoleFlow

import java.io.File

object BrowserCacheFiles {
    fun tabThumbnailFile(cacheDir: File, tabId: Int): File =
        File(cacheDir, "thumb_$tabId.webp")

    fun homePreviewFile(cacheDir: File, width: Int, height: Int): File =
        File(cacheDir, "home_preview_${width}x${height}.webp")

    fun homePreviewSigFile(cacheDir: File, width: Int, height: Int): File =
        File(cacheDir, "home_preview_${width}x${height}.sig")

    fun hasCachedTabThumbnail(cacheDir: File, tabId: Int): Boolean =
        tabThumbnailFile(cacheDir, tabId).exists()

    fun homePreviewCacheKey(width: Int, height: Int): String =
        "${width}x${height}"
}
