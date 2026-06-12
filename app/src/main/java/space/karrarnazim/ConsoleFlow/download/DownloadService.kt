package space.karrarnazim.ConsoleFlow

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that downloads files via OkHttp.
 * Lives as long as there are active downloads, then stops itself.
 *
 * Design: one thread per download (max 3 concurrent), no blocking on main.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START  = "cf.dl.start"
        const val ACTION_CANCEL = "cf.dl.cancel"

        const val EXTRA_URL      = "url"
        const val EXTRA_FILENAME = "fname"
        const val EXTRA_MIME     = "mime"
        const val EXTRA_UA       = "ua"
        const val EXTRA_COOKIES  = "cookies"
        const val EXTRA_DL_ID    = "dlid"

        const val CHANNEL_ID      = "cf_downloads"
        private const val NOTIF_FOREGROUND = 999
        private const val NOTIF_BASE       = 1000

        // Update notification at most every 500ms
        private const val NOTIF_INTERVAL_MS = 500L
    }

    private val executor    = Executors.newFixedThreadPool(3)
    private val activeCount = AtomicInteger(0)
    private val notifIdGen  = AtomicInteger(NOTIF_BASE)

    private val notifManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)!!
    }

    // Dedicated OkHttp client: no read timeout (large files), 30s connect
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_FOREGROUND, buildForegroundNotif())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Command handlers
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        val url      = intent.getStringExtra(EXTRA_URL) ?: return
        val fileName = intent.getStringExtra(EXTRA_FILENAME)
            ?: URLUtil.guessFileName(url, null, null)
        val mime     = intent.getStringExtra(EXTRA_MIME) ?: "application/octet-stream"
        val ua       = intent.getStringExtra(EXTRA_UA) ?: "Mozilla/5.0"
        val cookies  = intent.getStringExtra(EXTRA_COOKIES)

        val id      = DownloadTracker.nextId()
        val notifId = notifIdGen.getAndIncrement()

        DownloadTracker.add(DownloadItem(id = id, url = url, fileName = fileName, mimeType = mime))
        activeCount.incrementAndGet()
        executor.execute { doDownload(id, url, fileName, mime, ua, cookies, notifId) }
    }

    private fun handleCancel(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_DL_ID, -1)
        if (id != -1) {
            DownloadTracker.update(id) { state = DownloadState.CANCELLED }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun doDownload(
        id: Int,
        url: String,
        fileName: String,
        mime: String,
        ua: String,
        cookies: String?,
        notifId: Int
    ) {
        DownloadTracker.update(id) { state = DownloadState.RUNNING }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
                .build()

            val response = okClient.newCall(request).execute()
            if (!response.isSuccessful) {
                fail(id, notifId, fileName, "HTTP ${response.code}")
                return
            }

            val totalBytes = response.body?.contentLength() ?: -1L
            DownloadTracker.update(id) { this.totalBytes = totalBytes }

            val (outputStream, filePath) = createOutputFile(fileName, mime)

            try {
                val body  = response.body ?: throw IOException("Empty response body")
                val buf   = ByteArray(16 * 1024)
                val input = body.byteStream()
                var done  = 0L

                // Sliding window: last 10 samples for smooth speed/ETA
                val winTime  = ArrayDeque<Long>()
                val winBytes = ArrayDeque<Long>()
                var lastNotifMs = 0L

                loop@ while (true) {
                    // Cancellation check before every read
                    if (DownloadTracker.getById(id)?.state == DownloadState.CANCELLED) {
                        input.close()
                        outputStream.close()
                        deletePartial(filePath)
                        return
                    }

                    val n = input.read(buf)
                    if (n == -1) break@loop

                    outputStream.write(buf, 0, n)
                    done += n

                    // Sliding window for speed
                    val now = System.currentTimeMillis()
                    winTime.addLast(now)
                    winBytes.addLast(done)
                    if (winTime.size > 10) { winTime.removeFirst(); winBytes.removeFirst() }

                    val speed: Long = if (winTime.size >= 2) {
                        val dt = (winTime.last() - winTime.first()) / 1000.0
                        val db = winBytes.last() - winBytes.first()
                        if (dt > 0) (db / dt).toLong() else 0L
                    } else 0L

                    val eta = if (speed > 0 && totalBytes > 0)
                        (totalBytes - done) / speed
                    else -1L

                    DownloadTracker.update(id) {
                        downloadedBytes  = done
                        speedBytesPerSec = speed
                        etaSeconds       = eta
                    }

                    // Rate-limit notification updates
                    if (now - lastNotifMs >= NOTIF_INTERVAL_MS) {
                        lastNotifMs = now
                        val pct = if (totalBytes > 0) ((done * 100) / totalBytes).toInt() else -1
                        updateProgressNotif(notifId, fileName, pct, done, totalBytes, speed, id)
                    }
                }

                outputStream.close()
                input.close()
                finalizeFile(filePath, fileName, mime)

                DownloadTracker.update(id) {
                    state           = DownloadState.COMPLETED
                    downloadedBytes = done
                    this.filePath   = filePath
                }
                notifManager.cancel(notifId)
                showCompletedNotif(notifId, fileName, filePath, mime)

            } catch (e: IOException) {
                try { outputStream.close() } catch (_: Exception) {}
                if (DownloadTracker.getById(id)?.state != DownloadState.CANCELLED) {
                    deletePartial(filePath)
                    fail(id, notifId, fileName, e.message ?: "IO error")
                } else {
                    notifManager.cancel(notifId)
                }
            }

        } catch (e: Exception) {
            if (DownloadTracker.getById(id)?.state != DownloadState.CANCELLED) {
                fail(id, notifId, fileName, e.message ?: "Network error")
            } else {
                notifManager.cancel(notifId)
            }
        } finally {
            if (activeCount.decrementAndGet() == 0) stopWhenIdle()
        }
    }

    private fun fail(id: Int, notifId: Int, fileName: String, msg: String) {
        DownloadTracker.update(id) { state = DownloadState.FAILED; error = msg }
        notifManager.cancel(notifId)
        notifManager.notify(notifId, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(fileName)
            .setSubText(msg)
            .setAutoCancel(true)
            .build())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File I/O helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns (OutputStream, filePath-or-contentUri-string).
     * API 29+: MediaStore content:// URI.
     * API 24-28: public Downloads dir (WRITE_EXTERNAL_STORAGE permission
     * already declared in manifest with maxSdkVersion="28").
     */
    private fun createOutputFile(fileName: String, mime: String): Pair<OutputStream, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("MediaStore insert failed")
            Pair(contentResolver.openOutputStream(uri)!!, uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var file = File(dir, fileName)
            if (file.exists()) {
                val base = fileName.substringBeforeLast(".")
                val ext  = fileName.substringAfterLast(".", "")
                var n = 1
                do {
                    file = if (ext.isNotEmpty()) File(dir, "$base ($n).$ext")
                           else File(dir, "$fileName ($n)")
                    n++
                } while (file.exists())
            }
            Pair(FileOutputStream(file), file.absolutePath)
        }
    }

    private fun finalizeFile(path: String, fileName: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = Uri.parse(path)
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        } else {
            try {
                MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf(mime), null)
            } catch (_: Exception) {}
        }
    }

    private fun deletePartial(path: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                contentResolver.delete(Uri.parse(path), null, null)
            else
                File(path).delete()
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ConsoleFlow download progress"
                setShowBadge(false)
            }
            notifManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotif(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ConsoleFlow Downloads")
            .setContentText("Preparing…")
            .setOngoing(true)
            .build()

    private fun updateProgressNotif(
        notifId: Int,
        fileName: String,
        pct: Int,
        done: Long,
        total: Long,
        speed: Long,
        dlId: Int
    ) {
        val cancelPi = PendingIntent.getService(
            this, dlId,
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DL_ID, dlId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPagePi = PendingIntent.getActivity(
            this, notifId,
            Intent(this, DownloadsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = buildString {
            append(formatBytes(done))
            if (total > 0) append(" / ${formatBytes(total)}")
            if (speed > 0) {
                append("  •  ${formatSpeed(speed)}")
                val eta = DownloadTracker.getById(dlId)?.etaSeconds ?: -1
                if (eta > 0) append("  •  ${formatEta(eta)}")
            }
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(statusText)
            .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPagePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
            .build()

        notifManager.notify(notifId, notif)
    }

    private fun showCompletedNotif(
        notifId: Int,
        fileName: String,
        filePath: String,
        mime: String
    ) {
        val openFilePi: PendingIntent? = try {
            val uri = if (filePath.startsWith("content://")) Uri.parse(filePath) else null
            if (uri != null) {
                PendingIntent.getActivity(
                    this, notifId,
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                // Pre-Q: open Downloads page instead of file directly
                PendingIntent.getActivity(
                    this, notifId,
                    Intent(this, DownloadsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        } catch (_: Exception) { null }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(fileName)
            .setAutoCancel(true)
            .apply { if (openFilePi != null) setContentIntent(openFilePi) }
            .build()

        notifManager.notify(notifId, notif)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Self-management
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopWhenIdle() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatters (used in progress notifications and activity)
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024L         -> "$bytes B"
        bytes < 1_048_576L     -> "${"%.1f".format(bytes / 1_024.0)} KB"
        bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        else                   -> "${"%.2f".format(bytes / 1_073_741_824.0)} GB"
    }

    private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

    private fun formatEta(sec: Long): String = when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}
