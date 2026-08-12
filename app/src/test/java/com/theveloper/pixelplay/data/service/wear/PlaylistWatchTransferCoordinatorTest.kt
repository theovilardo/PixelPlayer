package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearPlaylistSyncAck
import com.theveloper.pixelplay.shared.WearTransferProgress
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * capabilityClient/messageClient are constructor-injected into the coordinator (unlike most of
 * the wear/ package, which resolves them via `Wearable.getXClient(application)` internally) so
 * they can be faked here directly — both are non-final abstract GMS classes, so MockK subclasses
 * them with no inline-mocking agent involved. Mocking `Wearable`'s static factory methods instead
 * would need that agent, which hangs indefinitely under this environment's sandboxing.
 */
class PlaylistWatchTransferCoordinatorTest {

    private val application = mockk<Application>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val musicRepository = mockk<MusicRepository>()
    private val watchAudioTranscoder = mockk<WatchAudioTranscoder>()
    private val directTransferCoordinator = mockk<PhoneDirectWatchTransferCoordinator>(relaxed = true)
    private val wearPhoneTransferSender = mockk<WearPhoneTransferSender>(relaxed = true)
    private val transferStateStore = PhoneWatchTransferStateStore()
    private val capabilityClient = mockk<CapabilityClient>()
    private val messageClient = mockk<MessageClient>()

    private val transferredSongIdsInOrder = mutableListOf<String>()
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var batchPersistence: PlaylistBatchTransferPersistence

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("playlist-watch-transfer-coordinator-test")
        // Default: no song needs transcoding. transcodeIfNeeded is what the coordinator actually
        // calls — requiresTranscoding lives inside it and is never invoked directly by the
        // coordinator, so stubbing that instead would silently test nothing.
        coEvery { watchAudioTranscoder.transcodeIfNeeded(any(), any(), any()) } returns
            WatchAudioTranscoder.TranscodeResult.Passthrough
        every { watchAudioTranscoder.cleanup(any()) } just Runs

        // Tasks.forResult builds a real, already-completed Task — play-services-tasks has no
        // Android framework dependency for this, so it resolves correctly off-device. Playlist
        // syncs additionally auto-ack (simulating a healthy watch) so every existing test here
        // keeps its original one-send-per-node behavior; tests that care about the ack-timeout/
        // retry path override this locally.
        every { messageClient.sendMessage(any(), any(), any()) } answers {
            autoAckIfPlaylistSync(thirdArg())
            Tasks.forResult(0)
        }

        every { musicRepository.getSongsByIds(any()) } answers {
            val requestedIds = firstArg<List<String>>()
            flowOf(requestedIds.mapNotNull { id -> songsById[id] })
        }
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private val songsById = mutableMapOf<String, Song>()

    private fun song(id: String, title: String = "Song $id"): Song {
        val song = Song.emptySong().copy(id = id, title = title)
        songsById[id] = song
        return song
    }

    /** Decodes [bytes] as a [WearPlaylistSync] and, if it carries a requestId, immediately acks it. */
    private fun autoAckIfPlaylistSync(bytes: ByteArray) {
        val sync = runCatching {
            json.decodeFromString<WearPlaylistSync>(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return
        if (sync.requestId.isEmpty()) return
        transferStateStore.onPlaylistSyncAckReceived(
            WearPlaylistSyncAck(playlistId = sync.playlistId, requestId = sync.requestId)
        )
    }

    private fun stubReachableNodes(vararg nodeIds: String) {
        val nodes = nodeIds.map { nodeId -> mockk<Node> { every { id } returns nodeId } }.toSet()
        val capabilityInfo = mockk<CapabilityInfo> { every { this@mockk.nodes } returns nodes }
        every { capabilityClient.getCapability(any(), any()) } returns Tasks.forResult(capabilityInfo)
    }

    /** Every startTransferToWatch call resolves to [status] as soon as it's invoked. */
    private fun stubTransfersResolveTo(status: String) {
        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(),
                requestId = any(),
                songId = any(),
                transferMode = any(),
                startPositionMs = any(),
                autoPlay = any(),
                audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            transferredSongIdsInOrder += songId
            transferStateStore.markProgress(
                requestId = requestId,
                songId = songId,
                bytesTransferred = 100L,
                totalBytes = 100L,
                status = status,
            )
        }
    }

    private fun buildCoordinator(scope: kotlinx.coroutines.CoroutineScope): PlaylistWatchTransferCoordinator {
        batchPersistence = PlaylistBatchTransferPersistence(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
            ),
        )
        return PlaylistWatchTransferCoordinator(
            application = application,
            musicRepository = musicRepository,
            watchAudioTranscoder = watchAudioTranscoder,
            directTransferCoordinator = directTransferCoordinator,
            wearPhoneTransferSender = wearPhoneTransferSender,
            transferStateStore = transferStateStore,
            batchPersistence = batchPersistence,
            capabilityClient = capabilityClient,
            messageClient = messageClient,
            scope = scope,
        )
    }

    @Test
    fun `an empty playlist does not start a batch`() = runTest {
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Empty", emptyList())
        advanceUntilIdle()

        assertThat(transferStateStore.batchTransfers.value).isEmpty()
        verify(exactly = 0) { directTransferCoordinator.startTransferToWatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fails the batch when no watch is reachable`() = runTest {
        stubReachableNodes()
        song("s1")
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_FAILED)
    }

    @Test
    fun `transfers songs in playlist order`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s3"); song("s1"); song("s2")
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s3", "s1", "s2"))
        advanceUntilIdle()

        assertThat(transferredSongIdsInOrder).containsExactly("s3", "s1", "s2").inOrder()
    }

    @Test
    fun `the playlist sync sent to the watch carries song titles in the same order as ids`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s3", title = "Third"); song("s1", title = "First"); song("s2", title = "Second")
        val syncPayloads = mutableListOf<WearPlaylistSync>()
        every { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) } answers {
            val bytes = thirdArg<ByteArray>()
            syncPayloads += json.decodeFromString<WearPlaylistSync>(String(bytes, Charsets.UTF_8))
            autoAckIfPlaylistSync(bytes)
            Tasks.forResult(0)
        }
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s3", "s1", "s2"))
        advanceUntilIdle()

        assertThat(syncPayloads).hasSize(1)
        assertThat(syncPayloads.single().songIds).containsExactly("s3", "s1", "s2").inOrder()
        assertThat(syncPayloads.single().songTitles).containsExactly("Third", "First", "Second").inOrder()
    }

    @Test
    fun `songs already saved on every reachable watch are not re-transferred`() = runTest {
        stubReachableNodes("node-1")
        transferStateStore.retainReachableWatchNodes(setOf("node-1"))
        transferStateStore.markSongPresentOnWatch("node-1", "already-there")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("already-there"); song("pending")
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("already-there", "pending"))
        advanceUntilIdle()

        assertThat(transferredSongIdsInOrder).containsExactly("pending")
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.completedSongCount).isEqualTo(2)
    }

    @Test
    fun `one song failing does not abort the rest of the batch`() = runTest {
        stubReachableNodes("node-1")
        song("s1"); song("s2"); song("s3")
        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            transferredSongIdsInOrder += songId
            // s2 fails on every attempt, including its retry (see the dedicated retry tests
            // below) — this test is only about the batch surviving a song that never recovers.
            val status = if (songId == "s2") WearTransferProgress.STATUS_FAILED else WearTransferProgress.STATUS_COMPLETED
            transferStateStore.markProgress(requestId, songId, 0L, 0L, status)
        }
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1", "s2", "s3"))
        advanceUntilIdle()

        // s2 appears twice: the first attempt and its retry.
        assertThat(transferredSongIdsInOrder).containsExactly("s1", "s2", "s2", "s3").inOrder()
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.completedSongCount).isEqualTo(2)
        assertThat(batch?.failedSongCount).isEqualTo(1)
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
    }

    @Test
    fun `a song that fails once but succeeds on retry counts as completed`() = runTest {
        stubReachableNodes("node-1")
        song("s1")
        var attempt = 0
        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            attempt += 1
            val status = if (attempt == 1) WearTransferProgress.STATUS_FAILED else WearTransferProgress.STATUS_COMPLETED
            transferStateStore.markProgress(requestId, songId, 0L, 0L, status)
        }
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(attempt).isEqualTo(2)
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.completedSongCount).isEqualTo(1)
        assertThat(batch?.failedSongCount).isEqualTo(0)
    }

    @Test
    fun `a song failing twice in a row is only retried once, not indefinitely`() = runTest {
        stubReachableNodes("node-1")
        song("s1")
        var attempts = 0
        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            attempts += 1
            transferStateStore.markProgress(requestId, songId, 0L, 0L, WearTransferProgress.STATUS_FAILED)
        }
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(attempts).isEqualTo(2)
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.failedSongCount).isEqualTo(1)
        assertThat(batch?.completedSongCount).isEqualTo(0)
    }

    @Test
    fun `cancelling during the backoff window skips the retry`() = runTest {
        stubReachableNodes("node-1")
        song("s1")
        val coordinator = buildCoordinator(this)
        lateinit var batchId: String

        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            transferredSongIdsInOrder += songId
            coordinator.cancelPlaylistTransfer(batchId)
            transferStateStore.markProgress(requestId, songId, 0L, 0L, WearTransferProgress.STATUS_FAILED)
        }

        batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(transferredSongIdsInOrder).containsExactly("s1")
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_CANCELLED)
    }

    @Test
    fun `cancelling a batch stops remaining songs from being transferred`() = runTest {
        stubReachableNodes("node-1")
        song("s1"); song("s2"); song("s3")
        val coordinator = buildCoordinator(this)
        lateinit var batchId: String

        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            transferredSongIdsInOrder += songId
            if (songId == "s1") {
                // Cancel mid-batch, right after the first song starts, before it resolves.
                coordinator.cancelPlaylistTransfer(batchId)
            }
            transferStateStore.markProgress(requestId, songId, 0L, 0L, WearTransferProgress.STATUS_COMPLETED)
        }

        batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1", "s2", "s3"))
        advanceUntilIdle()

        assertThat(transferredSongIdsInOrder).containsExactly("s1")
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_CANCELLED)
    }

    @Test
    fun `a song whose transfer never reaches a terminal state is failed as timed out`() = runTest {
        stubReachableNodes("node-1")
        song("s1")
        // directTransferCoordinator is a relaxed mock here — startTransferToWatch is a no-op and
        // never pushes a terminal state into transferStateStore, simulating a watch that never
        // acknowledges the transfer.
        val coordinator = buildCoordinator(this)
        coordinator.songTransferAwaitTimeoutMs = 50L

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.failedSongCount).isEqualTo(1)
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
    }

    @Test
    fun `sends the song to every reachable node, counting it as one completed song`() = runTest {
        stubReachableNodes("node-1", "node-2")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        verify(exactly = 2) {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        }
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.completedSongCount).isEqualTo(1)
    }

    @Test
    fun `a song missing from the library is counted as failed, not silently dropped`() = runTest {
        stubReachableNodes("node-1")
        // "missing" is never registered via song(), so musicRepository.getSongsByIds returns nothing for it.
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("missing"))
        advanceUntilIdle()

        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.failedSongCount).isEqualTo(1)
        assertThat(batch?.completedSongCount).isEqualTo(0)
    }

    // --- Playlist sync reliability: ack + retry ---

    @Test
    fun `a playlist sync acked on the first attempt is sent only once`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        verify(exactly = 1) { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) }
    }

    @Test
    fun `a playlist sync that's never acked is retried once, then given up on`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        // Overrides the auto-acking default stub — this node never acks, simulating the watch
        // being mid-reconnect when both attempts go out.
        every { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) } returns Tasks.forResult(0)
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        // One initial attempt plus exactly one retry — not retried indefinitely.
        verify(exactly = 2) { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) }
    }

    @Test
    fun `a playlist sync acked only on the retry stops after that retry`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        var attempt = 0
        every { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) } answers {
            attempt += 1
            val bytes = thirdArg<ByteArray>()
            if (attempt >= 2) autoAckIfPlaylistSync(bytes)
            Tasks.forResult(0)
        }
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(attempt).isEqualTo(2)
    }

    @Test
    fun `songs still transfer even when the playlist sync is never acked`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        every { messageClient.sendMessage(any(), WearDataPaths.PLAYLIST_SYNC, any()) } returns Tasks.forResult(0)
        val coordinator = buildCoordinator(this)

        val batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        // An unconfirmed playlist sync is a warning, not a batch failure — the song itself still
        // lands on the watch, it just might not show up under the playlist until the next sync.
        assertThat(transferredSongIdsInOrder).containsExactly("s1")
        val batch = transferStateStore.batchTransfers.value[batchId]
        assertThat(batch?.status).isEqualTo(WearTransferProgress.STATUS_COMPLETED)
        assertThat(batch?.completedSongCount).isEqualTo(1)
    }

    // --- Persistence: resuming a batch interrupted by process death (PR7) ---

    @Test
    fun `a completed batch clears its persisted intent`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1")
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(batchPersistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `a batch that fails with no reachable watch clears its persisted intent`() = runTest {
        stubReachableNodes()
        song("s1")
        val coordinator = buildCoordinator(this)

        coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1"))
        advanceUntilIdle()

        assertThat(batchPersistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `cancelling a batch clears its persisted intent`() = runTest {
        stubReachableNodes("node-1")
        song("s1"); song("s2")
        val coordinator = buildCoordinator(this)
        lateinit var batchId: String

        every {
            directTransferCoordinator.startTransferToWatch(
                nodeId = any(), requestId = any(), songId = any(),
                transferMode = any(), startPositionMs = any(), autoPlay = any(), audioOverride = any(),
            )
        } answers {
            val requestId = secondArg<String>()
            val songId = thirdArg<String>()
            coordinator.cancelPlaylistTransfer(batchId)
            transferStateStore.markProgress(requestId, songId, 0L, 0L, WearTransferProgress.STATUS_COMPLETED)
        }

        batchId = coordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1", "s2"))
        advanceUntilIdle()

        assertThat(batchPersistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `resuming with nothing persisted does not start a transfer`() = runTest {
        val coordinator = buildCoordinator(this)

        coordinator.resumePersistedBatchIfNeeded()
        advanceUntilIdle()

        assertThat(transferStateStore.batchTransfers.value).isEmpty()
        verify(exactly = 0) { directTransferCoordinator.startTransferToWatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resuming a persisted intent re-runs the transfer for the same playlist and songs`() = runTest {
        stubReachableNodes("node-1")
        stubTransfersResolveTo(WearTransferProgress.STATUS_COMPLETED)
        song("s1"); song("s2")
        val coordinator = buildCoordinator(this)
        batchPersistence.saveInFlightBatch(
            PersistedPlaylistBatchIntent(
                batchId = "orphaned-batch",
                playlistId = "p1",
                playlistName = "Playlist",
                songIds = listOf("s1", "s2"),
                requestedAtMillis = 0L,
            )
        )

        coordinator.resumePersistedBatchIfNeeded()
        advanceUntilIdle()

        assertThat(transferredSongIdsInOrder).containsExactly("s1", "s2").inOrder()
        coVerify { wearPhoneTransferSender.refreshWatchLibraryState() }
    }
}
