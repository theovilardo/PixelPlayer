package com.theveloper.pixelplay.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearPlaybackStallWatchdogTest {

    @Test
    fun `not playing resets the counter to zero`() {
        val result = wearPlaybackStalledTickCount(
            isPlaying = false,
            positionAdvancedSinceLastTick = false,
            previousConsecutiveStalledTicks = 2,
        )

        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `playing with position advancing resets the counter to zero`() {
        val result = wearPlaybackStalledTickCount(
            isPlaying = true,
            positionAdvancedSinceLastTick = true,
            previousConsecutiveStalledTicks = 2,
        )

        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `playing with a frozen position increments the counter`() {
        val result = wearPlaybackStalledTickCount(
            isPlaying = true,
            positionAdvancedSinceLastTick = false,
            previousConsecutiveStalledTicks = 1,
        )

        assertThat(result).isEqualTo(2)
    }

    @Test
    fun `a frozen position starting from zero counts as one stalled tick`() {
        val result = wearPlaybackStalledTickCount(
            isPlaying = true,
            positionAdvancedSinceLastTick = false,
            previousConsecutiveStalledTicks = 0,
        )

        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `a single advancing tick after several stalled ones fully resets, not decrements`() {
        val result = wearPlaybackStalledTickCount(
            isPlaying = true,
            positionAdvancedSinceLastTick = true,
            previousConsecutiveStalledTicks = 5,
        )

        assertThat(result).isEqualTo(0)
    }

    @Test
    fun `three consecutive stalled ticks reaches the production threshold`() {
        var ticks = 0
        repeat(3) {
            ticks = wearPlaybackStalledTickCount(
                isPlaying = true,
                positionAdvancedSinceLastTick = false,
                previousConsecutiveStalledTicks = ticks,
            )
        }

        assertThat(ticks).isEqualTo(3)
    }
}
