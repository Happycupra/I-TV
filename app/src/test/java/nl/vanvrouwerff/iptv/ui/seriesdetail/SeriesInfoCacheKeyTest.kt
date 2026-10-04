package nl.vanvrouwerff.iptv.ui.seriesdetail

import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeriesInfoCacheKeyTest {
    private val source = SourceConfig.Xtream("https://provider.example", "viewer", "secret")

    @Test fun identicalSeriesIdsFromDifferentProvidersNeverShareCache() {
        assertNotEquals(
            seriesInfoCacheKey(source, "42"),
            seriesInfoCacheKey(source.copy(host = "https://other.example"), "42"),
        )
    }

    @Test fun differentAccountsOnTheSameProviderNeverShareCache() {
        assertNotEquals(
            seriesInfoCacheKey(source, "42"),
            seriesInfoCacheKey(source.copy(username = "another-viewer"), "42"),
        )
    }

    @Test fun changedPasswordInvalidatesTheAccountCache() {
        assertNotEquals(
            seriesInfoCacheKey(source, "42"),
            seriesInfoCacheKey(source.copy(password = "changed-secret"), "42"),
        )
    }

    @Test fun sameAccountNormalizesWhitespaceAndTrailingSlash() {
        assertEquals(
            seriesInfoCacheKey(source, "42"),
            seriesInfoCacheKey(source.copy(host = " https://provider.example/ ", username = " viewer "), "42"),
        )
    }

    @Test fun differentSeriesOnTheSameSourceHaveDifferentKeys() {
        assertNotEquals(seriesInfoCacheKey(source, "42"), seriesInfoCacheKey(source, "43"))
    }

    @Test fun persistedCacheKeyDoesNotContainSourceCredentials() {
        val key = seriesInfoCacheKey(source, "42")
        assertFalse(key.contains(source.host))
        assertFalse(key.contains(source.username))
        assertFalse(key.contains(source.password))
    }
}
