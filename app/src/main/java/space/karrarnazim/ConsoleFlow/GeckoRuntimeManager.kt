package space.karrarnazim.ConsoleFlow

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Singleton holder for GeckoRuntime.
 *
 * GeckoRuntime is expensive to create (launches the Gecko process) and
 * MUST be created only once per application lifetime. Creating a second
 * instance while one exists causes a crash. We use double-checked locking
 * so the first call from onCreate() pays the cost; all subsequent calls
 * are a cheap volatile read.
 */
object GeckoRuntimeManager {

    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: build(context.applicationContext).also { runtime = it }
        }

    private fun build(ctx: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            // No remote-debugging in release builds (can be toggled in SettingsActivity)
            .remoteDebuggingEnabled(false)
            // Disable console output to Android logcat (saves log noise)
            .consoleOutput(false)
            // On TV, auto-zooming into form fields is disruptive — disable it
            // ✅ صُحِّح اسم الدالة: inputAutoZoomEnabled وليس inputAutoZoom
            // (تحقق منه فعلياً عبر javadoc الرسمي — الاسم الخاطئ كان سيمنع
            // التصريف بالكامل: unresolved reference).
            .inputAutoZoomEnabled(false)
            // Allow http:// connections (matches the old WebView mixed-content setting)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            // Prefer dark color scheme where sites support it
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .build()
        return GeckoRuntime.create(ctx, settings)
    }
}
