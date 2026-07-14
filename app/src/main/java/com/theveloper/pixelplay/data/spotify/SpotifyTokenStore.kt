@file:Suppress("DEPRECATION")

package com.theveloper.pixelplay.data.spotify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.data.spotify.model.SpotifyCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class SpotifyTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Timber.e(e, "SpotifyTokenStore: encrypted prefs unavailable, using plain prefs")
        context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    fun save(credentials: SpotifyCredentials) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, credentials.clientId)
            .putString(KEY_ACCESS_TOKEN, credentials.accessToken)
            .putString(KEY_REFRESH_TOKEN, credentials.refreshToken)
            .putString(KEY_TOKEN_TYPE, credentials.tokenType)
            .putLong(KEY_EXPIRES_AT, credentials.expiresAtEpochMs)
            .putString(KEY_SCOPE, credentials.scope)
            .putString(KEY_DISPLAY_NAME, credentials.displayName)
            .apply()
    }

    fun load(): SpotifyCredentials? {
        val clientId = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyCredentials(
            clientId = clientId,
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            tokenType = prefs.getString(KEY_TOKEN_TYPE, null) ?: "Bearer",
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            scope = prefs.getString(KEY_SCOPE, null),
            displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "spotify_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SCOPE = "scope"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}

