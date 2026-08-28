package space.karrarnazim.ConsoleFlow

import android.app.Application

class ConsoleFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
