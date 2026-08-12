package com.theveloper.pixelplay.presentation.viewmodel

import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.WearAudioOutputRoute
import com.theveloper.pixelplay.data.WearFavoriteSyncRepository
import com.theveloper.pixelplay.data.WearLifecycleState
import com.theveloper.pixelplay.data.WearLocalQueueState
import com.theveloper.pixelplay.data.WearLocalPlayerRepository
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.data.WearPerformanceSettingsRepository
import com.theveloper.pixelplay.data.WearPlaybackController
import com.theveloper.pixelplay.data.WearStateRepository
import com.theveloper.pixelplay.data.WearTransferRepository
import com.theveloper.pixelplay.data.WearVolumeRepository
import com.theveloper.pixelplay.shared.WearPlayerState
import com.theveloper.pixelplay.shared.WearThemePalette
import com.theveloper.pixelplay.shared.WearVolumeState
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * ViewModel for the Wear player screen.
 * Supports dual-mode playback:
 * - Remote: phone playback via WearPlaybackController (Phase 1)
 * - Local: standalone ExoPlayer via WearLocalPlayerRepository (Phase 3)
 *
 * When local playback is active, commands are routed to the local player.
 * The [playerState] flow combines both sources into a unified WearPlayerState.
 */
@HiltViewModel
class WearPlayerViewModel @Inject constructor(
    private val stateRepository: WearStateRepository,
    private val playbackController: WearPlaybackController,
    private val localPlayerRepository: WearLocalPlayerRepository,
    private val transferRepository: WearTransferRepository,
    private val volumeRepository: WearVolumeRepository,
    private val favoriteSyncRepository: WearFavoriteSyncRepository,
    private val performanceSettingsRepository: WearPerformanceSettingsRepository,
) : ViewModel() {
    companion object {
        private const val PHONE_SYNC_BOOTSTRAP_ATTEMPTS = 3
        private const val PHONE_SYNC_BOOTSTRAP_RETRY_DELAY_MS = 1200L
        // The route callback already pushes updates reactively; this is a slow safety
        // net for cases where a system event was missed (e.g. headset just paired).
        // Wear batteries are tiny, so we keep it infrequent and pause it whenever the
        // screen is off / ambient.
        private const val PHONE_ROUTE_REFRESH_INTERVAL_MS = 30_000L
    }

    private val _sleepTimerUiState = MutableStateFlow(WearSleepTimerUiState())
    val sleepTimerUiState: StateFlow<WearSleepTimerUiState> = _sleepTimerUiState.asStateFlow()

    /** Whether local playback is currently active on the watch */
    val isLocalPlaybackActive: StateFlow<Boolean> = localPlayerRepository.isLocalPlaybackActive
    val localQueueState: StateFlow<WearLocalQueueState> = localPlayerRepository.localQueueState
    private val localSongs = transferRepository.localSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Current target selected by the user in Output screen */
    val outputTarget: StateFlow<WearOutputTarget> = stateRepository.outputTarget

    /** Whether UI is currently controlling the watch output */
    val isWatchOutputSelected: StateFlow<Boolean> = outputTarget
        .map { it == WearOutputTarget.WATCH }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Unified player state based on selected output target.
     */
    val playerState: StateFlow<WearPlayerState> = combine(
        stateRepository.outputTarget,
        localPlayerRepository.localPlayerState,
        stateRepository.playerState,
    ) { target, localState, remoteState ->
        when (target) {
            WearOutputTarget.WATCH -> {
                WearPlayerState(
                    songId = localState.songId,
                    songTitle = localState.songTitle,
                    artistName = localState.artistName,
                    albumName = localState.albumName,
                    isPlaying = localState.isPlaying,
                    currentPositionMs = localState.currentPositionMs,
                    totalDurationMs = localState.totalDurationMs,
                    isFavorite = localState.isFavorite,
                    isShuffleEnabled = localState.isShuffleEnabled,
                    repeatMode = localState.repeatMode,
                )
            }

            WearOutputTarget.PHONE -> remoteState
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearPlayerState())

    val albumArt: StateFlow<Bitmap?> = combine(
        stateRepository.outputTarget,
        stateRepository.albumArt,
        localPlayerRepository.localAlbumArt,
    ) { target, remoteAlbumArt, localAlbumArt ->
        if (target == WearOutputTarget.PHONE) remoteAlbumArt else localAlbumArt
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val paletteSeedArgb: StateFlow<Int?> = combine(
        stateRepository.outputTarget,
        localPlayerRepository.localPaletteSeedArgb,
    ) { target, localSeed ->
        if (target == WearOutputTarget.WATCH) localSeed else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val themePalette: StateFlow<WearThemePalette?> = combine(
        stateRepository.outputTarget,
        stateRepository.playerState,
        localPlayerRepository.localThemePalette,
    ) { target, remoteState, localThemePalette ->
        if (target == WearOutputTarget.PHONE) remoteState.themePalette else localThemePalette
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the play button's continuous rotation/ring animation should run. Only restricted
     * during local playback — remote-controller mode never decodes anything heavy on the watch,
     * so there's nothing to save there and the full animation always shows.
     */
    val showPlayButtonAnimation: StateFlow<Boolean> = combine(
        stateRepository.outputTarget,
        performanceSettingsRepository.playButtonAnimation,
    ) { target, animationEnabled ->
        target != WearOutputTarget.WATCH || animationEnabled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isPhoneConnected: StateFlow<Boolean> = stateRepository.isPhoneConnected
    val phoneVolumeState: StateFlow<WearVolumeState> = stateRepository.volumeState
    val watchVolumeState: StateFlow<WearVolumeState> = volumeRepository.watchVolumeState
    val watchAudioRoutes: StateFlow<List<WearAudioOutputRoute>> = volumeRepository.watchAudioRoutes

    val activeVolumeState: StateFlow<WearVolumeState> = combine(
        isWatchOutputSelected,
        phoneVolumeState,
        watchVolumeState,
    ) { isWatchOutput, phoneState, watchState ->
        if (isWatchOutput) watchState else phoneState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearVolumeState())

    val activeVolumePercent: StateFlow<Int> = activeVolumeState
        .map { state ->
            if (state.max <= 0) 0 else ((state.level.toFloat() / state.max.toFloat()) * 100f).toInt()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeVolumeDeviceName: StateFlow<String> = combine(
        isWatchOutputSelected,
        phoneVolumeState,
        watchVolumeState,
        stateRepository.phoneDeviceName,
    ) { isWatchOutput, phoneVolume, watchVolume, phoneDeviceName ->
        if (isWatchOutput) {
            watchVolume.routeName.ifBlank {
                Build.MODEL.takeIf { it.isNotBlank() } ?: "Watch"
            }
        } else {
            phoneVolume.routeName.ifBlank { phoneDeviceName.ifBlank { "Phone" } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Phone")

    val activeOutputRouteType: StateFlow<String> = combine(
        isWatchOutputSelected,
        phoneVolumeState,
        watchVolumeState,
    ) { isWatchOutput, phoneVolume, watchVolume ->
        if (isWatchOutput) {
            watchVolume.routeType.ifBlank { WearVolumeState.ROUTE_TYPE_WATCH }
        } else {
            phoneVolume.routeType.ifBlank { WearVolumeState.ROUTE_TYPE_PHONE }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearVolumeState.ROUTE_TYPE_PHONE)

    val canCurrentSongPlayOnWatch: StateFlow<Boolean> = combine(
        isLocalPlaybackActive,
        isPhoneConnected,
        stateRepository.playerState,
        localSongs,
    ) { localActive, phoneConnected, remoteState, songs ->
        localActive || (
            remoteState.songId.isNotBlank() &&
                (songs.any { it.songId == remoteState.songId } || phoneConnected)
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canCurrentSongBeFavorited: StateFlow<Boolean> = combine(
        outputTarget,
        isPhoneConnected,
        stateRepository.playerState,
        localPlayerRepository.localPlayerState,
    ) { target, phoneConnected, remoteState, localState ->
        when (target) {
            WearOutputTarget.WATCH -> localState.songId.isNotBlank() && localState.canToggleFavorite
            WearOutputTarget.PHONE -> phoneConnected && remoteState.songId.isNotBlank()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            // Recovers a queue/position that survived a process death mid-playback (see
            // WearLocalPlayerRepository's KDoc) — a no-op whenever nothing was persisted, which
            // is the overwhelming majority of app opens. `outputTarget` itself isn't persisted
            // (WearStateRepository always starts at PHONE), so a successful restore is the
            // signal that the user actually was on watch-local playback; it wouldn't otherwise
            // be visible in `playerState` until switched here.
            val restored = localPlayerRepository.restorePersistedPlaybackIfAvailable()
            if (restored) {
                stateRepository.setOutputTarget(WearOutputTarget.WATCH)
            }
        }
        viewModelScope.launch {
            outputTarget.collect {
                refreshActiveVolumeState()
            }
        }
        viewModelScope.launch {
            WearLifecycleState.isInteractive.collect { interactive ->
                if (!interactive) return@collect
                while (WearLifecycleState.isInteractiveNow) {
                    if (isWatchOutputSelected.value) {
                        volumeRepository.refreshWatchVolumeState()
                    } else if (isPhoneConnected.value) {
                        playbackController.requestPhoneVolumeState()
                    }
                    delay(PHONE_ROUTE_REFRESH_INTERVAL_MS)
                }
            }
        }
        viewModelScope.launch {
            combine(
                outputTarget,
                isPhoneConnected,
                localPlayerRepository.localPlayerState,
            ) { target, phoneConnected, localState ->
                if (
                    target == WearOutputTarget.WATCH &&
                    phoneConnected &&
                    localState.canToggleFavorite &&
                    localState.songId.isNotBlank()
                ) {
                    localState.songId
                } else {
                    null
                }
            }
                .distinctUntilChanged()
                .collect { songId ->
                    if (songId != null) {
                        favoriteSyncRepository.requestFavoriteSync(songIds = listOf(songId))
                    }
                }
        }
        bootstrapPhoneStateSync()
    }

    private fun bootstrapPhoneStateSync() {
        viewModelScope.launch {
            if (isWatchOutputSelected.value) return@launch
            repeat(PHONE_SYNC_BOOTSTRAP_ATTEMPTS) {
                playbackController.requestPhoneVolumeState()
                if (hasRemotePhoneState()) {
                    return@launch
                }
                delay(PHONE_SYNC_BOOTSTRAP_RETRY_DELAY_MS)
            }
        }
    }

    private fun hasRemotePhoneState(): Boolean {
        val state = stateRepository.playerState.value
        return state.songId.isNotBlank() ||
            state.songTitle.isNotBlank() ||
            state.artistName.isNotBlank() ||
            state.isPlaying
    }

    fun selectOutput(target: WearOutputTarget) {
        when (target) {
            WearOutputTarget.WATCH -> {
                val remoteState = stateRepository.playerState.value
                val remoteSongId = remoteState.songId
                val localState = localPlayerRepository.localPlayerState.value

                if (
                    localPlayerRepository.isLocalPlaybackActive.value &&
                    (remoteSongId.isBlank() || localState.songId == remoteSongId)
                ) {
                    if (remoteSongId.isNotBlank() && localState.songId == remoteSongId) {
                        localPlayerRepository.seekTo(remoteState.currentPositionMs)
                        if (remoteState.isPlaying) {
                            localPlayerRepository.play()
                            playbackController.pause()
                        } else {
                            localPlayerRepository.pause()
                        }
                    }
                    stateRepository.setOutputTarget(WearOutputTarget.WATCH)
                    return
                }

                if (remoteSongId.isBlank()) return
                val songs = localSongs.value
                val startIndex = songs.indexOfFirst { it.songId == remoteSongId }
                if (startIndex != -1 && songs.isNotEmpty()) {
                    localPlayerRepository.playLocalSongs(
                        songs = songs,
                        startIndex = startIndex,
                        startPositionMs = remoteState.currentPositionMs,
                        autoPlay = remoteState.isPlaying,
                    )
                    stateRepository.setOutputTarget(WearOutputTarget.WATCH)
                    if (remoteState.isPlaying) {
                        playbackController.pause()
                    }
                    return
                }

                if (!isPhoneConnected.value) return
                transferRepository.requestTemporaryPlayback(
                    songId = remoteSongId,
                    startPositionMs = remoteState.currentPositionMs,
                    autoPlay = remoteState.isPlaying,
                    pausePhoneAfterStart = remoteState.isPlaying,
                )
            }

            WearOutputTarget.PHONE -> {
                stateRepository.setOutputTarget(WearOutputTarget.PHONE)
                if (localPlayerRepository.localPlayerState.value.isPlaying) {
                    localPlayerRepository.pause()
                }
                playbackController.requestPhoneVolumeState()
            }
        }
    }

    fun selectWatchOutput(routeId: String) {
        volumeRepository.selectWatchAudioRoute(routeId)
        if (outputTarget.value != WearOutputTarget.WATCH) {
            selectOutput(WearOutputTarget.WATCH)
        } else {
            refreshActiveVolumeState()
        }
    }

    fun openWatchOutputPicker() {
        volumeRepository.launchWatchAudioOutputPicker()
    }

    fun playLocalQueueIndex(index: Int) {
        if (!isWatchOutputSelected.value) return
        localPlayerRepository.playQueueIndex(index)
    }

    fun setWatchRouteDiscoveryEnabled(enabled: Boolean) {
        volumeRepository.setWatchRouteDiscoveryEnabled(enabled)
    }

    fun refreshWatchAudioState() {
        volumeRepository.refreshWatchVolumeState()
    }

    fun togglePlayPause() {
        if (isWatchOutputSelected.value) {
            localPlayerRepository.togglePlayPause()
        } else {
            val current = stateRepository.playerState.value
            stateRepository.updatePlayerState(
                current.copy(isPlaying = !current.isPlaying)
            )
            playbackController.togglePlayPause()
        }
    }

    fun next() {
        if (isWatchOutputSelected.value) localPlayerRepository.next()
        else playbackController.next()
    }

    fun previous() {
        if (isWatchOutputSelected.value) localPlayerRepository.previous()
        else playbackController.previous()
    }

    fun toggleFavorite() {
        if (isWatchOutputSelected.value) {
            val current = localPlayerRepository.localPlayerState.value
            if (!current.canToggleFavorite || current.songId.isBlank()) return
            favoriteSyncRepository.setFavorite(
                songId = current.songId,
                isFavorite = !current.isFavorite,
            )
            return
        }

        val current = stateRepository.playerState.value
        if (!isPhoneConnected.value || current.songId.isBlank()) return
        stateRepository.updatePlayerState(current.copy(isFavorite = !current.isFavorite))
        playbackController.toggleFavorite(
            songId = current.songId,
            targetEnabled = !current.isFavorite,
        )
    }

    fun refreshCurrentSongFavoriteState() {
        if (!isWatchOutputSelected.value || !isPhoneConnected.value) return
        val current = localPlayerRepository.localPlayerState.value
        if (!current.canToggleFavorite || current.songId.isBlank()) return
        favoriteSyncRepository.requestFavoriteSync(songIds = listOf(current.songId))
    }

    fun toggleShuffle() {
        if (isWatchOutputSelected.value) {
            localPlayerRepository.toggleShuffle()
        } else {
            playbackController.toggleShuffle()
        }
    }

    fun cycleRepeat() {
        if (isWatchOutputSelected.value) {
            localPlayerRepository.cycleRepeat()
        } else {
            playbackController.cycleRepeat()
        }
    }

    fun volumeUp() {
        if (isWatchOutputSelected.value) {
            volumeRepository.volumeUpOnWatch()
        } else {
            stateRepository.nudgePhoneVolumeLevel(delta = 1)
            playbackController.volumeUp()
        }
    }

    fun volumeDown() {
        if (isWatchOutputSelected.value) {
            volumeRepository.volumeDownOnWatch()
        } else {
            stateRepository.nudgePhoneVolumeLevel(delta = -1)
            playbackController.volumeDown()
        }
    }

    fun setActiveVolume(level: Int) {
        if (isWatchOutputSelected.value) {
            volumeRepository.setWatchVolume(level)
            return
        }

        val current = stateRepository.volumeState.value
        val max = current.max
        if (max <= 0) return

        val targetLevel = level.coerceIn(0, max)
        val targetPercent = ((targetLevel.toFloat() / max.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)

        stateRepository.updateVolumeState(
            level = targetLevel,
            max = max,
            routeType = current.routeType,
            routeName = current.routeName,
        )
        playbackController.setPhoneVolume(targetPercent)
    }

    fun refreshActiveVolumeState() {
        if (isWatchOutputSelected.value) {
            volumeRepository.refreshWatchVolumeState()
        } else {
            playbackController.requestPhoneVolumeState()
        }
    }

    fun setSleepTimerDuration(durationMinutes: Int) {
        if (durationMinutes <= 0 || !isPhoneConnected.value) return
        playbackController.setSleepTimerDuration(durationMinutes)
        _sleepTimerUiState.value = WearSleepTimerUiState(
            mode = WearSleepTimerMode.DURATION,
            durationMinutes = durationMinutes,
        )
    }

    fun setSleepTimerEndOfTrack(enabled: Boolean = true) {
        if (!isPhoneConnected.value) return
        playbackController.setSleepTimerEndOfTrack(enabled)
        _sleepTimerUiState.value = if (enabled) {
            WearSleepTimerUiState(mode = WearSleepTimerMode.END_OF_TRACK)
        } else {
            WearSleepTimerUiState()
        }
    }

    fun cancelSleepTimer() {
        if (!isPhoneConnected.value) return
        playbackController.cancelSleepTimer()
        _sleepTimerUiState.value = WearSleepTimerUiState()
    }

    /** Stop local playback and switch back to remote mode */
    fun stopLocalPlayback() {
        localPlayerRepository.release()
    }
}

data class WearSleepTimerUiState(
    val mode: WearSleepTimerMode = WearSleepTimerMode.OFF,
    val durationMinutes: Int = 0,
)

enum class WearSleepTimerMode {
    OFF,
    DURATION,
    END_OF_TRACK,
}
