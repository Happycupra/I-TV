package nl.vanvrouwerff.iptv.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueIndexCacheTest {
    @Test fun refreshDuringBuildRejectsStaleIndexPublication() {
        val cache = CatalogueIndexCache<Map<String, String>>()
        val beforeRefresh = cache.snapshot()
        cache.invalidate()
        assertFalse(cache.publish(beforeRefresh.generation, mapOf("film" to "old-provider-url")))
        assertNull(cache.snapshot().value)
    }

    @Test fun rebuildingAfterRefreshPublishesTheNewProviderUrls() {
        val cache = CatalogueIndexCache<Map<String, String>>()
        val oldBuild = cache.snapshot()
        cache.invalidate()
        val newBuild = cache.snapshot()
        assertTrue(cache.publish(newBuild.generation, mapOf("film" to "new-provider-url")))
        assertFalse(cache.publish(oldBuild.generation, mapOf("film" to "old-provider-url")))
        assertEquals(mapOf("film" to "new-provider-url"), cache.snapshot().value)
    }

    @Test fun invalidationAlsoRemovesAnAlreadyPublishedIndex() {
        val cache = CatalogueIndexCache<String>()
        assertTrue(cache.publish(cache.snapshot().generation, "cached catalogue"))
        cache.invalidate()
        assertNull(cache.snapshot().value)
    }

    @Test fun emptyFirstImportCanBeRebuiltWithoutPinningAnEmptyCatalogue() {
        val cache = CatalogueIndexCache<List<String>>()
        assertTrue(cache.publish(cache.snapshot().generation, null))
        assertNull(cache.snapshot().value)
        assertTrue(cache.publish(cache.snapshot().generation, listOf("fresh film")))
        assertEquals(listOf("fresh film"), cache.snapshot().value)
    }
}
