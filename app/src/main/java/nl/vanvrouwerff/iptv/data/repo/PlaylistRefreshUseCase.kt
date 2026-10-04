package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import java.util.Locale

/**
 * Resilient catalogue coordinator. Each content type is committed independently, suspicious
 * collapses are rejected, and provider failures leave the last known-good rows untouched.
 */
class PlaylistRefreshUseCase(
    private val settings: SettingsStore,
    private val dao: ChannelDao,
    private val onCatalogueChanged: () -> Unit = {},
    private val repositoryFactory: (SourceConfig, String) -> PlaylistRepository = ::defaultRepository,
) {
    private val mutex = Mutex()
    private val epgMutex = Mutex()
    @Volatile private var completedCatalogueRuns = 0L
    private var completedCatalogueKey: String? = null
    private var completedCatalogueConfig: SourceConfig? = null
    private var completedCatalogueFilter: String? = null
    private var completedCatalogueResult: Result<Unit> = Result.success(Unit)
    private var completedEpgResult: Result<Unit> = Result.success(Unit)
    private var completedEpgConfig: SourceConfig? = null
    private var completedEpgFilter: String? = null

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()
    private val _refreshingEpg = MutableStateFlow(false)
    val refreshingEpg: StateFlow<Boolean> = _refreshingEpg.asStateFlow()
    private val _lastEpgError = MutableStateFlow<String?>(null)
    val lastEpgError: StateFlow<String?> = _lastEpgError.asStateFlow()

    suspend operator fun invoke(force: Boolean = false, onCatalogueReady: () -> Unit = {}): Result<Unit> {
        val runsAtRequest = completedCatalogueRuns
        return mutex.withLock {
            val currentConfig = settings.sourceConfig.first()
            val requestedFilter = settings.categoryFilter.first()
            val requestedKey = currentConfig?.let { catalogueSourceKey(it, requestedFilter) }
            if (completedCatalogueRuns != runsAtRequest && completedCatalogueKey == requestedKey &&
                completedCatalogueConfig == currentConfig && completedCatalogueFilter == requestedFilter) {
                onCatalogueReady()
                return@withLock completedCatalogueResult
            }
            try {
                _refreshing.value = true
                _lastError.value = null
                var completedKey: String? = null
                var importedConfig: SourceConfig? = currentConfig
                var importedFilter = requestedFilter
                val result = withContext(Dispatchers.IO) {
                    runCatchingCancellable {
                        // A settings save can arrive while the previous provider is still downloading.
                        // Discard its pending sections and import the latest configuration in this run.
                        for (attempt in 0 until 3) {
                            val config = settings.sourceConfig.first() ?: error("Keine Quelle konfiguriert.")
                            val filter = settings.categoryFilter.first()
                            importedConfig = config
                            importedFilter = filter
                            try {
                                refresh(force, config, filter, onCatalogueReady)
                                completedKey = catalogueSourceKey(config, filter)
                                return@runCatchingCancellable
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                val changed = settings.sourceConfig.first() != config || settings.categoryFilter.first() != filter
                                if (!changed) throw error
                                if (attempt == 2) throw SourceConfigurationChangedException()
                            }
                        }
                    }
                }
                result.exceptionOrNull()?.let { err ->
                    val safe = safeError(err)
                    Log.w(TAG, "Refresh failed; known-good catalogue retained where possible", err)
                    _lastError.value = safe
                    if (err !is PartialRefreshException && err !is SourceConfigurationChangedException) settings.markProviderFailure(safe)
                }
                completedCatalogueKey = completedKey ?: requestedKey
                completedCatalogueConfig = importedConfig
                completedCatalogueFilter = importedFilter
                completedCatalogueResult = result
                completedCatalogueRuns++
                result
            } finally {
                _refreshing.value = false
                _progress.value = null
            }
        }
    }

    suspend fun refreshEpg(force: Boolean = false): Result<Unit> {
        if (!epgMutex.tryLock()) {
            epgMutex.withLock { }
            if (completedEpgResult.exceptionOrNull() is CancellationException ||
                completedEpgConfig != settings.sourceConfig.first() || completedEpgFilter != settings.categoryFilter.first()) {
                return refreshEpg(force)
            }
            return completedEpgResult
        }
        return try {
            _refreshingEpg.value = true
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    mutex.withLock {
                        val last = settings.lastEpgRefreshAt.first()
                        val age = System.currentTimeMillis() - last
                        if (!force && last != 0L && age in 0 until EPG_MAX_AGE_MS) return@withLock
                        _lastEpgError.value = null
                        val config = settings.sourceConfig.first() ?: throw EpgUnavailableException("Keine Quelle konfiguriert.")
                        val filter = settings.categoryFilter.first()
                        completedEpgConfig = config
                        completedEpgFilter = filter
                        val catalogueKey = settings.catalogueSourceKey.first()
                        val sameLegacySource = settings.catalogueVersion.first() > 0 &&
                            catalogueKey == legacyCatalogueSourceKey(config)
                        if (catalogueKey.isNotBlank() && catalogueKey != catalogueSourceKey(config, filter) && !sameLegacySource) {
                            error("Bitte zuerst die Senderliste für die geänderte Quelle aktualisieren.")
                        }
                        writeEpg(repositoryFactory(config, filter), config, filter)
                    }
                }.onFailure {
                    _lastEpgError.value = safeError(it)
                    Log.w(TAG, "EPG refresh failed; existing EPG retained")
                }
            }.also { completedEpgResult = it }
        } catch (cancelled: CancellationException) {
            completedEpgResult = Result.failure(cancelled)
            throw cancelled
        } finally {
            _refreshingEpg.value = false
            epgMutex.unlock()
        }
    }

    private suspend fun writeEpg(repo: PlaylistRepository, config: SourceConfig, filter: String) {
        val keys = dao.liveEpgKeys().toHashSet()
        if (keys.isEmpty()) throw EpgUnavailableException("Für diese Sender sind keine EPG-Kennungen vorhanden.")
        val programmes = repo.fetchProgrammes(keys)
            ?: throw EpgUnavailableException("Diese Quelle enthält keine EPG-Adresse (url-tvg/x-tvg-url).")
        if (programmes.isEmpty()) error("Der Anbieter hat keine passenden EPG-Sendungen geliefert. Der bisherige Stand bleibt erhalten.")
        ensureCurrentConfiguration(config, filter)
        dao.replaceProgrammes(programmes)
        ensureCurrentConfiguration(config, filter)
        settings.markEpgRefresh()
        _lastEpgError.value = null
        Log.i(TAG, "EPG refreshed: ${programmes.size} programmes")
    }

    private suspend fun refresh(force: Boolean, config: SourceConfig, filter: String, onCatalogueReady: () -> Unit) {
        val repo = repositoryFactory(config, filter)
        val currentSourceKey = catalogueSourceKey(config, filter)
        val storedSourceKey = settings.catalogueSourceKey.first()
        // Adding the filter to the key is a format upgrade, not an intentional source change.
        // A settings save invalidates the catalogue version, distinguishing an explicit edit.
        val sameLegacySource = settings.catalogueVersion.first() > 0 &&
            storedSourceKey == legacyCatalogueSourceKey(config)
        val sourceChanged = storedSourceKey.isNotBlank() && storedSourceKey != currentSourceKey && !sameLegacySource

        val etag = if (force || sourceChanged) null else settings.playlistEtag.first()
        val lastMod = if (force || sourceChanged) null else settings.playlistLastModified.first()
        val sectionErrors = mutableListOf<String>()
        var readySignalled = false
        var acceptedAny = false
        var liveAccepted = false

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
                ensureCurrentConfiguration(config, filter)
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
                ensureCurrentConfiguration(config, filter)
                settings.markSectionSuccess(section.type.name, entities.size)
                acceptedAny = true
                if (section.type == ContentType.TV) liveAccepted = true
                onCatalogueChanged()
                if (section.type == ContentType.TV || !readySignalled) signalReady()
                Log.i(TAG, "Committed ${section.type}: ${entities.size} rows; old other partitions untouched")
            },
        )
        ensureCurrentConfiguration(config, filter)

        if (snapshot.notModified) {
            settings.markRefreshSuccess()
            settings.markProviderRecovered()
            signalReady()
            runCatchingCancellable { writeEpg(repo, config, filter) }.onFailure {
                if (it is SourceConfigurationChangedException) throw it
                _lastEpgError.value = safeError(it)
            }
            ensureCurrentConfiguration(config, filter)
            return
        }

        if (acceptedAny) {
            settings.savePlaylistValidators(snapshot.etag, snapshot.lastModified)
            settings.setCatalogueVersion(CATALOGUE_VERSION)
            settings.setCatalogueSourceKey(currentSourceKey)
        }
        signalReady()

        if (liveAccepted && snapshot.programmes.isNotEmpty()) {
            ensureCurrentConfiguration(config, filter)
            dao.replaceProgrammes(snapshot.programmes)
            ensureCurrentConfiguration(config, filter)
            settings.markEpgRefresh()
            _lastEpgError.value = null
        } else if (liveAccepted) {
            _lastEpgError.value = snapshot.epgError
                ?: "Der Anbieter hat keine passenden EPG-Sendungen geliefert. Der bisherige Stand bleibt erhalten."
        }
        ensureCurrentConfiguration(config, filter)

        if (sectionErrors.isEmpty()) {
            settings.markRefreshSuccess()
            settings.markProviderRecovered()
        } else {
            val summary = sectionErrors.joinToString(" · ").take(300)
            settings.markProviderFailure(summary)
            throw PartialRefreshException(summary)
        }
    }

    private suspend fun ensureCurrentConfiguration(config: SourceConfig, filter: String) {
        if (settings.sourceConfig.first() != config || settings.categoryFilter.first() != filter) {
            throw SourceConfigurationChangedException()
        }
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
    private class SourceConfigurationChangedException : IllegalStateException("Die Quelle wurde während der Aktualisierung geändert.")

    companion object {
        private const val TAG = "PlaylistRefresh"
        const val CATALOGUE_VERSION = 17
        const val EPG_MAX_AGE_MS: Long = 6L * 3_600_000L

        private fun defaultRepository(config: SourceConfig, categoryFilter: String): PlaylistRepository = when (config) {
            is SourceConfig.M3u -> M3uPlaylistRepository(config.url, HttpClient.okHttp)
            is SourceConfig.Xtream -> XtreamPlaylistRepository(config.host, config.username, config.password, CategoryFilter.parse(categoryFilter))
        }

        internal fun catalogueSourceKey(config: SourceConfig, categoryFilter: String): String {
            val raw = when (config) {
                is SourceConfig.M3u -> "m3u|${config.url.trim()}"
                is SourceConfig.Xtream -> {
                    val filter = categoryFilter.split(',', ';', ' ').map { it.trim().uppercase(Locale.ROOT) }
                        .filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
                    "xtream|${config.host.trim().trimEnd('/')}|${config.username.trim()}|filter=$filter"
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        internal fun legacyCatalogueSourceKey(config: SourceConfig): String {
            val raw = when (config) {
                is SourceConfig.M3u -> "m3u|${config.url.trim()}"
                is SourceConfig.Xtream -> "xtream|${config.host.trim().trimEnd('/')}|${config.username.trim()}"
            }
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
