package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-wide logger used by the in-app Logs screen (see LogsActivity).
 *
 * - Keeps the last [MAX_ENTRIES] lines in memory for instant display.
 * - Mirrors every line to Logcat, so `adb logcat` still works as usual.
 * - Persists lines to a small rotating file under filesDir/logs, so a log survives
 *   process death and crashes can be recovered and attached to GitHub issues.
 * - Never throws: a failure to log must never be the reason the app crashes.
 */
object AppLogger {

    private const val MAX_ENTRIES    = 2000
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val entries  = ArrayDeque<LogEntry>()
    private val lock     = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null
    private var oldLogFile: File? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile    = File(dir, "consoleflow.log")
        oldLogFile = File(dir, "consoleflow.old.log")

        installUncaughtExceptionHandler()

        i("AppLogger", "Session start — app v${appVersion(context)}, " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, message, tr)

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        ioExecutor.execute {
            runCatching {
                logFile?.writeText("")
                oldLogFile?.delete()
            }
        }
    }

    /** Writes the current in-memory session (with a device/app header) to a fresh file for sharing. */
    fun exportToFile(context: Context): File {
        val dir = File(context.cacheDir, "logs_export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "consoleflow-log-${System.currentTimeMillis()}.txt")
        out.printWriter().use { writer ->
            writer.print(headerBlock(context))
            snapshot().forEach { writer.println(formatLine(it)) }
        }
        return out
    }

    private fun headerBlock(context: Context): String = buildString {
        appendLine("ConsoleFlow debug log")
        appendLine("App version: ${appVersion(context)}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Generated: ${timeFormat.format(Date())}")
        appendLine("----------------------------------------")
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun log(level: LogLevel, tag: String, message: String, tr: Throwable?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level     = level,
            tag       = tag,
            message   = message,
            throwable = tr?.let { stackTraceOf(it) }
        )

        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, tr)
            LogLevel.INFO  -> Log.i(tag, message, tr)
            LogLevel.WARN  -> Log.w(tag, message, tr)
            LogLevel.ERROR -> Log.e(tag, message, tr)
        }

        if (initialized) ioExecutor.execute { appendToFile(entry) }
    }

    private fun appendToFile(entry: LogEntry) {
        val file = logFile ?: return
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                oldLogFile?.delete()
                file.renameTo(oldLogFile ?: return)
            }
            file.appendText(formatLine(entry) + "\n")
        }
        // A failed disk write must never surface as a crash — the in-memory buffer still has it.
    }

    private fun formatLine(entry: LogEntry): String {
        val time = timeFormat.format(Date(entry.timestamp))
        val base = "$time ${entry.level.label}/${entry.tag}: ${entry.message}"
        return if (entry.throwable != null) "$base\n${entry.throwable}" else base
    }

    private fun stackTraceOf(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
                // Give the async file write a brief window to actually land before the process dies.
                ioExecutor.submit {}.get(500, TimeUnit.MILLISECONDS)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
