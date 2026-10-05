package nl.vanvrouwerff.iptv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.util.Log
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.m3u.M3uParser
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.epg.EpgSources
import nl.vanvrouwerff.iptv.data.epg.XmltvParser
import nl.vanvrouwerff.iptv.data.epg.withEpgResponse
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class M3uPlaylistRepository(
    private val url: String,
    private val httpClient: OkHttpClient,
) : PlaylistRepository {
    private var epgUrls: List<String>? = null

    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
        includeEpg: Boolean,
        onSectionReady: suspend (PlaylistSectionResult) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        val snapshot = retryProviderCall("M3U") {
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
                val coroutineContext = currentCoroutineContext()
                val channels = body.charStream().buffered().useLines { lines ->
                    M3uParser.parse(lines.onEach { line ->
                        coroutineContext.ensureActive()
                        if (line.trimStart('\uFEFF', ' ', '\t').startsWith("#EXTM3U", ignoreCase = true)) {
                            epgUrls = EpgSources.fromM3uHeader(line, response.request.url.toString())
                        }
                    })
                }
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
        if (snapshot.notModified || !includeEpg) return@withContext snapshot
        val keys = snapshot.channels.mapNotNullTo(HashSet()) { it.epgChannelId }
        var epgError: String? = null
        val programmes = if (keys.isEmpty()) emptyList() else runCatchingCancellable {
            fetchProgrammes(keys) ?: throw EpgUnavailableException("Diese M3U-Quelle enthält keine EPG-Adresse (url-tvg/x-tvg-url).")
        }.onFailure {
            epgError = HttpClient.redact(it.message ?: "EPG konnte nicht geladen werden").take(300)
            Log.w("M3uRepository", "EPG unavailable; existing EPG retained")
        }.getOrElse { emptyList() }
        snapshot.copy(programmes = programmes, epgError = epgError)
    }

    override suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity>? = withContext(Dispatchers.IO) {
        val urls = epgUrls ?: discoverEpgUrls().also { epgUrls = it }
        if (urls.isEmpty()) return@withContext null
        val context = currentCoroutineContext()
        val programmes = mutableListOf<ProgrammeEntity>()
        // All advertised feeds must succeed before the coordinator replaces the previous snapshot.
        for (epgUrl in urls) {
            context.ensureActive()
            programmes += retryProviderCall("M3U EPG", longArrayOf(1_500L)) {
                httpClient.newCall(Request.Builder().url(epgUrl).build()).withEpgResponse { response ->
                    checkResponse(response.code, response.isSuccessful)
                    val body = response.body ?: error("Leere EPG-Antwort")
                    EpgSources.xmlStream(body.byteStream()).use { stream ->
                        XmltvParser.parse(stream, keepChannel = { it in epgKeys }, checkCancelled = { context.ensureActive() })
                    }
                }
            }
        }
        programmes
    }

    private suspend fun discoverEpgUrls(): List<String> = retryProviderCall("M3U EPG-Adresse") {
        httpClient.newCall(Request.Builder().url(url).build()).withEpgResponse { response ->
            checkResponse(response.code, response.isSuccessful)
            val body = response.body ?: error("Leere Playlist")
            body.charStream().buffered().use { reader ->
                // The extended M3U header precedes the channel entries; do not download the catalogue twice.
                var header: String? = null
                for (index in 0 until 20) {
                    // Header discovery is bounded; the call remains attached to coroutine cancellation.
                    val line = reader.readLine() ?: break
                    if (line.trimStart('\uFEFF', ' ', '\t').startsWith("#EXTM3U", ignoreCase = true)) {
                        header = line
                        break
                    }
                    if (line.startsWith("#EXTINF")) break
                }
                header?.let { EpgSources.fromM3uHeader(it, response.request.url.toString()) }.orEmpty()
            }
        }
    }

    private fun checkResponse(code: Int, successful: Boolean) {
        if (successful) return
        if (PlaylistResilience.isRetryableHttp(code)) throw IOException("EPG HTTP $code")
        error("EPG HTTP $code")
    }
}
