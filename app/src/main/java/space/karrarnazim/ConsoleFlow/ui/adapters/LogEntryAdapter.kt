package space.karrarnazim.ConsoleFlow.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import space.karrarnazim.ConsoleFlow.R
import space.karrarnazim.ConsoleFlow.logging.LogEntry

class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.Holder>() {

    private var items: List<LogEntry> = emptyList()

    fun submit(newItems: List<LogEntry>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].timestamp == newItems[newPos].timestamp &&
                items[oldPos].message == newItems[newPos].message
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newItems[newPos]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    class Holder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val levelBar: android.view.View = itemView.findViewById(R.id.logLevelBar)
        private val header: TextView = itemView.findViewById(R.id.logHeaderLine)
        private val message: TextView = itemView.findViewById(R.id.logMessageLine)

        fun bind(entry: LogEntry) {
            header.text = "${entry.timestamp}  ${entry.level}/${entry.tag}"
            message.text = entry.message
            levelBar.setBackgroundColor(colorFor(entry.level))
        }

        private fun colorFor(level: String): Int = when (level) {
            "E", "A" -> Color.parseColor("#E5484D")
            "W"      -> Color.parseColor("#F5A623")
            "I"      -> Color.parseColor("#3B82F6")
            "D"      -> Color.parseColor("#8B8B8B")
            else     -> Color.parseColor("#555555")
        }
    }
}
