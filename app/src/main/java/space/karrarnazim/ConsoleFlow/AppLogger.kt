package space.karrarnazim.ConsoleFlow

import java.util.Collections
import java.util.LinkedList

// ─────────────────────────────────────────────────────────────────────────────
//  AppLogger — مخزن سجلات مركزي مُتزامن (thread-safe)
//  يُستخدم من MainActivity (WebView hooks) و LogsActivity (عرض)
// ─────────────────────────────────────────────────────────────────────────────

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestampMs: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val source: String,    // "JS", "NET", "APP", "WV"
    val message: String,
    val url: String? = null
) {
    val timeString: String
        get() {
            val ms = timestampMs
            val h  = (ms / 3_600_000 % 24).toInt()
            val m  = (ms /    60_000 % 60).toInt()
            val s  = (ms /     1_000 % 60).toInt()
            val ml = (ms               % 1_000).toInt()
            return "%02d:%02d:%02d.%03d".format(h, m, s, ml)
        }
}

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR;

    val label: String get() = name
    val color: Int get() = when (this) {
        VERBOSE -> 0xFF888888.toInt()
        DEBUG   -> 0xFF4FC3F7.toInt()
        INFO    -> 0xFF66BB6A.toInt()
        WARN    -> 0xFFFFA726.toInt()
        ERROR   -> 0xFFEF5350.toInt()
    }
    val bgColor: Int get() = when (this) {
        VERBOSE -> 0xFF2A2A2A.toInt()
        DEBUG   -> 0xFF0D2A36.toInt()
        INFO    -> 0xFF0D2A0D.toInt()
        WARN    -> 0xFF2A1A00.toInt()
        ERROR   -> 0xFF2A0A0A.toInt()
    }
}

object AppLogger {
    private const val MAX_ENTRIES = 500

    // LinkedList محمي بـ Collections.synchronizedList — قراءة/كتابة آمنة من أي thread
    private val entries: MutableList<LogEntry> =
        Collections.synchronizedList(LinkedList<LogEntry>())

    // ─── كتابة ──────────────────────────────────────────────────────────────

    fun log(level: LogLevel, source: String, message: String, url: String? = null) {
        val entry = LogEntry(level = level, source = source, message = message, url = url)
        synchronized(entries) {
            (entries as LinkedList).addLast(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun v(source: String, msg: String, url: String? = null) = log(LogLevel.VERBOSE, source, msg, url)
    fun d(source: String, msg: String, url: String? = null) = log(LogLevel.DEBUG,   source, msg, url)
    fun i(source: String, msg: String, url: String? = null) = log(LogLevel.INFO,    source, msg, url)
    fun w(source: String, msg: String, url: String? = null) = log(LogLevel.WARN,    source, msg, url)
    fun e(source: String, msg: String, url: String? = null) = log(LogLevel.ERROR,   source, msg, url)

    // ─── قراءة ──────────────────────────────────────────────────────────────

    /** نسخة ثابتة للقراءة على main thread */
    fun snapshot(): List<LogEntry> = synchronized(entries) { entries.toList() }

    fun errorCount(): Int = synchronized(entries) {
        entries.count { it.level == LogLevel.ERROR }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    // ─── تصدير ──────────────────────────────────────────────────────────────

    fun exportText(): String = synchronized(entries) {
        entries.joinToString("\n") { e ->
            "[${e.timeString}] ${e.level.label} [${e.source}] ${e.message}" +
                    (if (e.url != null) "\n  → ${e.url}" else "")
        }
    }
}
