package space.karrarnazim.ConsoleFlow

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController

object BrowserRuntimeStore {
    @Volatile
    var runtime: GeckoRuntime? = null
        private set

    fun setRuntime(newRuntime: GeckoRuntime) {
        runtime = newRuntime
    }

    fun clearBrowserData(context: Context) {
        runtime?.storageController?.clearData(StorageController.ClearFlags.ALL)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
