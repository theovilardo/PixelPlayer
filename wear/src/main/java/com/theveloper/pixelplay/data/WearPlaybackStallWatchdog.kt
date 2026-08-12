package com.theveloper.pixelplay.data

/**
 * Detects a playback stall that [wearShouldFallBackFromAudioOffload] can't see.
 *
 * That check only reacts to a stall within ~500ms of *starting* playback, surfaced as
 * `STATE_BUFFERING` — the pattern the phone's `DualPlayerEngine` originally guarded against. Real
 * hardware testing on the watch (a Samsung Galaxy Watch) showed a different failure: the offload
 * HAL wedges *mid-song*, well past that early window, and the player's own reported state never
 * changes — it stays `STATE_READY` / `isPlaying=true` (the AudioTrack has simply stopped draining
 * what ExoPlayer feeds it). That's consistent with what was observed: `play()` does nothing once
 * this happens (from the player's point of view, `playWhenReady` is already `true` — there's
 * nothing to resume), while `seekToNext()` still works (it tears down and rebuilds the sink for
 * the new item, sidestepping the wedged one — and then wedges again on that new item too, since
 * whatever the underlying condition is hasn't changed).
 *
 * Since there's no state transition to hook, this is driven by a timer polling the player's own
 * reported position instead: called once per tick while ticking at a fixed interval (see
 * [WearPlaybackService]), it turns "did the position actually move since last tick" into a
 * consecutive-stall counter, so a real freeze (not just a brief legitimate pause in advancing) is
 * required before anything reacts.
 */
internal fun wearPlaybackStalledTickCount(
    isPlaying: Boolean,
    positionAdvancedSinceLastTick: Boolean,
    previousConsecutiveStalledTicks: Int,
): Int {
    if (!isPlaying || positionAdvancedSinceLastTick) return 0
    return previousConsecutiveStalledTicks + 1
}
