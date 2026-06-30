package space.karrarnazim.ConsoleFlow


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService

class TabAdapter(
    private val context: Context,
    // FIX #5 — نستقبل الـ executor من الـ Activity بدلاً من إنشاء thread pool خاص
    private val ioExecutor: ExecutorService,
    private val onTabClick: (TabState) -> Unit,
    private val onTabClose: (TabState) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private var tabs: MutableList<TabState> = mutableListOf()
    private var activeId: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    // FIX #6 — DiffUtil بدلاً من notifyDataSetChanged الكارثية
    fun submitUpdate(newTabs: List<TabState>, newActiveId: Int) {
        val oldTabs     = tabs
        val oldActiveId = activeId
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldTabs.size
            override fun getNewListSize() = newTabs.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldTabs[oldPos].id == newTabs[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldTabs[oldPos]; val new = newTabs[newPos]
                // يعيد رسم العنصر فقط إذا تغيّر المحتوى أو حالة النشاط
                return old.title == new.title &&
                       old.url == new.url &&
                       old.hasThumbnail == new.hasThumbnail &&
                       (old.id == oldActiveId) == (new.id == newActiveId)
            }
        })
        tabs     = newTabs.toMutableList()
        activeId = newActiveId
        diff.dispatchUpdatesTo(this)  // يُطبّق فقط التغييرات الضرورية
    }

    fun updateFavicon(tabId: Int, favicon: Bitmap) {
        val position = tabs.indexOfFirst { it.id == tabId }
        if (position >= 0) {
            tabs[position].faviconBitmap = favicon
            mainHandler.post { notifyItemChanged(position) }
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:     TextView  = v.findViewById(R.id.tabTitle)
        val favicon:   ImageView = v.findViewById(R.id.tabFavicon)
        val thumbnail: ImageView = v.findViewById(R.id.tabThumbnail)
        val close:     ImageView = v.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val tab      = tabs[position]
        val isActive = tab.id == activeId

        h.title.text = tab.title.ifEmpty { "New Tab" }

        if (tab.faviconBitmap != null) {
            h.favicon.setImageBitmap(tab.faviconBitmap)
            h.favicon.imageTintList = null
        } else {
            h.favicon.setImageResource(R.drawable.ic_favicon_fallback)
            h.favicon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt()
            )
        }

        h.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

        if (tab.ramThumbnail != null) {
            h.thumbnail.setImageBitmap(tab.ramThumbnail)
        } else if (isHomeUrl(tab.url)) {
            val homePreview = (context as? MainActivity)?.getHomePreviewBitmap()
                ?: generateHomePreviewBitmap()
            h.thumbnail.setImageBitmap(homePreview)
        } else {
            val file = File(context.cacheDir, "thumb_${tab.id}.webp")
            if (tab.hasThumbnail || file.exists()) {
                // FIX #8 (adapter) — استخدام الـ executor الممرر من الـ Activity
                ioExecutor.execute {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    mainHandler.post {
                        // FIX #8 — استخدام bindingAdapterPosition بدلاً من adapterPosition المُهمل
                        val currentPos = h.bindingAdapterPosition
                        if (currentPos != RecyclerView.NO_POSITION && currentPos == position) {
                            if (bitmap != null) h.thumbnail.setImageBitmap(bitmap)
                            else h.thumbnail.setImageResource(android.R.color.transparent)
                        }
                    }
                }
            } else {
                h.thumbnail.setImageResource(android.R.color.transparent)
            }
        }

        h.itemView.background = context.getDrawable(
            if (isActive) R.drawable.tab_card_active else R.drawable.tab_card_bg
        )
        h.title.setTextColor(if (isActive) 0xFF003366.toInt() else 0xFFFFFFFF.toInt())
        h.close.setColorFilter(if (isActive) 0xFF003366.toInt() else 0xFFAAAAAA.toInt())

        h.itemView.setOnClickListener { onTabClick(tab) }
        h.close.setOnClickListener   { onTabClose(tab) }
    }

    override fun getItemCount(): Int = tabs.size
}
