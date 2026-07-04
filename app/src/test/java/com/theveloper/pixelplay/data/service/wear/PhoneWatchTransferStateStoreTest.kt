package com.theveloper.pixelplay.data.service.wear

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.shared.WearTransferProgress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PhoneWatchTransferStateStoreTest {

    private val store = PhoneWatchTransferStateStore()

    @Test
    fun `markBatchStarted publishes the initial aggregate state`() = runTest {
        store.batchTransfers.test {
            assertThat(awaitItem()).isEmpty()

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 4)

            val batch = awaitItem().getValue("batch-1")
            assertThat(batch.totalSongs).isEqualTo(4)
            assertThat(batch.completedSongs).isEqualTo(0)
            assertThat(batch.status).isEqualTo(WearTransferProgress.STATUS_TRANSFERRING)
            assertThat(batch.overallProgress).isEqualTo(0f)
        }
    }

    @Test
    fun `song lifecycle updates activeRequestId, progress and completedSongs`() = runTest {
        store.batchTransfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 2)
            awaitItem()

            store.markBatchSongStarted("batch-1", "req-1", "test_flac")
            val started = awaitItem().getValue("batch-1")
            assertThat(started.activeRequestId).isEqualTo("req-1")
            assertThat(started.currentSongTitle).isEqualTo("test_flac")
            assertThat(started.status).isEqualTo(WearTransferProgress.STATUS_TRANSFERRING)

            store.markBatchSongProgress("batch-1", WearTransferProgress.STATUS_TRANSCODING, 0.5f)
            val transcoding = awaitItem().getValue("batch-1")
            assertThat(transcoding.status).isEqualTo(WearTransferProgress.STATUS_TRANSCODING)
            assertThat(transcoding.currentSongProgress).isEqualTo(0.5f)
            // First of 2 songs, halfway through: (0 completed + 0.5) / 2
            assertThat(transcoding.overallProgress).isEqualTo(0.25f)

            store.markBatchSongCompleted("batch-1")
            val completedOne = awaitItem().getValue("batch-1")
            assertThat(completedOne.completedSongs).isEqualTo(1)
            assertThat(completedOne.activeRequestId).isNull()
            assertThat(completedOne.currentSongProgress).isEqualTo(0f)
            assertThat(completedOne.overallProgress).isEqualTo(0.5f)
        }
    }

    @Test
    fun `markBatchCompleted sets terminal status and clears the active song`() = runTest {
        store.batchTransfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 1)
            awaitItem()
            store.markBatchSongStarted("batch-1", "req-1", "test_mp3_320")
            awaitItem()

            store.markBatchCompleted("batch-1")
            val completed = awaitItem().getValue("batch-1")
            assertThat(completed.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
            assertThat(completed.activeRequestId).isNull()
        }
    }

    @Test
    fun `markBatchCancelled sets cancelled status and keeps completedSongs so far`() = runTest {
        store.batchTransfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 3)
            awaitItem()
            store.markBatchSongStarted("batch-1", "req-1", "test_flac")
            awaitItem()
            store.markBatchSongCompleted("batch-1")
            awaitItem()

            store.markBatchCancelled("batch-1")
            val cancelled = awaitItem().getValue("batch-1")
            assertThat(cancelled.status).isEqualTo(WearTransferProgress.STATUS_CANCELLED)
            // The song already sent before cancelling is not undone.
            assertThat(cancelled.completedSongs).isEqualTo(1)
        }
    }

    @Test
    fun `markBatchFailed records the error message`() = runTest {
        store.batchTransfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 1)
            awaitItem()

            store.markBatchFailed("batch-1", "No reachable watch with PixelPlay")
            val failed = awaitItem().getValue("batch-1")
            assertThat(failed.status).isEqualTo(WearTransferProgress.STATUS_FAILED)
            assertThat(failed.error).isEqualTo("No reachable watch with PixelPlay")
        }
    }

    @Test
    fun `updates for an unknown batchId are ignored`() = runTest {
        store.markBatchSongStarted("unknown-batch", "req-1", "test_flac")
        store.markBatchSongProgress("unknown-batch", WearTransferProgress.STATUS_TRANSFERRING, 0.5f)
        store.markBatchSongCompleted("unknown-batch")
        store.markBatchCompleted("unknown-batch")

        assertThat(store.batchTransfers.value).isEmpty()
    }

    @Test
    fun `markBatchSongFailed increments the failure count and records the reason`() = runTest {
        store.batchTransfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 2)
            awaitItem()
            store.markBatchSongStarted("batch-1", "req-1", "test_flac")
            awaitItem()

            store.markBatchSongFailed("batch-1", WearTransferProgress.ERROR_CODE_TIMED_OUT)
            val failedOnce = awaitItem().getValue("batch-1")
            assertThat(failedOnce.failedSongCount).isEqualTo(1)
            assertThat(failedOnce.lastFailureErrorCode).isEqualTo(WearTransferProgress.ERROR_CODE_TIMED_OUT)
            assertThat(failedOnce.activeRequestId).isNull()

            // A later failure with a null errorCode shouldn't erase the last known reason.
            store.markBatchSongFailed("batch-1", null)
            val failedTwice = awaitItem().getValue("batch-1")
            assertThat(failedTwice.failedSongCount).isEqualTo(2)
            assertThat(failedTwice.lastFailureErrorCode).isEqualTo(WearTransferProgress.ERROR_CODE_TIMED_OUT)
        }
    }

    @Test
    fun `markSongPresentOnWatch and clearSongPresentOnWatch toggle isSongSavedOnAllReachableWatches`() = runTest {
        store.retainReachableWatchNodes(setOf("node-1"))
        assertThat(store.isSongSavedOnAllReachableWatches("song-1")).isFalse()

        store.markSongPresentOnWatch("node-1", "song-1")
        assertThat(store.isSongSavedOnAllReachableWatches("song-1")).isTrue()

        store.clearSongPresentOnWatch("node-1", "song-1")
        assertThat(store.isSongSavedOnAllReachableWatches("song-1")).isFalse()
    }

    @Test
    fun `clearSongPresentOnWatch is a no-op for a song that was never marked present`() = runTest {
        store.retainReachableWatchNodes(setOf("node-1"))
        store.markSongPresentOnWatch("node-1", "song-1")

        store.clearSongPresentOnWatch("node-1", "song-2")

        assertThat(store.isSongSavedOnAllReachableWatches("song-1")).isTrue()
    }

    @Test
    fun `markProgress with AWAITING_WATCH_ACK status is stored as-is`() = runTest {
        store.transfers.test {
            awaitItem() // initial empty snapshot from subscribing to the StateFlow

            store.markProgress(
                requestId = "req-1",
                songId = "song-1",
                bytesTransferred = 100L,
                totalBytes = 100L,
                status = WearTransferProgress.STATUS_AWAITING_WATCH_ACK,
            )
            val awaitingAck = awaitItem().getValue("req-1")
            assertThat(awaitingAck.status).isEqualTo(WearTransferProgress.STATUS_AWAITING_WATCH_ACK)

            // The watch's ack later resolves it to a real terminal status.
            store.markProgress(
                requestId = "req-1",
                songId = "song-1",
                bytesTransferred = 100L,
                totalBytes = 100L,
                status = WearTransferProgress.STATUS_COMPLETED,
            )
            val completed = awaitItem().getValue("req-1")
            assertThat(completed.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
        }
    }
}
