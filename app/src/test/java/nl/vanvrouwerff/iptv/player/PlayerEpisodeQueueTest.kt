package nl.vanvrouwerff.iptv.player

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerEpisodeQueueTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun queue(count: Int = 2, urlLength: Int = 60): PlayerEpisodeQueue = PlayerEpisodeQueue(
        seriesChannelId = "xt-series:42",
        seriesName = "Daily series",
        seriesCover = null,
        seasonNumber = 1,
        episodes = List(count) { number ->
            PlayerEpisodeQueueItem(
                id = "xt-episode:$number",
                url = "https://provider.example/" + "x".repeat(urlLength),
                name = "Episode $number",
                episodeNumber = number,
                cover = null,
                durationSecs = 1200L,
            )
        },
    )

    @Test fun shortSeasonCanUseNormalIntent() {
        assertFalse(queue().requiresFileTransfer())
    }

    @Test fun longTokensTriggerFileTransferEvenForASingleEpisode() {
        assertTrue(queue(count = 1, urlLength = 100_000).requiresFileTransfer())
    }

    @Test fun largeSeasonKeepsEveryEpisodeOutsideBinder() {
        val largeSeason = queue(count = 2_048, urlLength = 1_024)
        assertTrue(largeSeason.estimatedIntentBytes() > 1_048_576L)
        assertTrue(largeSeason.requiresFileTransfer())
        val store = EpisodeQueueStore(temporaryFolder.root)
        val reference = store.write(largeSeason)
        assertTrue(reference.length < 100)
        assertEquals(largeSeason, store.read(reference))
        assertEquals(largeSeason, store.read(reference)) // Activity recreation.
    }

    @Test fun invalidReferencesCannotReadOrDeleteFilesOutsideQueueDirectory() {
        val outside = temporaryFolder.newFile("outside.json").apply { writeText("private") }
        val store = EpisodeQueueStore(temporaryFolder.root)
        listOf("../outside.json", outside.absolutePath, "../player_episode_queues/file.json").forEach { reference ->
            assertNull(store.read(reference))
        }
        assertEquals("private", outside.readText())
    }

    @Test fun corruptAndMissingQueuesReturnNoQueue() {
        val store = EpisodeQueueStore(temporaryFolder.root)
        val reference = store.write(queue())
        File(temporaryFolder.root, "player_episode_queues/$reference").writeText("broken JSON")
        assertNull(store.read(reference))
        assertNull(store.read("00000000-0000-0000-0000-000000000000.json"))
    }

    @Test fun oversizedQueueIsRejectedBeforeJsonDecoding() {
        val store = EpisodeQueueStore(temporaryFolder.root)
        val reference = store.write(queue())
        val file = File(temporaryFolder.root, "player_episode_queues/$reference")
        RandomAccessFile(file, "rw").use { it.setLength(EpisodeQueueStore.MAX_QUEUE_BYTES + 1L) }
        assertNull(store.read(reference))
    }

    @Test fun expiredQueuesAreRemovedWhileCurrentQueueRemainsReadable() {
        val store = EpisodeQueueStore(temporaryFolder.root)
        val now = 1_700_000_000_000L
        val expired = store.write(queue(), now - EpisodeQueueStore.MAX_AGE_MS)
        val current = store.write(queue(), now)
        assertNull(store.read(expired, now))
        assertEquals(queue(), store.read(current, now))
        assertFalse(File(temporaryFolder.root, "player_episode_queues/$expired").exists())
    }
}
