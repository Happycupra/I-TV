package nl.vanvrouwerff.iptv.data.repo

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.IptvDatabase
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.db.toEntity
import nl.vanvrouwerff.iptv.data.settings.SettingsStore
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpgSynchronizationTest {
    private lateinit var db: IptvDatabase
    private lateinit var dao: ChannelDao
    private lateinit var settings: SettingsStore
    private val original = ProgrammeEntity("news", 1_000, 2_000, "Cached programme", null)

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).build()
        dao = db.channelDao()
        settings = SettingsStore(context)
        settings.saveM3u("https://provider.test/original.m3u")
        settings.setCategoryFilter("")
        settings.setCatalogueSourceKey("")
        settings.setExternalEpgUrl("")
        settings.markEpgRefresh(0)
        dao.insertChannels(listOf(channel("news").toEntity(0)))
        dao.insertProgrammes(listOf(original))
    }

    @After fun tearDown() { db.close() }

    @Test fun `saving source and category filter normalizes one configuration and invalidates previous refreshes`() = runBlocking {
        settings.markRefreshSuccess(42)
        settings.markEpgRefresh(42)
        settings.setCatalogueVersion(PlaylistRefreshUseCase.CATALOGUE_VERSION)
        settings.savePlaylistValidators("previous-etag", "previous-date")
        settings.saveSource(SourceConfig.Xtream(" https://provider.test/ ", " user ", "password"), " NL, UK ")
        assertEquals(SourceConfig.Xtream("https://provider.test", "user", "password"), settings.sourceConfig.first())
        assertEquals("NL, UK", settings.categoryFilter.first())
        assertEquals(0L, settings.lastRefreshSuccessAt.first())
        assertEquals(0L, settings.lastEpgRefreshAt.first())
        assertEquals(0, settings.catalogueVersion.first())
        assertEquals(null, settings.playlistEtag.first())
        assertEquals(null, settings.playlistLastModified.first())
    }

    @Test fun `saving the unchanged configuration preserves successful EPG and catalogue refreshes`() = runBlocking {
        val source = settings.sourceConfig.first()!!
        settings.markRefreshSuccess(42)
        settings.markEpgRefresh(43)
        settings.setCatalogueVersion(PlaylistRefreshUseCase.CATALOGUE_VERSION)
        settings.savePlaylistValidators("same-etag", "same-date")
        settings.saveSource(source, "")
        assertEquals(42L, settings.lastRefreshSuccessAt.first())
        assertEquals(43L, settings.lastEpgRefreshAt.first())
        assertEquals(PlaylistRefreshUseCase.CATALOGUE_VERSION, settings.catalogueVersion.first())
        assertEquals("same-etag", settings.playlistEtag.first())
        assertEquals("same-date", settings.playlistLastModified.first())
    }

    @Test fun `failed and empty feeds preserve cached programmes and successful timestamp`() = runBlocking {
        settings.markEpgRefresh(42)
        val repo = FakeRepository().apply { epgFailure = IOException("Connection interrupted") }
        val useCase = coordinator(repo)
        assertTrue(useCase.refreshEpg(force = true).isFailure)
        assertEquals(original, dao.getNowPlayingFor("news", 1_500))
        assertEquals(42L, settings.lastEpgRefreshAt.first())
        repo.epgFailure = null
        repo.epgRows = emptyList()
        assertTrue(useCase.refreshEpg(force = true).isFailure)
        assertEquals(original, dao.getNowPlayingFor("news", 1_500))
        assertEquals(42L, settings.lastEpgRefreshAt.first())
        assertTrue(useCase.lastEpgError.value!!.contains("keine passenden"))
    }

    @Test fun `manual EPG synchronization waits for an active catalogue instead of being skipped`() = runBlocking {
        withTimeout(10_000) {
            val repo = FakeRepository().apply { catalogueGate = CompletableDeferred() }
            val useCase = coordinator(repo)
            val catalogue = async { useCase(force = true) }
            repo.catalogueStarted.await()
            val epg = async(start = CoroutineStart.UNDISPATCHED) { useCase.refreshEpg(force = true) }
            assertFalse(epg.isCompleted)
            assertEquals(0, repo.epgCalls)
            repo.catalogueGate!!.complete(Unit)
            assertTrue(catalogue.await().isSuccess)
            assertTrue(epg.await().isSuccess)
            assertEquals(1, repo.epgCalls)
            assertEquals("Fresh programme", dao.getNowPlayingFor("news", 1_500)?.title)
        }
    }

    @Test fun `concurrent EPG synchronization shares one transfer`() = runBlocking {
        withTimeout(10_000) {
            val repo = FakeRepository().apply { epgGate = CompletableDeferred() }
            val useCase = coordinator(repo)
            val first = async { useCase.refreshEpg(force = true) }
            repo.epgStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { useCase.refreshEpg(force = true) }
            assertFalse(second.isCompleted)
            repo.epgGate!!.complete(Unit)
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
            assertEquals(1, repo.epgCalls)
        }
    }

    @Test fun `source change during download discards old result and imports the latest source once`() = runBlocking {
        withTimeout(10_000) {
            val oldRepo = FakeRepository().apply { catalogueGate = CompletableDeferred(); catalogueRows = listOf(channel("old")) }
            val newRepo = FakeRepository().apply { catalogueRows = listOf(channel("new")) }
            val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { config, _ ->
                if ((config as SourceConfig.M3u).url.endsWith("new.m3u")) newRepo else oldRepo
            })
            val first = async { useCase(force = true) }
            oldRepo.catalogueStarted.await()
            settings.saveM3u("https://provider.test/new.m3u")
            val second = async(start = CoroutineStart.UNDISPATCHED) { useCase(force = true) }
            oldRepo.catalogueGate!!.complete(Unit)
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
            assertEquals(listOf("new"), dao.allChannels().map { it.id })
            assertEquals(1, newRepo.catalogueCalls)
            assertEquals(
                PlaylistRefreshUseCase.catalogueSourceKey(SourceConfig.M3u("https://provider.test/new.m3u"), ""),
                settings.catalogueSourceKey.first(),
            )
        }
    }

    @Test fun `intentional category filtering permits a smaller catalogue`() = runBlocking {
        val source = SourceConfig.Xtream("https://provider.test", "user", "password")
        settings.saveXtream(source.host, source.username, source.password)
        settings.setCatalogueSourceKey(PlaylistRefreshUseCase.catalogueSourceKey(source, ""))
        dao.replaceType("TV", (0 until 100).map { channel("channel-$it").toEntity(it) }, emptyList())
        settings.setCategoryFilter("NL")
        val repo = FakeRepository().apply { catalogueRows = (0 until 20).map { channel("channel-$it") } }
        assertTrue(coordinator(repo)(force = true).isSuccess)
        assertEquals(20, dao.channelCountByType("TV"))
        assertEquals(PlaylistRefreshUseCase.catalogueSourceKey(source, "NL"), settings.catalogueSourceKey.first())
        assertEquals(PlaylistRefreshUseCase.catalogueSourceKey(source, "nl; UK"), PlaylistRefreshUseCase.catalogueSourceKey(source, "UK,NL"))
    }

    @Test fun `upgrading a legacy source key preserves collapse protection until an intentional filter edit`() = runBlocking {
        val source = SourceConfig.Xtream("https://provider.test", "user", "password")
        settings.saveSource(source, "NL")
        settings.setCatalogueSourceKey(PlaylistRefreshUseCase.legacyCatalogueSourceKey(source))
        settings.setCatalogueVersion(16)
        dao.replaceType("TV", (0 until 100).map { channel("channel-$it").toEntity(it) }, emptyList())
        val repo = FakeRepository().apply { catalogueRows = (0 until 10).map { channel("channel-$it") } }
        val useCase = coordinator(repo)

        assertTrue(useCase(force = true).isFailure)
        assertEquals(100, dao.channelCountByType("TV"))
        assertEquals(16, settings.catalogueVersion.first())
        assertTrue(useCase.refreshEpg(force = true).isSuccess)
        assertEquals("Fresh programme", dao.getNowPlayingFor("news", 1_500)?.title)

        settings.setCategoryFilter("UK")
        assertEquals(0, settings.catalogueVersion.first())
        assertTrue(useCase(force = true).isSuccess)
        assertEquals(10, dao.channelCountByType("TV"))
        assertEquals(PlaylistRefreshUseCase.catalogueSourceKey(source, "UK"), settings.catalogueSourceKey.first())
    }

    @Test fun `cancellation never commits a partial EPG refresh`() = runBlocking {
        withTimeout(10_000) {
            settings.markEpgRefresh(42)
            val repo = FakeRepository().apply { epgGate = CompletableDeferred() }
            val useCase = coordinator(repo)
            val pending = async { useCase.refreshEpg(force = true) }
            repo.epgStarted.await()
            pending.cancelAndJoin()
            assertEquals(original, dao.getNowPlayingFor("news", 1_500))
            assertEquals(42L, settings.lastEpgRefreshAt.first())
            assertFalse(useCase.refreshingEpg.value)
        }
    }

    private fun coordinator(repo: PlaylistRepository) = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> repo })

    @Test fun `automatic refresh reuses fresh EPG while manual refresh still downloads`() = runBlocking {
        settings.markEpgRefresh()
        val repo = FakeRepository()
        val useCase = coordinator(repo)
        assertTrue(useCase.refreshEpg().isSuccess)
        assertEquals(0, repo.epgCalls)
        assertTrue(useCase.refreshEpg(force = true).isSuccess)
        assertEquals(1, repo.epgCalls)
    }

    @Test fun `external URL edits invalidate only EPG freshness while identical saves retain it`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/guide.xml")
        settings.markRefreshSuccess(42)
        settings.setCatalogueVersion(17)
        settings.markEpgRefresh(43)
        settings.setExternalEpgUrl(" https://epg.test/guide.xml ")
        assertEquals(43L, settings.lastEpgRefreshAt.first())
        settings.setExternalEpgUrl("https://epg.test/new.xml")
        assertEquals(0L, settings.lastEpgRefreshAt.first())
        assertEquals(42L, settings.lastRefreshSuccessAt.first())
        assertEquals(17, settings.catalogueVersion.first())
    }

    @Test fun `a configuration change during EPG insertion rolls the entire replacement back`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/old.xml")
        var checks = 0
        val result = runCatching {
            dao.replaceProgrammesIfCurrent(listOf(original.copy(title = "Obsolete programme"))) {
                checks++
                if (checks == 2) settings.setExternalEpgUrl("https://epg.test/new.xml")
                check(settings.externalEpgUrl.first() == "https://epg.test/old.xml") { "EPG configuration changed" }
            }
        }
        assertTrue(result.isFailure)
        assertEquals(2, checks)
        assertEquals(original, dao.getNowPlayingFor("news", 1_500))
        assertEquals(0L, settings.lastEpgRefreshAt.first())
    }

    @Test fun `successful EPG timestamps are written only for the current source filter and URL`() = runBlocking {
        val source = settings.sourceConfig.first()!!
        settings.setExternalEpgUrl("https://epg.test/new.xml")
        assertFalse(settings.markEpgRefreshIfCurrent(source, "", "https://epg.test/old.xml", 42))
        assertFalse(settings.markEpgRefreshIfCurrent(source, "NL", "https://epg.test/new.xml", 42))
        assertFalse(settings.markEpgRefreshIfCurrent(SourceConfig.M3u("https://other.test/list.m3u"), "", "https://epg.test/new.xml", 42))
        assertEquals(0L, settings.lastEpgRefreshAt.first())
        assertTrue(settings.markEpgRefreshIfCurrent(source, "", "https://epg.test/new.xml", 43))
        assertEquals(43L, settings.lastEpgRefreshAt.first())
    }

    @Test fun `external URL belongs to its provider but survives a category filter edit`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/guide.xml")
        settings.saveSource(settings.sourceConfig.first()!!, "NL")
        assertEquals("https://epg.test/guide.xml", settings.externalEpgUrl.first())
        settings.saveM3u("https://new-provider.test/list.m3u")
        assertEquals("", settings.externalEpgUrl.first())
    }

    @Test fun `manual external EPG synchronization bypasses the provider feed`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/guide.xml")
        val repo = FakeRepository()
        var externalCalls = 0
        val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> repo }, externalEpgLoader = { url, keys ->
            assertEquals("https://epg.test/guide.xml", url)
            assertEquals(setOf("news"), keys)
            externalCalls++
            listOf(original.copy(title = "External programme"))
        })
        assertTrue(useCase.refreshEpg(force = true).isSuccess)
        assertEquals(0, repo.epgCalls)
        assertEquals(1, externalCalls)
        assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
    }

    @Test fun `catalogue and not-modified refreshes keep the external EPG override authoritative`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/guide.xml")
        val repo = FakeRepository().apply { snapshotProgrammes = listOf(original.copy(title = "Provider programme")) }
        var externalCalls = 0
        val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> repo }, externalEpgLoader = { _, _ ->
            externalCalls++
            listOf(original.copy(title = "External programme"))
        })
        assertTrue(useCase(force = true).isSuccess)
        assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
        assertEquals(listOf(false), repo.includeEpgRequests)
        repo.notModified = true
        assertTrue(useCase().isSuccess)
        assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
        assertEquals(2, externalCalls)
        assertEquals(0, repo.epgCalls)
    }

    @Test fun `an external feed without matching IDs preserves the cached EPG and reports the mismatch`() = runBlocking {
        settings.setExternalEpgUrl("https://epg.test/guide.xml")
        settings.markEpgRefresh(42)
        val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> FakeRepository() }, externalEpgLoader = { _, _ -> emptyList() })
        assertTrue(useCase.refreshEpg(force = true).isFailure)
        assertEquals(original, dao.getNowPlayingFor("news", 1_500))
        assertEquals(42L, settings.lastEpgRefreshAt.first())
        assertTrue(useCase.lastEpgError.value!!.contains("XMLTV-Sender-IDs"))
    }

    @Test fun `changing the external URL during a transfer discards its result and a waiting sync uses the new URL`() = runBlocking {
        withTimeout(10_000) {
            settings.setExternalEpgUrl("https://epg.test/old.xml")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val repo = FakeRepository()
            val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> repo }, externalEpgLoader = { url, _ ->
                if (url.endsWith("old.xml")) {
                    started.complete(Unit)
                    release.await()
                } else {
                    newStarted.complete(Unit)
                    releaseNew.await()
                }
                listOf(original.copy(title = url))
            })
            val old = async { useCase.refreshEpg(force = true) }
            started.await()
            settings.setExternalEpgUrl("https://epg.test/new.xml")
            val current = async(start = CoroutineStart.UNDISPATCHED) { useCase.refreshEpg(force = true) }
            release.complete(Unit)
            assertTrue(old.await().isFailure)
            newStarted.await()
            assertEquals(original, dao.getNowPlayingFor("news", 1_500))
            assertEquals(0L, settings.lastEpgRefreshAt.first())
            releaseNew.complete(Unit)
            assertTrue(current.await().isSuccess)
            assertEquals("https://epg.test/new.xml", dao.getNowPlayingFor("news", 1_500)?.title)
            assertEquals(0, repo.epgCalls)
        }
    }

    @Test fun `saving an external URL during catalogue download prevents provider programmes from overwriting it`() = runBlocking {
        withTimeout(10_000) {
            val repo = FakeRepository().apply {
                catalogueGate = CompletableDeferred()
                snapshotProgrammes = listOf(original.copy(title = "Provider programme"))
            }
            val useCase = PlaylistRefreshUseCase(settings, dao, repositoryFactory = { _, _ -> repo }, externalEpgLoader = { _, _ -> listOf(original.copy(title = "External programme")) })
            val catalogue = async { useCase(force = true) }
            repo.catalogueStarted.await()
            settings.setExternalEpgUrl("https://epg.test/guide.xml")
            repo.catalogueGate!!.complete(Unit)
            assertTrue(catalogue.await().isSuccess)
            assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
        }
    }

    @Test fun `cancelling a waiting external synchronization does not cancel the active transfer`() = runBlocking {
        withTimeout(10_000) {
            settings.setExternalEpgUrl("https://epg.test/guide.xml")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var externalCalls = 0
            val useCase = PlaylistRefreshUseCase(settings, dao, externalEpgLoader = { _, _ ->
                externalCalls++
                started.complete(Unit)
                release.await()
                listOf(original.copy(title = "External programme"))
            })
            val leader = async { useCase.refreshEpg(force = true) }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { useCase.refreshEpg(force = true) }
            waiter.cancelAndJoin()
            assertTrue(useCase.refreshingEpg.value)
            release.complete(Unit)
            assertTrue(leader.await().isSuccess)
            assertEquals(1, externalCalls)
            assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
        }
    }

    @Test fun `a waiting external synchronization takes over if the active caller is cancelled`() = runBlocking {
        withTimeout(10_000) {
            settings.setExternalEpgUrl("https://epg.test/guide.xml")
            val started = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            var externalCalls = 0
            val useCase = PlaylistRefreshUseCase(settings, dao, externalEpgLoader = { _, _ ->
                externalCalls++
                if (externalCalls == 1) {
                    started.complete(Unit)
                    neverReleased.await()
                }
                listOf(original.copy(title = "External programme"))
            })
            val leader = async { useCase.refreshEpg(force = true) }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { useCase.refreshEpg(force = true) }
            leader.cancelAndJoin()
            assertTrue(waiter.await().isSuccess)
            assertEquals(2, externalCalls)
            assertEquals("External programme", dao.getNowPlayingFor("news", 1_500)?.title)
        }
    }

    private class FakeRepository : PlaylistRepository {
        var catalogueRows = listOf(channel("news"))
        var epgRows = listOf(ProgrammeEntity("news", 1_000, 2_000, "Fresh programme", null))
        var epgFailure: Exception? = null
        var catalogueGate: CompletableDeferred<Unit>? = null
        var epgGate: CompletableDeferred<Unit>? = null
        val catalogueStarted = CompletableDeferred<Unit>()
        val epgStarted = CompletableDeferred<Unit>()
        var catalogueCalls = 0
        var epgCalls = 0
        var snapshotProgrammes = emptyList<ProgrammeEntity>()
        var notModified = false
        val includeEpgRequests = mutableListOf<Boolean>()

        override suspend fun fetch(etag: String?, lastModified: String?, onProgress: (ImportProgress) -> Unit, includeEpg: Boolean, onSectionReady: suspend (PlaylistSectionResult) -> Unit): PlaylistSnapshot {
            catalogueCalls++
            includeEpgRequests += includeEpg
            catalogueStarted.complete(Unit)
            catalogueGate?.await()
            if (notModified) return PlaylistSnapshot(channels = emptyList(), notModified = true)
            onSectionReady(PlaylistSectionResult(ContentType.TV, catalogueRows))
            return PlaylistSnapshot(channels = catalogueRows, programmes = snapshotProgrammes)
        }

        override suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity> {
            epgCalls++
            epgStarted.complete(Unit)
            epgGate?.await()
            epgFailure?.let { throw it }
            return epgRows
        }
    }

    companion object {
        private fun channel(id: String) = Channel(id, id, null, null, "https://provider.test/$id.ts", "news", ContentType.TV)
    }
}
