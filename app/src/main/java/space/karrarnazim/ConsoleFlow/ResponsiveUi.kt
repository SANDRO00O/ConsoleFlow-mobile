package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * Simple TV / large-screen scaling helpers.
 *
 * The app uses many programmatic pixel values. On Android TV and other
 * large-screen devices these values can end up too small, so we gently scale
 * the UI up while preserving the original design on phones.
 */
internal fun Context.isTelevisionLike(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        uiMode == Configuration.UI_MODE_TYPE_TELEVISION
}

internal fun Context.responsiveScale(): Float {
    val cfg = resources.configuration
    return when {
        isTelevisionLike() -> 1.20f
        cfg.smallestScreenWidthDp >= 720 -> 1.10f
        cfg.smallestScreenWidthDp >= 600 -> 1.05f
        cfg.screenWidthDp >= 960 && cfg.screenHeightDp >= 600 -> 1.05f
        else -> 1.0f
    }
}

internal fun Context.responsiveDensity(): Float =
    resources.displayMetrics.density * responsiveScale()

internal fun Context.responsiveDp(value: Int): Int =
    (value * responsiveDensity()).roundToInt()

internal fun Context.responsiveSp(value: Float): Float =
    value * responsiveScale()
