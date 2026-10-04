package nl.vanvrouwerff.iptv.data.repo

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
            val result = withContext(Dispatchers.IO) { runCatchingCancellable { refresh(force, onCatalogueReady) } }
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
                runCatchingCancellable {
                    val config = settings.sourceConfig.first() ?: return@runCatchingCancellable
                    val filter = settings.categoryFilter.first()
                    writeEpg(repository(config, filter))
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
        val filter = settings.categoryFilter.first()
        val repo = repository(config, filter)
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

                // Keep the original global order (Live -> Movies -> Series) even though
                // partitions are now committed independently.
                val base = when (section.type) {
                    ContentType.TV -> 0
                    ContentType.MOVIE -> 1_000_000
                    ContentType.SERIES -> 2_000_000
                }
                val entities = section.channels.mapIndexed { i, c -> c.toEntity(base + i) }
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
            epgMutex.withLock { runCatchingCancellable { writeEpg(repo) } }
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

    private fun repository(config: SourceConfig, categoryFilter: String): PlaylistRepository = when (config) {
        is SourceConfig.M3u -> M3uPlaylistRepository(config.url, HttpClient.okHttp)
        is SourceConfig.Xtream -> XtreamPlaylistRepository(
            config.host,
            config.username,
            config.password,
            CategoryFilter.parse(categoryFilter),
        )
    }

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
