package nl.vanvrouwerff.iptv.data.epg

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/** XMLTV locations advertised by extended M3U playlists. Relative locations use the final URL. */
object EpgSources {
    private val attributes = Regex(
        "(?:url-tvg|x-tvg-url|tvg-url)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))",
        RegexOption.IGNORE_CASE,
    )
    private val separator = Regex(",(?=\\s*(?:https?://|/|\\.\\.?/))", RegexOption.IGNORE_CASE)

    fun fromM3uHeader(header: String, playlistUrl: String): List<String> {
        if (!header.trimStart('\uFEFF', ' ', '\t').startsWith("#EXTM3U", ignoreCase = true)) return emptyList()
        val base = playlistUrl.toHttpUrlOrNull() ?: return emptyList()
        return attributes.findAll(header).flatMap { match ->
            separator.split(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()).asSequence()
        }.mapNotNull { raw -> base.resolve(raw.trim())?.toString() }
            .distinct().take(MAX_SOURCES).toList()
    }

    /** Some providers return .gz files without a Content-Encoding header. Detect the file itself. */
    fun xmlStream(input: InputStream): InputStream {
        val stream = PushbackInputStream(input.buffered(), 2)
        val first = stream.read()
        val second = stream.read()
        if (second >= 0) stream.unread(second)
        if (first >= 0) stream.unread(first)
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(stream) else stream
    }

    private const val MAX_SOURCES = 8
}
