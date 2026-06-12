package space.karrarnazim.ConsoleFlow

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val id: Int,
    val url: String,
    val fileName: String,
    val mimeType: String,
    var totalBytes: Long = -1L,
    var downloadedBytes: Long = 0L,
    var state: DownloadState = DownloadState.QUEUED,
    var filePath: String? = null,
    var speedBytesPerSec: Long = 0L,
    var etaSeconds: Long = -1L,
    var error: String? = null,
    val startTime: Long = System.currentTimeMillis()
)
