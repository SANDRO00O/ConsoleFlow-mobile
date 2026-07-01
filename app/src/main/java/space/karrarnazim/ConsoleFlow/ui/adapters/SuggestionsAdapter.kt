package space.karrarnazim.ConsoleFlow.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import space.karrarnazim.ConsoleFlow.R

/**
 * RecyclerView adapter for the search overlay suggestion list.
 *
 * Each row:   [🔍 icon]  suggestion text …  [↗ fill]
 *
 * Touch:  tap row → navigate  |  tap arrow → fill bar only
 * TV / D-pad:
 *   DPAD_UP on first row → [onBackToSearch] (returns focus to search EditText)
 *   DPAD_CENTER / ENTER  → navigate
 *   The fill arrow is NOT focusable — D-pad focus stays on the row as a unit.
 */
class SuggestionsAdapter(
    private val context: Context,
    var onNavigate: (String) -> Unit,
    var onFill: (String) -> Unit,
    /** TV: called when the user presses D-pad UP on the first suggestion row. */
    var onBackToSearch: (() -> Unit)? = null
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
        val dp = context.responsiveDensity()

        val icon = ImageView(context).apply {
            val size = (20 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also { lp ->
                lp.gravity    = Gravity.CENTER_VERTICAL
                lp.marginStart = (16 * dp).toInt()
                lp.marginEnd  = (12 * dp).toInt()
            }
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(0xFF555555.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                lp.gravity = Gravity.CENTER_VERTICAL
            }
            setTextColor(0xFFEEEEEE.toInt())
            textSize  = context.responsiveSp(14f)
            typeface  = Typeface.DEFAULT
            maxLines  = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER_VERTICAL
        }

        val fill = ImageView(context).apply {
            val size = (44 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.ic_suggestion_fill)
            imageTintList = ColorStateList.valueOf(0xFF555555.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bottom_btn_ripple)
            // TV: fill is a touch-only affordance. D-pad focus stays on the row
            // as a whole; making fill separately focusable would require two
            // D-pad presses to move past each suggestion instead of one.
            isFocusable = false
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * dp).toInt()
            )
            // bg_suggestion_row shows a solid highlight on focus (TV D-pad)
            // AND a pressed-state on touch — bottom_btn_ripple only handles touch.
            background = ContextCompat.getDrawable(context, R.drawable.bg_suggestion_row)
            isFocusable = true
            isClickable = true
            addView(icon)
            addView(label)
            addView(fill)
        }

        return VH(row)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val suggestion = items[position]
        h.label.text = suggestion

        h.row.setOnClickListener { onNavigate(suggestion) }
        h.fill.setOnClickListener { onFill(suggestion) }

        // TV D-pad key handling on the focused row.
        h.row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    // Confirm → navigate immediately
                    onNavigate(suggestion)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // First row: UP returns focus to the search bar EditText.
                    // Other rows: let RecyclerView's default traversal handle it.
                    if (h.bindingAdapterPosition == 0) {
                        onBackToSearch?.invoke()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    override fun getItemCount(): Int = items.size
}

