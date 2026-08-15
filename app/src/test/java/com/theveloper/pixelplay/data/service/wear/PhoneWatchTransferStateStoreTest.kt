package com.theveloper.pixelplay.data.service.wear

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.shared.WearTransferProgress
import org.junit.jupiter.api.Test

/**
 * Doesn't exercise the store's terminal-state cleanup (it runs on an internal, non-injectable
 * `Dispatchers.Default` scope after a fixed real-time delay — asserting on it here would mean
 * either a real sleep, which `GEN-TEST-04` rules out, or refactoring the store's scope handling,
 * which is out of scope for this change). Every test below only asserts on state transitions that
 * are visible synchronously. The awaiting-watch-ack timeout runs on that same scope and is
 * excluded for the same reason — it's covered by device testing instead.
 */
class PhoneWatchTransferStateStoreTest {

    private val store = PhoneWatchTransferStateStore()

    // --- Per-song transfers (existing, previously untested) ---

    @Test
    fun `markRequested creates a transferring entry`() {
        store.markRequested(requestId = "r1", songId = "s1", songTitle = "Song")

        val state = store.transfers.value["r1"]
        assertThat(state?.songId).isEqualTo("s1")
        assertThat(state?.status).isEqualTo(WearTransferProgress.STATUS_TRANSFERRING)
    }

    @Test
    fun `markProgress keeps the highest bytesTransferred seen, never regresses`() {
        store.markProgress("r1", "s1", bytesTransferred = 500L, totalBytes = 1000L, status = WearTransferProgress.STATUS_TRANSFERRING)
        store.markProgress("r1", "s1", bytesTransferred = 200L, totalBytes = 1000L, status = WearTransferProgress.STATUS_TRANSFERRING)

        assertThat(store.transfers.value["r1"]?.bytesTransferred).isEqualTo(500L)
    }

    @Test
    fun `progress is the clamped ratio of bytesTransferred to totalBytes`() {
        val state = PhoneWatchTransferState(requestId = "r1", songId = "s1", bytesTransferred = 50L, totalBytes = 100L)
        assertThat(state.progress).isEqualTo(0.5f)
    }

    @Test
    fun `progress is zero when totalBytes is not yet known`() {
        val state = PhoneWatchTransferState(requestId = "r1", songId = "s1", bytesTransferred = 0L, totalBytes = 0L)
        assertThat(state.progress).isEqualTo(0f)
    }

    @Test
    fun `awaiting-watch-ack is recorded as a live, non-terminal state`() {
        store.markRequested(requestId = "r1", songId = "s1")
        store.markProgress(
            requestId = "r1",
            songId = "s1",
            bytesTransferred = 100L,
            totalBytes = 100L,
            status = WearTransferProgress.STATUS_AWAITING_WATCH_ACK,
        )

        // The phone is waiting on the watch's own write-complete report here, so the entry must
        // stay visible (the notification still shows the song) and must not be read as finished.
        assertThat(store.transfers.value["r1"]?.status)
            .isEqualTo(WearTransferProgress.STATUS_AWAITING_WATCH_ACK)
    }

    @Test
    fun `markCancelled marks an existing transfer as cancelled without creating a new one`() {
        store.markRequested("r1", "s1")
        store.markCancelled("r1", error = "user cancelled")

        val state = store.transfers.value["r1"]
        assertThat(state?.status).isEqualTo(WearTransferProgress.STATUS_CANCELLED)
        assertThat(state?.error).isEqualTo("user cancelled")
    }

    @Test
    fun `markCancelled for an unknown requestId is a no-op`() {
        store.markCancelled("unknown")
        assertThat(store.transfers.value).isEmpty()
    }

    @Test
    fun `markSongPresentOnWatch and isSongSavedOnAllReachableWatches agree once every reachable node has it`() {
        store.retainReachableWatchNodes(setOf("node-1", "node-2"))

        assertThat(store.isSongSavedOnAllReachableWatches("s1")).isFalse()

        store.markSongPresentOnWatch("node-1", "s1")
        assertThat(store.isSongSavedOnAllReachableWatches("s1")).isFalse()

        store.markSongPresentOnWatch("node-2", "s1")
        assertThat(store.isSongSavedOnAllReachableWatches("s1")).isTrue()
    }

    @Test
    fun `isSongSavedOnAllReachableWatches is false when there are no reachable watches`() {
        assertThat(store.isSongSavedOnAllReachableWatches("s1")).isFalse()
    }

    @Test
    fun `retainReachableWatchNodes forgets song presence recorded for a node that dropped out`() {
        store.retainReachableWatchNodes(setOf("node-1"))
        store.markSongPresentOnWatch("node-1", "s1")
        assertThat(store.isSongSavedOnAllReachableWatches("s1")).isTrue()

        store.retainReachableWatchNodes(setOf("node-2"))

        assertThat(store.watchSongIds.value).isEmpty()
    }

    // --- Playlist batch transfers ---

    @Test
    fun `markBatchStarted publishes the initial aggregate state`() {
        store.markBatchStarted("b1", "playlist-1", "Running mix", totalSongCount = 20)

        val batch = store.batchTransfers.value["b1"]
        assertThat(batch?.playlistName).isEqualTo("Running mix")
        assertThat(batch?.totalSongCount).isEqualTo(20)
        assertThat(batch?.completedSongCount).isEqualTo(0)
        assertThat(batch?.failedSongCount).isEqualTo(0)
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_TRANSFERRING)
    }

    @Test
    fun `song lifecycle updates activeRequestId, progress and completed count`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 2)

        store.markBatchSongStarted("b1", activeRequestId = "r1", songTitle = "Track 1")
        var batch = store.batchTransfers.value["b1"]
        assertThat(batch?.activeRequestId).isEqualTo("r1")
        assertThat(batch?.currentSongTitle).isEqualTo("Track 1")

        store.markBatchSongProgress("b1", WearTransferProgress.STATUS_TRANSFERRING, progress = 0.6f)
        batch = store.batchTransfers.value["b1"]
        assertThat(batch?.currentSongProgress).isEqualTo(0.6f)

        store.markBatchSongCompleted("b1")
        batch = store.batchTransfers.value["b1"]
        assertThat(batch?.completedSongCount).isEqualTo(1)
        assertThat(batch?.activeRequestId).isNull()
        assertThat(batch?.currentSongProgress).isEqualTo(0f)
    }

    @Test
    fun `markBatchSongProgress clamps out-of-range values`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 1)

        store.markBatchSongProgress("b1", WearTransferProgress.STATUS_TRANSFERRING, progress = 1.5f)
        assertThat(store.batchTransfers.value["b1"]?.currentSongProgress).isEqualTo(1f)

        store.markBatchSongProgress("b1", WearTransferProgress.STATUS_TRANSFERRING, progress = -0.5f)
        assertThat(store.batchTransfers.value["b1"]?.currentSongProgress).isEqualTo(0f)
    }

    @Test
    fun `markBatchSongFailed increments the failure count and records the reason`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 1)

        store.markBatchSongFailed("b1", errorMessage = WearTransferProgress.ERROR_CODE_TIMED_OUT)

        val batch = store.batchTransfers.value["b1"]
        assertThat(batch?.failedSongCount).isEqualTo(1)
        assertThat(batch?.errorMessage).isEqualTo(WearTransferProgress.ERROR_CODE_TIMED_OUT)
    }

    @Test
    fun `markBatchSongFailed without a new reason keeps the previous one`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 2)
        store.markBatchSongFailed("b1", errorMessage = "first failure")

        store.markBatchSongFailed("b1", errorMessage = null)

        assertThat(store.batchTransfers.value["b1"]?.errorMessage).isEqualTo("first failure")
        assertThat(store.batchTransfers.value["b1"]?.failedSongCount).isEqualTo(2)
    }

    @Test
    fun `markBatchCompleted sets the terminal status and clears the active song`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 1)
        store.markBatchSongStarted("b1", "r1", "Track")

        store.markBatchCompleted("b1")

        val batch = store.batchTransfers.value["b1"]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
        assertThat(batch?.activeRequestId).isNull()
    }

    @Test
    fun `markBatchFailed records the error message and sets the terminal status`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 1)

        store.markBatchFailed("b1", "No reachable watch with PixelPlay")

        val batch = store.batchTransfers.value["b1"]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_FAILED)
        assertThat(batch?.errorMessage).isEqualTo("No reachable watch with PixelPlay")
    }

    @Test
    fun `markBatchCancelled sets the cancelled status and keeps the song counts so far`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 3)
        store.markBatchSongCompleted("b1")

        store.markBatchCancelled("b1")

        val batch = store.batchTransfers.value["b1"]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_CANCELLED)
        assertThat(batch?.completedSongCount).isEqualTo(1)
    }

    @Test
    fun `updates for an unknown batchId are ignored rather than creating a partial entry`() {
        store.markBatchSongCompleted("never-started")
        store.markBatchSongProgress("never-started", WearTransferProgress.STATUS_TRANSFERRING, 0.5f)
        store.markBatchCompleted("never-started")

        assertThat(store.batchTransfers.value).isEmpty()
    }

    @Test
    fun `processedSongCount sums completed and failed songs`() {
        store.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 5)
        store.markBatchSongCompleted("b1")
        store.markBatchSongCompleted("b1")
        store.markBatchSongFailed("b1")

        assertThat(store.batchTransfers.value["b1"]?.processedSongCount).isEqualTo(3)
    }

    @Test
    fun `two concurrent batches keep independent state`() {
        store.markBatchStarted("b1", "p1", "Playlist 1", totalSongCount = 2)
        store.markBatchStarted("b2", "p2", "Playlist 2", totalSongCount = 5)

        store.markBatchSongCompleted("b1")

        assertThat(store.batchTransfers.value["b1"]?.completedSongCount).isEqualTo(1)
        assertThat(store.batchTransfers.value["b2"]?.completedSongCount).isEqualTo(0)
    }

    // --- isAnyWatchPaired: distinct from reachableWatchNodeIds (paired vs. reachable now) ---

    @Test
    fun `isAnyWatchPaired defaults to false`() {
        assertThat(store.isAnyWatchPaired.value).isFalse()
    }

    @Test
    fun `setAnyWatchPaired true flips the flag`() {
        store.setAnyWatchPaired(true)

        assertThat(store.isAnyWatchPaired.value).isTrue()
    }

    @Test
    fun `setAnyWatchPaired is independent of reachableWatchNodeIds`() {
        store.setAnyWatchPaired(true)
        store.retainReachableWatchNodes(emptySet())

        // A paired watch that's simply out of range right now shouldn't un-pair itself.
        assertThat(store.isAnyWatchPaired.value).isTrue()
        assertThat(store.reachableWatchNodeIds.value).isEmpty()
    }
}
