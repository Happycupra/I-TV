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

        override suspend fun fetch(etag: String?, lastModified: String?, onProgress: (ImportProgress) -> Unit, onSectionReady: suspend (PlaylistSectionResult) -> Unit): PlaylistSnapshot {
            catalogueCalls++
            catalogueStarted.complete(Unit)
            catalogueGate?.await()
            onSectionReady(PlaylistSectionResult(ContentType.TV, catalogueRows))
            return PlaylistSnapshot(channels = catalogueRows)
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
