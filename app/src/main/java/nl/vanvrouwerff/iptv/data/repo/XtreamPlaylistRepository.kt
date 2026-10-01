package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.epg.XmltvParser
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.xtream.CategoryFilter
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi
import nl.vanvrouwerff.iptv.data.xtream.XtreamUrls
import nl.vanvrouwerff.iptv.data.xtream.XtreamCategory
import nl.vanvrouwerff.iptv.data.xtream.XtreamLiveStream
import nl.vanvrouwerff.iptv.data.xtream.XtreamSeries
import nl.vanvrouwerff.iptv.data.xtream.XtreamVodStream
import okhttp3.ResponseBody

class XtreamPlaylistRepository(
    private val host: String,
    private val username: String,
    private val password: String,
    private val categoryFilter: CategoryFilter = CategoryFilter(emptyList()),
) : PlaylistRepository {

    private val api: XtreamApi = HttpClient.retrofitFor(host).create(XtreamApi::class.java)
    private val json: Json get() = HttpClient.json

    private fun <T> applyFilter(items: List<T>, category: (T) -> String?, label: String): List<T> {
        if (!categoryFilter.isEnabled) return items
        val kept = items.filter { categoryFilter.accepts(category(it)) }
        if (kept.isEmpty() && items.isNotEmpty()) {
            Log.w(TAG, "$label: category filter matched nothing, keeping all ${items.size}")
            return items
        }
        return kept
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
        onSectionReady: suspend (PlaylistSectionResult) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        onProgress(ImportProgress(ImportProgress.Stage.Downloading, 0))

        // Sequential on purpose: VOD/series responses can be tens of MB on TV boxes.
        // Live is required and retried. If it still fails, nothing is committed and the
        // local known-good catalogue remains intact.
        val live = retryProviderCall("Xtream Live") {
            val cats = api.getLiveCategories(username, password)
            val streams = api.getLiveStreams(username, password)
            mapLive(cats, streams)
        }
        Log.i(TAG, "Live: ${live.size} channels")
        onProgress(ImportProgress(ImportProgress.Stage.Live, live.size))
        onSectionReady(PlaylistSectionResult(ContentType.TV, live))

        var vodError: String? = null
        val vod = runCatching {
            retryProviderCall("Xtream VOD") {
                val cats = api.getVodCategories(username, password)
                val streams = api.getVodStreamsStream(username, password)
                    .useStream { ListSerializer(XtreamVodStream.serializer()).decodeFrom(it) }
                mapVod(cats, streams)
            }
        }.fold(
            onSuccess = { mapped ->
                Log.i(TAG, "VOD: ${mapped.size} movies")
                onProgress(ImportProgress(ImportProgress.Stage.Movies, mapped.size))
                onSectionReady(PlaylistSectionResult(ContentType.MOVIE, mapped))
                mapped
            },
            onFailure = { error ->
                vodError = safeError(error)
                Log.e(TAG, "VOD fetch/decode failed; preserving known-good movies", error)
                onSectionReady(PlaylistSectionResult(ContentType.MOVIE, error = vodError))
                emptyList()
            },
        )

        var seriesError: String? = null
        val series = runCatching {
            retryProviderCall("Xtream Series") {
                val cats = api.getSeriesCategories(username, password)
                val list = api.getSeriesStream(username, password)
                    .useStream { ListSerializer(XtreamSeries.serializer()).decodeFrom(it) }
                mapSeries(cats, list)
            }
        }.fold(
            onSuccess = { mapped ->
                Log.i(TAG, "Series: ${mapped.size} shows")
                onProgress(ImportProgress(ImportProgress.Stage.Series, mapped.size))
                onSectionReady(PlaylistSectionResult(ContentType.SERIES, mapped))
                mapped
            },
            onFailure = { error ->
                seriesError = safeError(error)
                Log.e(TAG, "Series fetch/decode failed; preserving known-good series", error)
                onSectionReady(PlaylistSectionResult(ContentType.SERIES, error = seriesError))
                emptyList()
            },
        )

        val keptEpgIds = live.mapNotNullTo(HashSet()) { it.epgChannelId }
        val programmes: List<ProgrammeEntity> = runCatching {
            retryProviderCall("Xtream EPG", longArrayOf(1_500L)) {
                api.getXmltv(username, password).useStream { stream ->
                    XmltvParser.parse(stream) { key -> key in keptEpgIds }
                }
            }
        }.onSuccess {
            Log.i(TAG, "EPG: ${it.size} programmes")
        }.onFailure {
            // EPG never invalidates catalogue data.
            Log.e(TAG, "EPG fetch/parse failed; keeping existing EPG", it)
        }.getOrElse { emptyList() }

        PlaylistSnapshot(channels = live + vod + series, programmes = programmes).also {
            if (vodError != null || seriesError != null) {
                Log.w(TAG, "Partial Xtream refresh completed with preserved partitions")
            }
        }
    }

    override suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity> =
        withContext(Dispatchers.IO) {
            retryProviderCall("Xtream EPG", longArrayOf(1_500L)) {
                api.getXmltv(username, password).useStream { stream ->
                    XmltvParser.parse(stream) { key -> key in epgKeys }
                }
            }
        }

    private fun safeError(t: Throwable): String = HttpClient.redact(
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName,
    ).take(180)

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> kotlinx.serialization.DeserializationStrategy<T>.decodeFrom(stream: java.io.InputStream): T =
        json.decodeFromStream(this, stream)

    private inline fun <T> ResponseBody.useStream(block: (java.io.InputStream) -> T): T =
        use { body -> body.byteStream().use(block) }

    private fun mapLive(categories: List<XtreamCategory>, streams: List<XtreamLiveStream>): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        val groupOf = { s: XtreamLiveStream -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(streams, groupOf, "Live").map { s ->
            val streamId = s.streamId.asScalarString()
            Channel(
                id = "xt-live:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupOf(s),
                streamUrl = XtreamUrls.stream(host, "live", username, password, "$streamId.ts"),
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                type = ContentType.TV,
                archiveDays = if (s.tvArchive?.asScalarString() == "1") {
                    s.tvArchiveDuration?.asScalarString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                } else 0,
            )
        }
    }

    private fun mapVod(categories: List<XtreamCategory>, streams: List<XtreamVodStream>): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        val groupOf = { s: XtreamVodStream -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(streams, groupOf, "VOD").map { s ->
            val streamId = s.streamId.asScalarString()
            val ext = s.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
            Channel(
                id = "xt-vod:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupOf(s),
                streamUrl = XtreamUrls.stream(host, "movie", username, password, "$streamId.$ext"),
                epgChannelId = null,
                type = ContentType.MOVIE,
            )
        }
    }

    private fun mapSeries(categories: List<XtreamCategory>, series: List<XtreamSeries>): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        val groupOf = { s: XtreamSeries -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(series, groupOf, "Series").map { s ->
            val seriesId = s.seriesId.asScalarString()
            Channel(
                id = "xt-series:$seriesId",
                name = s.name,
                logoUrl = s.cover?.takeIf { it.isNotBlank() },
                groupTitle = groupOf(s),
                streamUrl = null,
                epgChannelId = null,
                type = ContentType.SERIES,
            )
        }
    }

    private fun JsonElement.asScalarString(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')

    private companion object { const val TAG = "XtreamRepo" }
}
