package com.theveloper.pixelplay.presentation.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistUiState
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose coverage for the in-playlist search filter added to
 * [PlaylistDetailScreen]. Exercises the real composable (no ViewModel is
 * introduced for this feature; state lives in the composable itself) with
 * relaxed mocks for [PlayerViewModel]/[PlaylistViewModel], following the
 * same pattern already used for concrete-class mocking in this project's
 * instrumented tests (see SyncWorkerTest).
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDetailScreenSearchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val songBohemianRhapsody = buildSong(id = "song-1", title = "Bohemian Rhapsody", artist = "Queen")
    private val songYesterday = buildSong(id = "song-2", title = "Yesterday", artist = "The Beatles")
    private val songUnderPressure = buildSong(id = "song-3", title = "Under Pressure", artist = "Queen")
    private val songImagine = buildSong(id = "song-4", title = "Imagine", artist = "John Lennon")

    private val fakeSongs = listOf(songBohemianRhapsody, songYesterday, songUnderPressure, songImagine)
    private val fakePlaylist = Playlist(
        id = "playlist-1",
        name = "Road Trip",
        songIds = fakeSongs.map { it.id }
    )

    @Test
    fun searchField_typingQuery_filtersVisibleSongs() {
        setPlaylistDetailContent(songs = fakeSongs, playlist = fakePlaylist)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("queen")

        composeTestRule.onNodeWithText("Bohemian Rhapsody").assertExists()
        composeTestRule.onNodeWithText("Under Pressure").assertExists()
        composeTestRule.onNodeWithText("Yesterday").assertDoesNotExist()
        composeTestRule.onNodeWithText("Imagine").assertDoesNotExist()
    }

    @Test
    fun searchField_typingNonMatchingQuery_showsEmptyStateWithQuery() {
        setPlaylistDetailContent(songs = fakeSongs, playlist = fakePlaylist)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("zzz")

        val expectedEmptyState = composeTestRule.activity.getString(R.string.search_no_results_for_query, "zzz")
        composeTestRule.onNodeWithText(expectedEmptyState).assertExists()
    }

    @Test
    fun searchField_clearingQuery_restoresFullListAndActionsRow() {
        setPlaylistDetailContent(songs = fakeSongs, playlist = fakePlaylist)
        // "Play it"/"Shuffle" are drawn by TightWrapText directly on a Canvas (no semantics
        // text node), so the icon's content description is what's actually queryable here.
        val playCd = composeTestRule.activity.getString(R.string.common_play)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("queen")
        composeTestRule.onNodeWithText("Yesterday").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(playCd).assertDoesNotExist()

        composeTestRule.onNode(hasSetTextAction()).performTextClearance()

        composeTestRule.onNodeWithText("Yesterday").assertExists()
        composeTestRule.onNodeWithContentDescription(playCd).assertExists()
    }

    @Test
    fun actionsRow_hiddenWhileSearchQueryIsNotBlank() {
        setPlaylistDetailContent(songs = fakeSongs, playlist = fakePlaylist)
        val playCd = composeTestRule.activity.getString(R.string.common_play)
        val shuffleCd = composeTestRule.activity.getString(R.string.common_shuffle)

        composeTestRule.onNodeWithContentDescription(playCd).assertExists()
        composeTestRule.onNodeWithContentDescription(shuffleCd).assertExists()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("xyz")

        composeTestRule.onNodeWithContentDescription(playCd).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(shuffleCd).assertDoesNotExist()
    }

    @Test
    fun reorderMode_disabledAutomaticallyWhenSearchStartsAndStaysDisabledAfterClearing() {
        setPlaylistDetailContent(songs = fakeSongs, playlist = fakePlaylist)
        val reorderSongsCd = composeTestRule.activity.getString(R.string.playlist_cd_reorder_songs)
        val reorderLabel = composeTestRule.activity.getString(R.string.playlist_action_reorder_songs)

        // Only the toggle button itself exposes this content description before reorder mode is on.
        assertReorderCdCount(reorderSongsCd, expectedCount = 1)

        composeTestRule.onNodeWithText(reorderLabel).performClick()
        composeTestRule.waitForIdle()
        // Toggle button + one drag handle per visible song.
        assertReorderCdCount(reorderSongsCd, expectedCount = 1 + fakeSongs.size)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("queen")
        composeTestRule.waitForIdle()
        // Actions row (and its toggle button) is hidden entirely while filtering.
        assertReorderCdCount(reorderSongsCd, expectedCount = 0)

        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.waitForIdle()
        // Reorder mode was force-disabled by the LaunchedEffect, not just hidden: only the
        // toggle button reappears, the drag handles do not come back on their own.
        assertReorderCdCount(reorderSongsCd, expectedCount = 1)
    }

    @Test
    fun clickingFilteredSong_playsFullPlaylistFromClickedSong() {
        val playerViewModel = mockPlayerViewModel()
        val playlistViewModel = mockPlaylistViewModel(fakeSongs, fakePlaylist)
        setPlaylistDetailContent(playerViewModel = playerViewModel, playlistViewModel = playlistViewModel)

        // Filter down to a single song that isn't the first in the playlist.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("yesterday")
        composeTestRule.onNodeWithText("Yesterday").performClick()

        verify(exactly = 1) {
            playerViewModel.playSongs(fakeSongs, songYesterday, fakePlaylist.name, fakePlaylist.id)
        }
    }

    @Test
    fun emptyPlaylist_doesNotShowSearchField() {
        setPlaylistDetailContent(songs = emptyList(), playlist = fakePlaylist)

        val searchLabel = composeTestRule.activity.getString(R.string.song_picker_search_label)
        composeTestRule.onNodeWithText(searchLabel).assertDoesNotExist()
    }

    private fun assertReorderCdCount(contentDescription: String, expectedCount: Int) {
        val actualCount = composeTestRule
            .onAllNodesWithContentDescription(contentDescription)
            .fetchSemanticsNodes()
            .size
        assert(actualCount == expectedCount) {
            "Expected $expectedCount node(s) with content description \"$contentDescription\", found $actualCount"
        }
    }

    private fun setPlaylistDetailContent(
        songs: List<Song>,
        playlist: Playlist,
        playerViewModel: PlayerViewModel = mockPlayerViewModel(),
        playlistViewModel: PlaylistViewModel = mockPlaylistViewModel(songs, playlist)
    ) {
        setPlaylistDetailContent(playerViewModel = playerViewModel, playlistViewModel = playlistViewModel)
    }

    private fun setPlaylistDetailContent(
        playerViewModel: PlayerViewModel,
        playlistViewModel: PlaylistViewModel
    ) {
        composeTestRule.setContent {
            PixelPlayTheme {
                PlaylistDetailScreen(
                    playlistId = fakePlaylist.id,
                    onBackClick = {},
                    onDeletePlayListClick = {},
                    playerViewModel = playerViewModel,
                    playlistViewModel = playlistViewModel,
                    navController = rememberNavController()
                )
            }
        }
    }

    private fun mockPlaylistViewModel(songs: List<Song>, playlist: Playlist): PlaylistViewModel {
        val viewModel = mockk<PlaylistViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            PlaylistUiState(currentPlaylistDetails = playlist, currentPlaylistSongs = songs)
        )
        return viewModel
    }

    private fun mockPlayerViewModel(): PlayerViewModel {
        val viewModel = mockk<PlayerViewModel>(relaxed = true)
        every { viewModel.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { viewModel.selectedSongForInfo } returns MutableStateFlow(null)
        every { viewModel.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { viewModel.navBarCompactMode } returns MutableStateFlow(false)
        every { viewModel.isSortingSheetVisible } returns MutableStateFlow(false)
        return viewModel
    }

    private fun buildSong(
        id: String,
        title: String,
        artist: String,
        album: String = "Album"
    ): Song = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = 1L,
        album = album,
        albumId = 1L,
        path = "/tmp/$id.mp3",
        contentUriString = "content://pixelplay/song/$id",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
