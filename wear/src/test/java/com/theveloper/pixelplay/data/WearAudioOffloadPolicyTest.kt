package com.theveloper.pixelplay.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearAudioOffloadPolicyTest {

    @Test
    fun earlyBuffering_fallsBackForGenuineHalReset() {
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = true,
            lastPlayingAtMs = 1_000L,
            timeSincePlayingMs = 120L,
            isPostSeekBuffering = false,
            isPostMediaItemTransition = false,
        )

        assertThat(shouldFallBack).isTrue()
    }

    @Test
    fun earlyBuffering_doesNotFallBackWhenOffloadIsAlreadyDisabled() {
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = false,
            lastPlayingAtMs = 1_000L,
            timeSincePlayingMs = 120L,
            isPostSeekBuffering = false,
            isPostMediaItemTransition = false,
        )

        assertThat(shouldFallBack).isFalse()
    }

    @Test
    fun earlyBuffering_doesNotFallBackRightAfterASeek() {
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = true,
            lastPlayingAtMs = 1_000L,
            timeSincePlayingMs = 120L,
            isPostSeekBuffering = true,
            isPostMediaItemTransition = false,
        )

        assertThat(shouldFallBack).isFalse()
    }

    @Test
    fun earlyBuffering_doesNotFallBackRightAfterATrackChange() {
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = true,
            lastPlayingAtMs = 1_000L,
            timeSincePlayingMs = 120L,
            isPostSeekBuffering = false,
            isPostMediaItemTransition = true,
        )

        assertThat(shouldFallBack).isFalse()
    }

    @Test
    fun earlyBuffering_doesNotFallBackAfterLongSteadyPlayback() {
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = true,
            lastPlayingAtMs = 1_000L,
            timeSincePlayingMs = 5_000L,
            isPostSeekBuffering = false,
            isPostMediaItemTransition = false,
        )

        assertThat(shouldFallBack).isFalse()
    }

    @Test
    fun earlyBuffering_doesNotFallBackBeforeAnyPlaybackEverStarted() {
        // lastPlayingAtMs == 0L means playback never reached PLAYING yet — the very first
        // buffer-up on cold start is not an offload HAL reset.
        val shouldFallBack = wearShouldFallBackFromAudioOffload(
            audioOffloadEnabled = true,
            lastPlayingAtMs = 0L,
            timeSincePlayingMs = 120L,
            isPostSeekBuffering = false,
            isPostMediaItemTransition = false,
        )

        assertThat(shouldFallBack).isFalse()
    }
}
