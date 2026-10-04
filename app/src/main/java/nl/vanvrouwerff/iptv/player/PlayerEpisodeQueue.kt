package nl.vanvrouwerff.iptv.player

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

const val EPISODE_QUEUE_REFERENCE_EXTRA = "episode_queue_reference"

@Serializable
data class PlayerEpisodeQueue(
    val seriesChannelId: String,
    val seriesName: String,
    val seriesCover: String?,
    val seasonNumber: Int,
    val episodes: List<PlayerEpisodeQueueItem>,
) {
    /** Keep ample room for Android's other transaction state below the 1 MiB Binder cap. */
    fun requiresFileTransfer(): Boolean = estimatedIntentBytes() > MAX_INLINE_BYTES

    internal fun estimatedIntentBytes(): Long =
        8_192L + parcelStringBytes(seriesChannelId) + parcelStringBytes(seriesName) +
            parcelStringBytes(seriesCover) + episodes.sumOf { item ->
                12L + parcelStringBytes(item.id) + parcelStringBytes(item.url) +
                    parcelStringBytes(item.name) + parcelStringBytes(item.cover)
            }

    private fun parcelStringBytes(value: String?): Long =
        if (value == null) 4L else 8L + (value.length.toLong() + 1L) * 2L

    companion object {
        private const val MAX_INLINE_BYTES = 192L * 1024L
    }
}

@Serializable
data class PlayerEpisodeQueueItem(
    val id: String,
    val url: String,
    val name: String,
    val episodeNumber: Int,
    val cover: String?,
    val durationSecs: Long,
)

/** Private cache transfer for queues that cannot safely fit in an Activity Intent. */
@OptIn(ExperimentalSerializationApi::class)
class EpisodeQueueStore(cacheDir: File) {
    private val directory = File(cacheDir, "player_episode_queues")
    private val json = Json { ignoreUnknownKeys = true }

    fun write(queue: PlayerEpisodeQueue, nowMs: Long = System.currentTimeMillis()): String {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create player queue cache")
        }
        cleanup(nowMs)
        val reference = "${UUID.randomUUID()}.json"
        val destination = File(directory, reference)
        val temporary = File(directory, "$reference.tmp")
        try {
            temporary.outputStream().buffered().use { stream ->
                json.encodeToStream(PlayerEpisodeQueue.serializer(), queue, stream)
            }
            if (temporary.length() > MAX_QUEUE_BYTES) throw IOException("Player queue is too large")
            if (!temporary.renameTo(destination)) throw IOException("Cannot save player queue")
            destination.setLastModified(nowMs)
            return reference
        } finally {
            temporary.delete()
        }
    }

    /** Read again after Activity recreation; retain the file until its normal cache expiry. */
    fun read(reference: String, nowMs: Long = System.currentTimeMillis()): PlayerEpisodeQueue? {
        if (!REFERENCE_PATTERN.matches(reference)) return null
        cleanup(nowMs)
        val file = File(directory, reference)
        if (!file.isFile || file.length() !in 1L..MAX_QUEUE_BYTES) return null
        return try {
            file.inputStream().buffered().use { stream ->
                json.decodeFromStream(PlayerEpisodeQueue.serializer(), stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cleanup(nowMs: Long = System.currentTimeMillis()) {
        directory.listFiles()?.forEach { file ->
            if (nowMs - file.lastModified() >= MAX_AGE_MS) file.delete()
        }
    }

    companion object {
        const val MAX_AGE_MS = 24L * 3_600_000L
        /** Bound memory used by a corrupt JSON payload before the decoder reads it. */
        const val MAX_QUEUE_BYTES = 32L * 1024L * 1024L
        private val REFERENCE_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json",
        )
    }
}
