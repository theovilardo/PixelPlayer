package com.theveloper.pixelplay.data.worker

import androidx.work.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the constraints the automatic pass runs under. These are the difference
 * between a background feature and a surprise on someone's mobile data bill,
 * and they are set once, far from where the pass is written.
 */
class AutoCoverArtWorkerRequestTest {

    @Test
    fun `the default pass waits for an unmetered network`() {
        val request = AutoCoverArtWorker.buildRequest(unmeteredOnly = true)

        assertEquals(
            NetworkType.UNMETERED,
            request.workSpec.constraints.requiredNetworkType
        )
    }

    @Test
    fun `a user who allows mobile data only needs a connection`() {
        val request = AutoCoverArtWorker.buildRequest(unmeteredOnly = false)

        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
    }

    @Test
    fun `a pass never runs without a network at all`() {
        listOf(true, false).forEach { unmeteredOnly ->
            val request = AutoCoverArtWorker.buildRequest(unmeteredOnly)

            // Every album this pass touches costs at least one catalog request,
            // so running offline would burn through the library marking albums
            // as having no match.
            assertEquals(
                false,
                request.workSpec.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED
            )
        }
    }
}
