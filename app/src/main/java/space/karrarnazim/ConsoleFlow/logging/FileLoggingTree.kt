package space.karrarnazim.ConsoleFlow.logging

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * ── نظام السجلات الاحترافي ──────────────────────────────────────────────────
 *
 * قواعد صُممت اعتماداً على بحث مباشر في أفضل ممارسات تسجيل تطبيقات الموبايل:
 * 1. سجلات دوّارة بحجم محدود (لا نملأ تخزين الجهاز إلى الأبد) — حجم أقصى
 *    لكل ملف + عدد أقصى للملفات المؤرشفة، بالضبط كما توصي المصادر المتخصصة.
 * 2. كل سطر يحمل الوقت، المستوى، الوسم — قابل للفرز والفلترة لاحقاً بسهولة.
 * 3. الكتابة على خيط خلفي واحد مخصَّص (لا يحجب أي خيط UI ولا حتى الشبكة).
 * 4. لا نعتمد فقط على logcat حي متصل بجهاز — الملف يبقى حتى تُشاركه لاحقاً،
 *    بالضبط الفجوة التي واجهتنا سابقاً (سجل مطلوب أثناء استخدام حقيقي للتطبيق
 *    من مستخدم لا يملك adb متصلاً في تلك اللحظة).
 */
class FileLoggingTree(context: Context) : Timber.Tree() {

    private val logsDir: File = File(context.filesDir, "logs").apply { mkdirs() }
    private val activeFile: File get() = File(logsDir, "app.log")

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024   // 2MB لكل ملف
        private const val MAX_ARCHIVED_FILES = 5                    // أرشيف = 10MB إجمالاً كحد أقصى
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun levelName(priority: Int): String = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG   -> "D"
            Log.INFO    -> "I"
            Log.WARN    -> "W"
            Log.ERROR   -> "E"
            Log.ASSERT  -> "A"
            else        -> "?"
        }
    }

    // خيط خلفي واحد فقط للكتابة — يمنع تعارض كتابة متزامنة من عدة خيوط،
    // ويضمن ترتيب الأسطر زمنياً بدقة بلا Race.
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val pending = LinkedBlockingQueue<String>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val line = buildString {
            append(dateFormat.format(Date()))
            append(' ').append(levelName(priority))
            append('/').append(tag ?: "App")
            append(": ").append(message)
            if (t != null) {
                append('\n').append(Log.getStackTraceString(t))
            }
        }
        writeExecutor.execute { appendLine(line) }
    }

    private fun appendLine(line: String) {
        try {
            rotateIfNeeded()
            activeFile.appendText(line + "\n")
        } catch (_: Exception) {
            // فشل الكتابة نفسه لا يجب أن يُسقط التطبيق أو يُنتج حلقة تسجيل.
        }
    }

    private fun rotateIfNeeded() {
        if (!activeFile.exists() || activeFile.length() < MAX_FILE_SIZE_BYTES) return

        // إزاحة الأرشيف: app.log.4 يُحذف، app.log.3 → app.log.4، ... app.log → app.log.1
        val oldest = File(logsDir, "app.log.$MAX_ARCHIVED_FILES")
        if (oldest.exists()) oldest.delete()
        for (i in MAX_ARCHIVED_FILES - 1 downTo 1) {
            val src = File(logsDir, "app.log.$i")
            if (src.exists()) src.renameTo(File(logsDir, "app.log.${i + 1}"))
        }
        activeFile.renameTo(File(logsDir, "app.log.1"))
    }

    /** كل ملفات السجل الحالية، من الأحدث إلى الأقدم. */
    fun allLogFiles(): List<File> {
        val archived = (1..MAX_ARCHIVED_FILES)
            .map { File(logsDir, "app.log.$it") }
            .filter { it.exists() }
        return (listOf(activeFile).filter { it.exists() } + archived)
    }

    fun clearAll() {
        writeExecutor.execute {
            allLogFiles().forEach { it.delete() }
        }
    }
}
