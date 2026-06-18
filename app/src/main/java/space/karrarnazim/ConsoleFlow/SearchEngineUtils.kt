package space.karrarnazim.ConsoleFlow

import android.net.Uri
import android.webkit.URLUtil
import androidx.annotation.DrawableRes
import java.net.URLEncoder

enum class SearchEngineKind {
    GOOGLE,
    DUCKDUCKGO,
    BING,
    BRAVE,
    CUSTOM
}


private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

fun isLocalhostUrl(input: String?): Boolean {
    val value = input.orEmpty().trim().lowercase()
    if (value.isEmpty()) return false
    val host = runCatching {
        val normalized = if (value.contains("://")) value else "http://$value"
        Uri.parse(normalized).host.orEmpty().lowercase()
    }.getOrDefault("")
    return host in LOCALHOST_HOSTS
}

fun resolveSearchEngineKind(engineUrl: String?): SearchEngineKind {
    val url = engineUrl.orEmpty().lowercase()
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
    return when {
        host.contains("google.") || url.contains("google.com/search") -> SearchEngineKind.GOOGLE
        host.contains("duckduckgo.com") || url.contains("duckduckgo.com/?q=") -> SearchEngineKind.DUCKDUCKGO
        host.contains("bing.com") || url.contains("bing.com/search?q=") -> SearchEngineKind.BING
        host.contains("search.brave.com") || url.contains("search.brave.com/search?q=") -> SearchEngineKind.BRAVE
        else -> SearchEngineKind.CUSTOM
    }
}

fun searchEngineDisplayName(engineUrl: String?): String {
    return when (resolveSearchEngineKind(engineUrl)) {
        SearchEngineKind.GOOGLE -> "Google"
        SearchEngineKind.DUCKDUCKGO -> "DuckDuckGo"
        SearchEngineKind.BING -> "Bing"
        SearchEngineKind.BRAVE -> "Brave"
        SearchEngineKind.CUSTOM -> "Custom"
    }
}

@DrawableRes
fun searchEngineIconRes(engineUrl: String?): Int {
    return when (resolveSearchEngineKind(engineUrl)) {
        SearchEngineKind.GOOGLE -> R.drawable.ic_engine_google
        SearchEngineKind.DUCKDUCKGO -> R.drawable.ic_engine_duckduckgo
        SearchEngineKind.BING -> R.drawable.ic_engine_bing
        SearchEngineKind.BRAVE -> R.drawable.ic_engine_brave
        SearchEngineKind.CUSTOM -> R.drawable.ic_find
    }
}

fun buildSearchUrl(engineUrl: String, query: String): String {
    val encoded = URLEncoder.encode(query, "utf-8")
    val template = engineUrl.trim()
    return when {
        template.contains("%s") -> template.replace("%s", encoded)
        template.contains("{query}") -> template.replace("{query}", encoded)
        template.endsWith("=") || template.endsWith("?") || template.endsWith("&") -> template + encoded
        URLUtil.isValidUrl(template) && !template.contains("?") -> "$template?q=$encoded"
        else -> template + encoded
    }
}
