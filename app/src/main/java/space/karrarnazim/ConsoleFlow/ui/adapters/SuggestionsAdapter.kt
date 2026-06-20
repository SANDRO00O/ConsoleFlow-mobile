package space.karrarnazim.ConsoleFlow.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import space.karrarnazim.ConsoleFlow.R

/**
 * RecyclerView adapter for the address-bar suggestion dropdown.
 *
 * Each row has:
 *   [search icon]  suggestion text …  [↗ fill button]
 *
 * • Tapping the row itself navigates to that suggestion.
 * • Tapping the arrow icon fills the address bar with the suggestion
 *   text WITHOUT navigating, so the user can refine it (Chrome parity).
 */
class SuggestionsAdapter(
    private val context: Context,
    /** Called when the user taps a row — should navigate immediately. */
    private val onNavigate: (String) -> Unit,
    /** Called when the user taps the fill arrow — paste into bar only. */
    private val onFill: (String) -> Unit
) : RecyclerView.Adapter<SuggestionsAdapter.VH>() {

    var items: List<String> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // ─────────────────────────────────────────────────────────────────────────

    inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val icon:  ImageView = row.getChildAt(0) as ImageView
        val label: TextView  = row.getChildAt(1) as TextView
        val fill:  ImageView = row.getChildAt(2) as ImageView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val dp = context.resources.displayMetrics.density

        // ── left icon (magnifying glass) ──────────────────────────────────
        val icon = ImageView(context).apply {
            val size = (18 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also { lp ->
                lp.gravity    = Gravity.CENTER_VERTICAL
                lp.marginStart = (16 * dp).toInt()
                lp.marginEnd  = (12 * dp).toInt()
            }
            setImageResource(R.drawable.ic_find)
            imageTintList = ColorStateList.valueOf(0xFF666666.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // ── suggestion label ───────────────────────────────────────────────
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                lp.gravity = Gravity.CENTER_VERTICAL
            }
            setTextColor(0xFFEEEEEE.toInt())
            textSize     = 14f
            typeface     = Typeface.DEFAULT
            maxLines     = 1
            ellipsize    = TextUtils.TruncateAt.END
            gravity      = Gravity.CENTER_VERTICAL
        }

        // ── fill arrow (↗) ────────────────────────────────────────────────
        val fill = ImageView(context).apply {
            val size  = (44 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.ic_suggestion_fill)
            imageTintList = ColorStateList.valueOf(0xFF555555.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bottom_btn_ripple)
        }

        // ── row container ─────────────────────────────────────────────────
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * dp).toInt()
            )
            background = ContextCompat.getDrawable(context, R.drawable.bottom_btn_ripple)
            addView(icon)
            addView(label)
            addView(fill)
        }

        return VH(row)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val suggestion = items[position]
        h.label.text = suggestion
        // Tapping the row → navigate
        h.row.setOnClickListener { onNavigate(suggestion) }
        // Tapping the arrow → just fill the bar
        h.fill.setOnClickListener { onFill(suggestion) }
    }

    override fun getItemCount(): Int = items.size
}
