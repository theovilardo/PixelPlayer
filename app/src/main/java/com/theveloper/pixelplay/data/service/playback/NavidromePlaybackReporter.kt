package com.theveloper.pixelplay.data.service.playback

import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Handles Navidrome scrobble/playback reporting: periodic "playing" reports
 * and "stopped"/"paused" reports on state changes.
 *
 * Extracted from [MusicService] to isolate Navidrome-specific reporting
 * from the service's media-session lifecycle management.
 */
class NavidromePlaybackReporter(
    private val navidromeRepository: NavidromeRepository,
    private val engine: DualPlayerEngine,
    private val serviceScope: CoroutineScope,
    private val appScope: CoroutineScope,
) {

    private var reportJob: Job? = null

    fun getNavidromeId(mediaItem: MediaItem?): String? {
        mediaItem ?: return null
        return mediaItem.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_SONG_ID)
            ?: mediaItem.mediaId.let { if (it.startsWith("navidrome_")) it.substringAfter("navidrome_") else null }
            ?: mediaItem.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)?.let {
                if (it.startsWith("navidrome://")) it.substringAfter("navidrome://") else null
            }
    }

    fun isNavidromeMediaItem(mediaItem: MediaItem?): Boolean {
        return getNavidromeId(mediaItem) != null
    }

    fun reportPlayback(state: String, mediaItem: MediaItem? = engine.masterPlayer.currentMediaItem) {
        val player = engine.masterPlayer
        val targetItem = mediaItem ?: return
        val navidromeId = getNavidromeId(targetItem) ?: return

        val positionMs = if (targetItem === player.currentMediaItem) {
            player.currentPosition
        } else {
            targetItem.mediaMetadata.extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION) ?: 0L
        }
        val playbackRate = player.playbackParameters.speed

        appScope.launch(Dispatchers.IO) {
            navidromeRepository.reportPlayback(
                navidromeId = navidromeId,
                positionMs = positionMs,
                state = state,
                playbackRate = playbackRate
            )
        }
    }

    fun startReporting() {
        reportJob?.cancel()
        reportJob = serviceScope.launch {
            while (true) {
                delay(30_000)
                val player = engine.masterPlayer
                if (player.isPlaying && isNavidromeMediaItem(player.currentMediaItem)) {
                    reportPlayback("playing")
                }
            }
        }
    }

    fun stopReporting() {
        reportJob?.cancel()
        reportJob = null
    }

    companion object {
        private const val TAG = "NavidromePlaybackReporter"
    }
}
