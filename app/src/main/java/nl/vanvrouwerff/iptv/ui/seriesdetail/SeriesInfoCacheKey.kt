package nl.vanvrouwerff.iptv.ui.seriesdetail

import java.security.MessageDigest
import nl.vanvrouwerff.iptv.data.settings.SourceConfig

/** Provider IDs are local to an account; a cache entry must never cross sources. */
internal fun seriesInfoCacheKey(config: SourceConfig.Xtream, seriesId: String): String {
    val source = listOf(
        config.host.trim().trimEnd('/'),
        config.username.trim(),
        config.password,
    ).joinToString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "xtream:$digest:$seriesId"
}
