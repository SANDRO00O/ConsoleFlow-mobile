package space.karrarnazim.ConsoleFlow

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val PREFS         = "update_cache"
    private const val KEY_VERSION   = "latest_version"
    private const val KEY_NAME      = "release_name"
    private const val KEY_CHANGELOG = "changelog"
    private const val KEY_URL       = "release_url"
    private const val KEY_DATE      = "published_at"
    private const val KEY_CHECKED   = "last_checked"
    private const val CACHE_TTL     = 6 * 60 * 60 * 1000L
    private const val API_URL       =
        "https://api.github.com/repos/ConsoleFlow-Group/ConsoleFlow-mobile/releases/latest"

    data class ReleaseInfo(
        val latestVersion : String,
        val releaseName   : String,
        val changelog     : String,
        val releaseUrl    : String,
        val publishedAt   : String
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" }
        catch (_: Exception) { "0.0.0" }

    fun getCached(ctx: Context): ReleaseInfo? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ver = p.getString(KEY_VERSION, null) ?: return null
        return ReleaseInfo(
            latestVersion = ver,
            releaseName   = p.getString(KEY_NAME,      "") ?: "",
            changelog     = p.getString(KEY_CHANGELOG, "") ?: "",
            releaseUrl    = p.getString(KEY_URL,       "") ?: "",
            publishedAt   = p.getString(KEY_DATE,      "") ?: ""
        )
    }

    fun isUpdateAvailable(ctx: Context): Boolean {
        val cached = getCached(ctx) ?: return false
        return compare(currentVersion(ctx), cached.latestVersion) < 0
    }

    fun check(
        ctx          : Context,
        forceRefresh : Boolean = false,
        callback     : (release: ReleaseInfo?, updateAvailable: Boolean) -> Unit
    ) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age   = System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0L)

        if (!forceRefresh && age < CACHE_TTL) {
            val cached = getCached(ctx)
            val upd    = cached != null && compare(currentVersion(ctx), cached.latestVersion) < 0
            main.post { callback(cached, upd) }
            return
        }

        val req = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        http.newCall(req).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                AppLogger.w("UpdateManager", "Update check failed, using cached release info", e)
                val cached = getCached(ctx)
                val upd    = cached != null && compare(currentVersion(ctx), cached.latestVersion) < 0
                main.post { callback(cached, upd) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { onFailure(call, IOException("HTTP ${it.code}")); return }
                    try {
                        val j    = JSONObject(it.body!!.string())
                        val ver  = j.getString("tag_name").trimStart('v', 'V')
                        val info = ReleaseInfo(
                            latestVersion = ver,
                            releaseName   = j.optString("name", ver),
                            changelog     = j.optString("body", ""),
                            releaseUrl    = j.optString("html_url", ""),
                            publishedAt   = j.optString("published_at", "")
                        )
                        val upd = compare(currentVersion(ctx), ver) < 0

                        prefs.edit()
                            .putString(KEY_VERSION,   ver)
                            .putString(KEY_NAME,      info.releaseName)
                            .putString(KEY_CHANGELOG, info.changelog)
                            .putString(KEY_URL,       info.releaseUrl)
                            .putString(KEY_DATE,      info.publishedAt)
                            .putLong(KEY_CHECKED,     System.currentTimeMillis())
                            .apply()

                        main.post { callback(info, upd) }
                    } catch (e: Exception) {
                        AppLogger.w("UpdateManager", "Failed to parse release info from GitHub", e)
                        onFailure(call, IOException(e))
                    }
                }
            }
        })
    }

    fun compare(a: String, b: String): Int {
        val pa = a.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
