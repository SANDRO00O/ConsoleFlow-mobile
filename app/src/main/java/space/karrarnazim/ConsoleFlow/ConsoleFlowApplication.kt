package space.karrarnazim.ConsoleFlow

import android.app.Application
import space.karrarnazim.ConsoleFlow.logging.LogRepository
import timber.log.Timber

class ConsoleFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val fileTree = LogRepository.init(this)
        Timber.plant(fileTree)
        if (BuildConfig.DEBUG) {
            // في التطوير: نريد أيضاً logcat الحي المعتاد بجانب الملف.
            Timber.plant(Timber.DebugTree())
        }

        // ✅ يلتقط أي عطل غير معالَج (crash) ويكتبه للسجل قبل أن يموت
        // التطبيق — بلا هذا، عطل حقيقي يختفي بلا أي أثر يمكن الرجوع له لاحقاً.
        // هذه ميزة قياسية بأي نظام تسجيل احترافي (انظر "Uncaught exception
        // handler" في أدلة أفضل ممارسات تسجيل تطبيقات الموبايل).
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.tag("FatalCrash").e(throwable, "Uncaught exception on thread ${thread.name}")
            previousHandler?.uncaughtException(thread, throwable)
        }

        Timber.tag("AppLifecycle").i("ConsoleFlow started — versionName=${BuildConfig.VERSION_NAME}")
    }
}
