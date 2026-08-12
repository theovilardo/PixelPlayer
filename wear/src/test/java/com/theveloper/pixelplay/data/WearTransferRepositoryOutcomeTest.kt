package com.theveloper.pixelplay.data

import android.app.Application
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearTransferMetadata
import com.theveloper.pixelplay.shared.WearTransferProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Covers [WearTransferRepository.onMetadataReceived]'s ack to the phone and
 * [WearTransferRepository.dismissTransfer] — the phone-reliability fix (the watch reporting its
 * real outcome instead of the phone trusting its own send-completion) and the "can't clear a
 * failed transfer chip" UX fix, both from the same session.
 *
 * The channel-streaming success/failure paths (`onAudioChannelOpened` and everything downstream)
 * aren't covered here: they run on the repository's own background `scope`, not on the test
 * dispatcher, so asserting on them deterministically needs the on-device coverage this class
 * already leans on for that surface (see [WearTransferRepositoryPlaylistSyncTest]'s doc comment).
 * The "already on watch" case below uses the phone-rejected-with-`error` path rather than the
 * existing-local-file check for the same reason: the latter needs a real file on disk.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WearTransferRepositoryOutcomeTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainCoroutineExtension = MainCoroutineExtension()
    }

    private val application = mockk<Application>(relaxed = true)
    private val localSongDao = mockk<LocalSongDao>()
    private val localPlaylistDao = mockk<LocalPlaylistDao>()
    private val channelClient = mockk<ChannelClient>()
    private val messageClient = mockk<MessageClient>()
    private val nodeClient = mockk<NodeClient>()

    private lateinit var repository: WearTransferRepository

    private fun metadata(requestId: String, songId: String, error: String? = null) = WearTransferMetadata(
        requestId = requestId,
        songId = songId,
        title = "Title",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 1000L,
        mimeType = "audio/mpeg",
        fileSize = 4096L,
        bitrate = 128_000,
        sampleRate = 44_100,
        isFavorite = false,
        error = error,
    )

    @BeforeEach
    fun setUp() {
        every { localSongDao.getAllSongs() } returns flowOf(emptyList())
        coEvery { localSongDao.getSongById(any()) } returns null

        val stateRepository = WearStateRepository()
        val performanceSettingsRepository = mockk<WearPerformanceSettingsRepository> {
            every { showAlbumArt } returns MutableStateFlow(true)
            every { dynamicColorTheming } returns MutableStateFlow(true)
            every { playButtonAnimation } returns MutableStateFlow(true)
        }
        val localPlayerRepository = WearLocalPlayerRepository(
            application,
            localSongDao,
            mockk<WearPlaybackStatePersistence>(),
            performanceSettingsRepository,
        )
        val playbackController = WearPlaybackController(application, stateRepository)

        repository = WearTransferRepository(
            application = application,
            localSongDao = localSongDao,
            localPlaylistDao = localPlaylistDao,
            channelClient = channelClient,
            messageClient = messageClient,
            nodeClient = nodeClient,
            localPlayerRepository = localPlayerRepository,
            stateRepository = stateRepository,
            playbackController = playbackController,
        )
    }

    @Test
    fun `metadata received acks back to the source node before the audio channel opens`() = runTest {
        val pathSlot = slot<String>()
        val bytesSlot = slot<ByteArray>()
        every { messageClient.sendMessage("node-9", capture(pathSlot), capture(bytesSlot)) } returns
            Tasks.forResult(0)

        repository.onMetadataReceived(metadata(requestId = "req-1", songId = "song-1"), sourceNodeId = "node-9")

        assertThat(pathSlot.captured).isEqualTo(WearDataPaths.TRANSFER_PROGRESS)
        val progress = Json.decodeFromString<WearTransferProgress>(String(bytesSlot.captured, Charsets.UTF_8))
        assertThat(progress.requestId).isEqualTo("req-1")
        assertThat(progress.songId).isEqualTo("song-1")
        assertThat(progress.status).isEqualTo(WearTransferProgress.STATUS_METADATA_RECEIVED)
    }

    @Test
    fun `metadata received with no sourceNodeId sends no ack`() = runTest {
        repository.onMetadataReceived(metadata(requestId = "req-1", songId = "song-1"), sourceNodeId = null)

        verify(exactly = 0) { messageClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `phone-side rejection reports STATUS_FAILED with the phone's error to the phone`() = runTest {
        val rejected = metadata(
            requestId = "req-2",
            songId = "song-2",
            error = "Song must be downloaded locally on phone before saving to watch",
        )
        val pathSlot = slot<String>()
        val bytesSlot = slot<ByteArray>()
        every { messageClient.sendMessage("node-9", capture(pathSlot), capture(bytesSlot)) } returns
            Tasks.forResult(0)

        repository.onMetadataReceived(rejected, sourceNodeId = "node-9")

        // The errorMsg != null branch returns before the metadata-received ack point, so this
        // is the only message sent for this requestId.
        assertThat(pathSlot.captured).isEqualTo(WearDataPaths.TRANSFER_PROGRESS)
        val progress = Json.decodeFromString<WearTransferProgress>(String(bytesSlot.captured, Charsets.UTF_8))
        assertThat(progress.status).isEqualTo(WearTransferProgress.STATUS_FAILED)
        assertThat(progress.error).isEqualTo(rejected.error)
    }

    @Test
    fun `dismissTransfer removes a failed entry`() = runTest {
        every { messageClient.sendMessage(any(), any(), any()) } returns Tasks.forResult(0)

        // First call seeds an active-transfer entry (the error branch below never creates one on
        // its own — there's nothing to flip from null to FAILED without a prior entry).
        repository.onMetadataReceived(metadata(requestId = "req-3", songId = "song-3"), sourceNodeId = "node-9")
        repository.onMetadataReceived(
            metadata(
                requestId = "req-3",
                songId = "song-3",
                error = "Song must be downloaded locally on phone before saving to watch",
            ),
            sourceNodeId = "node-9",
        )
        assertThat(repository.activeTransfers.value["req-3"]?.status).isEqualTo(WearTransferProgress.STATUS_FAILED)

        repository.dismissTransfer("req-3")

        assertThat(repository.activeTransfers.value).doesNotContainKey("req-3")
    }

    @Test
    fun `dismissTransfer is a no-op for a transfer that is not in a terminal state`() = runTest {
        every { messageClient.sendMessage(any(), any(), any()) } returns Tasks.forResult(0)

        repository.onMetadataReceived(metadata(requestId = "req-4", songId = "song-4"), sourceNodeId = "node-9")
        assertThat(repository.activeTransfers.value["req-4"]?.status).isEqualTo(WearTransferProgress.STATUS_TRANSFERRING)

        repository.dismissTransfer("req-4")

        assertThat(repository.activeTransfers.value).containsKey("req-4")
    }
}
