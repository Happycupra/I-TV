package nl.vanvrouwerff.iptv.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val sourceConfig: Flow<SourceConfig?> = context.dataStore.data.map { prefs ->
        when (prefs[TYPE]) {
            TYPE_M3U -> prefs[M3U_URL]?.takeIf { it.isNotBlank() }?.let(SourceConfig::M3u)
            TYPE_XTREAM -> {
                val host = prefs[XT_HOST]?.trim().orEmpty()
                val user = prefs[XT_USER].orEmpty()
                val pass = prefs[XT_PASS].orEmpty()
                if (host.isNotBlank() && user.isNotBlank()) {
                    SourceConfig.Xtream(host, user, pass)
                } else null
            }
            else -> null
        }
    }

    val playlistEtag: Flow<String?> = context.dataStore.data.map { it[PLAYLIST_ETAG] }
    val playlistLastModified: Flow<String?> = context.dataStore.data.map { it[PLAYLIST_LAST_MODIFIED] }

    /**
     * Currently-active profile. Defaults to the built-in "default" profile seeded by
     * the database; anything the app reads before the user has picked a different profile
     * points at that one, so behaviour is identical to the pre-profile build.
     */
    val activeProfileId: Flow<String> =
        context.dataStore.data.map { it[ACTIVE_PROFILE_ID] ?: DEFAULT_PROFILE_ID }

    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit { prefs -> prefs[ACTIVE_PROFILE_ID] = id }
    }

    /**
     * Per-profile last-watched channel id. Each profile gets its own key so switching
     * profiles instantly swaps the hero banner without a single read of the others'
     * history. Returns null for profiles that have never played anything.
     */
    fun lastWatchedChannelId(profileId: String): Flow<String?> =
        context.dataStore.data.map { it[lastWatchedKey(profileId)] }

    suspend fun setLastWatched(profileId: String, channelId: String) {
        context.dataStore.edit { prefs -> prefs[lastWatchedKey(profileId)] = channelId }
    }

    /**
     * Per-profile recent search queries, newest first, capped to RECENT_SEARCHES_MAX.
     * Stored as a single \n-separated string — DataStore Preferences has no list
     * primitive and Proto DataStore would be overkill for five strings per profile.
     */
    fun recentSearches(profileId: String): Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            prefs[recentSearchesKey(profileId)]
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    suspend fun pushRecentSearch(profileId: String, query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val key = recentSearchesKey(profileId)
        context.dataStore.edit { prefs ->
            val existing = prefs[key]
                ?.split('\n')
                ?.filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                ?: emptyList()
            prefs[key] = (listOf(q) + existing).take(RECENT_SEARCHES_MAX).joinToString("\n")
        }
    }

    suspend fun clearRecentSearches(profileId: String) {
        context.dataStore.edit { prefs -> prefs.remove(recentSearchesKey(profileId)) }
    }

    /** Drop every per-profile key when a profile is deleted — no orphan prefs. */
    suspend fun wipeProfilePrefs(profileId: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(lastWatchedKey(profileId))
            prefs.remove(recentSearchesKey(profileId))
        }
    }

    private fun lastWatchedKey(profileId: String) =
        stringPreferencesKey("last_watched_id::$profileId")

    private fun recentSearchesKey(profileId: String) =
        stringPreferencesKey("recent_searches::$profileId")

    /** Epoch millis of the last successful catalogue refresh. 0 = never. */
    val lastRefreshSuccessAt: Flow<Long> = context.dataStore.data.map { it[LAST_REFRESH_AT] ?: 0L }

    suspend fun markRefreshSuccess(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[LAST_REFRESH_AT] = nowMs }
    }

    val lastLiveRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_LIVE_REFRESH_AT] ?: 0L }
    val lastMoviesRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_MOVIES_REFRESH_AT] ?: 0L }
    val lastSeriesRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_SERIES_REFRESH_AT] ?: 0L }
    val lastLiveCount: Flow<Int> = context.dataStore.data.map { it[LAST_LIVE_COUNT] ?: 0 }
    val lastMoviesCount: Flow<Int> = context.dataStore.data.map { it[LAST_MOVIES_COUNT] ?: 0 }
    val lastSeriesCount: Flow<Int> = context.dataStore.data.map { it[LAST_SERIES_COUNT] ?: 0 }
    val providerFailureStreak: Flow<Int> = context.dataStore.data.map { it[PROVIDER_FAILURE_STREAK] ?: 0 }
    val lastProviderError: Flow<String> = context.dataStore.data.map { it[LAST_PROVIDER_ERROR].orEmpty() }
    val catalogueSourceKey: Flow<String> = context.dataStore.data.map { it[CATALOGUE_SOURCE_KEY].orEmpty() }

    suspend fun markSectionSuccess(type: String, count: Int, nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            when (type) {
                "TV" -> { prefs[LAST_LIVE_REFRESH_AT] = nowMs; prefs[LAST_LIVE_COUNT] = count }
                "MOVIE" -> { prefs[LAST_MOVIES_REFRESH_AT] = nowMs; prefs[LAST_MOVIES_COUNT] = count }
                "SERIES" -> { prefs[LAST_SERIES_REFRESH_AT] = nowMs; prefs[LAST_SERIES_COUNT] = count }
            }
        }
    }

    suspend fun markProviderFailure(error: String) {
        context.dataStore.edit { prefs ->
            prefs[PROVIDER_FAILURE_STREAK] = ((prefs[PROVIDER_FAILURE_STREAK] ?: 0) + 1).coerceAtMost(999)
            prefs[LAST_PROVIDER_ERROR] = error.take(300)
        }
    }

    suspend fun markProviderRecovered() {
        context.dataStore.edit { prefs ->
            prefs[PROVIDER_FAILURE_STREAK] = 0
            prefs.remove(LAST_PROVIDER_ERROR)
        }
    }

    suspend fun setCatalogueSourceKey(value: String) {
        context.dataStore.edit { prefs -> prefs[CATALOGUE_SOURCE_KEY] = value }
    }

    val lastEpgRefreshAt: Flow<Long> = context.dataStore.data.map { it[LAST_EPG_REFRESH_AT] ?: 0L }

    suspend fun markEpgRefresh(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[LAST_EPG_REFRESH_AT] = nowMs }
    }

    /** Version of the catalogue format stored in Room; below the app's version forces a full reload. */
    val catalogueVersion: Flow<Int> = context.dataStore.data.map { it[CATALOGUE_VERSION] ?: 0 }

    suspend fun setCatalogueVersion(version: Int) {
        context.dataStore.edit { prefs -> prefs[CATALOGUE_VERSION] = version }
    }

    /**
     * Epoch millis of the last time the user picked a profile (cold start picker or the
     * "Wisselen van profiel" shortcut). Used to gate the cold-start profile picker —
     * within [PROFILE_SESSION_WINDOW_MS] of the last pick the picker is skipped and the
     * app boots straight into Channels.
     */
    val lastProfileSessionAt: Flow<Long> =
        context.dataStore.data.map { it[LAST_PROFILE_SESSION_AT] ?: 0L }

    suspend fun setLastProfileSessionAt(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[LAST_PROFILE_SESSION_AT] = nowMs }
    }

    /**
     * Automatic nightly refresh. Disabled by default so we don't silently burn the user's
     * bandwidth if they never open Instellingen. Hour is 0..23 in local time; minutes are
     * always :00 — a single knob is enough and avoids a fiddly minute-picker on the remote.
     */
    val autoRefreshEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_REFRESH_ENABLED] ?: false }

    val autoRefreshHour: Flow<Int> =
        context.dataStore.data.map { (it[AUTO_REFRESH_HOUR] ?: DEFAULT_AUTO_REFRESH_HOUR).coerceIn(0, 23) }

    /** Hero trailers start by themselves after a short idle; users on slow boxes can opt out. */
    val trailersAutoplay: Flow<Boolean> =
        context.dataStore.data.map { it[TRAILERS_AUTOPLAY] ?: true }

    suspend fun setTrailersAutoplay(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[TRAILERS_AUTOPLAY] = enabled }
    }

    /** Four-digit parental PIN; empty when none is set. */
    val parentalPin: Flow<String> = context.dataStore.data.map { it[PARENTAL_PIN].orEmpty() }

    suspend fun setParentalPin(pin: String) {
        context.dataStore.edit { prefs -> if (pin.isBlank()) prefs.remove(PARENTAL_PIN) else prefs[PARENTAL_PIN] = pin }
    }

    /** Tunneled playback: the video hardware keeps audio and video in sync. */
    val hardwareAvSync: Flow<Boolean> =
        context.dataStore.data.map { it[HARDWARE_AV_SYNC] ?: true }

    suspend fun setHardwareAvSync(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[HARDWARE_AV_SYNC] = enabled }
    }

    /** Switch the display refresh rate to the stream's frame rate while playing. */
    val frameRateMatching: Flow<Boolean> =
        context.dataStore.data.map { it[FRAME_RATE_MATCHING] ?: true }

    suspend fun setFrameRateMatching(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[FRAME_RATE_MATCHING] = enabled }
    }

    /** Player defaults. Aspect: "FIT" | "FILL" | "ZOOM". Languages: ISO 639-1, "" = no preference. */
    val playerAspect: Flow<String> = context.dataStore.data.map { it[PLAYER_ASPECT] ?: "FIT" }
    val preferredAudioLanguage: Flow<String> = context.dataStore.data.map { it[AUDIO_LANGUAGE] ?: "" }
    /** "" = no preference, "off" = subtitles off by default, else an ISO 639-1 code. */
    val preferredSubtitleLanguage: Flow<String> = context.dataStore.data.map { it[SUBTITLE_LANGUAGE] ?: "" }

    suspend fun setPlayerAspect(value: String) {
        context.dataStore.edit { prefs -> prefs[PLAYER_ASPECT] = value }
    }

    suspend fun setPreferredAudioLanguage(value: String) {
        context.dataStore.edit { prefs -> prefs[AUDIO_LANGUAGE] = value }
    }

    suspend fun setPreferredSubtitleLanguage(value: String) {
        context.dataStore.edit { prefs -> prefs[SUBTITLE_LANGUAGE] = value }
    }

    suspend fun setAutoRefreshEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_REFRESH_ENABLED] = enabled }
    }

    suspend fun setAutoRefreshHour(hour: Int) {
        context.dataStore.edit { prefs -> prefs[AUTO_REFRESH_HOUR] = hour.coerceIn(0, 23) }
    }

    /**
     * Comma-separated country/language codes matched against Xtream category prefixes
     * ("┃NL┃ …", "|UK| …"). Blank = keep the whole catalogue.
     */
    val categoryFilter: Flow<String> =
        context.dataStore.data.map { it[CATEGORY_FILTER] ?: DEFAULT_CATEGORY_FILTER }

    suspend fun setCategoryFilter(raw: String) {
        context.dataStore.edit { prefs ->
            if ((prefs[CATEGORY_FILTER] ?: DEFAULT_CATEGORY_FILTER) != raw.trim()) {
                prefs.remove(LAST_REFRESH_AT)
                prefs.remove(CATALOGUE_VERSION)
                prefs.remove(LAST_EPG_REFRESH_AT)
            }
            prefs[CATEGORY_FILTER] = raw.trim()
            prefs.remove(PLAYLIST_ETAG); prefs.remove(PLAYLIST_LAST_MODIFIED)
        }
    }

    suspend fun saveM3u(url: String) = saveSource(SourceConfig.M3u(url))

    suspend fun saveXtream(host: String, username: String, password: String) =
        saveSource(SourceConfig.Xtream(host, username, password))

    /** Source and filter form one configuration; a refresh must never see half a save. */
    suspend fun saveSource(source: SourceConfig, categoryFilter: String? = null) {
        context.dataStore.edit { prefs ->
            val sourceChanged = when (source) {
                is SourceConfig.M3u -> prefs[TYPE] != TYPE_M3U || prefs[M3U_URL] != source.url.trim()
                is SourceConfig.Xtream -> prefs[TYPE] != TYPE_XTREAM ||
                    prefs[XT_HOST] != source.host.trim().trimEnd('/') ||
                    prefs[XT_USER] != source.username.trim() || prefs[XT_PASS] != source.password
            }
            val filterChanged = categoryFilter != null &&
                (prefs[CATEGORY_FILTER] ?: DEFAULT_CATEGORY_FILTER) != categoryFilter.trim()
            when (source) {
                is SourceConfig.M3u -> {
                    prefs[TYPE] = TYPE_M3U
                    prefs[M3U_URL] = source.url.trim()
                    prefs.remove(XT_HOST); prefs.remove(XT_USER); prefs.remove(XT_PASS)
                }
                is SourceConfig.Xtream -> {
                    prefs[TYPE] = TYPE_XTREAM
                    prefs[XT_HOST] = source.host.trim().trimEnd('/')
                    prefs[XT_USER] = source.username.trim()
                    prefs[XT_PASS] = source.password
                    prefs.remove(M3U_URL)
                }
            }
            if (categoryFilter != null) prefs[CATEGORY_FILTER] = categoryFilter.trim()
            if (sourceChanged || filterChanged) {
                prefs.remove(LAST_REFRESH_AT)
                prefs.remove(CATALOGUE_VERSION)
                prefs.remove(LAST_EPG_REFRESH_AT)
                prefs.remove(PLAYLIST_ETAG)
                prefs.remove(PLAYLIST_LAST_MODIFIED)
            }
        }
    }

    suspend fun savePlaylistValidators(etag: String?, lastModified: String?) {
        context.dataStore.edit { prefs ->
            if (etag != null) prefs[PLAYLIST_ETAG] = etag else prefs.remove(PLAYLIST_ETAG)
            if (lastModified != null) prefs[PLAYLIST_LAST_MODIFIED] = lastModified
            else prefs.remove(PLAYLIST_LAST_MODIFIED)
        }
    }

    private companion object {
        val TYPE = stringPreferencesKey("source_type")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val XT_HOST = stringPreferencesKey("xt_host")
        val XT_USER = stringPreferencesKey("xt_user")
        val XT_PASS = stringPreferencesKey("xt_pass")
        val PLAYLIST_ETAG = stringPreferencesKey("playlist_etag")
        val PLAYLIST_LAST_MODIFIED = stringPreferencesKey("playlist_last_modified")
        val LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")
        val LAST_LIVE_REFRESH_AT = longPreferencesKey("last_live_refresh_at")
        val LAST_MOVIES_REFRESH_AT = longPreferencesKey("last_movies_refresh_at")
        val LAST_SERIES_REFRESH_AT = longPreferencesKey("last_series_refresh_at")
        val LAST_LIVE_COUNT = intPreferencesKey("last_live_count")
        val LAST_MOVIES_COUNT = intPreferencesKey("last_movies_count")
        val LAST_SERIES_COUNT = intPreferencesKey("last_series_count")
        val PROVIDER_FAILURE_STREAK = intPreferencesKey("provider_failure_streak")
        val LAST_PROVIDER_ERROR = stringPreferencesKey("last_provider_error")
        val CATALOGUE_SOURCE_KEY = stringPreferencesKey("catalogue_source_key")
        val LAST_EPG_REFRESH_AT = longPreferencesKey("last_epg_refresh_at")
        val CATALOGUE_VERSION = androidx.datastore.preferences.core.intPreferencesKey("catalogue_version")
        val LAST_PROFILE_SESSION_AT = longPreferencesKey("last_profile_session_at")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val AUTO_REFRESH_ENABLED = booleanPreferencesKey("auto_refresh_enabled")
        val TRAILERS_AUTOPLAY = booleanPreferencesKey("trailers_autoplay")
        val HARDWARE_AV_SYNC = booleanPreferencesKey("hardware_av_sync")
        val PARENTAL_PIN = stringPreferencesKey("parental_pin")
        val FRAME_RATE_MATCHING = booleanPreferencesKey("frame_rate_matching")
        val PLAYER_ASPECT = stringPreferencesKey("player_aspect")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val AUTO_REFRESH_HOUR = intPreferencesKey("auto_refresh_hour")
        val CATEGORY_FILTER = stringPreferencesKey("category_filter")
        const val TYPE_M3U = "m3u"
        const val TYPE_XTREAM = "xtream"
        const val RECENT_SEARCHES_MAX = 6
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_AUTO_REFRESH_HOUR = 3
        const val DEFAULT_CATEGORY_FILTER = "NL, UK, US, USA, EN"
    }
}
