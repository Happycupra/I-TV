package nl.vanvrouwerff.iptv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class XtreamUrlParserTest {
    @Test
    fun parsesStandardGetPhpUrl() {
        val parsed = XtreamUrlParser.parse(
            "http://provider.example/get.php?username=user123&password=secret456&type=m3u_plus&output=ts",
        )
        assertNotNull(parsed)
        assertEquals("http://provider.example", parsed?.host)
        assertEquals("user123", parsed?.username)
        assertEquals("secret456", parsed?.password)
    }

    @Test
    fun preservesPortAndPrefixPath() {
        val parsed = XtreamUrlParser.parse(
            "https://provider.example:8443/iptv/get.php?username=a%2Bb&password=p%40ss&type=m3u_plus",
        )
        assertNotNull(parsed)
        assertEquals("https://provider.example:8443/iptv", parsed?.host)
        assertEquals("a+b", parsed?.username)
        assertEquals("p@ss", parsed?.password)
    }

    @Test
    fun acceptsMissingScheme() {
        val parsed = XtreamUrlParser.parse(
            "provider.example/get.php?username=user&password=pass",
        )
        assertEquals("http://provider.example", parsed?.host)
    }

    @Test
    fun rejectsOrdinaryM3uUrl() {
        assertNull(XtreamUrlParser.parse("https://provider.example/list.m3u"))
    }

    @Test
    fun rejectsMissingCredentials() {
        assertNull(XtreamUrlParser.parse("https://provider.example/get.php?username=user"))
    }
}
