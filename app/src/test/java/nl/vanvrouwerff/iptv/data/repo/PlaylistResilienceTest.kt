package nl.vanvrouwerff.iptv.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistResilienceTest {
    @Test fun `empty provider response cannot wipe known good section`() {
        assertFalse(PlaylistResilience.assessSection(1500, 0, sourceChanged = false).accept)
    }

    @Test fun `catastrophic drop is rejected for same source`() {
        assertFalse(PlaylistResilience.assessSection(1500, 200, sourceChanged = false).accept)
    }

    @Test fun `normal catalogue change is accepted`() {
        assertTrue(PlaylistResilience.assessSection(1500, 1300, sourceChanged = false).accept)
    }

    @Test fun `intentional source change may be much smaller`() {
        assertTrue(PlaylistResilience.assessSection(1500, 50, sourceChanged = true).accept)
    }

    @Test fun `retryable http codes include throttling and server failures`() {
        assertTrue(PlaylistResilience.isRetryableHttp(429))
        assertTrue(PlaylistResilience.isRetryableHttp(503))
        assertFalse(PlaylistResilience.isRetryableHttp(401))
        assertFalse(PlaylistResilience.isRetryableHttp(404))
    }
}
