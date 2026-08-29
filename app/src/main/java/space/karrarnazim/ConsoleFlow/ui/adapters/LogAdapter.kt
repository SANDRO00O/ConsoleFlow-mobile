package space.karrarnazim.ConsoleFlow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private var items: List<LogEntry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun submit(newItems: List<LogEntry>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos].timestamp == newItems[newPos].timestamp && old[oldPos].message == newItems[newPos].message
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == newItems[newPos]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val line: TextView = v.findViewById(R.id.logLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val entry = items[position]
        val time  = timeFormat.format(Date(entry.timestamp))
        h.line.text = buildString {
            append(time).append("  ").append(entry.level.label).append('/').append(entry.tag)
            append(": ").append(entry.message)
            if (entry.throwable != null) append('\n').append(entry.throwable)
        }
        h.line.setTextColor(android.graphics.Color.parseColor(entry.level.colorHex))
    }

    override fun getItemCount(): Int = items.size
}
