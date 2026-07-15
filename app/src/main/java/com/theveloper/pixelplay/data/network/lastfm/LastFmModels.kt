package com.theveloper.pixelplay.data.network.lastfm

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// auth.getMobileSession response
// Ref: https://www.last.fm/api/mobileauth
// ---------------------------------------------------------------------------
data class LastFmSessionResponse(
    @SerializedName("session") val session: LastFmSession?
)

data class LastFmSession(
    @SerializedName("name") val name: String,
    @SerializedName("key") val key: String,
    @SerializedName("subscriber") val subscriber: Int
)

// ---------------------------------------------------------------------------
// track.updateNowPlaying response
// Ref: https://www.last.fm/api/show/track.updateNowPlaying
// ---------------------------------------------------------------------------
data class LastFmNowPlayingResponse(
    @SerializedName("nowplaying") val nowPlaying: LastFmNowPlayingResult?
)

data class LastFmNowPlayingResult(
    @SerializedName("artist") val artist: LastFmCorrectedValue?,
    @SerializedName("track") val track: LastFmCorrectedValue?,
    @SerializedName("album") val album: LastFmCorrectedValue?,
    @SerializedName("albumartist") val albumArtist: LastFmCorrectedValue?
)

// ---------------------------------------------------------------------------
// track.scrobble response
// Ref: https://www.last.fm/api/show/track.scrobble
// ---------------------------------------------------------------------------
data class LastFmScrobbleResponse(
    @SerializedName("scrobbles") val scrobbles: LastFmScrobblesWrapper?
)

data class LastFmScrobblesWrapper(
    @SerializedName("scrobble") val scrobble: LastFmScrobbleResult?,
    @SerializedName("@attr") val attr: LastFmScrobblesAttr?
)

data class LastFmScrobbleResult(
    @SerializedName("artist") val artist: LastFmCorrectedValue?,
    @SerializedName("track") val track: LastFmCorrectedValue?,
    @SerializedName("album") val album: LastFmCorrectedValue?,
    @SerializedName("ignoredMessage") val ignoredMessage: LastFmIgnoredMessage?
)

data class LastFmIgnoredMessage(
    @SerializedName("code") val code: Int,
    @SerializedName("#text") val text: String
)

data class LastFmScrobblesAttr(
    @SerializedName("accepted") val accepted: Int,
    @SerializedName("ignored") val ignored: Int
)

/**
 * Shared wrapper for correctable string values returned by Last.fm
 * (artist, track, album). The `corrected` field is "1" if Last.fm
 * applied a correction, "0" otherwise.
 */
data class LastFmCorrectedValue(
    @SerializedName("#text") val text: String,
    @SerializedName("corrected") val corrected: String
)

// ---------------------------------------------------------------------------
// Error envelope
// Last.fm returns HTTP 200 even on errors, with a JSON body:
// { "error": <code>, "message": "<description>" }
// ---------------------------------------------------------------------------
data class LastFmErrorResponse(
    @SerializedName("error") val error: Int,
    @SerializedName("message") val message: String
)
