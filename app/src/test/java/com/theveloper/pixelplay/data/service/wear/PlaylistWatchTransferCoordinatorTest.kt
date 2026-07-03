package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import android.content.Context
import app.cash.turbine.test
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.shared.WearTransferProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    @BeforeEach
    fun mockWearable() {
        mockkStatic(Wearable::class)
    }

    @AfterEach
    fun unmockWearable() {
        unmockkStatic(Wearable::class)
    }

    private fun stubNoReachableWatches() {
        val capabilityClient = mockk<CapabilityClient>()
        val capabilityInfo = mockk<CapabilityInfo> { every { nodes } returns emptySet() }
        every { capabilityClient.getCapability(any(), any()) } returns Tasks.forResult(capabilityInfo)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capabilityClient
        every { Wearable.getMessageClient(any<Context>()) } returns mockk<MessageClient>(relaxed = true)
    }

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
}
