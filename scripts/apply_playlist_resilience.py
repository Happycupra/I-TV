#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def write(rel: str, content: str):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    print("wrote", rel)

def patch(rel: str, old: str, new: str):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if new in text:
        print("already patched", rel)
        return
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("patched", rel)

write("app/src/main/java/nl/vanvrouwerff/iptv/data/repo/PlaylistRepository.kt", r'''package nl.vanvrouwerff.iptv.data.repo

import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity

/** One independently refreshable catalogue partition. */
data class PlaylistSectionResult(
    val type: ContentType,
    val channels: List<Channel> = emptyList(),
    val error: String? = null,
) {
    val successful: Boolean get() = error == null
}

data class PlaylistSnapshot(
    val channels: List<Channel>,
    /** EPG programmes, when the source provides them. Best-effort and independent. */
    val programmes: List<ProgrammeEntity> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
)

/** What a running import has done so far, for progressive loading/status UI. */
data class ImportProgress(val stage: Stage, val count: Int) {
    enum class Stage { Downloading, Live, Movies, Series, Saving }
}

interface PlaylistRepository {
    suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit = {},
        /** Called as soon as a partition is available, so Live can land before VOD/series. */
        onSectionReady: suspend (PlaylistSectionResult) -> Unit = {},
    ): PlaylistSnapshot

    /** Only the EPG, for channels whose key is in [epgKeys]; null when the source has none. */
    suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity>? = null
}
''')

write("app/src/main/java/nl/vanvrouwerff/iptv/data/repo/PlaylistResilience.kt", r'''package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Pure decision used before replacing a known-good catalogue partition. */
data class SectionDecision(val accept: Boolean, val reason: String? = null)

object PlaylistResilience {
    private const val MIN_BASELINE_FOR_RATIO_GUARD = 20
    private const val MIN_RETAINED_PERCENT = 25

    /**
     * Same source: never replace a non-empty known-good partition with zero or a sudden
     * >75% collapse. A deliberately changed source is authoritative and may be smaller.
     */
    fun assessSection(existingCount: Int, incomingCount: Int, sourceChanged: Boolean): SectionDecision {
        if (sourceChanged) return SectionDecision(true)
        if (existingCount <= 0) return SectionDecision(true)
        if (incomingCount <= 0) {
            return SectionDecision(false, "0 statt $existingCount Einträgen")
        }
        if (existingCount >= MIN_BASELINE_FOR_RATIO_GUARD &&
            incomingCount * 100 < existingCount * MIN_RETAINED_PERCENT
        ) {
            return SectionDecision(false, "$incomingCount statt $existingCount Einträgen")
        }
        return SectionDecision(true)
    }

    fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code >= 500
}

/** Short foreground retry budget; WorkManager provides the longer retry horizon afterwards. */
internal suspend fun <T> retryProviderCall(
    label: String,
    delaysMs: LongArray = longArrayOf(1_500L, 5_000L),
    block: suspend () -> T,
): T {
    var last: Throwable? = null
    for (attempt in 0..delaysMs.size) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            last = t
            val retryable = when (t) {
                is HttpException -> PlaylistResilience.isRetryableHttp(t.code())
                is IOException -> true
                is SerializationException -> true // truncated/half-written provider JSON
                else -> false
            }
            if (!retryable || attempt >= delaysMs.size) throw t
            val waitMs = delaysMs[attempt]
            Log.w("PlaylistRetry", "$label failed; retry ${attempt + 1}/${delaysMs.size} in ${waitMs}ms")
            delay(waitMs)
        }
    }
    throw last ?: IllegalStateException("Provider request failed")
}
''')

write("app/src/main/java/nl/vanvrouwerff/iptv/data/repo/M3uPlaylistRepository.kt", r'''package nl.vanvrouwerff.iptv.data.repo

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
''')

write("app/src/main/java/nl/vanvrouwerff/iptv/data/repo/XtreamPlaylistRepository.kt", r'''package nl.vanvrouwerff.iptv.data.repo

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
''')

write("app/src/main/java/nl/vanvrouwerff/iptv/data/repo/PlaylistRefreshUseCase.kt", r'''package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.CategoryEntity
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.toEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SettingsStore
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.xtream.CategoryFilter
import java.security.MessageDigest

/**
 * Resilient catalogue coordinator. Each content type is committed independently, suspicious
 * collapses are rejected, and provider failures leave the last known-good rows untouched.
 */
class PlaylistRefreshUseCase(
    private val settings: SettingsStore,
    private val dao: ChannelDao,
    private val onCatalogueChanged: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val epgMutex = Mutex()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    suspend operator fun invoke(force: Boolean = false, onCatalogueReady: () -> Unit = {}): Result<Unit> {
        if (!mutex.tryLock()) {
            mutex.withLock { }
            onCatalogueReady()
            return _lastError.value?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(Unit)
        }
        try {
            _refreshing.value = true
            _lastError.value = null
            val result = withContext(Dispatchers.IO) { runCatching { refresh(force, onCatalogueReady) } }
            result.exceptionOrNull()?.let { err ->
                val safe = safeError(err)
                Log.w(TAG, "Refresh failed; known-good catalogue retained where possible", err)
                _lastError.value = safe
                if (err !is PartialRefreshException) settings.markProviderFailure(safe)
            }
            return result
        } finally {
            _refreshing.value = false
            _progress.value = null
            mutex.unlock()
        }
    }

    suspend fun refreshEpg(): Result<Unit> {
        if (mutex.isLocked || !epgMutex.tryLock()) return Result.success(Unit)
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    val config = settings.sourceConfig.first() ?: return@runCatching
                    writeEpg(repository(config))
                }.onFailure { Log.w(TAG, "EPG refresh failed; existing EPG retained", it) }
            }
        } finally { epgMutex.unlock() }
    }

    private suspend fun writeEpg(repo: PlaylistRepository) {
        val keys = dao.liveEpgKeys().toHashSet()
        if (keys.isEmpty()) return
        val programmes = repo.fetchProgrammes(keys) ?: return
        if (programmes.isEmpty()) return
        dao.replaceProgrammes(programmes)
        settings.markEpgRefresh()
        Log.i(TAG, "EPG refreshed: ${programmes.size} programmes")
    }

    private suspend fun refresh(force: Boolean, onCatalogueReady: () -> Unit) {
        val config = settings.sourceConfig.first() ?: error("Keine Quelle konfiguriert.")
        val repo = repository(config)
        val currentSourceKey = sourceKey(config)
        val storedSourceKey = settings.catalogueSourceKey.first()
        val sourceChanged = storedSourceKey.isNotBlank() && storedSourceKey != currentSourceKey

        val etag = if (force) null else settings.playlistEtag.first()
        val lastMod = if (force) null else settings.playlistLastModified.first()
        val sectionErrors = mutableListOf<String>()
        var readySignalled = false
        var acceptedAny = false

        fun signalReady() {
            if (!readySignalled) {
                readySignalled = true
                onCatalogueReady()
            }
        }

        val snapshot = repo.fetch(
            etag = etag,
            lastModified = lastMod,
            onProgress = { _progress.value = it },
            onSectionReady = { section ->
                if (!section.successful) {
                    sectionErrors += "${label(section.type)}: ${section.error ?: "Fehler"}"
                    return@fetch
                }
                val existing = dao.channelCountByType(section.type.name)
                val decision = PlaylistResilience.assessSection(existing, section.channels.size, sourceChanged)
                if (!decision.accept) {
                    val reason = "${label(section.type)}: verdächtige Antwort (${decision.reason}); alter Stand bleibt"
                    Log.w(TAG, reason)
                    sectionErrors += reason
                    return@fetch
                }

                val entities = section.channels.mapIndexed { i, c -> c.toEntity(i) }
                val categories = section.channels
                    .mapNotNull { c -> c.groupTitle?.let { it to c.type.name } }
                    .distinct()
                    .mapIndexed { i, (name, type) -> CategoryEntity(name, name, i, type) }
                _progress.value = ImportProgress(ImportProgress.Stage.Saving, entities.size)
                dao.replaceType(section.type.name, entities, categories)
                settings.markSectionSuccess(section.type.name, entities.size)
                acceptedAny = true
                onCatalogueChanged()
                if (section.type == ContentType.TV || !readySignalled) signalReady()
                Log.i(TAG, "Committed ${section.type}: ${entities.size} rows; old other partitions untouched")
            },
        )

        if (snapshot.notModified) {
            settings.markRefreshSuccess()
            settings.markProviderRecovered()
            signalReady()
            epgMutex.withLock { runCatching { writeEpg(repo) } }
            return
        }

        if (acceptedAny) {
            settings.savePlaylistValidators(snapshot.etag, snapshot.lastModified)
            settings.setCatalogueVersion(CATALOGUE_VERSION)
            settings.setCatalogueSourceKey(currentSourceKey)
        }
        signalReady()

        if (snapshot.programmes.isNotEmpty()) {
            dao.replaceProgrammes(snapshot.programmes)
            settings.markEpgRefresh()
        }

        if (sectionErrors.isEmpty()) {
            settings.markRefreshSuccess()
            settings.markProviderRecovered()
        } else {
            val summary = sectionErrors.joinToString(" · ").take(300)
            settings.markProviderFailure(summary)
            throw PartialRefreshException(summary)
        }
    }

    private fun repository(config: SourceConfig): PlaylistRepository = when (config) {
        is SourceConfig.M3u -> M3uPlaylistRepository(config.url, HttpClient.okHttp)
        is SourceConfig.Xtream -> XtreamPlaylistRepository(
            config.host,
            config.username,
            config.password,
            CategoryFilter.parse(runBlockingCategoryFilter()),
        )
    }

    /** Repository construction is synchronous; category filter is cached through a one-shot Flow read. */
    private fun runBlockingCategoryFilter(): String = kotlinx.coroutines.runBlocking { settings.categoryFilter.first() }

    private fun sourceKey(config: SourceConfig): String {
        val raw = when (config) {
            is SourceConfig.M3u -> "m3u|${config.url.trim()}"
            is SourceConfig.Xtream -> "xtream|${config.host.trim().trimEnd('/')}|${config.username.trim()}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun safeError(t: Throwable): String = HttpClient.redact(
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName,
    ).take(300)

    private fun label(type: ContentType): String = when (type) {
        ContentType.TV -> "Live"
        ContentType.MOVIE -> "Filme"
        ContentType.SERIES -> "Serien"
    }

    private class PartialRefreshException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "PlaylistRefresh"
        const val CATALOGUE_VERSION = 16
        const val EPG_MAX_AGE_MS: Long = 6L * 3_600_000L
    }
}
''')

# ChannelDao: independent per-type replacement without schema changes.
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/data/db/ChannelDao.kt",
    '''    @Query("DELETE FROM channels")\n    suspend fun clearChannels()\n\n    @Query("DELETE FROM categories")\n    suspend fun clearCategories()\n''',
    '''    @Query("DELETE FROM channels")\n    suspend fun clearChannels()\n\n    @Query("DELETE FROM channels WHERE type = :type")\n    suspend fun clearChannelsByType(type: String)\n\n    @Query("SELECT COUNT(*) FROM channels WHERE type = :type")\n    suspend fun channelCountByType(type: String): Int\n\n    @Query("DELETE FROM categories")\n    suspend fun clearCategories()\n\n    @Query("DELETE FROM categories WHERE type = :type")\n    suspend fun clearCategoriesByType(type: String)\n''',
)
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/data/db/ChannelDao.kt",
    '''    @Query("SELECT id, addedAt FROM channels")\n    suspend fun getAddedAtSnapshot(): List<ChannelAddedAtRow>\n''',
    '''    @Query("SELECT id, addedAt FROM channels")\n    suspend fun getAddedAtSnapshot(): List<ChannelAddedAtRow>\n\n    @Query("SELECT id, addedAt FROM channels WHERE type = :type")\n    suspend fun getAddedAtSnapshotByType(type: String): List<ChannelAddedAtRow>\n\n    @Transaction\n    suspend fun replaceType(type: String, channels: List<ChannelEntity>, categories: List<CategoryEntity>) {\n        val now = System.currentTimeMillis()\n        val existing = getAddedAtSnapshotByType(type).associate { it.id to it.addedAt }\n        val firstImportForType = existing.isEmpty()\n        val merged = channels.map { ch ->\n            existing[ch.id]?.let { ch.copy(addedAt = it) }\n                ?: ch.copy(addedAt = if (firstImportForType) 0L else now)\n        }\n        clearChannelsByType(type)\n        clearCategoriesByType(type)\n        insertCategories(categories)\n        merged.chunked(1000).forEach { insertChannels(it) }\n    }\n''',
)

# SettingsStore provider watchdog / last-known-good metadata.
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/data/settings/SettingsStore.kt",
    '''    val lastEpgRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_EPG_REFRESH_AT] ?: 0L }\n''',
    '''    val lastLiveRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_LIVE_REFRESH_AT] ?: 0L }\n    val lastMoviesRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_MOVIES_REFRESH_AT] ?: 0L }\n    val lastSeriesRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_SERIES_REFRESH_AT] ?: 0L }\n    val lastLiveCount: Flow<Int> = context.dataStore.data.map { it[LAST_LIVE_COUNT] ?: 0 }\n    val lastMoviesCount: Flow<Int> = context.dataStore.data.map { it[LAST_MOVIES_COUNT] ?: 0 }\n    val lastSeriesCount: Flow<Int> = context.dataStore.data.map { it[LAST_SERIES_COUNT] ?: 0 }\n    val providerFailureStreak: Flow<Int> = context.dataStore.data.map { it[PROVIDER_FAILURE_STREAK] ?: 0 }\n    val lastProviderError: Flow<String> = context.dataStore.data.map { it[LAST_PROVIDER_ERROR].orEmpty() }\n    val catalogueSourceKey: Flow<String> = context.dataStore.data.map { it[CATALOGUE_SOURCE_KEY].orEmpty() }\n\n    suspend fun markSectionSuccess(type: String, count: Int, nowMs: Long = System.currentTimeMillis()) {\n        context.dataStore.edit { prefs ->\n            when (type) {\n                "TV" -> { prefs[LAST_LIVE_REFRESH_AT] = nowMs; prefs[LAST_LIVE_COUNT] = count }\n                "MOVIE" -> { prefs[LAST_MOVIES_REFRESH_AT] = nowMs; prefs[LAST_MOVIES_COUNT] = count }\n                "SERIES" -> { prefs[LAST_SERIES_REFRESH_AT] = nowMs; prefs[LAST_SERIES_COUNT] = count }\n            }\n        }\n    }\n\n    suspend fun markProviderFailure(error: String) {\n        context.dataStore.edit { prefs ->\n            prefs[PROVIDER_FAILURE_STREAK] = ((prefs[PROVIDER_FAILURE_STREAK] ?: 0) + 1).coerceAtMost(999)\n            prefs[LAST_PROVIDER_ERROR] = error.take(300)\n        }\n    }\n\n    suspend fun markProviderRecovered() {\n        context.dataStore.edit { prefs ->\n            prefs[PROVIDER_FAILURE_STREAK] = 0\n            prefs.remove(LAST_PROVIDER_ERROR)\n        }\n    }\n\n    suspend fun setCatalogueSourceKey(value: String) {\n        context.dataStore.edit { prefs -> prefs[CATALOGUE_SOURCE_KEY] = value }\n    }\n\n    val lastEpgRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_EPG_REFRESH_AT] ?: 0L }\n''',
)
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/data/settings/SettingsStore.kt",
    '''        val LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")\n        val LAST_EPG_REFRESH_AT = longPreferencesKey("last_epg_refresh_at")\n''',
    '''        val LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")\n        val LAST_LIVE_REFRESH_AT = longPreferencesKey("last_live_refresh_at")\n        val LAST_MOVIES_REFRESH_AT = longPreferencesKey("last_movies_refresh_at")\n        val LAST_SERIES_REFRESH_AT = longPreferencesKey("last_series_refresh_at")\n        val LAST_LIVE_COUNT = intPreferencesKey("last_live_count")\n        val LAST_MOVIES_COUNT = intPreferencesKey("last_movies_count")\n        val LAST_SERIES_COUNT = intPreferencesKey("last_series_count")\n        val PROVIDER_FAILURE_STREAK = intPreferencesKey("provider_failure_streak")\n        val LAST_PROVIDER_ERROR = stringPreferencesKey("last_provider_error")\n        val CATALOGUE_SOURCE_KEY = stringPreferencesKey("catalogue_source_key")\n        val LAST_EPG_REFRESH_AT = longPreferencesKey("last_epg_refresh_at")\n''',
)

write("app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/HomeSoftResetButton.kt", r'''package nl.vanvrouwerff.iptv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R

/** Compact provider-health indicator + safe recovery action beside the I-TV name. */
@Composable
fun HomeSoftResetButton() {
    val app = IptvApp.get()
    val context = LocalContext.current
    val refreshing by app.refreshUseCase.refreshing.collectAsState()
    val progress by app.refreshUseCase.progress.collectAsState()
    val failureStreak by app.settings.providerFailureStreak.collectAsState(initial = 0)
    val lastError by app.settings.lastProviderError.collectAsState(initial = "")
    val liveCount by app.settings.lastLiveCount.collectAsState(initial = 0)
    val movieCount by app.settings.lastMoviesCount.collectAsState(initial = 0)
    val seriesCount by app.settings.lastSeriesCount.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = {
            val stage = progress?.let { "${it.stage}: ${it.count}" } ?: ""
            val detail = context.getString(
                R.string.playlist_health_detail,
                liveCount,
                movieCount,
                seriesCount,
                failureStreak,
                if (lastError.isBlank()) context.getString(R.string.playlist_health_no_error) else lastError,
                stage,
            )
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
        }) {
            Text(
                text = when {
                    refreshing || running -> stringResource(R.string.playlist_health_refreshing)
                    failureStreak > 0 && liveCount + movieCount + seriesCount > 0 -> stringResource(R.string.playlist_health_cached)
                    failureStreak > 0 -> stringResource(R.string.playlist_health_problem)
                    liveCount + movieCount + seriesCount > 0 -> stringResource(R.string.playlist_health_online)
                    else -> stringResource(R.string.playlist_health_ready)
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        Button(
            enabled = !refreshing && !running,
            onClick = {
                if (refreshing || running) return@Button
                running = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { app.clearTransientCaches() }
                        app.refreshUseCase(force = true).getOrThrow()
                    }
                    Toast.makeText(
                        context,
                        context.getString(
                            if (result.isSuccess) R.string.settings_soft_reset_done
                            else R.string.playlist_recovery_preserved,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    running = false
                }
            },
        ) {
            Text(
                text = if (running || refreshing) stringResource(R.string.status_refreshing)
                else stringResource(R.string.settings_soft_reset),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
''')

write("app/src/main/res/values/strings_resilience.xml", r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="playlist_health_online">● Online</string>
    <string name="playlist_health_cached">● Gespeicherter Stand</string>
    <string name="playlist_health_problem">● Anbieter-Störung</string>
    <string name="playlist_health_refreshing">● Aktualisierung…</string>
    <string name="playlist_health_ready">● Bereit</string>
    <string name="playlist_health_no_error">kein Fehler</string>
    <string name="playlist_health_detail">Live: %1$d · Filme: %2$d · Serien: %3$d\nFehlerfolge: %4$d\nLetzter Fehler: %5$s\n%6$s</string>
    <string name="playlist_recovery_preserved">Anbieter nicht vollständig erreichbar. Gespeicherter Stand bleibt erhalten.</string>
</resources>
''')

# Player: three staggered silent retries instead of one.
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt",
    '''                    autoRetryJob = lifecycleScope.launch {\n                        delay(AUTO_RETRY_DELAY_MS)\n''',
    '''                    autoRetryJob = lifecycleScope.launch {\n                        val retryDelayMs = AUTO_RETRY_DELAYS_MS.getOrElse(autoRetryCount - 1) { AUTO_RETRY_DELAYS_MS.last() }\n                        delay(retryDelayMs)\n''',
)
patch(
    "app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt",
    '''        /** Silent-retry budget for transient IO errors before showing the overlay. */\n        private const val MAX_AUTO_RETRY = 1\n        private const val AUTO_RETRY_DELAY_MS = 1_500L\n''',
    '''        /** Silent-retry budget for transient IO errors before showing the overlay. */\n        private const val MAX_AUTO_RETRY = 3\n        private val AUTO_RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L, 9_000L)\n''',
)

write("app/src/test/java/nl/vanvrouwerff/iptv/data/repo/PlaylistResilienceTest.kt", r'''package nl.vanvrouwerff.iptv.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistResilienceTest {
    @Test fun `empty provider response cannot wipe known good section`() {
        assertFalse(PlaylistResilience.assessSection(1500, 0, sourceChanged = false).accept)
    }

    @Test fun `catastrophic drop is rejected for same source`() {
        assertFalse(PlaylistResilience.assessSection(1500, 200, sourceChanged = false).accept)
    }

    @Test fun `normal catalogue change is accepted`() {
        assertTrue(PlaylistResilience.assessSection(1500, 1300, sourceChanged = false).accept)
    }

    @Test fun `intentional source change may be much smaller`() {
        assertTrue(PlaylistResilience.assessSection(1500, 50, sourceChanged = true).accept)
    }

    @Test fun `retryable http codes include throttling and server failures`() {
        assertTrue(PlaylistResilience.isRetryableHttp(429))
        assertTrue(PlaylistResilience.isRetryableHttp(503))
        assertFalse(PlaylistResilience.isRetryableHttp(401))
        assertFalse(PlaylistResilience.isRetryableHttp(404))
    }
}
''')

print("playlist resilience patch complete")
