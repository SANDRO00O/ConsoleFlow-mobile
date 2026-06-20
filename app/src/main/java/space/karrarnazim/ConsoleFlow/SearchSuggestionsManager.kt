package space.karrarnazim.ConsoleFlow

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches search-engine suggestions as-you-type.
 *
 * Design notes:
 * - 300 ms debounce so we don't hammer the network on every keystroke.
 * - Stale-response guard: if the user typed faster than the network
 *   responded, the older result is silently discarded.
 * - All callbacks are always delivered on the main thread.
 * - Queries that look like full URLs (http/https/file prefix) are skipped —
 *   no point fetching "https://exam" suggestions.
 * - Minimum query length: 2 characters.
 */
class SearchSuggestionsManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val handler           = Handler(Looper.getMainLooper())
    private var pendingCall: Call? = null
    private var pendingRunnable: Runnable? = null
    @Volatile private var lastQuery = ""

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedule a suggestion fetch for [query] after the debounce window.
     * Any previously scheduled or in-flight fetch is cancelled first.
     * [onResult] is called on the main thread with up to MAX_SUGGESTIONS
     * strings, or an empty list when there is nothing to show.
     */
    fun fetchDebounced(
        query: String,
        kind: SearchEngineKind,
        onResult: (List<String>) -> Unit
    ) {
        pendingRunnable?.let { handler.removeCallbacks(it) }

        val trimmed = query.trim()

        // Don't fetch for explicit URL schemes — not a search query.
        if (trimmed.length < 2
            || trimmed.startsWith("http://")
            || trimmed.startsWith("https://")
            || trimmed.startsWith("file://")
        ) {
            onResult(emptyList())
            return
        }

        val r = Runnable { doFetch(trimmed, kind, onResult) }
        pendingRunnable = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    /** Cancel any scheduled or in-flight fetch immediately. */
    fun cancel() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        pendingCall?.cancel()
        pendingCall = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun doFetch(
        query: String,
        kind: SearchEngineKind,
        onResult: (List<String>) -> Unit
    ) {
        pendingCall?.cancel()
        lastQuery = query

        val url = suggestUrl(query, kind)
            ?: run { onResult(emptyList()); return }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 Firefox/120.0")
            .build()

        val call = client.newCall(request).also { pendingCall = it }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) handler.post { onResult(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (call.isCanceled()) return
                    if (!resp.isSuccessful) { handler.post { onResult(emptyList()) }; return }
                    val body = resp.body?.string()
                        ?: run { handler.post { onResult(emptyList()) }; return }
                    val list = runCatching { parse(body, kind) }.getOrDefault(emptyList())
                    // Stale-response guard: discard if user has since typed more.
                    handler.post { if (query == lastQuery) onResult(list) }
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun suggestUrl(query: String, kind: SearchEngineKind): String? {
        val q = URLEncoder.encode(query, "utf-8")
        return when (kind) {
            SearchEngineKind.GOOGLE     -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
            SearchEngineKind.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$q&type=list"
            // Fix 3: osjson.aspx is the standard OpenSearch suggestions endpoint
            // that Firefox and Chrome use for Bing (same format as Google/DDG).
            // api.bing.com/qsonhs.aspx is a legacy endpoint with a different
            // proprietary JSON structure that is no longer reliable.
            SearchEngineKind.BING       -> "https://www.bing.com/osjson.aspx?query=$q"
            SearchEngineKind.BRAVE      -> "https://search.brave.com/api/suggest?q=$q"
            SearchEngineKind.CUSTOM     ->
                // Fallback to Google's open suggestions endpoint for custom engines.
                "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
        }
    }

    private fun parse(body: String, kind: SearchEngineKind): List<String> =
        // All supported engines now return OpenSearch format:
        // ["query", ["suggestion1", "suggestion2", ...], ...]
        parseOpenSearch(body).take(MAX_SUGGESTIONS)

    /**
     * OpenSearch suggestion format (Google, DuckDuckGo type=list, Bing osjson, Brave):
     *   ["query", ["suggestion1", "suggestion2", ...], ...]
     *
     * Documented references:
     * - Google:     https://suggestqueries.google.com/complete/search (client=firefox)
     * - DuckDuckGo: https://duckduckgo.com/opensearch.xml (type=list param)
     * - Bing:       https://www.bing.com/profile/search (osjson.aspx endpoint)
     * - Brave:      https://search.brave.com/api/suggest
     */
    private fun parseOpenSearch(body: String): List<String> {
        val root = JSONArray(body)
        val arr  = root.getJSONArray(1)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val DEBOUNCE_MS     = 300L
        private const val MAX_SUGGESTIONS = 5
    }
}
