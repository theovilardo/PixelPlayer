package com.theveloper.pixelplay.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
@UnstableApi
class WearPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this, BufferedAudioRenderersFactory(this))
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
            .build()
        player = exoPlayer

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId(MEDIA_SESSION_ID)
            .setSessionActivity(buildOpenAppIntent())
            .setCallback(MediaItemUriRestoringCallback())
            .build()

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
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        Timber.tag(TAG).d("WearPlaybackService destroyed")
        super.onDestroy()
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

    /**
     * Local playback here is a single background audio track with no latency requirements, so
     * trade a larger [AudioSink] buffer for resilience: this watch's SoC can have as few as 2
     * cores, and a momentary CPU stall (our own UI work, or a concurrent fitness-tracking app in
     * the future) is enough to starve the platform default's small low-latency buffer and produce
     * an audible underrun. See [WearDeviceTier].
     */
    private class BufferedAudioRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(MIN_PCM_BUFFER_DURATION_US)
                        .setMaxPcmBufferDurationUs(MAX_PCM_BUFFER_DURATION_US)
                        .build()
                )
                .build()
        }
    }

    companion object {
        private const val TAG = "WearPlaybackService"
        private const val MEDIA_SESSION_ID = "wear-local-playback"
        private const val MIN_PCM_BUFFER_DURATION_US = 1_000_000 // 1s
        private const val MAX_PCM_BUFFER_DURATION_US = 3_000_000 // 3s
    }
}
