package nl.vanvrouwerff.iptv.data.tmdb

/** An index built before an invalidation must not repopulate the cache afterwards. */
internal class CatalogueIndexCache<T> {
    private var generation = 0L
    private var value: T? = null

    @Synchronized
    fun snapshot(): Snapshot<T> = Snapshot(generation, value)

    @Synchronized
    fun publish(expectedGeneration: Long, candidate: T?): Boolean {
        if (expectedGeneration != generation) return false
        value = candidate
        return true
    }

    @Synchronized
    fun invalidate() {
        generation++
        value = null
    }

    data class Snapshot<T>(val generation: Long, val value: T?)
}
