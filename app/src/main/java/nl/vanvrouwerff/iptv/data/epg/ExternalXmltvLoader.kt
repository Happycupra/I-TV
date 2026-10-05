package nl.vanvrouwerff.iptv.data.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.repo.PlaylistResilience
import nl.vanvrouwerff.iptv.data.repo.retryProviderCall
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Optional external XMLTV feed, matched only against exact channel IDs in the current catalogue. */
class ExternalXmltvLoader(private val httpClient: OkHttpClient = HttpClient.okHttp) {
    suspend fun fetch(url: String, channelKeys: Set<String>): List<ProgrammeEntity> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        retryProviderCall("Externes EPG", longArrayOf(1_500L)) {
            httpClient.newCall(Request.Builder().url(url).build()).withEpgResponse { response ->
                if (!response.isSuccessful) {
                    if (PlaylistResilience.isRetryableHttp(response.code)) throw IOException("EPG HTTP ${response.code}")
                    error("EPG HTTP ${response.code}")
                }
                val body = response.body ?: error("Leere EPG-Antwort")
                EpgSources.xmlStream(body.byteStream()).use { stream ->
                    XmltvParser.parse(stream, checkCancelled = { context.ensureActive() }, keepChannel = { it in channelKeys })
                }
            }
        }
    }
}
