package nl.vanvrouwerff.iptv.data.epg

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalXmltvLoaderTest {
    private lateinit var server: MockWebServer
    private val xml = """<tv>
        <channel id="news.de"><display-name>News</display-name></channel>
        <programme channel="news.de" start="20261005080000 +0200" stop="20261005083000 +0200"><title>News</title></programme>
        <programme channel="other.de" start="20261005080000 +0200" stop="20261005083000 +0200"><title>Other</title></programme>
    </tv>"""

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `downloads external gzip XMLTV and keeps only exact requested IDs`() = runBlocking {
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(xml.toByteArray()) } }.toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(gzip)))
        val rows = ExternalXmltvLoader(OkHttpClient()).fetch(server.url("/guide.xml.gz").toString(), setOf("news.de"))
        assertEquals(listOf("news.de"), rows.map { it.channelKey })
        assertEquals(listOf("News"), rows.map { it.title })
    }

    @Test fun `display names and differently cased IDs do not silently map to another channel`() = runBlocking {
        server.enqueue(MockResponse().setBody(xml))
        val rows = ExternalXmltvLoader(OkHttpClient()).fetch(server.url("/guide.xml").toString(), setOf("News", "NEWS.DE"))
        assertTrue(rows.isEmpty())
    }

    @Test fun `a missing external feed fails immediately without repeated HTTP requests`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = runCatching { ExternalXmltvLoader(OkHttpClient()).fetch(server.url("/missing.xml").toString(), setOf("news.de")) }
        assertTrue(result.isFailure)
        assertEquals("EPG HTTP 404", result.exceptionOrNull()?.message)
        assertEquals(1, server.requestCount)
    }
}
