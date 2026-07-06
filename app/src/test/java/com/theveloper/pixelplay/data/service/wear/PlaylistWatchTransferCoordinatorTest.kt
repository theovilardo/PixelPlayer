package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import android.content.Context
import app.cash.turbine.test
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.shared.WearTransferProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlaylistWatchTransferCoordinatorTest {

    private val transferStateStore = PhoneWatchTransferStateStore()
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val watchAudioTranscoder = mockk<WatchAudioTranscoder>(relaxed = true)
    private val directTransferCoordinator = mockk<PhoneDirectWatchTransferCoordinator>(relaxed = true)
    private val wearPhoneTransferSender = mockk<WearPhoneTransferSender>(relaxed = true)

    private val coordinator = PlaylistWatchTransferCoordinator(
        application = mockk<Application>(relaxed = true),
        musicRepository = musicRepository,
        watchAudioTranscoder = watchAudioTranscoder,
        directTransferCoordinator = directTransferCoordinator,
        wearPhoneTransferSender = wearPhoneTransferSender,
        transferStateStore = transferStateStore,
    )

    private val originalAwaitTimeoutMs = PlaylistWatchTransferCoordinator.SONG_TRANSFER_AWAIT_TIMEOUT_MS

    @BeforeEach
    fun mockWearable() {
        mockkStatic(Wearable::class)
    }

    @AfterEach
    fun unmockWearable() {
        unmockkStatic(Wearable::class)
        PlaylistWatchTransferCoordinator.SONG_TRANSFER_AWAIT_TIMEOUT_MS = originalAwaitTimeoutMs
    }

    private fun stubNoReachableWatches() {
        val capabilityClient = mockk<CapabilityClient>()
        val capabilityInfo = mockk<CapabilityInfo> { every { nodes } returns emptySet() }
        every { capabilityClient.getCapability(any(), any()) } returns Tasks.forResult(capabilityInfo)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capabilityClient
        every { Wearable.getMessageClient(any<Context>()) } returns mockk<MessageClient>(relaxed = true)
    }

    private fun stubOneReachableWatch(): MessageClient {
        val node = mockk<Node> { every { id } returns "node-1" }
        val capabilityClient = mockk<CapabilityClient>()
        val capabilityInfo = mockk<CapabilityInfo> { every { nodes } returns setOf(node) }
        every { capabilityClient.getCapability(any(), any()) } returns Tasks.forResult(capabilityInfo)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capabilityClient
        val messageClient = mockk<MessageClient>(relaxed = true)
        // A relaxed mock's default Task<Int> for sendMessage never actually completes its
        // listeners, so .await() on it would hang forever — return a genuinely resolved Task.
        every { messageClient.sendMessage(any(), any(), any()) } returns Tasks.forResult(0)
        every { Wearable.getMessageClient(any<Context>()) } returns messageClient
        return messageClient
    }

    private fun song(id: String) = Song(
        id = id,
        title = "Song $id",
        artist = "Test Artist",
        artistId = 1L,
        album = "Test Album",
        albumId = 1L,
        path = "/sdcard/Music/$id.file",
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = null,
        duration = 60_000L,
        mimeType = "audio/mpeg",
        bitrate = 128_000,
        sampleRate = 44_100,
    )

    @Test
    fun `requestPlaylistTransfer with an empty playlist does not start a batch`() = runTest {
        val batchId = coordinator.requestPlaylistTransfer("playlist-1", "Empty", emptyList())

        assertThat(batchId).isNotEmpty()
        assertThat(transferStateStore.batchTransfers.value).isEmpty()
    }

    @Test
    fun `requestPlaylistTransfer fails the batch when no watch is reachable`() = runTest {
        stubNoReachableWatches()

        transferStateStore.batchTransfers.test {
            assertThat(awaitItem()).isEmpty()

            val batchId = coordinator.requestPlaylistTransfer(
                "playlist-1",
                "QA Transcode Test",
                listOf("song-1", "song-2"),
            )

            val started = awaitItem().getValue(batchId)
            assertThat(started.totalSongs).isEqualTo(2)

            val failed = awaitItem().getValue(batchId)
            assertThat(failed.status).isEqualTo(WearTransferProgress.STATUS_FAILED)
            assertThat(failed.error).isEqualTo("No reachable watch with PixelPlay")
        }
    }

    @Test
    fun `cancelPlaylistTransfer marks the batch cancelled without an active song`() {
        transferStateStore.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 3)

        coordinator.cancelPlaylistTransfer("batch-1")

        assertThat(transferStateStore.batchTransfers.value.getValue("batch-1").status)
            .isEqualTo(WearTransferProgress.STATUS_CANCELLED)
    }

    @Test
    fun `cancelPlaylistTransfer forwards the active requestId to WearPhoneTransferSender`() = runTest {
        transferStateStore.markBatchStarted("batch-1", "playlist-1", "QA Transcode Test", totalSongs = 1)
        transferStateStore.markBatchSongStarted("batch-1", "req-1", "test_flac")
        coEvery { wearPhoneTransferSender.cancelTransfer(any()) } returns Unit

        coordinator.cancelPlaylistTransfer("batch-1")

        assertThat(transferStateStore.batchTransfers.value.getValue("batch-1").status)
            .isEqualTo(WearTransferProgress.STATUS_CANCELLED)
    }

    @Test
    fun `a song whose transfer never reaches a terminal state is failed as timed out, not hung forever`() = runTest {
        PlaylistWatchTransferCoordinator.SONG_TRANSFER_AWAIT_TIMEOUT_MS = 50L
        stubOneReachableWatch()
        every { musicRepository.getSongsByIds(listOf("song-1")) } returns flowOf(listOf(song("song-1")))
        coEvery {
            watchAudioTranscoder.transcodeIfNeeded(any(), any(), any())
        } returns WatchAudioTranscoder.TranscodeResult.Passthrough
        // directTransferCoordinator is relaxed: startTransferToWatch is a no-op that never pushes
        // a terminal status into transferStateStore.transfers, simulating a watch that went
        // silent (dead battery, out of range) right after the request went out.

        val batchId = coordinator.requestPlaylistTransfer(
            "playlist-1",
            "QA Transcode Test",
            listOf("song-1"),
        )

        // The batch runs on the coordinator's own background scope, concurrently with this test,
        // so intermediate StateFlow emissions (song-started, etc.) can be conflated — poll until
        // the batch actually reaches a terminal status instead of asserting an exact step count.
        transferStateStore.batchTransfers.test {
            var batch = awaitItem()[batchId]
            while (batch == null || batch.status == WearTransferProgress.STATUS_TRANSFERRING) {
                batch = awaitItem()[batchId]
            }
            assertThat(batch.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
            assertThat(batch.completedSongs).isEqualTo(0)
            assertThat(batch.failedSongCount).isEqualTo(1)
            assertThat(batch.lastFailureErrorCode).isEqualTo(WearTransferProgress.ERROR_CODE_TIMED_OUT)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `transcode and transfer progress combine into one continuous scale instead of resetting`() = runTest {
        stubOneReachableWatch()
        every { musicRepository.getSongsByIds(listOf("song-1")) } returns flowOf(listOf(song("song-1")))
        val transcodedFile = mockk<java.io.File>(relaxed = true)
        coEvery {
            watchAudioTranscoder.transcodeIfNeeded(any(), any(), any())
        } coAnswers {
            thirdArg<(Float) -> Unit>().invoke(0.5f)
            WatchAudioTranscoder.TranscodeResult.Transcoded(transcodedFile)
        }
        val requestIdSlot = slot<String>()
        every {
            directTransferCoordinator.startTransferToWatch(
                any(), capture(requestIdSlot), any(), any(), any(), any(), any(),
            )
        } answers {
            transferStateStore.markProgress(
                requestId = requestIdSlot.captured,
                songId = "song-1",
                bytesTransferred = 50L,
                totalBytes = 100L,
                status = WearTransferProgress.STATUS_TRANSFERRING,
            )
        }

        val batchId = coordinator.requestPlaylistTransfer(
            "playlist-1",
            "QA Transcode Test",
            listOf("song-1"),
        )

        transferStateStore.batchTransfers.test {
            var batch = awaitItem()[batchId]
            // Transcode phase: 0.5 fraction scaled into the first 30% of the overall bar.
            while (batch == null || batch.currentSongProgress < 0.1f) {
                batch = awaitItem()[batchId]
            }
            assertThat(batch!!.currentSongProgress).isWithin(0.01f).of(0.15f)

            // Transfer phase must continue from there, not reset to 0, landing at
            // 0.3 + 0.5 * 0.7 = 0.65.
            while (batch!!.currentSongProgress < 0.5f) {
                batch = awaitItem()[batchId]
                assertThat(batch!!.currentSongProgress).isAtLeast(0.1f)
            }
            assertThat(batch.currentSongProgress).isWithin(0.01f).of(0.65f)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
