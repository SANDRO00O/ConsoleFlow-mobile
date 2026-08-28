package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import java.io.Serializable

data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var hasThumbnail: Boolean = false,
    var thumbnailUrl: String? = null
) : Serializable {
    @Transient var ramThumbnail: Bitmap? = null
    @Transient var faviconBitmap: Bitmap? = null
}