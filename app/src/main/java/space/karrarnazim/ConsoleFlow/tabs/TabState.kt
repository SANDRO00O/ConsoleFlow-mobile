package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import java.io.Serializable

data class TabState(
    val id: Int,
    var title: String = "New Tab",
    var url: String = "",
    var hasThumbnail: Boolean = false,
    var thumbnailUrl: String? = null,
    // ⚠️ يُغلق فجوة "حفظ حالة التبويب" المذكورة سابقاً. يُحدَّث تلقائياً من
    // GeckoSession.ProgressDelegate.onSessionStateChange (دفع، لا استطلاع
    // يدوي) ويحمل تاريخ التصفح/موضع التمرير/بيانات النماذج لهذا التبويب —
    // انظر GeckoTabSession.sessionStateJson و MainActivity.savePersistentTabs.
    var sessionStateJson: String? = null
) : Serializable {
    @Transient var ramThumbnail: Bitmap? = null
    @Transient var faviconBitmap: Bitmap? = null
}