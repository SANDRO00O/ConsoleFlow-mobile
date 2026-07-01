package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog

object BrowserDialogHelpers {
    fun showModernPopup(
        context: Context,
        title: String,
        items: List<String>,
        onSelect: (Int) -> Unit
    ) {
        val sheet = BottomSheetDialog(context, R.style.AppBottomSheetDialogTheme)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            try { setBackgroundResource(R.drawable.bg_bottom_sheet) }
            catch (_: Exception) { setBackgroundColor(Color.parseColor("#2C2C2C")) }
            setPadding(context.responsiveDp(0), context.responsiveDp(32), context.responsiveDp(0), context.responsiveDp(32))
        }

        val handle = View(context).apply {
            val dp = context.responsiveDensity()
            layoutParams = LinearLayout.LayoutParams(
                (40 * dp).toInt(), (4 * dp).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
                topMargin = (4 * dp).toInt()
            }
            setBackgroundColor(Color.parseColor("#444444"))
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = context.responsiveSp(18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, context.responsiveDp(24))
        }

        container.addView(handle)
        container.addView(titleView)

        items.forEachIndexed { index, text ->
            val item = TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = context.responsiveSp(16f)
                setPadding(context.responsiveDp(48), context.responsiveDp(36), context.responsiveDp(48), context.responsiveDp(36))
                setOnClickListener {
                    sheet.dismiss()
                    onSelect(index)
                }
            }
            container.addView(item)
        }

        sheet.setContentView(container)
        sheet.show()
    }

    fun showListWithFavicons(
        context: Context,
        title: String,
        items: List<Pair<String, String>>,
        loadFavicon: (String, ImageView) -> Unit,
        onSelect: (Int) -> Unit
    ) {
        val sheet = BottomSheetDialog(context, R.style.AppBottomSheetDialogTheme)
        val dp = context.responsiveDensity()
        val scrollView = ScrollView(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            try { setBackgroundResource(R.drawable.bg_bottom_sheet) }
            catch (_: Exception) { setBackgroundColor(Color.parseColor("#2C2C2C")) }
            setPadding(context.responsiveDp(0), context.responsiveDp(32), context.responsiveDp(0), context.responsiveDp(32))
        }
        scrollView.addView(container)

        val handle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (40 * dp).toInt(), (4 * dp).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
                topMargin = (4 * dp).toInt()
            }
            setBackgroundColor(Color.parseColor("#444444"))
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = context.responsiveSp(18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, (24 * dp).toInt())
        }

        container.addView(handle)
        container.addView(titleView)

        items.forEachIndexed { index, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                    marginEnd = (12 * dp).toInt()
                }
                setImageResource(android.R.drawable.ic_menu_info_details)
            }

            loadFavicon(item.second, icon)

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleText = TextView(context).apply {
                text = item.first
                textSize = context.responsiveSp(16f)
                setTextColor(Color.WHITE)
            }

            val subText = TextView(context).apply {
                text = item.second
                textSize = context.responsiveSp(12f)
                setTextColor(Color.LTGRAY)
            }

            textContainer.addView(titleText)
            textContainer.addView(subText)

            row.addView(icon)
            row.addView(textContainer)
            row.setOnClickListener {
                sheet.dismiss()
                onSelect(index)
            }
            container.addView(row)
        }

        sheet.setContentView(scrollView)
        sheet.show()
    }

    fun showBookmarksDialog(
        context: Context,
        getBookmarks: () -> List<Pair<String, String>>,
        openUrl: (String) -> Unit,
        loadFavicon: (String, ImageView) -> Unit
    ) {
        val bookmarks = getBookmarks()
        if (bookmarks.isEmpty()) {
            Toast.makeText(context, "No bookmarks", Toast.LENGTH_SHORT).show()
            return
        }
        showListWithFavicons(context, "Bookmarks", bookmarks, loadFavicon) { index ->
            openUrl(bookmarks[index].second)
        }
    }

    fun showHistoryDialog(
        context: Context,
        getHistory: () -> List<Pair<String, String>>,
        openUrl: (String) -> Unit,
        loadFavicon: (String, ImageView) -> Unit
    ) {
        val history = getHistory()
        if (history.isEmpty()) {
            Toast.makeText(context, "No history", Toast.LENGTH_SHORT).show()
            return
        }
        showListWithFavicons(context, "History", history, loadFavicon) { index ->
            openUrl(history[index].second)
        }
    }
}
