package com.theveloper.pixelplay.data

/**
 * Decides whether a stall shortly after audio-offload playback started should be read as an
 * offload HAL reset and trigger falling back to normal (non-offload) playback for the rest of
 * the session.
 *
 * Mirrors the phone's `DualPlayerEngine.shouldDisableAudioOffloadOnEarlyBuffering` — same
 * pattern, simplified for [WearPlaybackService]'s single-player service (the phone's version
 * also guards against its dual-player crossfade transitions, which don't exist here). Wear OS
 * has no equivalent yet to the phone's per-OEM offload denylist
 * (`shouldDisableAudioOffloadByDefaultForDevice` in `:app`) — there is no field evidence of which
 * watch chipsets misbehave with offload, so [WearPlaybackService] always requests it and relies
 * entirely on this runtime safety net instead of guessing at a denylist with no evidence behind
 * it.
 *
 * The buffering is NOT treated as a HAL reset when it's explained by a recent user seek
 * ([isPostSeekBuffering]) or a track change ([isPostMediaItemTransition]) — in those cases
 * buffering is expected, and falling back would needlessly rebuild the player (an audible
 * glitch) for no reason.
 */
internal fun wearShouldFallBackFromAudioOffload(
    audioOffloadEnabled: Boolean,
    lastPlayingAtMs: Long,
    timeSincePlayingMs: Long,
    isPostSeekBuffering: Boolean,
    isPostMediaItemTransition: Boolean,
): Boolean {
    return audioOffloadEnabled &&
        lastPlayingAtMs > 0L &&
        timeSincePlayingMs < 500L &&
        !isPostSeekBuffering &&
        !isPostMediaItemTransition
}
