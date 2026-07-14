package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.network.spotify.SpotifyAuthApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyResponseParser
import com.theveloper.pixelplay.data.spotify.model.SpotifyCredentials
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthManager @Inject constructor(
    private val authApi: SpotifyAuthApiService,
    private val tokenStore: SpotifyTokenStore
) {

    fun loadCredentials(): SpotifyCredentials? = tokenStore.load()

    fun clear() = tokenStore.clear()

    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): SpotifyCredentials {
        val response = authApi.exchangeAuthorizationCode(
            code = code,
            redirectUri = redirectUri,
            clientId = clientId,
            codeVerifier = codeVerifier
        )
        return SpotifyCredentials(
            clientId = clientId,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            tokenType = response.tokenType,
            expiresAtEpochMs = System.currentTimeMillis() + response.expiresIn * 1000L,
            scope = response.scope
        ).also(tokenStore::save)
    }

    suspend fun refreshIfNeeded(credentials: SpotifyCredentials?): SpotifyCredentials? {
        if (credentials == null || !credentials.isExpired) return credentials
        val refreshToken = credentials.refreshToken?.takeIf { it.isNotBlank() } ?: return credentials
        val response = authApi.refreshAccessToken(
            refreshToken = refreshToken,
            clientId = credentials.clientId
        )
        return credentials.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: credentials.refreshToken,
            tokenType = response.tokenType,
            expiresAtEpochMs = System.currentTimeMillis() + response.expiresIn * 1000L,
            scope = response.scope ?: credentials.scope
        ).also(tokenStore::save)
    }

    fun withDisplayName(credentials: SpotifyCredentials, displayName: String): SpotifyCredentials {
        return credentials.copy(displayName = displayName).also(tokenStore::save)
    }
}

