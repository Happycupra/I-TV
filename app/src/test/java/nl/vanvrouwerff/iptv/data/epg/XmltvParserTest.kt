package nl.vanvrouwerff.iptv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserTest {

    @Test
    fun `parses utc timestamps`() {
        assertEquals(1776709800000L, XmltvParser.parseXmltvTime("20260420183000"))
        assertEquals(1776709800000L, XmltvParser.parseXmltvTime("20260420183000 UTC"))
        assertEquals(1776709800000L, XmltvParser.parseXmltvTime("20260420183000Z"))
    }

    @Test
    fun `applies offsets with and without a space`() {
        val utc = XmltvParser.parseXmltvTime("20260420163000")
        assertEquals(utc, XmltvParser.parseXmltvTime("20260420183000 +0200"))
        assertEquals(utc, XmltvParser.parseXmltvTime("20260420183000+0200"))
    }

    @Test
    fun `accepts timestamps without seconds`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20260420183000"),
            XmltvParser.parseXmltvTime("202604201830"),
        )
    }

    @Test
    fun `rejects garbage`() {
        assertNull(XmltvParser.parseXmltvTime("2026"))
    }

    @Test fun `rejects invalid dates instead of silently shifting the guide`() {
        listOf("20260230183000", "20260420243000", "20260420186000", "20260420183060", "20260020183000").forEach {
            assertNull(it, XmltvParser.parseXmltvTime(it))
        }
    }

    @Test fun `rejects malformed timestamp suffixes and offsets`() {
        listOf("2026042018300", "202604201830000", "20260420183000 garbage", "20260420183000 +0260", "20260420183000 +2500").forEach {
            assertNull(it, XmltvParser.parseXmltvTime(it))
        }
    }

    @Test fun `handles negative offsets across a day boundary`() {
        assertEquals(XmltvParser.parseXmltvTime("20260421010000"), XmltvParser.parseXmltvTime("20260420233000 -0130"))
    }
}
