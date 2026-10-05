package nl.vanvrouwerff.iptv.data.repo

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CancellableResultTest {
    @Test fun cancellingSuspendedLoadCannotPublishAnErrorOverTheNextScreen() = runTest {
        var state = "first screen loading"
        val firstLoad = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { awaitCancellation() }
                .onFailure { state = "first screen failed" }
        }
        state = "second screen loading"
        firstLoad.cancelAndJoin()
        assertEquals("second screen loading", state)
    }

    @Test fun cancelledRefreshCannotContinueIntoFallbackSections() {
        val cancelled = CancellationException("Refresh stopped")
        var fallbackRan = false
        try {
            runCatchingCancellable<Unit> { throw cancelled }
                .getOrElse { fallbackRan = true }
            throw AssertionError("Cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertFalse(fallbackRan)
    }

    @Test fun networkFailureStillReturnsFailureForCachedCatalogueFallback() {
        val failure = IOException("Provider unavailable")
        val result = runCatchingCancellable<Unit> { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun successfulRefreshKeepsItsResult() {
        assertEquals("catalogue", runCatchingCancellable { "catalogue" }.getOrThrow())
    }
}
