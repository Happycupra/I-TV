package nl.vanvrouwerff.iptv.data.epg

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class XmltvFeedParserTest {
    @Test fun `skips invalid programmes and retains the next valid entry`() {
        val feed = """<tv>
            <programme channel="news" start="invalid"><title>Broken</title></programme>
            <programme channel="news" start="20260420183000" stop="20260420170000"><title>Backwards</title></programme>
            <programme channel="news" start="20260420183000" stop="bad"><title>Bad stop</title></programme>
            <programme channel=" news " start="20260420183000 +0200" stop="20260420190000 +0200"><title> News </title><desc> Today </desc></programme>
        </tv>"""
        val rows = XmltvParser.parse(feed.byteInputStream(), keepChannel = { it == "news" })
        assertEquals(1, rows.size)
        assertEquals("news", rows.single().channelKey)
        assertEquals("News", rows.single().title)
        assertEquals("Today", rows.single().description)
        assertEquals(30 * 60_000L, rows.single().stopMs - rows.single().startMs)
    }

    @Test fun `filters unselected channels and permits an absent stop time`() {
        val feed = """<tv>
            <programme channel="other" start="20260420180000"><title>Other</title></programme>
            <programme channel="news" start="20260420180000"><title>News</title></programme>
        </tv>"""
        val rows = XmltvParser.parse(feed.byteInputStream(), keepChannel = { it == "news" })
        assertEquals(1, rows.size)
        assertEquals(30 * 60_000L, rows.single().stopMs - rows.single().startMs)
    }

    @Test(expected = CancellationException::class)
    fun `feed parsing preserves cancellation`() {
        XmltvParser.parse("<tv />".byteInputStream(), checkCancelled = { throw CancellationException("cancel") })
    }
}
