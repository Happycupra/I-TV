package nl.vanvrouwerff.iptv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InfoBannerPresentationTest {
    @Test fun slowEpgCannotExtendOrReopenExpiredBanner() = runTest {
        val banner = InfoBannerPresentation<String>(backgroundScope)
        val request = banner.show("A")
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        banner.update(request) { "A programme" }
        assertNull(banner.banner.value)
    }

    @Test fun rapidZapsRestartExpiryAndClearPreviousProgramme() = runTest {
        val banner = InfoBannerPresentation<String>(backgroundScope)
        banner.show("A programme")
        runCurrent()
        advanceTimeBy(3_000)
        banner.show("B")
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals("B", banner.banner.value)
        advanceTimeBy(2_500)
        runCurrent()
        assertNull(banner.banner.value)
    }

    @Test fun returningToSameChannelRejectsItsOlderEpgRequest() = runTest {
        val banner = InfoBannerPresentation<String>(backgroundScope)
        val oldA = banner.show("A")
        val oldB = banner.show("B")
        val latestA = banner.show("A")
        banner.update(oldA) { "A stale programme" }
        banner.update(oldB) { "B stale programme" }
        assertEquals("A", banner.banner.value)
        banner.update(latestA) { "A current programme" }
        assertEquals("A current programme", banner.banner.value)
    }

    @Test fun metadataUpdatesDoNotResetTheFourSecondDeadline() = runTest {
        val banner = InfoBannerPresentation<String>(backgroundScope)
        val request = banner.show("A")
        runCurrent()
        advanceTimeBy(3_000)
        banner.update(request) { "A programme" }
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(banner.banner.value)
    }

    @Test fun openingManualOverlayInvalidatesPendingMetadataAndOldTimer() = runTest {
        val banner = InfoBannerPresentation<String>(backgroundScope)
        val request = banner.show("A")
        runCurrent()
        advanceTimeBy(3_000)
        banner.dismiss()
        banner.update(request) { "A programme" }
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(banner.banner.value)
        banner.show("B")
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals("B", banner.banner.value)
    }
}
