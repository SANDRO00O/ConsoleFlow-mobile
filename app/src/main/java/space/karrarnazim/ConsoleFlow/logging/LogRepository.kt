package space.karrarnazim.ConsoleFlow.logging

import android.content.Context

/**
 * نقطة وصول واحدة لملفات السجل من أي مكان بالتطبيق (شاشة عرض السجلات،
 * زر "شارك السجلات"، إلخ) بلا الحاجة لتمرير مرجع FileLoggingTree يدوياً.
 */
object LogRepository {
    @Volatile private var tree: FileLoggingTree? = null

    fun init(context: Context): FileLoggingTree {
        return tree ?: synchronized(this) {
            tree ?: FileLoggingTree(context.applicationContext).also { tree = it }
        }
    }

    fun get(): FileLoggingTree? = tree
}
