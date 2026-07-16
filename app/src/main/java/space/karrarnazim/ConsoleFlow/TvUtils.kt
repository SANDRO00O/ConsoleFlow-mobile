package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View

/**
 * Root cause of three separate bug reports turned out to be one thing:
 * this app never checked whether it was running on a television.
 *
 *  1. "Home screen has no padding, elements bleed off-screen"
 *  2. "About page banner is huge, mostly hidden"
 *
 * Both are the TV overscan problem: most TV panels crop 5-10% off every
 * edge of the picture. Phones don't do this, so it was never noticed.
 * Fix once, here, use everywhere — don't sprinkle magic dp values through
 * five different Activities.
 */
object TvUtils {

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Standard Android TV safe-area guidance: 5% of width, 5% of height. */
    fun overscanPaddingPx(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val h = (dm.widthPixels * 0.05f).toInt()
        val v = (dm.heightPixels * 0.05f).toInt()
        return h to v
    }

    /** Applies overscan-safe padding to [view] only when running on TV; no-op on phones. */
    fun applyOverscanSafePadding(activity: Activity, view: View) {
        if (!isTelevision(activity)) return
        val (h, v) = overscanPaddingPx(activity)
        view.setPadding(
            view.paddingLeft + h,
            view.paddingTop + v,
            view.paddingRight + h,
            view.paddingBottom + v
        )
    }
}
