package nl.vanvrouwerff.iptv.data.repo

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class M3uEpgRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private val routes = ConcurrentHashMap<String, ByteArray>()
    private var epgBodyDelayMs = 0L
    private val xml = """<tv>
        <programme channel="news" start="20260420183000" stop="20260420190000"><title>News</title></programme>
        <programme channel="other" start="20260420183000" stop="20260420190000"><title>Other</title></programme>
    </tv>"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bytes = routes[request.requestUrl?.encodedPath] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(bytes)).apply {
                    if (request.requestUrl?.encodedPath == "/guide.xml") setBodyDelay(epgBodyDelayMs, TimeUnit.MILLISECONDS)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `full M3U refresh synchronizes its relative gzip EPG feed`() = runBlocking {
        serve("/list.m3u", "#EXTM3U url-tvg=\"/guide.xml.gz\"\n#EXTINF:-1 tvg-id=\"news\",News\n$baseUrl/live.ts".toByteArray())
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(xml.toByteArray()) } }.toByteArray()
        serve("/guide.xml.gz", gzip)
        val snapshot = M3uPlaylistRepository("$baseUrl/list.m3u", OkHttpClient()).fetch(null, null)
        assertEquals(1, snapshot.channels.size)
        assertEquals(listOf("News"), snapshot.programmes.map { it.title })
        assertNull(snapshot.epgError)
    }

    @Test fun `independent EPG refresh discovers the playlist header and filters the current channels`() = runBlocking {
        serve("/list.m3u", "#EXTM3U x-tvg-url=\"/guide.xml\"\n#EXTINF:-1 tvg-id=\"news\",News\n$baseUrl/live.ts".toByteArray())
        serve("/guide.xml", xml.toByteArray())
        val rows = M3uPlaylistRepository("$baseUrl/list.m3u", OkHttpClient()).fetchProgrammes(setOf("news"))
        assertEquals(listOf("News"), rows!!.map { it.title })
    }

    @Test fun `an M3U without an EPG address remains a valid catalogue`() = runBlocking {
        serve("/list.m3u", "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",News\n$baseUrl/live.ts".toByteArray())
        val repository = M3uPlaylistRepository("$baseUrl/list.m3u", OkHttpClient())
        val snapshot = repository.fetch(null, null)
        assertEquals(1, snapshot.channels.size)
        assertEquals(0, snapshot.programmes.size)
        assertNull(repository.fetchProgrammes(setOf("news")))
    }

    @Test fun `cancelling an EPG body transfer returns before the delayed body arrives`() = runBlocking {
        serve("/list.m3u", "#EXTM3U x-tvg-url=\"/guide.xml\"\n".toByteArray())
        serve("/guide.xml", xml.toByteArray())
        // Three seconds keeps MockWebServer's bounded shutdown reliable while exposing a blocked socket read.
        epgBodyDelayMs = 3_000
        val bodyStarted = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                if (call.request().url.encodedPath == "/guide.xml") bodyStarted.complete(Unit)
            }
        }).build()
        val request = async { M3uPlaylistRepository("$baseUrl/list.m3u", client).fetchProgrammes(setOf("news")) }
        withTimeout(5_000) { bodyStarted.await() }
        withTimeout(2_000) { request.cancelAndJoin() }
    }

    private fun serve(path: String, bytes: ByteArray) {
        routes[path] = bytes
    }
}
