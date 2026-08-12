package com.theveloper.pixelplay.data

/** ExoPlayer [androidx.media3.exoplayer.DefaultLoadControl] buffer durations (ms). */
internal data class WearLoadControlBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

/**
 * Picks the [androidx.media3.exoplayer.DefaultLoadControl] buffer profile for
 * [WearPlaybackService]'s player.
 *
 * Mirrors the phone's `DualPlayerEngine.buildAdaptiveLoadControl()` RAM tiering — the same
 * reasoning applies here, amplified: a Wear OS SoC has far less RAM and CPU headroom than even
 * a low-end phone, and it's sharing both with whatever fitness/health app the user has running
 * at the same time (confirmed on-device: the app's own process was recycled during a system-wide
 * low-memory episode while a workout tracker ran alongside local playback). ExoPlayer's default
 * buffer window wasn't sized for that, and measured on-device it produced sustained
 * PLAYING/BUFFERING oscillation. [android.app.ActivityManager.isLowRamDevice] is the same signal
 * the phone already uses to pick its conservative tier, so this reuses it rather than inventing a
 * new watch-specific threshold with no evidence behind it.
 */
internal fun wearLoadControlBufferProfileFor(isLowRamDevice: Boolean): WearLoadControlBufferProfile {
    return if (isLowRamDevice) {
        WearLoadControlBufferProfile(
            minBufferMs = 15_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
        )
    } else {
        WearLoadControlBufferProfile(
            minBufferMs = 30_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
        )
    }
}
