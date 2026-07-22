package space.karrarnazim.ConsoleFlow.logging

import java.io.File

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)

object LogParser {
    // يطابق: "2026-07-22 10:15:30.123 I/ConsoleFlowNav: onPageStart ..."
    private val headerRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEA?])/([^:]+): (.*)$""")

    /** يقرأ كل ملفات السجل ويحوّلها لقائمة مُدخلات مُرتَّبة زمنياً تنازلياً (الأحدث أولاً).
     *  [files] يُتوقَّع أن تكون بترتيب "الأحدث أولاً" (كما تُرجعها allLogFiles())
     *  — نعالجها هنا بالترتيب المعاكس (الأقدم أولاً) لأن كل ملف نفسه مكتوب
     *  داخلياً بترتيب تصاعدي (قديم→جديد)، فالانعكاس النهائي الواحد فقط يُنتج
     *  ترتيباً صحيحاً للقائمة كاملة. */
    fun parseAll(files: List<File>): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        for (file in files.asReversed()) {
            runCatching {
                val lines = file.readLines()
                var current: LogEntry? = null
                for (line in lines) {
                    val match = headerRegex.find(line)
                    if (match != null) {
                        current?.let { entries.add(it) }
                        val (ts, lvl, tag, msg) = match.destructured
                        current = LogEntry(ts, lvl, tag, msg)
                    } else if (current != null) {
                        // سطر تتمة (مثل stack trace) — يُضاف لرسالة السطر السابق.
                        current = current.copy(message = current!!.message + "\n" + line)
                    }
                }
                current?.let { entries.add(it) }
            }
        }
        return entries.asReversed()
    }
}
