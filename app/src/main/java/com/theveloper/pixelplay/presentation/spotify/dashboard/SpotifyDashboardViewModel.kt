package com.theveloper.pixelplay.presentation.spotify.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SpotifyDashboardViewModel @Inject constructor(
    private val repository: SpotifyRepository
) : ViewModel() {

    val playlists: StateFlow<List<SpotifyPlaylistEntity>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    val displayName: String?
        get() = repository.displayName

    val lastSyncTime: Long
        get() = repository.lastSyncTime

    fun syncLibrary() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing Spotify library..."

            val result = repository.syncLibrary()

            _isSyncing.value = false
            _syncMessage.value = result.fold(
                onSuccess = { count -> "Synced $count Spotify songs" },
                onFailure = { error -> error.message ?: "Spotify sync failed" }
            )
        }
    }

    fun syncPlaylists() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing Spotify playlists..."

            val result = repository.syncPlaylists()

            _isSyncing.value = false
            _syncMessage.value = result.fold(
                onSuccess = { count -> "Synced $count Spotify playlists" },
                onFailure = { error -> error.message ?: "Spotify playlist sync failed" }
            )
        }
    }

    fun syncPlaylist(playlistId: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing playlist..."

            val result = repository.syncPlaylistTracks(playlistId)

            _isSyncing.value = false
            _syncMessage.value = result.fold(
                onSuccess = { count -> "Synced $count songs" },
                onFailure = { error -> error.message ?: "Playlist sync failed" }
            )
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deleteCachedPlaylist(playlistId)
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }
}