package nl.vanvrouwerff.iptv.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Credentials extracted from a standard Xtream M3U `get.php` URL. */
data class ParsedXtreamUrl(
    val host: String,
    val username: String,
    val password: String,
)

/**
 * Recognises the long M3U URLs commonly supplied by Xtream providers and converts them
 * into the three values the Xtream API needs. Nothing is logged here because the input
 * contains credentials in its query string.
 */
object XtreamUrlParser {
    fun parse(raw: String): ParsedXtreamUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (!url.pathSegments.lastOrNull().orEmpty().equals("get.php", ignoreCase = true)) return null

        val username = url.queryParameter("username")?.trim().orEmpty()
        val password = url.queryParameter("password").orEmpty()
        if (username.isEmpty() || password.isEmpty()) return null

        // player_api.php normally lives beside get.php. Preserve an optional provider
        // prefix path and any non-default port, but strip the credential query entirely.
        val parentPath = url.encodedPath.substringBeforeLast('/', missingDelimiterValue = "")
        val basePath = if (parentPath.isEmpty()) "/" else "$parentPath/"
        val host = url.newBuilder()
            .query(null)
            .fragment(null)
            .encodedPath(basePath)
            .build()
            .toString()
            .trimEnd('/')

        return ParsedXtreamUrl(host = host, username = username, password = password)
    }
}
