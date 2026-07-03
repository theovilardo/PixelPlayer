package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.WearLocalPlayerRepository
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.data.WearStateRepository
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.LocalSongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** A song's position in a local playlist snapshot, resolved against what's actually on disk. */
data class WearLocalPlaylistSongItem(
    val songId: String,
    val song: LocalSongEntity?,
) {
    val isAvailable: Boolean get() = song != null
}

/**
 * ViewModel backing [LocalPlaylistsScreen] and [LocalPlaylistDetailScreen]. Song availability
 * is resolved reactively by joining the playlist's song order against [LocalSongDao.getAllSongs],
 * so a song that finishes transferring while the detail screen is open flips from pending to
 * playable without the user needing to back out and re-enter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WearLocalPlaylistViewModel @Inject constructor(
    private val localPlaylistDao: LocalPlaylistDao,
    private val localSongDao: LocalSongDao,
    private val localPlayerRepository: WearLocalPlayerRepository,
    private val stateRepository: WearStateRepository,
) : ViewModel() {

    val playlists: StateFlow<List<LocalPlaylistEntity>> = localPlaylistDao.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _playlistId = MutableStateFlow<String?>(null)

    val playlistDetails: StateFlow<LocalPlaylistEntity?> = combine(
        playlists,
        _playlistId,
    ) { allPlaylists, playlistId ->
        allPlaylists.find { it.playlistId == playlistId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val playlistSongs: StateFlow<List<WearLocalPlaylistSongItem>> = _playlistId
        .flatMapLatest { playlistId ->
            if (playlistId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    localPlaylistDao.observePlaylistSongs(playlistId),
                    localSongDao.getAllSongs(),
                ) { crossRefs, allSongs ->
                    val songsById = allSongs.associateBy { it.songId }
                    crossRefs.map { ref -> WearLocalPlaylistSongItem(ref.songId, songsById[ref.songId]) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun loadPlaylist(playlistId: String) {
        if (_playlistId.value == playlistId) return
        _playlistId.value = playlistId
    }

    /** Plays every available (already-transferred) song in order, from the start. */
    fun playAll() {
        val available = playlistSongs.value.mapNotNull { it.song }
        if (available.isEmpty()) return
        localPlayerRepository.playLocalSongs(available, startIndex = 0)
        stateRepository.setOutputTarget(WearOutputTarget.WATCH)
    }

    /** Plays every available song, starting from [songId] — pending songs aren't tappable. */
    fun playFrom(songId: String) {
        val available = playlistSongs.value.mapNotNull { it.song }
        val startIndex = available.indexOfFirst { it.songId == songId }
        if (startIndex == -1) return
        localPlayerRepository.playLocalSongs(available, startIndex = startIndex)
        stateRepository.setOutputTarget(WearOutputTarget.WATCH)
    }
}
