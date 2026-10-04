package nl.vanvrouwerff.iptv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRetryPolicyTest {
    private val networkError = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    @Test fun shortPlayingSpellsDoNotAllowUnlimitedRetries() {
        val policy = PlaybackRetryPolicy()
        for ((attempt, expected) in listOf(1_500L, 4_000L, 9_000L).withIndex()) {
            val now = attempt * 2_000L
            policy.onPlayingChanged(true, now)
            policy.onPlayingChanged(false, now + 500)
            assertEquals(expected, policy.nextDelayMs(networkError, null, now + 500))
        }
        policy.onPlayingChanged(true, 10_000)
        policy.onPlayingChanged(false, 10_500)
        assertNull(policy.nextDelayMs(networkError, null, 10_500))
    }

    @Test fun continuousHealthyPlaybackRenewsRetryBudget() {
        val policy = exhaustedPolicy()
        policy.onPlayingChanged(true, 20_000)
        policy.onPlayingChanged(false, 20_000 + PlaybackRetryPolicy.STABLE_PLAYBACK_MS)
        assertEquals(1_500L, policy.nextDelayMs(networkError, null, 40_000))
    }

    @Test fun pausedOrBufferingTimeDoesNotRenewBudget() {
        val policy = exhaustedPolicy()
        policy.onPlayingChanged(true, 20_000)
        policy.onPlayingChanged(false, 20_100)
        assertNull(policy.nextDelayMs(networkError, null, 200_000))
    }

    @Test fun channelChangeOrManualRetryStartsWithFreshBudget() {
        val policy = exhaustedPolicy()
        policy.reset()
        assertEquals(1_500L, policy.nextDelayMs(networkError, null, 100_000))
    }

    @Test fun permanentHttpErrorsAreNotRetried() {
        for (status in listOf(400, 401, 403, 404, 410)) {
            assertNull(PlaybackRetryPolicy().nextDelayMs(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, 0,
            ))
        }
    }

    @Test fun temporaryHttpErrorsAreRetried() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504)) {
            assertEquals(1_500L, PlaybackRetryPolicy().nextDelayMs(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, 0,
            ))
        }
    }

    @Test fun missingFilesPermissionsAndDecoderFailuresAreNotRetried() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )) {
            assertNull(PlaybackRetryPolicy().nextDelayMs(code, null, 0))
        }
    }

    private fun exhaustedPolicy() = PlaybackRetryPolicy().apply {
        repeat(3) { nextDelayMs(networkError, null, it * 1_000L) }
    }
}
