package nl.vanvrouwerff.iptv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ChannelListMode { HIDDEN, PREVIEW, BROWSING }

/** Only automatic previews expire. Explicit browsing must survive zaps and old timers. */
internal class ChannelListPresentation(
    private val scope: CoroutineScope,
    private val previewDurationMs: Long = 4_000L,
) {
    private val _mode = MutableStateFlow(ChannelListMode.HIDDEN)
    val mode = _mode.asStateFlow()
    private var hideJob: Job? = null

    fun preview() {
        if (_mode.value == ChannelListMode.BROWSING) return
        hideJob?.cancel()
        _mode.value = ChannelListMode.PREVIEW
        hideJob = scope.launch {
            delay(previewDurationMs)
            _mode.value = ChannelListMode.HIDDEN
            hideJob = null
        }
    }

    fun browse() {
        hideJob?.cancel()
        hideJob = null
        _mode.value = ChannelListMode.BROWSING
    }

    fun close() {
        hideJob?.cancel()
        hideJob = null
        _mode.value = ChannelListMode.HIDDEN
    }
}
