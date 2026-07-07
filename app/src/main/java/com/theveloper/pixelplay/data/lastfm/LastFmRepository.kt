package com.theveloper.pixelplay.data.lastfm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.network.lastfm.LastFmApiService
import com.theveloper.pixelplay.data.network.lastfm.buildLastFmSignature
import com.theveloper.pixelplay.data.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Last.fm authentication state, session key storage,
 * and the two active scrobbling operations:
 *   - updateNowPlaying : called when a track starts
 *   - scrobble         : called once the track qualifies (>30 s played
 *                        AND >= half duration or 4 minutes)
 *
 * Auth uses the mobile auth flow (auth.getMobileSession) which accepts
 * a username + password directly over HTTPS — no browser redirect needed.
 *
 * Ref: https://www.last.fm/api/mobileauth
 */
@Singleton
class LastFmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: LastFmApiService
) {

    // -----------------------------------------------------------------------
    // DataStore keys
    // -----------------------------------------------------------------------
    private val KEY_SESSION = stringPreferencesKey("lastfm_session_key")
    private val KEY_USERNAME = stringPreferencesKey("lastfm_username")

    // -----------------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------------

    /** Emits the stored session key, or null when not logged in. */
    val sessionKeyFlow: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[KEY_SESSION] }

    /** Emits the Last.fm username, or null when not logged in. */
    val usernameFlow: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[KEY_USERNAME] }

    /** True when a valid session key is persisted. */
    val isLoggedInFlow: Flow<Boolean> = sessionKeyFlow.map { it != null }

    // Internal cache so DualPlayerEngine can read synchronously
    private val _sessionKey = MutableStateFlow<String?>(null)
    val sessionKey = _sessionKey.asStateFlow()

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    /**
     * Authenticate with Last.fm using username + password.
     * On success, persists the session key and username to DataStore.
     *
     * @return true on success, false on failure.
     */
    suspend fun login(username: String, password: String): Boolean {
        return try {
            val params = buildParams(
                "method" to "auth.getMobileSession",
                "username" to username,
                "password" to password
            )
            val response = apiService.getMobileSession(params)
            val key = response.session?.key
            if (key != null) {
                context.dataStore.edit { prefs ->
                    prefs[KEY_SESSION] = key
                    prefs[KEY_USERNAME] = username
                }
                _sessionKey.value = key
                Timber.tag(TAG).i("Last.fm login successful for $username")
                true
            } else {
                Timber.tag(TAG).w("Last.fm login returned null session")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Last.fm login failed")
            false
        }
    }

    /** Clear the stored session and mark the user as logged out. */
    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SESSION)
            prefs.remove(KEY_USERNAME)
        }
        _sessionKey.value = null
        Timber.tag(TAG).i("Last.fm session cleared")
    }

    /** Warm the in-memory cache from DataStore on app start. */
    suspend fun warmSessionCache(sk: String?) {
        _sessionKey.value = sk
    }

    // -----------------------------------------------------------------------
    // Scrobbling
    // -----------------------------------------------------------------------

    /**
     * Notify Last.fm that the user has started playing a track.
     * Should be called immediately when a track begins.
     *
     * Ref: https://www.last.fm/api/show/track.updateNowPlaying
     */
    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        duration: Long? = null
    ) {
        val sk = _sessionKey.value ?: return
        try {
            val extra = mutableMapOf(
                "method" to "track.updateNowPlaying",
                "artist" to artist,
                "track" to track,
                "sk" to sk
            )
            album?.let { extra["album"] = it }
            duration?.let { extra["duration"] = it.toString() }
            val params = buildParams(*extra.entries.map { it.key to it.value }.toTypedArray())
            apiService.updateNowPlaying(params)
            Timber.tag(TAG).d("Now playing: $artist - $track")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "updateNowPlaying failed")
        }
    }

    /**
     * Scrobble a track to Last.fm.
     *
     * Call this once the track qualifies per the Scrobbling 2.0 spec:
     *   - Track duration > 30 seconds.
     *   - Track has been played for >= half its duration, or >= 4 minutes.
     *
     * @param startTimestamp  UNIX UTC timestamp (seconds) when playback started.
     *
     * Ref: https://www.last.fm/api/show/track.scrobble
     *      https://www.last.fm/api/scrobbling
     */
    suspend fun scrobble(
        artist: String,
        track: String,
        startTimestamp: Long,
        album: String? = null,
        duration: Long? = null
    ) {
        val sk = _sessionKey.value ?: return
        try {
            val extra = mutableMapOf(
                "method" to "track.scrobble",
                "artist[0]" to artist,
                "track[0]" to track,
                "timestamp[0]" to startTimestamp.toString(),
                "sk" to sk
            )
            album?.let { extra["album[0]"] = it }
            duration?.let { extra["duration[0]"] = it.toString() }
            val params = buildParams(*extra.entries.map { it.key to it.value }.toTypedArray())
            val response = apiService.scrobble(params)
            val accepted = response.scrobbles?.attr?.accepted ?: 0
            val ignored = response.scrobbles?.attr?.ignored ?: 0
            Timber.tag(TAG).d("Scrobbled $artist - $track (accepted=$accepted, ignored=$ignored)")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Scrobble failed")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build the full parameter map for a Last.fm API call:
     *  - Adds api_key and format automatically.
     *  - Computes and appends api_sig per the auth spec.
     */
    private fun buildParams(vararg pairs: Pair<String, String>): Map<String, String> {
        val map = mutableMapOf(*pairs)
        map["api_key"] = BuildConfig.LASTFM_API_KEY
        map["format"] = "json"
        // api_sig is computed BEFORE adding format (format is excluded per spec)
        map["api_sig"] = buildLastFmSignature(map, BuildConfig.LASTFM_SHARED_SECRET)
        return map
    }

    companion object {
        private const val TAG = "LastFmRepository"
    }
}
