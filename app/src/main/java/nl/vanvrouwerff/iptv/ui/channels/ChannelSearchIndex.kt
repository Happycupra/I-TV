package nl.vanvrouwerff.iptv.ui.channels

import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import nl.vanvrouwerff.iptv.data.db.SearchIndexRow

/** Normalized search fields only; full channel metadata is hydrated for the hits. */
internal class ChannelSearchIndex private constructor(
    private val ids: Array<String>,
    private val names: Array<String>,
    private val types: Array<String>,
) {
    suspend fun matchingIds(query: String, limitPerType: Int): List<String> {
        val needle = normalizeForSearch(query)
        if (needle.isEmpty() || limitPerType <= 0) return emptyList()
        val perType = HashMap<String, Int>()
        val hits = ArrayList<String>()
        val context = coroutineContext
        for (i in ids.indices) {
            if (i % 256 == 0) context.ensureActive()
            if (!names[i].contains(needle)) continue
            val count = perType[types[i]] ?: 0
            if (count >= limitPerType) continue
            perType[types[i]] = count + 1
            hits += ids[i]
        }
        return hits
    }

    companion object {
        suspend fun build(rows: List<SearchIndexRow>): ChannelSearchIndex {
            val context = coroutineContext
            val names = Array(rows.size) { i ->
                if (i % 256 == 0) context.ensureActive()
                normalizeForSearch(rows[i].name)
            }
            return ChannelSearchIndex(
                ids = Array(rows.size) { rows[it].id },
                names = names,
                types = Array(rows.size) { rows[it].type },
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun buildSearchIndexFlow(
    rows: Flow<List<SearchIndexRow>>,
): Flow<ChannelSearchIndex> = rows
    .distinctUntilChanged()
    .debounce(500)
    .mapLatest { ChannelSearchIndex.build(it) }

internal fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .trim()

private val COMBINING_MARKS = Regex("\\p{M}+")
