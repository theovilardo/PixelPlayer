package com.theveloper.pixelplay.presentation.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.WearLocalPlayerRepository
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.data.WearStateRepository
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistSongCrossRef
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.LocalSongEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Covers the phone-song ↔ local-song join in [WearLocalPlaylistViewModel] — the part of Fase 7
 * that actually has logic (deciding availability and building the playable-only queue for
 * playAll/playFrom). The two screens that render this state are UI-only glue, verified manually.
 *
 * Uses an unconfined Main dispatcher (rather than the shared MainCoroutineExtension default) so
 * the viewModelScope.stateIn(...) chains resolve synchronously without needing a second,
 * independently-ticked test scheduler.
 */
@ExperimentalCoroutinesApi
class WearLocalPlaylistViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainCoroutineExtension(UnconfinedTestDispatcher())

    private val localPlaylistDao = mockk<LocalPlaylistDao>()
    private val localSongDao = mockk<LocalSongDao>()
    private val localPlayerRepository = mockk<WearLocalPlayerRepository>(relaxed = true)
    private val stateRepository = mockk<WearStateRepository>(relaxed = true)

    private fun song(id: String) = LocalSongEntity(
        songId = id,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 60_000L,
        mimeType = "audio/mpeg",
        fileSize = 1_000L,
        bitrate = 128_000,
        sampleRate = 44_100,
        isFavorite = false,
        favoriteSyncPending = false,
        localPath = "/music/$id.mp3",
        transferredAt = 0L,
    )

    private fun crossRef(songId: String, position: Int) =
        LocalPlaylistSongCrossRef(playlistId = "p1", songId = songId, position = position)

    private fun buildViewModel() = WearLocalPlaylistViewModel(
        localPlaylistDao = localPlaylistDao,
        localSongDao = localSongDao,
        localPlayerRepository = localPlayerRepository,
        stateRepository = stateRepository,
    )

    @Test
    fun `playlistSongs marks songs without a matching local song as unavailable`() = runTest {
        every { localPlaylistDao.observePlaylists() } returns flowOf(emptyList())
        every { localPlaylistDao.observePlaylistSongs("p1") } returns
            flowOf(listOf(crossRef("s1", 0), crossRef("s2", 1)))
        every { localSongDao.getAllSongs() } returns flowOf(listOf(song("s1")))

        val viewModel = buildViewModel()
        viewModel.loadPlaylist("p1")

        viewModel.playlistSongs.test {
            val items = awaitItem()
            assertThat(items.map { it.songId to it.isAvailable })
                .containsExactly("s1" to true, "s2" to false)
                .inOrder()
        }
    }

    @Test
    fun `playAll sends only the available songs to the player, skipping pending ones`() = runTest {
        every { localPlaylistDao.observePlaylists() } returns flowOf(emptyList())
        every { localPlaylistDao.observePlaylistSongs("p1") } returns
            flowOf(listOf(crossRef("s1", 0), crossRef("s2", 1), crossRef("s3", 2)))
        every { localSongDao.getAllSongs() } returns flowOf(listOf(song("s1"), song("s3")))

        val viewModel = buildViewModel()
        viewModel.loadPlaylist("p1")
        viewModel.playlistSongs.test { awaitItem() }

        viewModel.playAll()

        verify { localPlayerRepository.playLocalSongs(listOf(song("s1"), song("s3")), startIndex = 0) }
        verify { stateRepository.setOutputTarget(WearOutputTarget.WATCH) }
    }

    @Test
    fun `playFrom resolves the start index within the available-only queue, not the full playlist`() = runTest {
        every { localPlaylistDao.observePlaylists() } returns flowOf(emptyList())
        every { localPlaylistDao.observePlaylistSongs("p1") } returns
            flowOf(listOf(crossRef("s1", 0), crossRef("s2", 1), crossRef("s3", 2)))
        every { localSongDao.getAllSongs() } returns flowOf(listOf(song("s1"), song("s3")))

        val viewModel = buildViewModel()
        viewModel.loadPlaylist("p1")
        viewModel.playlistSongs.test { awaitItem() }

        // s2 is pending (filtered out), so s3 is at index 1 of the [s1, s3] playable queue,
        // not index 2 of the full [s1, s2, s3] playlist.
        viewModel.playFrom("s3")

        verify { localPlayerRepository.playLocalSongs(listOf(song("s1"), song("s3")), startIndex = 1) }
    }

    @Test
    fun `playFrom does nothing for a pending song id`() = runTest {
        every { localPlaylistDao.observePlaylists() } returns flowOf(emptyList())
        every { localPlaylistDao.observePlaylistSongs("p1") } returns
            flowOf(listOf(crossRef("s1", 0), crossRef("s2", 1)))
        every { localSongDao.getAllSongs() } returns flowOf(listOf(song("s1")))

        val viewModel = buildViewModel()
        viewModel.loadPlaylist("p1")
        viewModel.playlistSongs.test { awaitItem() }

        viewModel.playFrom("s2")

        verify(exactly = 0) { localPlayerRepository.playLocalSongs(any(), any(), any(), any()) }
    }
}
