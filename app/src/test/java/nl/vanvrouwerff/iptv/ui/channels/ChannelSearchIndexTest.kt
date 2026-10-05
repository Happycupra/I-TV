package nl.vanvrouwerff.iptv.ui.channels

import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.vanvrouwerff.iptv.data.db.SearchIndexRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSearchIndexTest {
    @Test
    fun renameWithUnchangedRowCountUpdatesSearch() = runTest {
        val rows = MutableStateFlow(listOf(row("1", "Old title")))
        val indexes = mutableListOf<ChannelSearchIndex>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            buildSearchIndexFlow(rows).collect { indexes += it }
        }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("1"), indexes.last().matchingIds("old", 60))

        rows.value = listOf(row("1", "New title"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, indexes.size)
        assertTrue(indexes.last().matchingIds("old", 60).isEmpty())
        assertEquals(listOf("1"), indexes.last().matchingIds("new", 60))
    }

    @Test
    fun replacementWithUnchangedRowCountDropsRemovedIds() = runTest {
        val rows = MutableStateFlow(listOf(row("old-id", "Film")))
        val indexes = mutableListOf<ChannelSearchIndex>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            buildSearchIndexFlow(rows).collect { indexes += it }
        }
        advanceTimeBy(500)
        runCurrent()
        rows.value = listOf(row("new-id", "Film"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("new-id"), indexes.last().matchingIds("film", 60))
    }

    @Test
    fun repeatedUnchangedRowsDoNotRebuildIndex() = runTest {
        val rows = MutableSharedFlow<List<SearchIndexRow>>(replay = 1)
        rows.emit(listOf(row("1", "Film")))
        val indexes = mutableListOf<ChannelSearchIndex>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            buildSearchIndexFlow(rows).collect { indexes += it }
        }
        advanceTimeBy(500)
        runCurrent()
        rows.emit(listOf(row("1", "Film")))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, indexes.size)
    }

    @Test
    fun burstOfCatalogueUpdatesBuildsOnlyLatestIndex() = runTest {
        val rows = MutableStateFlow(listOf(row("1", "First")))
        val indexes = mutableListOf<ChannelSearchIndex>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            buildSearchIndexFlow(rows).collect { indexes += it }
        }
        advanceTimeBy(250)
        rows.value = listOf(row("1", "Second"))
        advanceTimeBy(250)
        assertTrue(indexes.isEmpty())
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, indexes.size)
        assertEquals(listOf("1"), indexes.last().matchingIds("second", 60))
    }

    @Test
    fun limitAppliesToEachContentTypeAndPreservesCatalogueOrder() = runTest {
        val index = ChannelSearchIndex.build(listOf(
            row("tv-1", "Match", "TV"),
            row("movie-1", "Match", "MOVIE"),
            row("tv-2", "Match", "TV"),
            row("series-1", "Match", "SERIES"),
            row("movie-2", "Match", "MOVIE"),
        ))
        assertEquals(listOf("tv-1", "movie-1", "series-1"), index.matchingIds("match", 1))
        assertTrue(index.matchingIds("  ", 60).isEmpty())
    }

    @Test
    fun searchIsAccentInsensitiveAndIndependentOfDeviceLocale() = runTest {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val index = ChannelSearchIndex.build(listOf(row("1", "POKÉMON FILM")))
            assertEquals(listOf("1"), index.matchingIds("pokemon film", 60))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun supersededSearchStopsBeforeScanningCatalogue() = runTest {
        val index = ChannelSearchIndex.build(List(1_000) { row("$it", "Film") })
        var completed = false
        val search = launch {
            currentCoroutineContext().cancel()
            index.matchingIds("film", 60)
            completed = true
        }
        search.join()
        assertFalse(completed)
        assertTrue(search.isCancelled)
    }

    private fun row(id: String, name: String, type: String = "MOVIE") =
        SearchIndexRow(id = id, name = name, type = type)
}
