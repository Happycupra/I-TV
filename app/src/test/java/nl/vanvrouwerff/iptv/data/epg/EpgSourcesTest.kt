package nl.vanvrouwerff.iptv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgSourcesTest {
    @Test fun `finds standard header attributes and resolves relative feeds`() {
        assertEquals(
            listOf("https://provider.test/guide.xml.gz", "https://other.test/epg.xml"),
            EpgSources.fromM3uHeader("#EXTM3U x-tvg-url=\"../guide.xml.gz,https://other.test/epg.xml\"", "https://provider.test/playlists/list.m3u"),
        )
        assertEquals(listOf("https://provider.test/epg"), EpgSources.fromM3uHeader("\uFEFF#EXTM3U URL-TVG='/epg'", "https://provider.test/list"))
    }

    @Test fun `unsupported schemes and non-header input are ignored`() {
        assertTrue(EpgSources.fromM3uHeader("#EXTM3U url-tvg=\"file:///secret\"", "https://provider.test/list").isEmpty())
        assertTrue(EpgSources.fromM3uHeader("#EXTINF url-tvg=\"https://provider.test/epg\"", "https://provider.test/list").isEmpty())
    }

    @Test fun `deduplicates advertised feeds and preserves query parameters`() {
        assertEquals(
            listOf("https://provider.test/epg?groups=uk,nl"),
            EpgSources.fromM3uHeader("#EXTM3U url-tvg=\"/epg?groups=uk,nl\" x-tvg-url=\"/epg?groups=uk,nl\"", "https://provider.test/list"),
        )
    }

    @Test fun `reads gzip files and plain xml independently of HTTP headers`() {
        val xml = "<tv><programme /></tv>"
        val bytes = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(xml.toByteArray()) } }.toByteArray()
        assertEquals(xml, EpgSources.xmlStream(bytes.inputStream()).bufferedReader().use { it.readText() })
        assertEquals(xml, EpgSources.xmlStream(xml.byteInputStream()).bufferedReader().use { it.readText() })
        assertEquals("", EpgSources.xmlStream(byteArrayOf().inputStream()).bufferedReader().use { it.readText() })
    }
}
