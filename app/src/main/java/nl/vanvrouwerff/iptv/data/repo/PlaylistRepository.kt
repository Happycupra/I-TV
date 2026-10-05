package nl.vanvrouwerff.iptv.data.repo

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
    /** Optional EPG failure, kept separate from catalogue/provider health. */
    val epgError: String? = null,
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
        /** An external EPG override makes the provider's feed unnecessary. */
        includeEpg: Boolean = true,
        /** Called as soon as a partition is available, so Live can land before VOD/series. */
        onSectionReady: suspend (PlaylistSectionResult) -> Unit = {},
    ): PlaylistSnapshot

    /** Only the EPG, for channels whose key is in [epgKeys]; null when the source has none. */
    suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity>? = null
}
