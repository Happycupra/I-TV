package nl.vanvrouwerff.iptv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.m3u.M3uParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class M3uPlaylistRepository(
    private val url: String,
    private val httpClient: OkHttpClient,
) : PlaylistRepository {

    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
        onSectionReady: suspend (PlaylistSectionResult) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        retryProviderCall("M3U") {
            onProgress(ImportProgress(ImportProgress.Stage.Downloading, 0))
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (etag != null) header("If-None-Match", etag)
                    if (lastModified != null) header("If-Modified-Since", lastModified)
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    return@retryProviderCall PlaylistSnapshot(channels = emptyList(), notModified = true)
                }
                if (!response.isSuccessful) {
                    if (PlaylistResilience.isRetryableHttp(response.code)) {
                        throw IOException("Playlist HTTP ${response.code}")
                    }
                    error("Playlist HTTP ${response.code}")
                }
                val body = response.body ?: error("Leere Playlist")
                val channels = body.charStream().buffered().useLines { M3uParser.parse(it) }
                ContentType.entries.forEach { type ->
                    val part = channels.filter { it.type == type }
                    onSectionReady(PlaylistSectionResult(type = type, channels = part))
                    val stage = when (type) {
                        ContentType.TV -> ImportProgress.Stage.Live
                        ContentType.MOVIE -> ImportProgress.Stage.Movies
                        ContentType.SERIES -> ImportProgress.Stage.Series
                    }
                    onProgress(ImportProgress(stage, part.size))
                }
                PlaylistSnapshot(
                    channels = channels,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
        }
    }
}
