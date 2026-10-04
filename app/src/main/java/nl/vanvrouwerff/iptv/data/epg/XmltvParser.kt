package nl.vanvrouwerff.iptv.data.epg

import android.util.Xml
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.TimeZone

/**
 * Minimal streaming XMLTV parser. Reads `<programme>` elements one at a time so we never
 * hold the whole document in memory — EPG feeds from big Xtream providers are routinely
 * 30-50 MB.
 *
 * XMLTV timestamps look like "20260420183000 +0200" or plain "20260420183000" (UTC). Only
 * the subset we actually need (title, desc, start/stop, channel) is parsed; everything
 * else is ignored.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        checkCancelled: () -> Unit = {},
        keepChannel: (String) -> Boolean = { true },
    ): List<ProgrammeEntity> {
        val programmes = mutableListOf<ProgrammeEntity>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            checkCancelled()
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")?.trim()
                if (channel != null && keepChannel(channel)) {
                    readProgramme(parser, checkCancelled)?.let(programmes::add)
                } else {
                    skipElement(parser, checkCancelled)
                }
            } else {
                // Fast-skip: don't descend into <channel> etc, we only need <programme>.
                event = parser.next()
                continue
            }
            event = parser.next()
        }
        return programmes
    }

    private fun readProgramme(parser: XmlPullParser, checkCancelled: () -> Unit): ProgrammeEntity? {
        val startAttr = parser.getAttributeValue(null, "start")
        val stopAttr = parser.getAttributeValue(null, "stop")
        val channel = parser.getAttributeValue(null, "channel")?.trim()?.takeIf { it.isNotBlank() }

        val startMs = startAttr?.let(::parseXmltvTime)
        if (channel == null || startMs == null) {
            skipElement(parser, checkCancelled)
            return null
        }
        val stopMs = if (stopAttr == null) startMs + DEFAULT_DURATION_MS else parseXmltvTime(stopAttr)

        var title: String? = null
        var description: String? = null

        var depth = 1
        while (depth > 0) {
            checkCancelled()
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> readText(parser, checkCancelled).also { depth-- }.let { if (title.isNullOrBlank()) title = it }
                        "desc" -> readText(parser, checkCancelled).also { depth-- }.let { if (description.isNullOrBlank()) description = it }
                        else -> skipElement(parser, checkCancelled).also { depth-- }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        val safeTitle = title?.takeIf { it.isNotBlank() } ?: return null
        if (stopMs == null || stopMs <= startMs) return null
        return ProgrammeEntity(
            channelKey = channel,
            startMs = startMs,
            stopMs = stopMs,
            title = safeTitle,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    private fun readText(parser: XmlPullParser, checkCancelled: () -> Unit): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            checkCancelled()
            when (parser.next()) {
                XmlPullParser.TEXT -> if (depth == 1) sb.append(parser.text ?: "")
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return sb.toString().trim()
    }

    private fun skipElement(parser: XmlPullParser, checkCancelled: () -> Unit) {
        var depth = 1
        while (depth > 0) {
            checkCancelled()
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Accepts "YYYYMMDDhhmm[ss]" (UTC) optionally followed by a "+0200"-style offset.
     * Returns epoch millis, or null if unparseable.
     */
    internal fun parseXmltvTime(raw: String): Long? {
        val trimmed = raw.trim()
        val match = TIMESTAMP.matchEntire(trimmed) ?: return null
        val timestamp = match.groupValues[1]
        val digits = timestamp.length
        val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
        val month = trimmed.substring(4, 6).toIntOrNull() ?: return null
        val day = trimmed.substring(6, 8).toIntOrNull() ?: return null
        val hour = trimmed.substring(8, 10).toIntOrNull() ?: return null
        val minute = trimmed.substring(10, 12).toIntOrNull() ?: return null
        val second = if (digits >= 14) trimmed.substring(12, 14).toIntOrNull() ?: return null else 0

        val offset = match.groupValues[2]
        val offsetMin = if (offset.startsWith('+') || offset.startsWith('-')) {
            val sign = when (offset[0]) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            val h = offset.substring(1, 3).toIntOrNull() ?: return null
            val m = offset.substring(3, 5).toIntOrNull() ?: return null
            if (h > 23 || m > 59) return null
            sign * (h * 60 + m)
        } else {
            0
        }

        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.isLenient = false
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return try { cal.timeInMillis - offsetMin * 60_000L } catch (_: IllegalArgumentException) { null }
    }

    private val TIMESTAMP = Regex("([0-9]{12}(?:[0-9]{2})?)(?:\\s*([+-][0-9]{4}|UTC|GMT|Z))?", RegexOption.IGNORE_CASE)
    private const val DEFAULT_DURATION_MS = 30L * 60_000L
}
