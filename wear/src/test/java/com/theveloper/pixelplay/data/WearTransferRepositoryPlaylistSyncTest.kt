package com.theveloper.pixelplay.data

import android.app.Application
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.data.local.LocalPlaylistSongCrossRef
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Covers [WearTransferRepository.onPlaylistSyncReceived] — the watch-side handler for a
 * whole-playlist sync from the phone. Doesn't need Room: [LocalPlaylistDao] is mocked, so this
 * runs as a plain JVM test; DAO upsert/ordering behavior itself is covered separately by the
 * instrumented LocalPlaylistDaoTest.
 */
class WearTransferRepositoryPlaylistSyncTest {

    private val localSongDao = mockk<LocalSongDao>(relaxed = true) {
        every { getAllSongs() } returns flowOf(emptyList())
    }
    private val localPlaylistDao = mockk<LocalPlaylistDao>(relaxed = true)

    private val repository = WearTransferRepository(
        application = mockk<Application>(relaxed = true),
        localSongDao = localSongDao,
        localPlaylistDao = localPlaylistDao,
        channelClient = mockk<ChannelClient>(relaxed = true),
        messageClient = mockk<MessageClient>(relaxed = true),
        nodeClient = mockk<NodeClient>(relaxed = true),
        localPlayerRepository = mockk<WearLocalPlayerRepository>(relaxed = true),
        stateRepository = mockk<WearStateRepository>(relaxed = true),
        playbackController = mockk<WearPlaybackController>(relaxed = true),
    )

    @Test
    fun `first sync sets createdAt and updatedAt to the same timestamp`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns null
        val entitySlot = slot<LocalPlaylistEntity>()
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()

        repository.onPlaylistSyncReceived(WearPlaylistSync("p1", "Road Trip", listOf("s1", "s2")))

        coVerify { localPlaylistDao.upsertPlaylist(capture(entitySlot), capture(crossRefsSlot)) }
        assertThat(entitySlot.captured.playlistId).isEqualTo("p1")
        assertThat(entitySlot.captured.name).isEqualTo("Road Trip")
        assertThat(entitySlot.captured.createdAt).isEqualTo(entitySlot.captured.updatedAt)
        assertThat(crossRefsSlot.captured.map { it.songId to it.position })
            .containsExactly("s1" to 0, "s2" to 1)
            .inOrder()
    }

    @Test
    fun `re-sync preserves the original createdAt but bumps updatedAt`() = runTest {
        val original = LocalPlaylistEntity("p1", "Road Trip", createdAt = 1_000L, updatedAt = 1_000L)
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns original
        val entitySlot = slot<LocalPlaylistEntity>()

        repository.onPlaylistSyncReceived(WearPlaylistSync("p1", "Road Trip", listOf("s1")))

        coVerify { localPlaylistDao.upsertPlaylist(capture(entitySlot), any()) }
        assertThat(entitySlot.captured.createdAt).isEqualTo(1_000L)
        assertThat(entitySlot.captured.updatedAt).isAtLeast(1_000L)
    }

    @Test
    fun `re-sync with a different song set is passed through as-is, not merged with the old one`() = runTest {
        coEvery { localPlaylistDao.getPlaylistById("p1") } returns
            LocalPlaylistEntity("p1", "Road Trip", createdAt = 1_000L, updatedAt = 1_000L)
        val crossRefsSlot = slot<List<LocalPlaylistSongCrossRef>>()

        repository.onPlaylistSyncReceived(WearPlaylistSync("p1", "Road Trip", listOf("s2", "s3")))

        coVerify { localPlaylistDao.upsertPlaylist(any(), capture(crossRefsSlot)) }
        assertThat(crossRefsSlot.captured.map { it.songId }).containsExactly("s2", "s3")
    }
}
