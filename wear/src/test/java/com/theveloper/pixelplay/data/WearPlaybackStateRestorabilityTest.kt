package com.theveloper.pixelplay.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearPlaybackStateRestorabilityTest {

    private val oneHourMs = 60 * 60 * 1000L

    private fun state(
        queueSongIds: List<String> = listOf("s1", "s2"),
        currentIndex: Int = 0,
        updatedAtMillis: Long = 0L,
    ) = PersistedLocalPlaybackState(
        queueSongIds = queueSongIds,
        currentIndex = currentIndex,
        positionMs = 1_000L,
        updatedAtMillis = updatedAtMillis,
    )

    @Test
    fun `a recent state with a valid index is restorable`() {
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(updatedAtMillis = 0L),
            nowMillis = oneHourMs,
        )

        assertThat(restorable).isTrue()
    }

    @Test
    fun `an empty queue is never restorable`() {
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(queueSongIds = emptyList(), currentIndex = 0, updatedAtMillis = 0L),
            nowMillis = 0L,
        )

        assertThat(restorable).isFalse()
    }

    @Test
    fun `an out-of-range index is not restorable`() {
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(queueSongIds = listOf("s1"), currentIndex = 5, updatedAtMillis = 0L),
            nowMillis = 0L,
        )

        assertThat(restorable).isFalse()
    }

    @Test
    fun `a state older than the max age is not restorable`() {
        val maxAge = 6 * oneHourMs
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(updatedAtMillis = 0L),
            nowMillis = maxAge + 1L,
            maxAgeMillis = maxAge,
        )

        assertThat(restorable).isFalse()
    }

    @Test
    fun `a state exactly at the max age boundary is still restorable`() {
        val maxAge = 6 * oneHourMs
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(updatedAtMillis = 0L),
            nowMillis = maxAge,
            maxAgeMillis = maxAge,
        )

        assertThat(restorable).isTrue()
    }

    @Test
    fun `a state with a future timestamp is not restorable`() {
        // Defensive: clock skew or a corrupted timestamp shouldn't be treated as "very fresh".
        val restorable = isPersistedLocalPlaybackStateRestorable(
            state = state(updatedAtMillis = 10_000L),
            nowMillis = 0L,
        )

        assertThat(restorable).isFalse()
    }
}
