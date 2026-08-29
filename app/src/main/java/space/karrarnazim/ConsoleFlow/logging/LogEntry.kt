package space.karrarnazim.ConsoleFlow

enum class LogLevel(val label: String, val colorHex: String) {
    DEBUG("D", "#777777"),
    INFO("I", "#CCCCCC"),
    WARN("W", "#E0A93D"),
    ERROR("E", "#E05C5C")
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null
)
