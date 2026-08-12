package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.TransferState
import com.theveloper.pixelplay.data.WearDeviceMusicRepository
import com.theveloper.pixelplay.data.WearDeviceSong
import com.theveloper.pixelplay.data.WearLocalPlayerRepository
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.data.WearPlaybackController
import com.theveloper.pixelplay.data.WearQueueSong
import com.theveloper.pixelplay.data.WearStateRepository
import com.theveloper.pixelplay.data.WearTransferRepository
import com.theveloper.pixelplay.data.local.LocalSongEntity
import com.theveloper.pixelplay.shared.WearPlaybackCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface WearDownloadsUiEvent {
    data class Message(val value: String) : WearDownloadsUiEvent
}

/**
 * ViewModel for managing downloaded songs and local playback on the watch.
 * Provides state for the DownloadsScreen and download indicators in SongListScreen.
 */
@HiltViewModel
class WearDownloadsViewModel @Inject constructor(
    private val transferRepository: WearTransferRepository,
    private val deviceMusicRepository: WearDeviceMusicRepository,
    private val localPlayerRepository: WearLocalPlayerRepository,
    private val playbackController: WearPlaybackController,
    private val stateRepository: WearStateRepository,
) : ViewModel() {

    companion object {
        private const val PHONE_PLAYBACK_TIMEOUT_MS = 6_000L
    }

    /** All locally stored songs (for DownloadsScreen) */
    val localSongs: StateFlow<List<LocalSongEntity>> = transferRepository.localSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Active transfer states */
    val activeTransfers: StateFlow<Map<String, TransferState>> =
        transferRepository.activeTransfers

    /** Set of song IDs already downloaded (for showing download indicators) */
    val downloadedSongIds: StateFlow<Set<String>> = transferRepository.downloadedSongIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _deviceSongs = MutableStateFlow<List<WearDeviceSong>>(emptyList())
    val deviceSongs: StateFlow<List<WearDeviceSong>> = _deviceSongs.asStateFlow()

    private val _isDeviceLibraryLoading = MutableStateFlow(false)
    val isDeviceLibraryLoading: StateFlow<Boolean> = _isDeviceLibraryLoading.asStateFlow()

    private val _deviceLibraryError = MutableStateFlow<String?>(null)
    val deviceLibraryError: StateFlow<String?> = _deviceLibraryError.asStateFlow()

    private val _pendingPhonePlaybackSongId = MutableStateFlow<String?>(null)
    val pendingPhonePlaybackSongId: StateFlow<String?> = _pendingPhonePlaybackSongId.asStateFlow()

    private val _events = MutableSharedFlow<WearDownloadsUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WearDownloadsUiEvent> = _events.asSharedFlow()

    private var pendingPhonePlaybackRequestId: String? = null
    private var phonePlaybackTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            stateRepository.playbackResults.collect { result ->
                if (result.action != WearPlaybackCommand.PLAY_ITEM) return@collect
                if (result.requestId != pendingPhonePlaybackRequestId) return@collect

                phonePlaybackTimeoutJob?.cancel()
                phonePlaybackTimeoutJob = null
                pendingPhonePlaybackRequestId = null
                _pendingPhonePlaybackSongId.value = null

                if (result.success) {
                    stateRepository.setOutputTarget(WearOutputTarget.PHONE)
                } else {
                    _events.emit(
                        WearDownloadsUiEvent.Message(
                            result.error ?: "This song is no longer available on phone"
                        )
                    )
                }
            }
        }
    }

    /**
     * Request download of a song from the phone to the watch.
     */
    fun requestDownload(songId: String) {
        transferRepository.requestTransfer(songId)
    }

    /**
     * Play a locally stored song, starting from it within the full downloads queue.
     */
    fun playLocalSong(songId: String) {
        val allSongs = localSongs.value
        val startIndex = allSongs.indexOfFirst { it.songId == songId }
        if (startIndex == -1 || allSongs.isEmpty()) return
        localPlayerRepository.playLocalSongs(allSongs, startIndex)
        stateRepository.setOutputTarget(WearOutputTarget.WATCH)
    }

    fun playSongOnPhone(songId: String) {
        if (_pendingPhonePlaybackSongId.value != null) return

        viewModelScope.launch {
            val requestId = UUID.randomUUID().toString()
            pendingPhonePlaybackRequestId = requestId
            _pendingPhonePlaybackSongId.value = songId

            val dispatched = playbackController.playItemAwaitDispatch(
                songId = songId,
                requestId = requestId,
            )
            if (!dispatched) {
                pendingPhonePlaybackRequestId = null
                _pendingPhonePlaybackSongId.value = null
                _events.emit(WearDownloadsUiEvent.Message("Phone not connected"))
                return@launch
            }

            phonePlaybackTimeoutJob?.cancel()
            phonePlaybackTimeoutJob = launch {
                delay(PHONE_PLAYBACK_TIMEOUT_MS)
                if (pendingPhonePlaybackRequestId != requestId) return@launch
                pendingPhonePlaybackRequestId = null
                _pendingPhonePlaybackSongId.value = null
                _events.emit(
                    WearDownloadsUiEvent.Message(
                        "Phone didn't confirm playback. The song may no longer be available."
                    )
                )
            }
        }
    }

    fun playDeviceSong(songId: String) {
        val songs = deviceSongs.value
        val startIndex = songs.indexOfFirst { it.songId == songId }
        if (startIndex == -1 || songs.isEmpty()) return
        localPlayerRepository.playUriSongs(
            songs = songs.map { song ->
                WearQueueSong(
                    songId = song.songId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    uri = song.contentUri,
                )
            },
            startIndex = startIndex,
        )
        stateRepository.setOutputTarget(WearOutputTarget.WATCH)
    }

    fun refreshDeviceLibrary(hasPermission: Boolean) {
        viewModelScope.launch {
            if (!hasPermission) {
                _deviceSongs.value = emptyList()
                _isDeviceLibraryLoading.value = false
                _deviceLibraryError.value = "Allow audio access to read watch library"
                return@launch
            }

            _isDeviceLibraryLoading.value = true
            _deviceLibraryError.value = null
            runCatching {
                deviceMusicRepository.scanDeviceSongs()
            }.onSuccess { songs ->
                _deviceSongs.value = songs
            }.onFailure { error ->
                _deviceSongs.value = emptyList()
                _deviceLibraryError.value = error.message ?: "Failed to load watch library"
            }
            _isDeviceLibraryLoading.value = false
        }
    }

    /**
     * Delete a locally stored song (file + database entry).
     */
    fun deleteSong(songId: String) {
        viewModelScope.launch {
            val error = runCatching {
                localPlayerRepository.removeSongFromActiveQueue(songId)
                transferRepository.deleteSong(songId).getOrThrow()
            }.exceptionOrNull()
            if (error != null) {
                _events.emit(
                    WearDownloadsUiEvent.Message(
                        error.message ?: "Couldn't remove this song from watch"
                    )
                )
            }
        }
    }

    /**
     * Cancel an in-progress transfer.
     */
    fun cancelTransfer(requestId: String) {
        transferRepository.cancelTransfer(requestId)
    }

    /**
     * Dismiss a failed/cancelled transfer's chip from the "issues" section — otherwise it stays
     * there indefinitely with no other way to clear it.
     */
    fun dismissTransfer(requestId: String) {
        transferRepository.dismissTransfer(requestId)
    }

    override fun onCleared() {
        phonePlaybackTimeoutJob?.cancel()
        super.onCleared()
    }
}
