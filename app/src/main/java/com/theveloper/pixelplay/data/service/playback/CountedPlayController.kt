package com.theveloper.pixelplay.data.service.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine

/**
 * Manages "counted play" — play a track N times then pause.
 * Used by the notification and Wear for "play 1/3/5 times" features.
 *
 * Extracted from [MusicService] to isolate counted-play state tracking
 * from the service's media-session lifecycle management.
 */
class CountedPlayController(
    private val engine: DualPlayerEngine,
) {

    private var active = false
    private var target = 0
    private var count = 0
    private var originalId: String? = null
    private var listener: Player.Listener? = null

    fun start(count: Int) {
        val player = engine.masterPlayer
        val currentItem = player.currentMediaItem ?: return

        stop()

        target = count
        this.count = 1
        originalId = currentItem.mediaId
        active = true

        player.repeatMode = Player.REPEAT_MODE_ONE

        val l = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (!active) return
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    this@CountedPlayController.count++
                    if (this@CountedPlayController.count > target) {
                        player.pause()
                        stop()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!active) return
                if (mediaItem?.mediaId != originalId) {
                    stop()
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (active && repeatMode != Player.REPEAT_MODE_ONE) {
                    stop(restoreRepeatMode = false)
                }
            }
        }

        listener = l
        player.addListener(l)
    }

    fun stop(restoreRepeatMode: Boolean = true) {
        if (!active) return

        active = false
        target = 0
        count = 0
        originalId = null

        listener?.let { engine.masterPlayer.removeListener(it) }
        listener = null

        if (restoreRepeatMode) {
            engine.masterPlayer.repeatMode = Player.REPEAT_MODE_OFF
        }
    }
}
