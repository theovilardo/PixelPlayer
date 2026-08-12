package com.theveloper.pixelplay.data

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground [MediaSessionService] that hosts the watch's standalone local playback.
 *
 * Playback used to run inside a plain `@Singleton` repository owned by the Application, which left
 * the process with **no foreground component** once the activity went to the background. Wear OS
 * then reaped the cached process after a few minutes and audio died silently. Hosting the
 * ExoPlayer + MediaSession in a MediaSessionService lets media3 promote the process to a
 * "mediaPlayback" foreground service (with a media notification) whenever audio is playing, so the
 * OS keeps it alive until playback actually stops.
 *
 * [WearLocalPlayerRepository] drives this service's player through a `MediaController`.
 */
class WearPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- Audio offload state -------------------------------------------------------------
    // AUDIO_OFFLOAD_MODE_ENABLED (as opposed to _REQUIRED) is a *soft* request: if the watch's
    // audio HAL doesn't support offloading this format, ExoPlayer silently falls back to the
    // normal decode path on its own — no capability probing needed on our side for that case.
    // What ExoPlayer *doesn't* handle on its own is a HAL that accepts the offloaded track but
    // then resets/stalls — that failure mode is exactly what motivated the phone's
    // DualPlayerEngine to build a runtime fallback (see AudioOffloadPolicyTest in :app), so this
    // service mirrors that safety net rather than assuming Wear OS audio HALs are better-behaved.
    private var audioOffloadEnabled = true
    private var lastPlayingAtMs = 0L
    private var isPostSeekBuffering = false
    private var isPostMediaItemTransition = false

    // --- Mid-song stall watchdog ----------------------------------------------------------
    // See WearPlaybackStallWatchdog.kt: catches a stall AudioOffloadFallbackListener can't, one
    // that happens well after playback started and never surfaces as STATE_BUFFERING.
    private var stallWatchdogJob: Job? = null
    private var lastWatchdogPositionMs = -1L
    private var consecutiveStalledTicks = 0

    override fun onCreate() {
        super.onCreate()
        player = buildExoPlayer()
        mediaSession = buildMediaSession(player!!)
        startStallWatchdog()
        Timber.tag(TAG).d("WearPlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away while nothing is playing, there's nothing to keep alive.
        val activePlayer = player
        if (activePlayer == null || !activePlayer.playWhenReady || activePlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stallWatchdogJob?.cancel()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        Timber.tag(TAG).d("WearPlaybackService destroyed")
        super.onDestroy()
    }

    private fun buildExoPlayer(): ExoPlayer {
        val isLowRamDevice = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val bufferProfile = wearLoadControlBufferProfileFor(isLowRamDevice)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.bufferForPlaybackMs,
                bufferProfile.bufferForPlaybackAfterRebufferMs,
            )
            // Buffered *duration*, not buffered *bytes*, decides when to (re)start playback —
            // matches the phone's DualPlayerEngine and is what makes the profile above meaningful
            // across formats/bitrates instead of being overridden by ExoPlayer's byte threshold.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keep the CPU running while the watch dozes with the screen off, otherwise audio
            // decoding stalls a few seconds after the display turns off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // The default DefaultMediaSourceFactory registers ~15 extractor types (Matroska,
            // FLV, AVI, MPEG-TS…) that this service never plays — every file here comes from
            // [WatchAudioTranscoder] on the phone, which always writes plain (non-fragmented)
            // MP4/AAC-LC. ART verifies each extractor class the first time DefaultExtractorsFactory
            // touches it while sniffing the container, on the main thread; measured on-device this
            // cost 120-300ms per unused class, ~2s total, stacked right on top of playback start.
            // Scoping the factory to the one extractor we actually need removes that cost entirely.
            .setMediaSourceFactory(
                // Every other Mp4Extractor constructor/factory in this media3 version is
                // deprecated in favor of newFactory(SubtitleParser.Factory); our files are
                // audio-only, so subtitle parsing is simply unsupported.
                DefaultMediaSourceFactory(this, Mp4Extractor.newFactory(SubtitleParser.Factory.UNSUPPORTED))
            )
            .build()
        exoPlayer.trackSelectionParameters = trackSelectionParametersFor(audioOffloadEnabled)
        exoPlayer.addListener(AudioOffloadFallbackListener())
        return exoPlayer
    }

    private fun trackSelectionParametersFor(offloadEnabled: Boolean): TrackSelectionParameters {
        return TrackSelectionParameters.DEFAULT.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (offloadEnabled) {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        } else {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        }
                    )
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build()
            )
            .build()
    }

    private fun buildMediaSession(exoPlayer: ExoPlayer): MediaSession {
        return MediaSession.Builder(this, exoPlayer)
            .setId(MEDIA_SESSION_ID)
            .setSessionActivity(buildOpenAppIntent())
            .setCallback(MediaItemUriRestoringCallback())
            .build()
    }

    /**
     * Rebuilds the player after [AudioOffloadFallbackListener] reads an early re-buffer as a HAL
     * reset, disabling offload for the rest of the session.
     */
    private fun fallBackFromAudioOffload(reason: String) {
        if (!audioOffloadEnabled) return
        audioOffloadEnabled = false
        Timber.tag(TAG).w("Falling back from audio offload: %s", reason)
        rebuildPlayerPreservingState()
    }

    /**
     * Rebuilds the player after the stall watchdog sees position frozen for
     * [STALL_TICKS_THRESHOLD] ticks in a row — the *mid-song* wedge [fallBackFromAudioOffload]
     * can't see (see WearPlaybackStallWatchdog.kt). Unlike that early check, this doesn't gate on
     * [audioOffloadEnabled]: if offload is still on, disabling it too is the best available guess
     * at the cause, but the rebuild itself — a fresh ExoPlayer/AudioTrack instance — is the actual
     * fix regardless, so it still runs even if offload was already off from an earlier fallback.
     */
    private fun recoverFromStalledPlayback(reason: String) {
        Timber.tag(TAG).w("Recovering from stalled playback: %s", reason)
        audioOffloadEnabled = false
        rebuildPlayerPreservingState()
    }

    /**
     * Preserves queue/position/play-state across a player rebuild. [MediaSession.setPlayer] lets
     * the existing session (and any connected `MediaController`, including the phone acting as a
     * remote) keep its binder connection across the swap instead of tearing down and
     * reconnecting — the rebuild is invisible to callers beyond a brief re-buffer.
     */
    private fun rebuildPlayerPreservingState() {
        val oldPlayer = player ?: return

        val mediaItems = ArrayList<MediaItem>(oldPlayer.mediaItemCount)
        for (i in 0 until oldPlayer.mediaItemCount) mediaItems.add(oldPlayer.getMediaItemAt(i))
        val currentIndex = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val positionMs = oldPlayer.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldPlayer.playWhenReady
        val repeatMode = oldPlayer.repeatMode
        val shuffleModeEnabled = oldPlayer.shuffleModeEnabled

        val newPlayer = buildExoPlayer()
        if (mediaItems.isNotEmpty()) {
            newPlayer.setMediaItems(mediaItems, currentIndex, positionMs)
            newPlayer.repeatMode = repeatMode
            newPlayer.shuffleModeEnabled = shuffleModeEnabled
            newPlayer.prepare()
            newPlayer.playWhenReady = playWhenReady
        }

        player = newPlayer
        mediaSession?.setPlayer(newPlayer)
        oldPlayer.release()

        // The new player instance starts wherever setMediaItems/positionMs put it — don't let a
        // stale reading from the old (just-released) player count as "no progress" against it.
        lastWatchdogPositionMs = -1L
        consecutiveStalledTicks = 0
    }

    /**
     * Ticks once a second, comparing the player's own reported position against the last tick's —
     * a stall that doesn't change [Player.getPlaybackState] (see WearPlaybackStallWatchdog.kt)
     * has no listener callback to hook, so this is the only way to catch it.
     */
    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = scope.launch {
            while (isActive) {
                delay(STALL_TICK_INTERVAL_MS)
                val current = player ?: continue
                val isPlaying = current.isPlaying
                val position = current.currentPosition
                val positionAdvanced = position != lastWatchdogPositionMs
                lastWatchdogPositionMs = position
                consecutiveStalledTicks = wearPlaybackStalledTickCount(
                    isPlaying = isPlaying,
                    positionAdvancedSinceLastTick = positionAdvanced,
                    previousConsecutiveStalledTicks = consecutiveStalledTicks,
                )
                if (consecutiveStalledTicks >= STALL_TICKS_THRESHOLD) {
                    recoverFromStalledPlayback(
                        "no position advance for ${STALL_TICK_INTERVAL_MS * STALL_TICKS_THRESHOLD}ms"
                    )
                }
            }
        }
    }

    private fun buildOpenAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Watches for the early-buffering pattern [wearShouldFallBackFromAudioOffload] recognizes as
     * an offload HAL reset, and triggers [fallBackFromAudioOffload] when it does.
     */
    private inner class AudioOffloadFallbackListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastPlayingAtMs = SystemClock.elapsedRealtime()
                isPostSeekBuffering = false
                isPostMediaItemTransition = false
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                isPostSeekBuffering = true
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            isPostMediaItemTransition = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_BUFFERING) return
            val now = SystemClock.elapsedRealtime()
            val shouldFallBack = wearShouldFallBackFromAudioOffload(
                audioOffloadEnabled = audioOffloadEnabled,
                lastPlayingAtMs = lastPlayingAtMs,
                timeSincePlayingMs = now - lastPlayingAtMs,
                isPostSeekBuffering = isPostSeekBuffering,
                isPostMediaItemTransition = isPostMediaItemTransition,
            )
            if (shouldFallBack) {
                fallBackFromAudioOffload("early re-buffer ${now - lastPlayingAtMs}ms after playing")
            }
        }
    }

    /**
     * A `MediaController` strips [MediaItem.localConfiguration] (the playable URI) when it hands
     * items across the binder to this service. The repository stashes the original URI in
     * `requestMetadata.mediaUri`; restore it here so the service's ExoPlayer can resolve the file.
     */
    private class MediaItemUriRestoringCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.requestMetadata.mediaUri
                        ?.let { uri -> item.buildUpon().setUri(uri).build() }
                        ?: item
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        private const val TAG = "WearPlaybackService"
        private const val MEDIA_SESSION_ID = "wear-local-playback"

        // 3 consecutive 1s ticks with zero position movement while isPlaying=true — long enough
        // that a legitimate single slow tick can't false-positive, short enough that a real wedge
        // doesn't sit silent for long before recovering.
        private const val STALL_TICK_INTERVAL_MS = 1_000L
        private const val STALL_TICKS_THRESHOLD = 3
    }
}
