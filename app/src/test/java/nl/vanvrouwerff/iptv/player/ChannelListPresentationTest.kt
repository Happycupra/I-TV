package nl.vanvrouwerff.iptv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListPresentationTest {
    @Test fun previewClosesAfterFourSeconds() = runTest {
        val list = ChannelListPresentation(backgroundScope)
        list.preview()
        runCurrent()
        advanceTimeBy(3_999)
        assertEquals(ChannelListMode.PREVIEW, list.mode.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ChannelListMode.HIDDEN, list.mode.value)
    }

    @Test fun rapidZapsRestartPreviewTimeout() = runTest {
        val list = ChannelListPresentation(backgroundScope)
        list.preview()
        runCurrent()
        advanceTimeBy(3_000)
        list.preview()
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals(ChannelListMode.PREVIEW, list.mode.value)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(ChannelListMode.HIDDEN, list.mode.value)
    }

    @Test fun pressingOkKeepsBrowsingOpenBeyondOldPreviewDeadline() = runTest {
        val list = ChannelListPresentation(backgroundScope)
        list.preview()
        runCurrent()
        advanceTimeBy(3_000)
        list.browse()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(ChannelListMode.BROWSING, list.mode.value)
    }

    @Test fun zappingFromManualListDoesNotStartAutoHide() = runTest {
        val list = ChannelListPresentation(backgroundScope)
        list.browse()
        list.preview()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(ChannelListMode.BROWSING, list.mode.value)
    }

    @Test fun closingAndReopeningCannotBeHiddenByPreviousTimer() = runTest {
        val list = ChannelListPresentation(backgroundScope)
        list.preview()
        runCurrent()
        advanceTimeBy(3_000)
        list.close()
        list.preview()
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals(ChannelListMode.PREVIEW, list.mode.value)
        list.close()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(ChannelListMode.HIDDEN, list.mode.value)
    }
}
