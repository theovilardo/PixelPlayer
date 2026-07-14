package com.theveloper.pixelplay.data.spotify.model

data class SpotifyCredentials(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String = "Bearer",
    val expiresAtEpochMs: Long,
    val scope: String? = null,
    val displayName: String? = null
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAtEpochMs - TOKEN_EXPIRY_SKEW_MS

    val authorizationHeader: String
        get() = "$tokenType $accessToken"

    companion object {
        private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
    }
}

