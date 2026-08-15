package com.theveloper.pixelplay.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearLoadControlProfileTest {

    @Test
    fun normalDevice_usesFullPrefetchProfile() {
        val profile = wearLoadControlBufferProfileFor(isLowRamDevice = false)

        assertThat(profile.minBufferMs).isEqualTo(30_000)
        assertThat(profile.maxBufferMs).isEqualTo(60_000)
        assertThat(profile.bufferForPlaybackMs).isEqualTo(2_500)
        assertThat(profile.bufferForPlaybackAfterRebufferMs).isEqualTo(5_000)
    }

    @Test
    fun lowRamDevice_cutsPrefetchWindow() {
        val normal = wearLoadControlBufferProfileFor(isLowRamDevice = false)
        val lowRam = wearLoadControlBufferProfileFor(isLowRamDevice = true)

        assertThat(lowRam.maxBufferMs).isLessThan(normal.maxBufferMs)
        assertThat(lowRam.minBufferMs).isLessThan(normal.minBufferMs)
    }

    @Test
    fun lowRamDevice_keepsStartLatencyIdenticalToNormal() {
        // Capping the prefetch window must not regress how quickly playback actually starts.
        val normal = wearLoadControlBufferProfileFor(isLowRamDevice = false)
        val lowRam = wearLoadControlBufferProfileFor(isLowRamDevice = true)

        assertThat(lowRam.bufferForPlaybackMs).isEqualTo(normal.bufferForPlaybackMs)
        assertThat(lowRam.bufferForPlaybackAfterRebufferMs)
            .isEqualTo(normal.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun bothProfiles_satisfyDefaultLoadControlConstraints() {
        for (isLowRam in listOf(false, true)) {
            val profile = wearLoadControlBufferProfileFor(isLowRam)

            // DefaultLoadControl.Builder.build() asserts these; violating them crashes at runtime.
            assertThat(profile.minBufferMs).isAtLeast(profile.bufferForPlaybackMs)
            assertThat(profile.minBufferMs).isAtLeast(profile.bufferForPlaybackAfterRebufferMs)
            assertThat(profile.maxBufferMs).isAtLeast(profile.minBufferMs)
        }
    }
}
