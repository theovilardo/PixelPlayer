package com.theveloper.pixelplay.presentation.spotify.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SpotifyLoginState {
    data object Idle : SpotifyLoginState
    data object Loading : SpotifyLoginState
    data class AwaitingBrowser(val authUri: Uri) : SpotifyLoginState
    data class Success(val displayName: String) : SpotifyLoginState
    data class Error(val message: String) : SpotifyLoginState
}

private const val SPOTIFY_SCOPES =
    "user-library-read playlist-read-private playlist-read-collaborative " +
        "user-read-playback-state user-modify-playback-state user-read-currently-playing"

object SpotifyAuthConstants {
    const val DEFAULT_REDIRECT_URI = "pixelplay://spotify-auth"
}

@HiltViewModel
class SpotifyLoginViewModel @Inject constructor(
    private val repository: SpotifyRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SpotifyLoginState>(SpotifyLoginState.Idle)
    val state: StateFlow<SpotifyLoginState> = _state.asStateFlow()

    private var expectedState: String? = null
    private var codeVerifier: String? = null
    private var spotifyClientId: String? = null
    private var redirectUri: String = SpotifyAuthConstants.DEFAULT_REDIRECT_URI

    fun startLogin(clientId: String, redirectUriOverride: String = SpotifyAuthConstants.DEFAULT_REDIRECT_URI) {
        if (_state.value is SpotifyLoginState.Loading) return

        val trimmedClientId = clientId.trim()
        if (trimmedClientId.isBlank()) {
            _state.value = SpotifyLoginState.Error("Enter your Spotify client ID")
            return
        }

        redirectUri = redirectUriOverride.trim().ifBlank { SpotifyAuthConstants.DEFAULT_REDIRECT_URI }
        val verifier = createCodeVerifier()
        val stateValue = createRandomUrlSafeString(18)
        spotifyClientId = trimmedClientId
        codeVerifier = verifier
        expectedState = stateValue

        val authUri = Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .appendPath("authorize")
            .appendQueryParameter("client_id", trimmedClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", createCodeChallenge(verifier))
            .appendQueryParameter("state", stateValue)
            .appendQueryParameter("scope", SPOTIFY_SCOPES)
            .build()

        _state.value = SpotifyLoginState.AwaitingBrowser(authUri)
    }

    fun handleCallback(code: String?, state: String?, error: String?) {
        if (!error.isNullOrBlank()) {
            _state.value = SpotifyLoginState.Error(error)
            return
        }

        val verifier = codeVerifier
        val expected = expectedState
        val clientId = spotifyClientId
        if (code.isNullOrBlank() || verifier.isNullOrBlank() || expected.isNullOrBlank() || clientId.isNullOrBlank()) {
            _state.value = SpotifyLoginState.Error("Spotify login session expired")
            return
        }

        if (state != expected) {
            _state.value = SpotifyLoginState.Error("Spotify login state mismatch")
            return
        }

        viewModelScope.launch {
            _state.value = SpotifyLoginState.Loading
            val result = repository.completeAuthorization(
                clientId = clientId,
                code = code,
                codeVerifier = verifier,
                redirectUri = redirectUri
            )
            _state.value = result.fold(
                onSuccess = { SpotifyLoginState.Success(it) },
                onFailure = { SpotifyLoginState.Error(it.message ?: "Spotify login failed") }
            )
            codeVerifier = null
            expectedState = null
            spotifyClientId = null
        }
    }

    fun clearTransientState() {
        if (_state.value is SpotifyLoginState.AwaitingBrowser || _state.value is SpotifyLoginState.Error) {
            _state.value = SpotifyLoginState.Idle
        }
    }

    private fun createCodeVerifier(): String = createRandomUrlSafeString(64)

    private fun createCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun createRandomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
