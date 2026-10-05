package nl.vanvrouwerff.iptv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Expiry starts at the zap, and late metadata can only update that exact presentation. */
internal class InfoBannerPresentation<T : Any>(
    private val scope: CoroutineScope,
    private val durationMs: Long = 4_000L,
) {
    private val _banner = MutableStateFlow<T?>(null)
    val banner = _banner.asStateFlow()
    private var generation = 0L
    private var hideJob: Job? = null

    fun show(initial: T): Long {
        dismiss()
        val request = generation
        _banner.value = initial
        hideJob = scope.launch {
            delay(durationMs)
            if (request == generation) dismiss()
        }
        return request
    }

    fun update(request: Long, transform: (T) -> T) {
        if (request != generation) return
        _banner.value?.let { _banner.value = transform(it) }
    }

    fun updateCurrent(transform: (T) -> T) = update(generation, transform)

    fun dismiss() {
        generation++
        hideJob?.cancel()
        hideJob = null
        _banner.value = null
    }
}
