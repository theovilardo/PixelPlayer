package com.theveloper.pixelplay.data.network.lastfm

import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Retrofit interface for the Last.fm API.
 *
 * All write-capable methods (auth, scrobble, nowPlaying) use POST over HTTPS
 * as required by Last.fm documentation.
 *
 * Base URL: https://ws.audioscrobbler.com/2.0/
 *
 * Each call includes:
 *   - method   : the Last.fm API method name
 *   - api_key  : application key (injected via BuildConfig)
 *   - api_sig  : HMAC-style MD5 signature (see LastFmAuthHelper)
 *   - format   : "json" (so responses are JSON, not XML)
 *
 * Refs:
 *   https://www.last.fm/api/mobileauth
 *   https://www.last.fm/api/show/track.updateNowPlaying
 *   https://www.last.fm/api/show/track.scrobble
 */
interface LastFmApiService {

    /**
     * Authenticate a user via username + password (mobile auth flow).
     * Returns a session key to be stored and reused for all subsequent calls.
     *
     * Required fields: method, username, password, api_key, api_sig
     * The `method` value MUST be "auth.getMobileSession".
     */
    @FormUrlEncoded
    @POST(".")
    suspend fun getMobileSession(
        @FieldMap fields: Map<String, String>
    ): LastFmSessionResponse

    /**
     * Notify Last.fm that the user has started listening to a track.
     * Should be called at the start of playback.
     *
     * Required fields: method, artist, track, api_key, api_sig, sk
     * Optional: album, trackNumber, duration, albumArtist
     * The `method` value MUST be "track.updateNowPlaying".
     */
    @FormUrlEncoded
    @POST(".")
    suspend fun updateNowPlaying(
        @FieldMap fields: Map<String, String>
    ): LastFmNowPlayingResponse

    /**
     * Scrobble a single track (record a play in the user's history).
     *
     * Per Scrobbling 2.0 spec:
     *   - Track must be longer than 30 seconds.
     *   - Track must have been played for at least half its duration OR 4 minutes.
     *
     * Required fields: method, artist[0], track[0], timestamp[0], api_key, api_sig, sk
     * Optional: album[0], trackNumber[0], duration[0], albumArtist[0]
     * The `method` value MUST be "track.scrobble".
     */
    @FormUrlEncoded
    @POST(".")
    suspend fun scrobble(
        @FieldMap fields: Map<String, String>
    ): LastFmScrobbleResponse
}
