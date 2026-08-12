package com.theveloper.pixelplay.data

import android.app.Application
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.data.local.LocalPlaylistSongCrossRef
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearPlaylistSyncAck
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
 * Covers [WearTransferRepository.onPlaylistSyncReceived] only — everything else on the repository
 * (song-by-song ChannelClient transfer, artwork, watchdogs) is exercised on-device, not here.
 *
 * [WearLocalPlayerRepository] and [WearPlaybackController] are constructed for real rather than
 * mocked: both are final Kotlin classes (no `open`), so MockK could only fake them via its
 * inline-mocking Java agent — which hangs indefinitely under this sandbox (see
 * `PlaylistWatchTransferCoordinatorTest` in `:app` for the same constraint on the GMS side).
 * Real construction needs no agent and is safe here because `onPlaylistSyncReceived` never calls
 * either collaborator; [MainCoroutineExtension] supplies the `Dispatchers.Main` both of their
 * `init` blocks need to launch on.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WearTransferRepositoryPlaylistSyncTest {

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

    @BeforeEach
    fun setUp() {
        every { localSongDao.getAllSongs() } returns flowOf(emptyList())
        coEvery { localPlaylistDao.upsertPlaylist(any(), any()) } just Runs

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
    fun `first sync sets createdAt equal to updatedAt`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val entitySlot = slot<LocalPlaylistEntity>()
        coEvery { localPlaylistDao.upsertPlaylist(capture(entitySlot), any()) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1")),
            sourceNodeId = "node-1",
        )

        assertThat(entitySlot.captured.createdAt).isEqualTo(entitySlot.captured.updatedAt)
    }

    @Test
    fun `re-sync preserves original createdAt but bumps updatedAt`() = runTest {
        val originalCreatedAt = 1_000L
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns LocalPlaylistEntity(
            playlistId = "p1",
            name = "Road trip",
            createdAt = originalCreatedAt,
            updatedAt = originalCreatedAt,
        )
        val entitySlot = slot<LocalPlaylistEntity>()
        coEvery { localPlaylistDao.upsertPlaylist(capture(entitySlot), any()) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1", "s2")),
            sourceNodeId = "node-1",
        )

        assertThat(entitySlot.captured.createdAt).isEqualTo(originalCreatedAt)
        assertThat(entitySlot.captured.updatedAt).isGreaterThan(originalCreatedAt)
    }

    @Test
    fun `re-sync with a different song set replaces membership, not merges it`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()
        coEvery { localPlaylistDao.upsertPlaylist(any(), capture(crossRefsSlot)) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("a", "b")),
            sourceNodeId = "node-1",
        )
        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("c")),
            sourceNodeId = "node-1",
        )

        // The repository always regenerates the full cross-ref list from the incoming sync's
        // songIds alone — it never reads current membership back in — so the last call's payload
        // is exactly the new set, with no trace of the songs from the first call.
        assertThat(crossRefsSlot.captured).containsExactly(
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "c", position = 0),
        )
    }

    @Test
    fun `cross-refs preserve songId order and position from the sync payload`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()
        coEvery { localPlaylistDao.upsertPlaylist(any(), capture(crossRefsSlot)) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s3", "s1", "s2")),
            sourceNodeId = "node-1",
        )

        assertThat(crossRefsSlot.captured).containsExactly(
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s3", position = 0),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 1),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s2", position = 2),
        ).inOrder()
    }

    @Test
    fun `empty song list still upserts an empty cross-ref list, not a no-op`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Empty playlist", songIds = emptyList()),
            sourceNodeId = "node-1",
        )

        coVerify(exactly = 1) { localPlaylistDao.upsertPlaylist(any(), emptyList()) }
    }

    @Test
    fun `entity carries the synced name and playlistId through`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val entitySlot = slot<LocalPlaylistEntity>()
        coEvery { localPlaylistDao.upsertPlaylist(capture(entitySlot), any()) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Summer mix", songIds = listOf("s1")),
            sourceNodeId = "node-1",
        )

        assertThat(entitySlot.captured.playlistId).isEqualTo("p1")
        assertThat(entitySlot.captured.name).isEqualTo("Summer mix")
    }

    @Test
    fun `cross-refs carry the matching pending title from the sync, by index`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()
        coEvery { localPlaylistDao.upsertPlaylist(any(), capture(crossRefsSlot)) } just Runs

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(
                playlistId = "p1",
                name = "Road trip",
                songIds = listOf("s1", "s2"),
                songTitles = listOf("First song", "Second song"),
            ),
            sourceNodeId = "node-1",
        )

        assertThat(crossRefsSlot.captured).containsExactly(
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s1", position = 0, pendingTitle = "First song"),
            LocalPlaylistSongCrossRef(playlistId = "p1", songId = "s2", position = 1, pendingTitle = "Second song"),
        ).inOrder()
    }

    @Test
    fun `a sync from an older phone with no songTitles falls back to an empty pending title`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()
        coEvery { localPlaylistDao.upsertPlaylist(any(), capture(crossRefsSlot)) } just Runs

        // songTitles omitted entirely — WearPlaylistSync.songTitles defaults to emptyList().
        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1")),
            sourceNodeId = "node-1",
        )

        assertThat(crossRefsSlot.captured.single().pendingTitle).isEmpty()
    }

    // --- Ack (playlist-sync reliability fix) ---

    @Test
    fun `a sync with a requestId acks back to the source node once applied`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val pathSlot = slot<String>()
        val bytesSlot = slot<ByteArray>()
        every { messageClient.sendMessage("node-9", capture(pathSlot), capture(bytesSlot)) } returns
            Tasks.forResult(0)

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1"), requestId = "req-1"),
            sourceNodeId = "node-9",
        )

        assertThat(pathSlot.captured).isEqualTo(WearDataPaths.PLAYLIST_SYNC_ACK)
        val ack = Json.decodeFromString<WearPlaylistSyncAck>(String(bytesSlot.captured, Charsets.UTF_8))
        assertThat(ack.playlistId).isEqualTo("p1")
        assertThat(ack.requestId).isEqualTo("req-1")
    }

    @Test
    fun `a sync with no requestId (old phone build) sends no ack`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null

        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1")),
            sourceNodeId = "node-9",
        )

        verify(exactly = 0) { messageClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `a failure sending the ack does not propagate out of onPlaylistSyncReceived`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        every { messageClient.sendMessage(any(), any(), any()) } returns
            Tasks.forException(RuntimeException("no route to node"))

        // Should not throw — a lost ack just means the phone times out and resends the sync.
        repository.onPlaylistSyncReceived(
            WearPlaylistSync(playlistId = "p1", name = "Road trip", songIds = listOf("s1"), requestId = "req-1"),
            sourceNodeId = "node-9",
        )
    }
}
