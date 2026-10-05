package nl.vanvrouwerff.iptv.player

import androidx.media3.common.PlaybackException

/** A short PLAYING spell must not turn a failing stream into an infinite retry loop. */
internal class PlaybackRetryPolicy {
    private var attempts = 0
    private var playingSinceMs: Long? = null

    fun reset() {
        attempts = 0
        playingSinceMs = null
    }

    fun onPlayingChanged(isPlaying: Boolean, nowMs: Long) {
        if (isPlaying) {
            if (playingSinceMs == null) playingSinceMs = nowMs
        } else {
            renewAfterStablePlayback(nowMs)
            playingSinceMs = null
        }
    }

    fun nextDelayMs(errorCode: Int, httpStatus: Int?, nowMs: Long): Long? {
        renewAfterStablePlayback(nowMs)
        playingSinceMs = null
        if (!isRetryable(errorCode, httpStatus)) return null
        return RETRY_DELAYS_MS.getOrNull(attempts)?.also { attempts++ }
    }

    private fun renewAfterStablePlayback(nowMs: Long) {
        val started = playingSinceMs ?: return
        if (nowMs - started >= STABLE_PLAYBACK_MS) attempts = 0
    }

    companion object {
        const val STABLE_PLAYBACK_MS = 10_000L
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L, 9_000L)

        private fun isRetryable(errorCode: Int, httpStatus: Int?): Boolean = when (errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                httpStatus == 408 || httpStatus == 425 || httpStatus == 429 ||
                    (httpStatus != null && httpStatus in 500..599)
            else -> false
        }
    }
}
