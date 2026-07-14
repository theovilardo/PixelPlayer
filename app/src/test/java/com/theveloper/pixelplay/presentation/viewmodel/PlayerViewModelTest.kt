package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import app.cash.turbine.test
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import io.mockk.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.telegram.TelegramCacheManager
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.utils.AppShortcutManager
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.presentation.viewmodel.*
import app.cash.turbine.Turbine

import androidx.core.content.ContextCompat

@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
class PlayerViewModelTest {

    private lateinit var playerViewModel: PlayerViewModel
    private val mockMusicRepository: MusicRepository = mockk()
    private val mockUserPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val mockAiPreferencesRepository: AiPreferencesRepository = mockk(relaxed = true)
    private val mockThemePreferencesRepository: ThemePreferencesRepository = mockk(relaxed = true)
    private val mockAlbumArtThemeDao: AlbumArtThemeDao = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)

    // New Mocks
    private val mockSyncManager: SyncManager = mockk(relaxed = true)
    private val mockDualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val mockAppShortcutManager: AppShortcutManager = mockk(relaxed = true)
    private val mockTelegramCacheManager: TelegramCacheManager = mockk(relaxed = true)
    private val mockTelegramCacheManagerProvider: Lazy<TelegramCacheManager> = mockk()
    private val mockTelegramRepository: com.theveloper.pixelplay.data.telegram.TelegramRepository = mockk(relaxed = true)
    private val mockListeningStatsTracker: ListeningStatsTracker = mockk(relaxed = true)
    private val mockDailyMixStateHolder: DailyMixStateHolder = mockk(relaxed = true)
    private val mockLyricsStateHolder: LyricsStateHolder = mockk(relaxed = true)
    private val mockCastStateHolder: CastStateHolder = mockk(relaxed = true)
    private val mockCastRouteStateHolder: CastRouteStateHolder = mockk(relaxed = true)
    private val mockQueueStateHolder: QueueStateHolder = mockk(relaxed = true)
    private val mockQueueUndoStateHolder: QueueUndoStateHolder = mockk(relaxed = true)
    private val mockPlaylistDismissUndoStateHolder: PlaylistDismissUndoStateHolder = mockk(relaxed = true)
    private val mockPlaybackStateHolder: PlaybackStateHolder = mockk(relaxed = true)
    private val mockConnectivityStateHolder: ConnectivityStateHolder = mockk(relaxed = true)
    private val mockSleepTimerStateHolder: SleepTimerStateHolder = mockk(relaxed = true)
    private val mockSearchStateHolder: SearchStateHolder = mockk(relaxed = true)
    private val mockAiStateHolder: AiStateHolder = mockk(relaxed = true)
    private val mockLibraryStateHolder: LibraryStateHolder = mockk(relaxed = true)
    private val mockFolderNavigationStateHolder: FolderNavigationStateHolder = mockk(relaxed = true)
    private val mockLibraryTabsStateHolder: LibraryTabsStateHolder = mockk(relaxed = true)
    private val mockCastTransferStateHolder: CastTransferStateHolder = mockk(relaxed = true)
    private val mockMetadataEditStateHolder: MetadataEditStateHolder = mockk(relaxed = true)
    private val mockSongRemovalStateHolder: SongRemovalStateHolder = mockk(relaxed = true)
    private val mockExternalMediaStateHolder: ExternalMediaStateHolder = mockk(relaxed = true)
    private val mockThemeStateHolder: ThemeStateHolder = mockk(relaxed = true)
    private val mockMultiSelectionStateHolder: MultiSelectionStateHolder = mockk(relaxed = true)
    private val mockPlaylistSelectionStateHolder: PlaylistSelectionStateHolder = mockk(relaxed = true)
    private val mockMediaMapper: com.theveloper.pixelplay.data.media.MediaMapper = mockk(relaxed = true)
    private lateinit var mockMediaControllerFactory: com.theveloper.pixelplay.data.media.MediaControllerFactory

    private val testDispatcher = StandardTestDispatcher()

    // Test Flows
    private val _allSongsFlow = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    // Fix: Use ImmutableList for Search Flows as per SearchStateHolder definition
    private val _searchHistoryFlow = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    private val _searchResultsFlow = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    private val _selectedSearchFilterFlow = MutableStateFlow(SearchFilterType.ALL)
    private val _castSessionFlow = MutableStateFlow<com.google.android.gms.cast.framework.CastSession?>(null)
    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    private val _favoriteIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private lateinit var stablePlayerStateFlow: MutableStateFlow<StablePlayerState>
    private lateinit var mockController: MediaController
    private val controllerRepeatModeWrites = mutableListOf<Int>()
    private var controllerRepeatMode = Player.REPEAT_MODE_OFF

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockKAnnotations.init(this)

        mockkStatic(ContextCompat::class)
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse(any()) } returns mockk(relaxed = true)
        val directExecutor = java.util.concurrent.Executor { it.run() }
        every { ContextCompat.getMainExecutor(any()) } returns directExecutor
        every { mockTelegramCacheManager.embeddedArtUpdated } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { mockTelegramCacheManagerProvider.get() } returns mockTelegramCacheManager

        // Mock UserPreferences
        coEvery { mockUserPreferencesRepository.favoriteSongIdsFlow } returns flowOf(emptySet())
        coEvery { mockUserPreferencesRepository.songsSortOptionFlow } returns flowOf("SongTitleAZ")
        coEvery { mockUserPreferencesRepository.albumsSortOptionFlow } returns flowOf("AlbumTitleAZ")
        coEvery { mockUserPreferencesRepository.artistsSortOptionFlow } returns flowOf("ArtistNameAZ")
        coEvery { mockUserPreferencesRepository.likedSongsSortOptionFlow } returns flowOf("LikedSongTitleAZ")
        coEvery { mockUserPreferencesRepository.navBarCornerRadiusFlow } returns flowOf(32)
        coEvery { mockUserPreferencesRepository.navBarStyleFlow } returns flowOf("Default")
        coEvery { mockUserPreferencesRepository.libraryNavigationModeFlow } returns flowOf("TabRow")
        coEvery { mockUserPreferencesRepository.carouselStyleFlow } returns flowOf("NoPeek")
        coEvery { mockUserPreferencesRepository.fullPlayerLoadingTweaksFlow } returns flowOf(com.theveloper.pixelplay.data.preferences.FullPlayerLoadingTweaks())
        coEvery { mockUserPreferencesRepository.tapBackgroundClosesPlayerFlow } returns flowOf(true)
        coEvery { mockUserPreferencesRepository.hapticsEnabledFlow } returns flowOf(true)
        coEvery { mockUserPreferencesRepository.foldersSortOptionFlow } returns flowOf("FolderNameAZ") // Added missing mock
        coEvery { mockUserPreferencesRepository.persistentShuffleEnabledFlow } returns flowOf(false) // Added missing mock
        coEvery { mockUserPreferencesRepository.isShuffleOnFlow } returns flowOf(false) // Added missing mock
        every { mockUserPreferencesRepository.repeatModeFlow } returns _repeatModeFlow
        coEvery { mockThemePreferencesRepository.playerThemePreferenceFlow } returns flowOf("Global")
        coEvery { mockAiPreferencesRepository.aiProvider } returns flowOf("GEMINI")
        coEvery { mockAiPreferencesRepository.geminiApiKey } returns flowOf("")
        coEvery { mockAiPreferencesRepository.deepseekApiKey } returns flowOf("")

        // Mock StateHolders Flows
        every { mockLibraryStateHolder.allSongs } returns _allSongsFlow
        every { mockLibraryStateHolder.isLoadingLibrary } returns MutableStateFlow(false)
        every { mockLibraryStateHolder.isLoadingCategories } returns MutableStateFlow(false)
        every { mockLibraryStateHolder.genres } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.albums } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.artists } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.musicFolders } returns MutableStateFlow(persistentListOf())
        every { mockLibraryStateHolder.currentSongSortOption } returns MutableStateFlow<SortOption>(SortOption.SongTitleAZ)
        every { mockLibraryStateHolder.currentAlbumSortOption } returns MutableStateFlow<SortOption>(SortOption.AlbumTitleAZ)
        every { mockLibraryStateHolder.currentArtistSortOption } returns MutableStateFlow<SortOption>(SortOption.ArtistNameAZ)
        every { mockLibraryStateHolder.currentFolderSortOption } returns MutableStateFlow<SortOption>(SortOption.FolderNameAZ)
        every { mockLibraryStateHolder.currentFavoriteSortOption } returns MutableStateFlow<SortOption>(SortOption.LikedSongTitleAZ)
        every { mockLibraryStateHolder.currentStorageFilter } returns MutableStateFlow(StorageFilter.ALL)

        every { mockSearchStateHolder.searchHistory } returns _searchHistoryFlow
        every { mockSearchStateHolder.searchResults } returns _searchResultsFlow
        every { mockSearchStateHolder.selectedSearchFilter } returns _selectedSearchFilterFlow
        every { mockSearchStateHolder.loadSearchHistory(any()) } just runs
        every { mockSearchStateHolder.clearSearchHistory() } just runs
        every { mockSearchStateHolder.deleteSearchHistoryItem(any()) } just runs
        every { mockSearchStateHolder.updateSearchFilter(any()) } just runs
        every { mockSearchStateHolder.initialize(any()) } just runs // Added missing initialize mock

        every { mockAiStateHolder.showAiPlaylistSheet } returns MutableStateFlow(false)
        every { mockAiStateHolder.isGeneratingAiPlaylist } returns MutableStateFlow(false)
        every { mockAiStateHolder.aiError } returns MutableStateFlow<String?>(null)
        every { mockAiStateHolder.initialize(any(), any(), any(), any(), any(), any()) } just runs
 
        every { mockCastStateHolder.castSession } returns _castSessionFlow
        every { mockCastStateHolder.startDiscovery() } just runs // Added missing mock
        every { mockCastStateHolder.selectedRoute } returns MutableStateFlow<androidx.mediarouter.media.MediaRouter.RouteInfo?>(null) // Added missing mock

        // Connectivity mocks removed as properties differ from expectations
        every { mockConnectivityStateHolder.initialize() } just runs
        every { mockConnectivityStateHolder.offlinePlaybackBlocked } returns MutableSharedFlow()

        _favoriteIdsFlow.value = emptySet()
        stablePlayerStateFlow = MutableStateFlow(StablePlayerState(currentSong = null))
        every { mockPlaybackStateHolder.stablePlayerState } returns stablePlayerStateFlow
        every { mockPlaybackStateHolder.setMediaController(any()) } just runs // Added missing mock

        every { mockSleepTimerStateHolder.initialize(any(), any(), any(), any(), any()) } just runs // Added missing mock
        every { mockLibraryStateHolder.initialize(any()) } just runs // Added missing mock
        every { mockCastTransferStateHolder.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any()) } just runs
        
        // Mock MusicRepository Basic Returns
        every { mockMusicRepository.getPaginatedSongs(any(), any()) } returns flowOf(androidx.paging.PagingData.empty())
        every { mockMusicRepository.getPaginatedFavoriteSongs(any(), any()) } returns flowOf(androidx.paging.PagingData.empty())
        every { mockMusicRepository.getAudioFiles() } returns flowOf(emptyList())
        every { mockMusicRepository.getDistinctAlbumArtSongs() } returns flowOf(emptyList())
        every { mockMusicRepository.getHomeMixPreviewSongs(any()) } returns flowOf(emptyList())
        every { mockMusicRepository.getCloudSongCountFlow() } returns flowOf(0)
        every { mockMusicRepository.searchSongs(any(), any()) } returns flowOf(emptyList())
        every { mockMusicRepository.getMusicByGenre(any()) } returns flowOf(emptyList())
        coEvery { mockMusicRepository.getFavoriteSongIdsOnce() } returns emptySet()
        every { mockMusicRepository.getFavoriteSongIdsFlow() } returns _favoriteIdsFlow
        every { mockMusicRepository.getSong(any()) } returns flowOf(null)
        coEvery { mockMusicRepository.getSongIdByContentUri(any()) } returns null
        coEvery { mockMusicRepository.getSongByPath(any()) } returns null
        coEvery { mockMusicRepository.setFavoriteStatus(any(), any()) } just Runs
        coEvery { mockMusicRepository.getAllSongsOnce() } returns emptyList()
        coEvery { mockMusicRepository.getFavoriteSongsOnce(any()) } returns emptyList()
        coEvery { mockMusicRepository.getFirstPlayableSong() } returns null
        coEvery { mockMusicRepository.getRandomSongs(any()) } returns emptyList()
        coEvery { mockMusicRepository.getSongIdsSorted(any(), any()) } returns emptyList()
        coEvery { mockMusicRepository.getFavoriteSongIdsSorted(any(), any()) } returns emptyList()
        every { mockMusicRepository.telegramRepository } returns mockTelegramRepository
        every { mockTelegramRepository.downloadCompleted } returns MutableSharedFlow<Int>()
        every { mockLyricsStateHolder.songUpdates } returns MutableSharedFlow()

        // Initialize PlayerViewModel
        val sessionToken = mockk<SessionToken>(relaxed = true)
        mockMediaControllerFactory = mockk(relaxed = true)
        
        // Mock ListenableFuture for MediaController creation
        mockController = mockk(relaxed = true)
        every { mockController.repeatMode } answers { controllerRepeatMode }
        every { mockController.repeatMode = any() } answers {
            controllerRepeatMode = firstArg()
            controllerRepeatModeWrites += controllerRepeatMode
        }
        val mockFuture = mockk<ListenableFuture<MediaController>>(relaxed = true)
        every { mockFuture.get() } returns mockController
        every { mockFuture.addListener(any(), any()) } answers {
            val runnable = firstArg<Runnable>()
            runnable.run()
        }
        every { mockMediaControllerFactory.create(any(), any(), any()) } returns mockFuture
        
        // Ensure manual executor for main thread to prevent RejectedExecutionException
        // We already mocked ContextCompat.getMainExecutor above.
        
        // Real dispatch holder wired to the same mocks the ViewModel uses, so the
        // existing end-to-end playback tests keep exercising the moved logic.
        val playbackDispatchStateHolder = PlaybackDispatchStateHolder(
            mockMusicRepository,
            mockUserPreferencesRepository,
            mockDualPlayerEngine,
            mockAppShortcutManager,
            mockSyncManager,
            mockExternalMediaStateHolder,
            mockPlaybackStateHolder,
            mockQueueStateHolder,
            mockLibraryStateHolder,
            mockCastStateHolder,
            mockCastTransferStateHolder,
            mockConnectivityStateHolder,
            mockThemeStateHolder,
            mockContext
        )
        // Real controller-sync holder wired to the same mocks, so existing tests
        // (e.g. the repeat-mode restore test) keep exercising the moved logic.
        val mediaControllerSyncStateHolder = MediaControllerSyncStateHolder(
            mockMusicRepository,
            mockUserPreferencesRepository,
            mockDualPlayerEngine,
            mockMediaMapper,
            mockPlaybackStateHolder,
            mockLibraryStateHolder,
            mockCastStateHolder,
            mockConnectivityStateHolder,
            mockThemeStateHolder,
            mockLyricsStateHolder,
            mockSleepTimerStateHolder,
            playbackDispatchStateHolder,
            mockContext
        )
        playerViewModel = PlayerViewModel(
            mockContext,
            mockMusicRepository,
            mockUserPreferencesRepository,
            mockAiPreferencesRepository,
            mockThemePreferencesRepository,
            mockSyncManager,
            mockDualPlayerEngine,
            mockTelegramCacheManagerProvider,
            mockListeningStatsTracker,
            mockDailyMixStateHolder,
            mockLyricsStateHolder,
            mockCastStateHolder,
            mockCastRouteStateHolder,
            mockQueueStateHolder,
            mockQueueUndoStateHolder,
            mockPlaylistDismissUndoStateHolder,
            mockPlaybackStateHolder,
            mockConnectivityStateHolder,
            mockSleepTimerStateHolder,
            mockSearchStateHolder,
            mockAiStateHolder,
            mockLibraryStateHolder,
            mockFolderNavigationStateHolder,
            mockLibraryTabsStateHolder,
            mockCastTransferStateHolder,
            mockMetadataEditStateHolder,
            mockSongRemovalStateHolder,
            mockThemeStateHolder,
            mockMultiSelectionStateHolder,
            mockPlaylistSelectionStateHolder,
            playbackDispatchStateHolder,
            mediaControllerSyncStateHolder,
            sessionToken,
            mockMediaControllerFactory
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Nested
    @DisplayName("GetSongUrisForGenre Tests")
    inner class GetSongUrisForGenreTests {

        private val song1 = Song(id = "1", title = "Song 1", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover1.png", artistId = 1L, albumId = 1L, contentUriString = "content://dummy/1", duration = 180000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song2 = Song(id = "2", title = "Song 2", artist = "Artist B", genre = "Pop", albumArtUriString = "pop_cover1.png", artistId = 2L, albumId = 2L, contentUriString = "content://dummy/2", duration = 200000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song3 = Song(id = "3", title = "Song 3", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover2.png", artistId = 3L, albumId = 3L, contentUriString = "content://dummy/3", duration = 190000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song4 = Song(id = "4", title = "Song 4", artist = "Artist C", genre = "Jazz", albumArtUriString = "jazz_cover1.png", artistId = 4L, albumId = 4L, contentUriString = "content://dummy/4", duration = 210000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song5 = Song(id = "5", title = "Song 5", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover3.png", artistId = 5L, albumId = 5L, contentUriString = "content://dummy/5", duration = 220000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song6 = Song(id = "6", title = "Song 6", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover4.png", artistId = 6L, albumId = 6L, contentUriString = "content://dummy/6", duration = 230000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song7 = Song(id = "7", title = "Song 7", artist = "Artist A", genre = "Rock", albumArtUriString = "rock_cover5.png", artistId = 7L, albumId = 7L, contentUriString = "content://dummy/7", duration = 240000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song8 = Song(id = "8", title = "Song 8", artist = "Artist A", genre = "Rock", albumArtUriString = "", artistId = 8L, albumId = 8L, contentUriString = "content://dummy/8", duration = 250000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg") // Empty cover
        private val song9 = Song(id = "9", title = "Song 9", artist = "Artist D", genre = null, albumArtUriString = "null_genre_cover.png", artistId = 9L, albumId = 9L, contentUriString = "content://dummy/9", duration = 260000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg") // Null genre

        private fun setupViewModelWithSongs(songs: List<Song>) {
            _allSongsFlow.value = songs.toImmutableList()
            testDispatcher.scheduler.advanceUntilIdle() // Ensure ViewModel collects the update
            
            // Still mock repository for getMusicByGenre calls
            val genreSlot = slot<String>()
            every { mockMusicRepository.getMusicByGenre(capture(genreSlot)) } answers {
                val genre = genreSlot.captured
                val filtered = songs.filter { it.genre.equals(genre, ignoreCase = true) }
                flowOf(filtered)
            }
        }

        @Test
        fun `single song with genre returns its cover`() = runTest {
            val testSongs = listOf(song1)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris)
        }

        @Test
        fun `multiple songs with same genre return all covers`() = runTest {
            val testSongs = listOf(song1, song3)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png"), uris)
        }

        @Test
        fun `no songs with genre returns empty list`() = runTest {
            val testSongs = listOf(song2, song4) // Pop and Jazz
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }
        
        @Test
        fun `genre with more than 3 songs returns first 4 valid covers`() = runTest {
             // song1, song3, song5, song6, song7 are all Rock (5 songs)
            val testSongs = listOf(song1, song3, song5, song6, song7)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(4, uris.size)
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png", "rock_cover3.png", "rock_cover4.png"), uris)
        }

        @Test
        fun `songs with blank album art are ignored`() = runTest {
            val testSongs = listOf(song1, song8) // song8 has empty cover
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris) // Only song1's cover
        }

        @Test
        fun `empty allSongs list returns empty list for any genre`() = runTest {
            setupViewModelWithSongs(emptyList())

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }

        @Test
        fun `case insensitive genre matching`() = runTest {
            val testSongs = listOf(song1, song3)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("rOcK").first()
            assertEquals(listOf("rock_cover1.png", "rock_cover2.png"), uris)
        }

        @Test
        fun `genre not found among songs with null genres`() = runTest {
            val testSongs = listOf(song9) // song9 has null genre
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertTrue(uris.isEmpty())
        }
         @Test
        fun `songs with null genre do not match specific genre query`() = runTest {
            val testSongs = listOf(song1, song9)
            setupViewModelWithSongs(testSongs)

            val uris = playerViewModel.getSongUrisForGenre("Rock").first()
            assertEquals(listOf("rock_cover1.png"), uris)
        }
    }

    @Test
    fun `repeat preference changes after startup are not pushed back into controller`() = runTest {
        advanceUntilIdle()
        controllerRepeatModeWrites.clear()

        _repeatModeFlow.value = Player.REPEAT_MODE_ONE
        advanceUntilIdle()

        assertTrue(controllerRepeatModeWrites.isEmpty())
        assertEquals(Player.REPEAT_MODE_OFF, controllerRepeatMode)
    }

    @Test
    fun `album navigation from player accepts synthetic negative album ids`() = runTest {
        playerViewModel.albumNavigationRequests.test {
            playerViewModel.triggerAlbumNavigationFromPlayer(-42L)
            advanceUntilIdle()

            assertEquals(-42L, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `album navigation from player still ignores sentinel album id`() = runTest {
        playerViewModel.albumNavigationRequests.test {
            playerViewModel.triggerAlbumNavigationFromPlayer(-1L)
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `toggleFavorite resolves external current song to MediaStore favorite id`() = runTest {
        val externalSong = Song(
            id = "external:content://media/external/audio/media/42",
            title = "External Song",
            artist = "Artist",
            artistId = -1L,
            album = "Album",
            albumId = -1L,
            path = "/storage/emulated/0/Music/external-song.mp3",
            contentUriString = "file:///data/user/0/com.theveloper.pixelplay/cache/external_audio/audio_42.mp3",
            albumArtUriString = null,
            duration = 180000L,
            mimeType = "audio/mpeg",
            bitrate = null,
            sampleRate = null
        )
        stablePlayerStateFlow.value = StablePlayerState(currentSong = externalSong)

        playerViewModel.toggleFavorite()
        advanceUntilIdle()

        coVerify { mockMusicRepository.setFavoriteStatus("42", true) }
    }

    @Test
    fun `isCurrentSongFavorite resolves external current song before reading favorite ids`() = runTest {
        _favoriteIdsFlow.value = setOf("42")
        val externalSong = Song(
            id = "external:content://media/external/audio/media/42",
            title = "External Song",
            artist = "Artist",
            artistId = -1L,
            album = "Album",
            albumId = -1L,
            path = "/storage/emulated/0/Music/external-song.mp3",
            contentUriString = "file:///data/user/0/com.theveloper.pixelplay/cache/external_audio/audio_42.mp3",
            albumArtUriString = null,
            duration = 180000L,
            mimeType = "audio/mpeg",
            bitrate = null,
            sampleRate = null
        )

        playerViewModel.isCurrentSongFavorite.test {
            assertEquals(false, awaitItem())

            stablePlayerStateFlow.value = StablePlayerState(currentSong = externalSong)
            advanceUntilIdle()

            assertEquals(true, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Nested
    @DisplayName("Shuffle Functionality")
    inner class ShuffleFunctionalityTests {

        private val song1 = Song(id = "1", title = "Song 1", artist = "Artist A", genre = "Rock", albumArtUriString = "cover1.png", artistId = 1L, albumId = 1L, contentUriString = "content://dummy/1", duration = 180000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song2 = Song(id = "2", title = "Song 2", artist = "Artist B", genre = "Pop", albumArtUriString = "cover2.png", artistId = 2L, albumId = 2L, contentUriString = "content://dummy/2", duration = 200000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")
        private val song3 = Song(id = "3", title = "Song 3", artist = "Artist C", genre = "Jazz", albumArtUriString = "cover3.png", artistId = 3L, albumId = 3L, contentUriString = "content://dummy/3", duration = 210000L, bitrate = null, sampleRate = null, album = "Album", path = "path", mimeType = "audio/mpeg")

        private fun stubShuffledPlayback(
            songs: List<Song>,
            queueName: String,
            startSong: Song = songs.first()
        ) {
            coEvery {
                mockQueueStateHolder.prepareShuffledQueueSuspending(songs, queueName, true)
            } returns Pair(songs, startSong)

            mockkObject(MediaItemBuilder)
            every { MediaItemBuilder.build(any()) } returns MediaItem.Builder()
                .setMediaId("test")
                .setUri("file:///tmp/test.mp3")
                .build()
            val mockedPlaybackUri = mockk<android.net.Uri>(relaxed = true)
            every { mockedPlaybackUri.scheme } returns "file"
            every { MediaItemBuilder.playbackUri(any<Song>()) } returns mockedPlaybackUri
        }

        // Source resolution + shuffled dispatch now live in QueueStateHolder (Pass 2). The
        // ViewModel methods are thin delegates, so these tests verify the delegation. The moved
        // business logic is covered directly in QueueStateHolderTest.
        @Test
        fun `shuffleAllSongs delegates to QueueStateHolder shuffleAll`() = runTest {
            playerViewModel.shuffleAllSongs()
            advanceUntilIdle()

            verify { mockQueueStateHolder.shuffleAll("All Songs (Shuffled)", any()) }
        }

        @Test
        fun `playRandomSong delegates to QueueStateHolder playRandom`() = runTest {
            playerViewModel.playRandomSong()
            advanceUntilIdle()

            verify { mockQueueStateHolder.playRandom(any()) }
        }

        @Test
        fun `shuffleFavoriteSongs delegates to QueueStateHolder shuffleFavorites`() = runTest {
            playerViewModel.shuffleFavoriteSongs()
            advanceUntilIdle()

            verify { mockQueueStateHolder.shuffleFavorites(any()) }
        }

        @Test
        fun `shuffleRandomAlbum delegates to QueueStateHolder shuffleRandomAlbum`() = runTest {
            playerViewModel.shuffleRandomAlbum()
            advanceUntilIdle()

            verify { mockQueueStateHolder.shuffleRandomAlbum(any()) }
        }

        @Test
        fun `shuffleRandomArtist delegates to QueueStateHolder shuffleRandomArtist`() = runTest {
            playerViewModel.shuffleRandomArtist()
            advanceUntilIdle()

            verify { mockQueueStateHolder.shuffleRandomArtist(any()) }
        }

        @Test
        fun `triggerShuffleAllFromTile uses startAtZero when library is already loaded`() = runTest {
            val songs = listOf(song1, song2, song3)
            _allSongsFlow.value = songs.toImmutableList()
            // The tile pulls a bounded random sample from the repository; stub it so the
            // "already loaded" path returns immediately without going through a sync retry.
            coEvery { mockMusicRepository.getRandomSongs(500) } returns songs
            stubShuffledPlayback(songs, "All Songs (Shuffled)", startSong = song1)

            playerViewModel.triggerShuffleAllFromTile()
            advanceUntilIdle()

            coVerify {
                mockQueueStateHolder.prepareShuffledQueueSuspending(
                    songs,
                    "All Songs (Shuffled)",
                    true
                )
            }
        }
    }

    @Nested
    @DisplayName("Search History")
    inner class SearchHistoryTests {

        @Test
        fun `test_loadSearchHistory_updatesUiState`() = runTest {
            val historyItems = listOf(SearchHistoryItem(query = "q1", timestamp = 1L))
             // Mock the SearchStateHolder's loadSearchHistory to update the flow
            every { mockSearchStateHolder.loadSearchHistory(any()) } answers {
                _searchHistoryFlow.value = historyItems.toImmutableList()
            }

            playerViewModel.playerUiState.test {
                // Skip initial state from init
                val initialState = awaitItem()
                if (initialState.searchHistory == historyItems) {
                    cancelAndConsumeRemainingEvents()
                    return@test
                }

                playerViewModel.loadSearchHistory()
                
                val currentItem = awaitItem()
                assertEquals(historyItems, currentItem.searchHistory)
                cancelAndConsumeRemainingEvents()
            }
        }


        @Test
        fun `test_clearSearchHistory_callsRepository_andUpdatesUiState`() = runTest {
            // Initialize with some history
            val initialHistory = listOf(SearchHistoryItem(query = "q1", timestamp = 1L))
            _searchHistoryFlow.value = initialHistory.toImmutableList()
            
            // Mock clear behavior
            every { mockSearchStateHolder.clearSearchHistory() } answers {
                _searchHistoryFlow.value = persistentListOf()
            }

            playerViewModel.playerUiState.test {
               // Await initial state with history
               var state = awaitItem()
               while (state.searchHistory != initialHistory) {
                   state = awaitItem()
               }

                playerViewModel.clearSearchHistory()

                var emitted = awaitItem()
                while (emitted.searchHistory.isNotEmpty()) {
                    emitted = awaitItem()
                }
                assertTrue(emitted.searchHistory.isEmpty())
                verify { mockSearchStateHolder.clearSearchHistory() }
                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `test_deleteSearchHistoryItem_callsRepository_andRefreshesHistory`() = runTest {
            val queryToDelete = "delete me"
            val keepQuery = "keep me"
            val initialHistory = listOf(SearchHistoryItem(query = queryToDelete, timestamp = 1L), SearchHistoryItem(query = keepQuery, timestamp = 2L))
            val finalHistory = listOf(SearchHistoryItem(query = keepQuery, timestamp = 2L))

            // Initial state
            _searchHistoryFlow.value = initialHistory.toImmutableList()
            
            // Mock delete behavior
            every { mockSearchStateHolder.deleteSearchHistoryItem(queryToDelete) } answers {
                _searchHistoryFlow.value = finalHistory.toImmutableList()
            }

            playerViewModel.playerUiState.test {
                // Await initial state
               var state = awaitItem()
               while (state.searchHistory != initialHistory) {
                   state = awaitItem()
               }

                playerViewModel.deleteSearchHistoryItem(queryToDelete)

                var emitted = awaitItem()
                while (emitted.searchHistory != finalHistory) {
                    emitted = awaitItem()
                }
                assertEquals(finalHistory, emitted.searchHistory)
                
                cancelAndConsumeRemainingEvents()
            }
        }
        
        @Test
        fun `test_updateSearchFilter_updatesUiState`() = runTest {
             val newFilter = SearchFilterType.ARTISTS
             
             // Mock update behavior
             coEvery { mockSearchStateHolder.updateSearchFilter(newFilter) } answers {
                 _selectedSearchFilterFlow.value = newFilter
             }

            playerViewModel.playerUiState.test {
                skipItems(1) // Skip initial

                playerViewModel.updateSearchFilter(newFilter)
                
                val emittedItem = awaitItem()
                assertEquals(newFilter, emittedItem.selectedSearchFilter)
                cancelAndConsumeRemainingEvents()
            }
        }
    }

    private fun PlayerViewModel.updateAllSongs(songs: List<Song>) {
        // Correctly update the flow that PlayerViewModel collects
        _allSongsFlow.value = songs.toImmutableList()
    }
}
