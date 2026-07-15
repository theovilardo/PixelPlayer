package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.cast.CastRemotePlaybackState
import com.google.android.gms.cast.MediaStatus
import timber.log.Timber
import com.theveloper.pixelplay.utils.QueueUtils
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlin.math.abs

@Singleton
class PlaybackStateHolder @Inject constructor(
    private val dualPlayerEngine: DualPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val castStateHolder: CastStateHolder,
    private val queueStateHolder: QueueStateHolder,
    @param:ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "PlaybackStateHolder"
        private const val DURATION_MISMATCH_TOLERANCE_MS = 1500L
        // Cap how long we trust a pending seek override against an out-of-date player position.
        // The override exists to mask the few ticks between seekTo() and the player actually
        // reporting the new position. If we never see drift converge within this window we
        // assume the seek will not land and fall back to the reported position rather than
        // pinning the UI on a stale value forever.
        private const val PAUSED_OVERRIDE_MAX_AGE_MS = 4_000L
        // Tick rates:
        //  - SLIDER_TICK_MS (250): keeps the player sheet's slider/time display visibly smooth.
        //    We tried 500 ms once to lower Compose recomposition pressure, but the smooth-progress
        //    sampler polls instead of interpolating, so a 500 ms source made the slider stutter
        //    in half-second jumps. Used only when the player sheet is open (slider visible).
        //  - MINIPLAYER_TICK_MS (1000): mini-player + lock screen / notification only need
        //    second-level precision. Drops position-polling-driven CPU wakes 4x → 1x per second.
        //  - BACKGROUND_TICK_MS (1000): screen off, no slider visible.
        private const val SLIDER_TICK_MS = 250L
        private const val MINIPLAYER_TICK_MS = 1000L
        private const val BACKGROUND_TICK_MS = 1000L
        /**
         * Threshold above which we skip per-item moveMediaItem calls and use
         * a single setMediaItems call instead. moveMediaItem triggers an IPC
         * round-trip for each call, which freezes the UI on large queues.
         */
        private const val BULK_REPLACE_THRESHOLD = 80
        private const val SHUFFLE_TOGGLE_COOLDOWN_MS = 400L
        private const val CAST_SEEK_BLOCKED_TOAST_COOLDOWN_MS = 2500L
    }

    private var scope: CoroutineScope? = null
    private var onCastSeekBlocked: (() -> Unit)? = null
    
    // MediaController
    var mediaController: MediaController? = null
        private set
    private val mediaControllerStack = mutableListOf<MediaController>()

    // Player State
    private val _stablePlayerState = MutableStateFlow(StablePlayerState())
    val stablePlayerState: StateFlow<StablePlayerState> = _stablePlayerState.asStateFlow()
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    // True while the full player sheet (slider visible) is mounted. Set by the
    // sheet via DisposableEffect. Controls whether the position ticker runs at
    // slider-smooth resolution (250 ms) or mini-player resolution (1 s).
    private val _sliderUiMounted = MutableStateFlow(false)
    fun setSliderUiMounted(mounted: Boolean) {
        _sliderUiMounted.value = mounted
    }

    // 用于节流的时间戳
    private var lastAmplitudeUpdateTimeMs = 0L

    /* -------------------------------------------------------------------------- */
    /* Audio EQ Visualizer                          */
    /* -------------------------------------------------------------------------- */

    /**
     * 高频调用的音频振幅更新通道。
     * 注意：此方法会被 ExoPlayer 的后台音频线程高频调用，绝对不能包含任何 IPC
     * 或复杂的耗时逻辑！
     */
    fun updateAudioAmplitude(amplitude: Float) {
        val nowMs = SystemClock.elapsedRealtime()

        // 节流：限制更新频率约为 50ms 一次 (20 FPS)，避免 StateFlow 频繁发射导致 UI 掉帧
        if (nowMs - lastAmplitudeUpdateTimeMs >= 50L) {
            lastAmplitudeUpdateTimeMs = nowMs

            // 直接操作底层的 _stablePlayerState，避开 updateStablePlayerState 中的耗时逻辑
            _stablePlayerState.update { current ->
                // 只有当振幅发生有意义的改变时才更新，避免无意义的重组
                if (current.audioAmplitude != amplitude) {
                    current.copy(audioAmplitude = amplitude)
                } else {
                    current
                }
            }
        }
    }

    // Internal State
    private var isSeeking = false
    private var remoteSeekUnlockJob: Job? = null
    private var activePositionOccurrenceMediaId: String? = null
    private var activePositionOccurrenceToken: Long = 0L
    private var nextPositionOccurrenceToken: Long = 1L
    private var pausedPositionOverrideMediaId: String? = null
    private var pausedPositionOverrideToken: Long? = null
    private var pausedPositionOverrideMs: Long? = null
    private var pausedPositionOverrideSetAtMs: Long = 0L
    private var coldStartSnapshotMediaId: String? = null
    private var coldStartSnapshotToken: Long? = null
    private var coldStartSnapshotPositionMs: Long? = null
    private var shuffleToggleJob: Job? = null
    private var lastShuffleToggleFinishedAtMs: Long = 0L
    private var lastCastSeekBlockedToastAtMs: Long = 0L
    private val powerManager: PowerManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private fun clearColdStartSnapshot() {
        coldStartSnapshotMediaId = null
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = null
    }

    /**
     * Binds a restored snapshot to the active playback occurrence when possible.
     *
     * If the first occurrence was already activated before the snapshot finished loading, we
     * attach the snapshot to that token so resume still works. If playback has already advanced
     * past the first occurrence, the snapshot is stale and must be discarded.
     */
    private fun rememberColdStartSnapshot(mediaId: String, positionMs: Long): Boolean {
        coldStartSnapshotMediaId = mediaId
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = positionMs

        if (nextPositionOccurrenceToken == 1L) {
            return true
        }

        if (
            activePositionOccurrenceToken == 1L &&
            nextPositionOccurrenceToken == 2L &&
            activePositionOccurrenceMediaId == mediaId
        ) {
            coldStartSnapshotToken = activePositionOccurrenceToken
            return true
        }

        clearColdStartSnapshot()
        return false
    }

    fun initialize(
        coroutineScope: CoroutineScope,
        onCastSeekBlocked: (() -> Unit)? = null
    ) {
        this.scope = coroutineScope
        this.onCastSeekBlocked = onCastSeekBlocked
        scope?.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val snapshotMediaId = snapshot.currentMediaId
                ?: snapshot.items.getOrNull(snapshot.currentIndex)?.mediaId
                ?: return@launch
            val snapshotPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L)
            if (snapshotPositionMs <= 0L) return@launch
            if (!rememberColdStartSnapshot(snapshotMediaId, snapshotPositionMs)) {
                return@launch
            }

            val controller = mediaController
            if (
                controller != null &&
                !controller.isPlaying &&
                controller.currentMediaItem?.mediaId == snapshotMediaId &&
                _currentPosition.value == 0L
            ) {
                _currentPosition.value = snapshotPositionMs
            }
        }
    }

    fun setMediaController(controller: MediaController?) {
        if (controller == null) {
            mediaControllerStack.clear()
            mediaController = null
            return
        }

        mediaControllerStack.removeAll { it === controller }
        mediaControllerStack.add(controller)
        mediaController = controller
    }

    fun clearMediaController(controller: MediaController?) {
        if (controller == null) return

        mediaControllerStack.removeAll { it === controller }
        if (mediaController === controller) {
            mediaController = mediaControllerStack.lastOrNull()
        }
    }

    private fun notifyCastSeekBlocked() {
        val nowMs = SystemClock.elapsedRealtime()
        if (
            lastCastSeekBlockedToastAtMs > 0L &&
            nowMs - lastCastSeekBlockedToastAtMs < CAST_SEEK_BLOCKED_TOAST_COOLDOWN_MS
        ) {
            return
        }

        lastCastSeekBlockedToastAtMs = nowMs
        onCastSeekBlocked?.invoke()
    }

    private fun activeLocalPlayer(): Player {
        val controller = mediaController
        return if (controller?.isConnected == true) {
            controller
        } else {
            dualPlayerEngine.masterPlayer
        }
    }
    
    fun updateStablePlayerState(update: (StablePlayerState) -> StablePlayerState) {
        _stablePlayerState.update { current ->
            val updated = update(current)
            // Auto-populate index from MediaController if not explicitly set by the update
            if (updated.currentMediaItemIndex == -1) {
                if (dualPlayerEngine.isUsingWindowedQueue()) {
                    updated.copy(currentMediaItemIndex = dualPlayerEngine.getCurrentAbsoluteIndex())
                } else {
                    mediaController?.let { controller ->
                        updated.copy(currentMediaItemIndex = controller.currentMediaItemIndex)
                    } ?: updated
                }
            } else {
                updated
            }
        }
    }

    fun setCurrentPosition(positionMs: Long) {
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }

    fun syncCurrentPositionFromPlayer(mediaId: String?, reportedPositionMs: Long) {
        _currentPosition.value = resolveUiPosition(mediaId, reportedPositionMs)
    }

    fun ensureCurrentPlaybackOccurrence(mediaId: String?) {
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = false)
    }

    fun onPlaybackOccurrenceTransition(mediaId: String?) {
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = true)
    }

    fun rememberPausedPositionOverride(mediaId: String?, positionMs: Long) {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return
        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false) ?: return
        val safePosition = positionMs.coerceAtLeast(0L)
        pausedPositionOverrideMediaId = safeMediaId
        pausedPositionOverrideToken = activeToken
        pausedPositionOverrideMs = safePosition
        pausedPositionOverrideSetAtMs = SystemClock.elapsedRealtime()
        _currentPosition.value = safePosition
    }

    fun clearCurrentPositionHints(mediaId: String? = null) {
        if (mediaId == null || pausedPositionOverrideMediaId == mediaId) {
            pausedPositionOverrideMediaId = null
            pausedPositionOverrideToken = null
            pausedPositionOverrideMs = null
            pausedPositionOverrideSetAtMs = 0L
        }
        if (mediaId == null || coldStartSnapshotMediaId == mediaId) {
            clearColdStartSnapshot()
        }
    }

    private fun resolveUiPosition(mediaId: String?, reportedPositionMs: Long): Long {
        val safeReportedPosition = reportedPositionMs.coerceAtLeast(0L)
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() }
        if (safeMediaId == null) {
            return safeReportedPosition
        }

        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false)
            ?: return safeReportedPosition

        val pausedOverride = pausedPositionOverrideMs
            ?.takeIf {
                pausedPositionOverrideMediaId == safeMediaId &&
                    pausedPositionOverrideToken == activeToken
            }
        val coldStartSeed = coldStartSnapshotPositionMs
            ?.takeIf {
                coldStartSnapshotMediaId == safeMediaId &&
                    coldStartSnapshotToken == activeToken
            }
        val preferredPosition = pausedOverride ?: coldStartSeed

        if (preferredPosition == null) {
            return safeReportedPosition
        }

        if (safeReportedPosition <= 0L) {
            return preferredPosition
        }

        val drift = abs(safeReportedPosition - preferredPosition)
        val pausedOverrideOwnsThisToken =
            pausedPositionOverrideMediaId == safeMediaId &&
                pausedPositionOverrideToken == activeToken
        val pausedOverrideActive = pausedOverride != null
        // Stale override fallback: if the player never converges on a freshly-issued seek
        // we don't want to pin the UI on the requested position forever. After this window
        // we trust the reported position again.
        val overrideIsStale = pausedOverrideActive &&
            pausedPositionOverrideSetAtMs > 0L &&
            SystemClock.elapsedRealtime() - pausedPositionOverrideSetAtMs > PAUSED_OVERRIDE_MAX_AGE_MS
        // The `reported >= preferred` shortcut is only safe for the cold-start seed (where
        // preferred represents "where playback should start" and the player passing it means
        // the seed has served its purpose). Applying the same shortcut to an active paused
        // override broke backward seeks — the player still reports the pre-seek (larger)
        // position for a tick or two after seekTo(), wiping the override before the seek
        // had landed and snapping the UI back to the old position.
        val coldStartPassed = !pausedOverrideActive && safeReportedPosition >= preferredPosition
        if (drift <= DURATION_MISMATCH_TOLERANCE_MS || overrideIsStale || coldStartPassed) {
            if (pausedOverrideOwnsThisToken) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotToken == activeToken) {
                clearColdStartSnapshot()
            }
            return safeReportedPosition
        }

        return preferredPosition
    }

    private fun activatePlaybackOccurrence(
        mediaId: String?,
        forceNewOccurrence: Boolean
    ): Long? {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: run {
            activePositionOccurrenceMediaId = null
            activePositionOccurrenceToken = 0L
            if (forceNewOccurrence) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            return null
        }

        val shouldAdvance =
            forceNewOccurrence ||
                activePositionOccurrenceToken == 0L ||
                activePositionOccurrenceMediaId != safeMediaId

        if (!shouldAdvance) {
            return activePositionOccurrenceToken
        }

        activePositionOccurrenceMediaId = safeMediaId
        activePositionOccurrenceToken = nextPositionOccurrenceToken++

        pausedPositionOverrideMediaId = null
        pausedPositionOverrideToken = null
        pausedPositionOverrideMs = null
        pausedPositionOverrideSetAtMs = 0L

        if (coldStartSnapshotToken != null) {
            clearColdStartSnapshot()
        } else if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotPositionMs != null) {
            coldStartSnapshotToken = activePositionOccurrenceToken
        } else if (coldStartSnapshotMediaId != null) {
            clearColdStartSnapshot()
        }

        return activePositionOccurrenceToken
    }
    
    /* -------------------------------------------------------------------------- */
    /*                               Playback Controls                            */
    /* -------------------------------------------------------------------------- */

    fun playPause() {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            val remotePlayback = remoteMediaClient.mediaStatus?.let { mediaStatus ->
                CastRemotePlaybackState.project(
                    mediaStatus = mediaStatus,
                    previousPlayIntent = _stablePlayerState.value.playWhenReady
                )
            }
            if (remoteMediaClient.isPlaying || remotePlayback?.playWhenReady == true) {
                castStateHolder.castPlayer?.pause()
                _stablePlayerState.update {
                    it.copy(
                        isPlaying = false,
                        playWhenReady = false,
                        isBuffering = false
                    )
                }
            } else {
                if (remoteMediaClient.mediaQueue.itemCount > 0) {
                    castStateHolder.castPlayer?.play()
                    _stablePlayerState.update {
                        it.copy(
                            isPlaying = true,
                            playWhenReady = true
                        )
                    }
                } else {
                    Timber.w("Remote queue empty, cannot resume.")
                }
            }
        } else {
            val controller = activeLocalPlayer()
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                    controller.prepare()
                }
                controller.play()
            }
        }
    }

    fun seekTo(position: Long) {
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            val targetPosition = position.coerceAtLeast(0L)
            val castPlayer = castStateHolder.castPlayer
            if (castPlayer?.canSeekCurrentItem() == false) {
                remoteSeekUnlockJob?.cancel()
                castStateHolder.setRemotelySeeking(false)
                castSession.remoteMediaClient?.requestStatus()
                notifyCastSeekBlocked()
                Timber.tag(TAG).w("Ignoring Cast seek for current item because receiver-side Ogg seeking is unstable.")
                return
            }
            castStateHolder.setRemotelySeeking(true)
            castStateHolder.setRemotePosition(targetPosition)
            setCurrentPosition(targetPosition)
            if (castPlayer?.seek(targetPosition) != true) {
                castStateHolder.setRemotelySeeking(false)
                castSession.remoteMediaClient?.requestStatus()
                if (castPlayer != null) {
                    notifyCastSeekBlocked()
                }
                return
            }

            remoteSeekUnlockJob?.cancel()
            remoteSeekUnlockJob = scope?.launch {
                // Fail-safe: never keep remote seeking lock indefinitely.
                delay(1800)
                castStateHolder.setRemotelySeeking(false)
                castSession.remoteMediaClient?.requestStatus()
            }
        } else {
            remoteSeekUnlockJob?.cancel()
            castStateHolder.setRemotelySeeking(false)
            val targetPosition = position.coerceAtLeast(0L)
            val player = activeLocalPlayer()
            val currentMediaId = player.currentMediaItem?.mediaId
            rememberPausedPositionOverride(currentMediaId, targetPosition)
            // Mark the seek before dispatching so the engine's HAL-reset heuristic does
            // not misinterpret the resulting STATE_BUFFERING as an audio HAL underflow and
            // rebuild the players (which would race with the in-flight seek command).
            dualPlayerEngine.notifyExternalSeekInitiated()
            player.seekTo(targetPosition)
        }
    }

    fun previousSong() {
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            castStateHolder.castPlayer?.previous()
        } else {
            val controller = activeLocalPlayer()
             if (controller.currentPosition > 10000) { // 10 seconds
                 controller.seekTo(0)
            } else {
                 controller.seekToPrevious()
            }
        }
    }

    fun nextSong() {
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            castStateHolder.castPlayer?.next()
        } else {
             activeLocalPlayer().seekToNext()
        }
    }

    fun cycleRepeatMode() {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            val currentRepeatMode = remoteMediaClient.mediaStatus?.getQueueRepeatMode() ?: MediaStatus.REPEAT_MODE_REPEAT_OFF
            val newMode = when (currentRepeatMode) {
                MediaStatus.REPEAT_MODE_REPEAT_OFF -> MediaStatus.REPEAT_MODE_REPEAT_ALL
                MediaStatus.REPEAT_MODE_REPEAT_ALL -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
            }
            castStateHolder.castPlayer?.setRepeatMode(newMode)
            
            // Map remote mode back to local constant for persistence/UI
            val mappedLocalMode = when (newMode) {
                MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
                MediaStatus.REPEAT_MODE_REPEAT_ALL, MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            scope?.launch { userPreferencesRepository.setRepeatMode(mappedLocalMode) }
            _stablePlayerState.update { it.copy(repeatMode = mappedLocalMode) }
        } else {
            val currentMode = _stablePlayerState.value.repeatMode
            val newMode = when (currentMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            mediaController?.repeatMode = newMode
            scope?.launch { userPreferencesRepository.setRepeatMode(newMode) }
            _stablePlayerState.update { it.copy(repeatMode = newMode) }
        }
    }

    fun setRepeatMode(mode: Int) {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            val remoteMode = when (mode) {
                Player.REPEAT_MODE_ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                Player.REPEAT_MODE_ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
                else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
            }
            castStateHolder.castPlayer?.setRepeatMode(remoteMode)
        } else {
             mediaController?.repeatMode = mode
        }
        
        scope?.launch { userPreferencesRepository.setRepeatMode(mode) }
        _stablePlayerState.update { it.copy(repeatMode = mode) }
    }

    /* -------------------------------------------------------------------------- */
    /*                               Progress Updates                             */
    /* -------------------------------------------------------------------------- */
    
    private var progressJob: kotlinx.coroutines.Job? = null

    /**
     * Reconciles duration reported by the player with the current song metadata duration.
     *
     * Why:
     * - During some transitions (notably crossfade player swaps), the reported duration can lag
     *   behind the currently visible track for a short period.
     * - Relying only on one source can make progress run too slow/fast.
     */
    private fun resolveEffectiveDuration(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long {
        val reported = when {
            reportedDurationMs == C.TIME_UNSET -> 0L
            reportedDurationMs < 0L -> 0L
            else -> reportedDurationMs
        }
        val hint = songDurationHintMs.coerceAtLeast(0L)
        val position = currentPositionMs.coerceAtLeast(0L)

        if (reported <= 0L) return hint
        if (hint <= 0L) return reported

        val diff = abs(reported - hint)
        if (diff <= DURATION_MISMATCH_TOLERANCE_MS) return reported

        // If playback already passed the metadata hint, trust the reported duration to avoid clipping.
        if (position > hint + DURATION_MISMATCH_TOLERANCE_MS && reported >= position) {
            return reported
        }

        // Otherwise prefer the shorter duration to avoid stale longer values after swaps.
        val resolved = minOf(reported, hint)
        if (diff > 10_000L) {
            Timber.tag(TAG).w(
                "Duration mismatch resolved (reported=%dms, hint=%dms, pos=%dms, resolved=%dms)",
                reported, hint, position, resolved
            )
        }
        return resolved
    }

    fun resolveDurationForPlaybackState(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long = resolveEffectiveDuration(
        reportedDurationMs = reportedDurationMs,
        songDurationHintMs = songDurationHintMs,
        currentPositionMs = currentPositionMs
    )

    fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope?.launch {
            // Battery: only spin the polling loop while something is actually
            // observing currentPosition. With no subscribers (screen off and
            // no lock-screen progress UI mounted), this collectLatest sits
            // idle and the CPU stays asleep. As soon as a subscriber appears
            // (player sheet opened, widget bound, etc.) the inner loop resumes.
            _currentPosition.subscriptionCount.collectLatest { subscriberCount ->
                if (subscriberCount == 0) return@collectLatest
                coroutineScope {
                    while (isActive) {
                        val tickMs = currentProgressTickMs()
                        val castSession = castStateHolder.castSession.value
                        val remoteClient = castSession?.remoteMediaClient
                        val isRemote = remoteClient != null

                        if (isRemote) {
                    val activeRemoteClient = checkNotNull(remoteClient)
                    val previousPlayIntent = _stablePlayerState.value.playWhenReady
                    val remotePlayback = activeRemoteClient.mediaStatus?.let { mediaStatus ->
                        CastRemotePlaybackState.project(
                            mediaStatus = mediaStatus,
                            previousPlayIntent = previousPlayIntent
                        )
                    }
                    val isRemotePlaying = remotePlayback?.isPlaying ?: activeRemoteClient.isPlaying
                    val remotePlayWhenReady = remotePlayback?.playWhenReady ?: activeRemoteClient.isPlaying
                    val currentPosition = activeRemoteClient.approximateStreamPosition.coerceAtLeast(0L)
                    val songDurationHint = _stablePlayerState.value.currentSong?.duration ?: 0L
                    val duration = resolveEffectiveDuration(
                        reportedDurationMs = activeRemoteClient.streamDuration,
                        songDurationHintMs = songDurationHint,
                        currentPositionMs = currentPosition
                    )
                    val isRemotelySeeking = castStateHolder.isRemotelySeeking.value
                    if (!isRemotelySeeking) {
                        castStateHolder.setRemotePosition(currentPosition)
                    }

                    val nextPosition = if (isRemotelySeeking) _currentPosition.value else currentPosition
                    if (_currentPosition.value != nextPosition) {
                        _currentPosition.value = nextPosition
                    }

                    _stablePlayerState.update { state ->
                        val finalAmplitude = if (isRemotePlaying || mediaController?.isPlaying == true) state.audioAmplitude else 0f
                        if (
                            state.totalDuration == duration &&
                            state.isPlaying == isRemotePlaying &&
                            state.playWhenReady == remotePlayWhenReady &&
                            state.isBuffering == (remotePlayback?.isBuffering ?: false) &&
                            state.audioAmplitude == finalAmplitude
                        ) {
                            state
                        } else {
                            state.copy(
                                totalDuration = duration,
                                isPlaying = isRemotePlaying,
                                playWhenReady = remotePlayWhenReady,
                                isBuffering = remotePlayback?.isBuffering ?: false,
                                audioAmplitude = finalAmplitude,
                            )
                        }
                    }
                } else {
                     val controller = activeLocalPlayer()
                     if (shouldSampleLocalProgress(controller)) {
                         val visibleSong = _stablePlayerState.value.currentSong
                         val currentMediaId = controller.currentMediaItem?.mediaId
                         val hasMediaMismatch = visibleSong?.id != null &&
                             currentMediaId != null &&
                             visibleSong.id != currentMediaId

                         if (hasMediaMismatch) {
                            Timber.tag(TAG).v(
                                 "Skipping local progress tick due media mismatch (visible=%s, player=%s)",
                                 visibleSong?.id,
                                 currentMediaId
                             )
                            delay(tickMs)
                            continue
                        }

                          val currentPosition = controller.currentPosition.coerceAtLeast(0L)
                          val songDurationHint = visibleSong?.duration ?: 0L
                          val duration = resolveEffectiveDuration(
                              reportedDurationMs = controller.duration,
                              songDurationHintMs = songDurationHint,
                              currentPositionMs = currentPosition
                          )

                          val resolvedPosition = resolveUiPosition(currentMediaId, currentPosition)
                          if (_currentPosition.value != resolvedPosition) {
                              _currentPosition.value = resolvedPosition
                          }

                          _stablePlayerState.update { state ->
                              if (state.totalDuration == duration) {
                                  state
                              } else {
                                  state.copy(totalDuration = duration)
                              }
                         }
                      }
                }
                        delay(tickMs)
                    }
                }
            }
        }
    }

    private fun shouldSampleLocalProgress(controller: Player): Boolean {
        if (isSeeking) return false
        if (controller.mediaItemCount <= 0) return false
        if (controller.isPlaying) return true

        return controller.playWhenReady &&
            controller.playbackState != Player.STATE_IDLE &&
            controller.playbackState != Player.STATE_ENDED
    }

    private fun currentProgressTickMs(): Long {
        if (!powerManager.isInteractive) return BACKGROUND_TICK_MS
        // Interactive but the slider isn't mounted (mini-player / lock-screen
        // notification only) — second-level precision is enough.
        return if (_sliderUiMounted.value) SLIDER_TICK_MS else MINIPLAYER_TICK_MS
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    /* -------------------------------------------------------------------------- */
    /*                               Shuffle & Repeat                             */
    /* -------------------------------------------------------------------------- */

    private data class PreparedQueueReplacement(
        val mediaItems: List<MediaItem>,
        val targetIndex: Int
    )

    private data class PreparedQueueSegments(
        val beforeCurrent: List<MediaItem>,
        val afterCurrent: List<MediaItem>
    )

    private fun reorderQueueInPlace(player: Player, desiredQueue: List<Song>): Boolean {
        if (desiredQueue.isEmpty()) return false

        val currentCount = player.mediaItemCount
        if (currentCount != desiredQueue.size) {
            Timber.tag(TAG).w(
                "Cannot reorder queue in place: size mismatch (player=%d, desired=%d)",
                currentCount,
                desiredQueue.size
            )
            return false
        }

        val currentIds = MutableList(currentCount) { index ->
            player.getMediaItemAt(index).mediaId
        }
        val desiredIds = desiredQueue.map { it.id }

        val currentCounts = currentIds.groupingBy { it }.eachCount()
        val desiredCounts = desiredIds.groupingBy { it }.eachCount()
        if (currentCounts != desiredCounts) {
            Timber.tag(TAG).w("Cannot reorder queue in place: mediaId mismatch")
            return false
        }

        for (targetIndex in desiredIds.indices) {
            val desiredId = desiredIds[targetIndex]
            if (currentIds[targetIndex] == desiredId) continue

            var fromIndex = -1
            for (searchIndex in targetIndex + 1 until currentIds.size) {
                if (currentIds[searchIndex] == desiredId) {
                    fromIndex = searchIndex
                    break
                }
            }

            if (fromIndex == -1) {
                Timber.tag(TAG).w(
                    "Cannot reorder queue in place: target mediaId '%s' not found",
                    desiredId
                )
                return false
            }

            player.moveMediaItem(fromIndex, targetIndex)
            val movedId = currentIds.removeAt(fromIndex)
            currentIds.add(targetIndex, movedId)
        }

        return true
    }

    /**
     * Replaces the player timeline with [newQueue] in a single setMediaItems call,
     * preserving the currently playing song and its position. This is O(1) IPC calls
     * versus O(n) for reorderQueueInPlace, making it suitable for large queue shuffles.
     */
    private suspend fun buildQueueReplacement(
        newQueue: List<Song>,
        targetIndex: Int,
        currentMediaItem: MediaItem?
    ): PreparedQueueReplacement = withContext(Dispatchers.Default) {
        val safeTargetIndex = targetIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val mediaItems = List(newQueue.size) { index ->
            currentMediaItem
                ?.takeIf { index == safeTargetIndex && it.mediaId == newQueue[safeTargetIndex].id }
                ?: MediaItemBuilder.build(newQueue[index])
        }

        PreparedQueueReplacement(
            mediaItems = mediaItems,
            targetIndex = safeTargetIndex
        )
    }

    private suspend fun buildQueueSegments(
        newQueue: List<Song>,
        currentIndex: Int,
        currentMediaItem: MediaItem?
    ): PreparedQueueSegments? = withContext(Dispatchers.Default) {
        val safeCurrentIndex = currentIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val currentQueueSong = newQueue.getOrNull(safeCurrentIndex) ?: return@withContext null
        if (currentMediaItem?.mediaId != currentQueueSong.id) {
            return@withContext null
        }

        val beforeCurrent = List(safeCurrentIndex) { index ->
            MediaItemBuilder.build(newQueue[index])
        }
        val afterStartIndex = safeCurrentIndex + 1
        val afterCurrent = List((newQueue.size - afterStartIndex).coerceAtLeast(0)) { offset ->
            MediaItemBuilder.build(newQueue[afterStartIndex + offset])
        }

        PreparedQueueSegments(
            beforeCurrent = beforeCurrent,
            afterCurrent = afterCurrent
        )
    }

    private fun replacePlayerQueuePreservingCurrent(
        currentIndex: Int,
        preparedSegments: PreparedQueueSegments
    ): Boolean {
        val masterPlayer = dualPlayerEngine.masterPlayer
        val mediaItemCount = masterPlayer.mediaItemCount
        if (currentIndex !in 0 until mediaItemCount) {
            return false
        }

        val afterStartIndex = currentIndex + 1
        if (preparedSegments.beforeCurrent.size != currentIndex) {
            return false
        }
        if (preparedSegments.afterCurrent.size != (mediaItemCount - afterStartIndex)) {
            return false
        }

        if (currentIndex > 0) {
            masterPlayer.replaceMediaItems(0, currentIndex, preparedSegments.beforeCurrent)
        }
        masterPlayer.replaceMediaItems(afterStartIndex, mediaItemCount, preparedSegments.afterCurrent)
        return masterPlayer.currentMediaItemIndex == currentIndex
    }

    private fun replacePlayerQueue(
        player: Player,
        preparedQueue: PreparedQueueReplacement,
        currentPosition: Long
    ) {
        val shouldResumePlayback = player.playWhenReady || player.isPlaying
        val masterPlayer = dualPlayerEngine.masterPlayer

        masterPlayer.setMediaItems(
            preparedQueue.mediaItems,
            preparedQueue.targetIndex,
            currentPosition
        )

        if (shouldResumePlayback) {
            masterPlayer.playWhenReady = true
            if (!masterPlayer.isPlaying) {
                masterPlayer.play()
            }
        }
    }

    fun toggleShuffle(
        currentSongs: List<Song>,
        currentSong: Song?,
        currentQueueSourceName: String,
        updateQueueCallback: (List<Song>) -> Unit
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        if (shuffleToggleJob?.isActive == true) return
        if ((nowMs - lastShuffleToggleFinishedAtMs) < SHUFFLE_TOGGLE_COOLDOWN_MS) return

        val coroutineScope = scope ?: return
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            shuffleToggleJob = coroutineScope.launch {
                _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = true) }
                try {
                    val remoteMediaClient = castSession.remoteMediaClient
                    val newRepeatMode = if (remoteMediaClient?.mediaStatus?.getQueueRepeatMode() == MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE) {
                        MediaStatus.REPEAT_MODE_REPEAT_ALL
                    } else {
                        MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE
                    }
                    castStateHolder.castPlayer?.setRepeatMode(newRepeatMode)
                } finally {
                    lastShuffleToggleFinishedAtMs = SystemClock.elapsedRealtime()
                    _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = false) }
                    shuffleToggleJob = null
                }
            }
         } else {
            shuffleToggleJob = coroutineScope.launch {
                _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = true) }
                try {
                    val player = mediaController ?: return@launch
                    if (currentSongs.isEmpty()) return@launch

                    val isCurrentlyShuffled = _stablePlayerState.value.isShuffleEnabled

                    if (!isCurrentlyShuffled) {
                        // Enable Shuffle
                        if (!queueStateHolder.hasOriginalQueue()) {
                            queueStateHolder.setOriginalQueueOrder(currentSongs)
                            queueStateHolder.saveOriginalQueueState(currentSongs, currentQueueSourceName)
                        }

                        val currentMediaId = player.currentMediaItem?.mediaId ?: currentSong?.id
                        val playerCurrentIndex = player.currentMediaItemIndex
                            .takeIf { it in currentSongs.indices }
                        val currentIndex = when {
                            playerCurrentIndex != null && currentMediaId != null &&
                                currentSongs.getOrNull(playerCurrentIndex)?.id == currentMediaId -> playerCurrentIndex
                            playerCurrentIndex != null && currentMediaId == null -> playerCurrentIndex
                            currentMediaId != null ->
                                currentSongs.indexOfFirst { it.id == currentMediaId }.takeIf { it >= 0 }
                            else -> null
                        } ?: 0
                        val currentPosition = player.currentPosition
                        val wasPlaying = player.isPlaying
                        val currentMediaItem = player.currentMediaItem

                        // Run heavy shuffle work off main to keep UI and playback responsive.
                        val shuffledQueue = withContext(Dispatchers.Default) {
                            QueueUtils.buildAnchoredShuffleQueueSuspending(currentSongs, currentIndex)
                        }

                        // For large queues, use bulk replace (1 IPC call) instead of
                        // per-item moveMediaItem (n IPC calls) which freezes the UI.
                        if (currentSongs.size > BULK_REPLACE_THRESHOLD) {
                            val preservedReplacement = buildQueueSegments(
                                newQueue = shuffledQueue,
                                currentIndex = currentIndex,
                                currentMediaItem = currentMediaItem
                            )
                            val replacedInPlace = preservedReplacement?.let { preparedSegments ->
                                replacePlayerQueuePreservingCurrent(currentIndex, preparedSegments)
                            } == true

                            if (!replacedInPlace) {
                                val preparedQueue = buildQueueReplacement(
                                    newQueue = shuffledQueue,
                                    targetIndex = currentIndex,
                                    currentMediaItem = currentMediaItem
                                )
                                replacePlayerQueue(player, preparedQueue, currentPosition)
                            }
                        } else {
                            val reordered = reorderQueueInPlace(player, shuffledQueue)
                            if (!reordered) {
                                val preservedReplacement = buildQueueSegments(
                                    newQueue = shuffledQueue,
                                    currentIndex = currentIndex,
                                    currentMediaItem = currentMediaItem
                                )
                                val replacedInPlace = preservedReplacement?.let { preparedSegments ->
                                    replacePlayerQueuePreservingCurrent(currentIndex, preparedSegments)
                                } == true

                                if (!replacedInPlace) {
                                    val preparedQueue = buildQueueReplacement(
                                        newQueue = shuffledQueue,
                                        targetIndex = currentIndex,
                                        currentMediaItem = currentMediaItem
                                    )
                                    replacePlayerQueue(player, preparedQueue, currentPosition)
                                }
                            }
                        }

                        updateQueueCallback(shuffledQueue)
                        _stablePlayerState.update { it.copy(isShuffleEnabled = true) }
                        if (wasPlaying && !player.isPlaying) {
                            player.play()
                        }

                        scope?.launch {
                            if (userPreferencesRepository.persistentShuffleEnabledFlow.first()) {
                                userPreferencesRepository.setShuffleOn(true)
                            }
                        }
                    } else {
                        // Disable Shuffle
                        scope?.launch {
                            if (userPreferencesRepository.persistentShuffleEnabledFlow.first()) {
                                userPreferencesRepository.setShuffleOn(false)
                            }
                        }

                        if (!queueStateHolder.hasOriginalQueue()) {
                            _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                            return@launch
                        }

                        val originalQueue = queueStateHolder.originalQueueOrder
                        val wasPlaying = player.isPlaying
                        val currentPosition = player.currentPosition
                        val currentSongId = currentSong?.id ?: player.currentMediaItem?.mediaId
                        val currentMediaItem = player.currentMediaItem
                        val originalIndex = originalQueue.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }

                        if (originalIndex == null) {
                            _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                            return@launch
                        }

                        // Use bulk replace for large queues to avoid UI freeze
                        if (originalQueue.size > BULK_REPLACE_THRESHOLD) {
                            val preservedReplacement = buildQueueSegments(
                                newQueue = originalQueue,
                                currentIndex = originalIndex,
                                currentMediaItem = currentMediaItem
                            )
                            val replacedInPlace = preservedReplacement?.let { preparedSegments ->
                                replacePlayerQueuePreservingCurrent(originalIndex, preparedSegments)
                            } == true

                            if (!replacedInPlace) {
                                val preparedQueue = buildQueueReplacement(
                                    newQueue = originalQueue,
                                    targetIndex = originalIndex,
                                    currentMediaItem = currentMediaItem
                                )
                                replacePlayerQueue(player, preparedQueue, currentPosition)
                            }
                        } else {
                            val reordered = reorderQueueInPlace(player, originalQueue)
                            if (!reordered) {
                                val preservedReplacement = buildQueueSegments(
                                    newQueue = originalQueue,
                                    currentIndex = originalIndex,
                                    currentMediaItem = currentMediaItem
                                )
                                val replacedInPlace = preservedReplacement?.let { preparedSegments ->
                                    replacePlayerQueuePreservingCurrent(originalIndex, preparedSegments)
                                } == true

                                if (!replacedInPlace) {
                                    val preparedQueue = buildQueueReplacement(
                                        newQueue = originalQueue,
                                        targetIndex = originalIndex,
                                        currentMediaItem = currentMediaItem
                                    )
                                    replacePlayerQueue(player, preparedQueue, currentPosition)
                                }
                            }
                        }

                        updateQueueCallback(originalQueue)
                        _stablePlayerState.update { it.copy(isShuffleEnabled = false) }
                        if (wasPlaying && !player.isPlaying) {
                            player.play()
                        }
                    }
                } finally {
                    lastShuffleToggleFinishedAtMs = SystemClock.elapsedRealtime()
                    _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = false) }
                    shuffleToggleJob = null
                }
            }
        }
    }

    fun onCleared() {
        stopProgressUpdates()
        remoteSeekUnlockJob?.cancel()
        remoteSeekUnlockJob = null
        shuffleToggleJob?.cancel()
        shuffleToggleJob = null
        scope = null
        onCastSeekBlocked = null
    }

}
