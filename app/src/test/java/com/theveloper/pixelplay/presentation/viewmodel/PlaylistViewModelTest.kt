package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.ai.AiPlaylistGenerator
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.playlist.M3uManager
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.preferences.TelegramTopicDisplayMode
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.wear.PhoneWatchTransferStateStore
import com.theveloper.pixelplay.data.service.wear.PlaylistWatchTransferCoordinator
import com.theveloper.pixelplay.data.service.wear.WatchAudioTranscoder
import com.theveloper.pixelplay.data.service.wear.WearPhoneTransferSender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Covers only the watch-transfer surface this feature adds — estimateWatchTransfer,
 * isPlaylistFullyOnWatch, sendPlaylistToWatch, cancelPlaylistTransfer, activePlaylistBatchTransfer,
 * refreshWatchAvailability. The rest of PlaylistViewModel's large existing surface (CRUD,
 * sorting, AI generation, M3U import/export) is out of scope for this change and untouched.
 */
@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
class PlaylistViewModelTest {

    private val playlistPreferencesRepository = mockk<PlaylistPreferencesRepository>()
    private val musicRepository = mockk<MusicRepository>()
    private val dailyMixManager = mockk<DailyMixManager>(relaxed = true)
    private val aiPlaylistGenerator = mockk<AiPlaylistGenerator>(relaxed = true)
    private val m3uManager = mockk<M3uManager>(relaxed = true)
    private val playlistWatchTransferCoordinator = mockk<PlaylistWatchTransferCoordinator>()
    private val watchTransferStateStore = PhoneWatchTransferStateStore()
    private val wearPhoneTransferSender = mockk<WearPhoneTransferSender>()
    private val watchAudioTranscoder = mockk<WatchAudioTranscoder>()
    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { playlistPreferencesRepository.userPlaylistsFlow } returns flowOf(emptyList())
        every { playlistPreferencesRepository.playlistSongOrderModesFlow } returns flowOf(emptyMap())
        every { playlistPreferencesRepository.playlistsSortOptionFlow } returns flowOf("")
        every { playlistPreferencesRepository.showTelegramCloudPlaylistsFlow } returns flowOf(true)
        every { playlistPreferencesRepository.telegramTopicDisplayModeFlow } returns
            flowOf(TelegramTopicDisplayMode.CHANNELS_AND_TOPICS)
        coEvery { wearPhoneTransferSender.refreshWatchPairingState() } returns true
    }

    private fun buildViewModel() = PlaylistViewModel(
        playlistPreferencesRepository = playlistPreferencesRepository,
        musicRepository = musicRepository,
        dailyMixManager = dailyMixManager,
        aiPlaylistGenerator = aiPlaylistGenerator,
        m3uManager = m3uManager,
        playlistWatchTransferCoordinator = playlistWatchTransferCoordinator,
        watchTransferStateStore = watchTransferStateStore,
        wearPhoneTransferSender = wearPhoneTransferSender,
        watchAudioTranscoder = watchAudioTranscoder,
        context = context,
    )

    private fun song(id: String, mimeType: String = "audio/mpeg", bitrate: Int? = 128_000) =
        Song.emptySong().copy(id = id, mimeType = mimeType, bitrate = bitrate)

    @Test
    fun `estimateWatchTransfer only counts songs not already on every reachable watch`() = runTest {
        coEvery { watchAudioTranscoder.transcodeIfNeeded(any(), any(), any()) } returns
            WatchAudioTranscoder.TranscodeResult.Passthrough
        every { watchAudioTranscoder.estimatedTransferBitrateBps(any()) } returns 128_000
        watchTransferStateStore.retainReachableWatchNodes(setOf("node-1"))
        watchTransferStateStore.markSongPresentOnWatch("node-1", "already-there")
        val viewModel = buildViewModel()

        val estimate = viewModel.estimateWatchTransfer(listOf(song("already-there"), song("pending")))

        assertThat(estimate.totalSongCount).isEqualTo(2)
        assertThat(estimate.pendingSongCount).isEqualTo(1)
    }

    @Test
    fun `isPlaylistFullyOnWatch is false for an empty playlist`() {
        val viewModel = buildViewModel()
        assertThat(viewModel.isPlaylistFullyOnWatch(emptyList())).isFalse()
    }

    @Test
    fun `isPlaylistFullyOnWatch is true only once every song is on every reachable watch`() {
        watchTransferStateStore.retainReachableWatchNodes(setOf("node-1"))
        val viewModel = buildViewModel()

        assertThat(viewModel.isPlaylistFullyOnWatch(listOf("s1", "s2"))).isFalse()

        watchTransferStateStore.markSongPresentOnWatch("node-1", "s1")
        assertThat(viewModel.isPlaylistFullyOnWatch(listOf("s1", "s2"))).isFalse()

        watchTransferStateStore.markSongPresentOnWatch("node-1", "s2")
        assertThat(viewModel.isPlaylistFullyOnWatch(listOf("s1", "s2"))).isTrue()
    }

    @Test
    fun `sendPlaylistToWatch delegates to the coordinator and returns its batchId`() {
        every {
            playlistWatchTransferCoordinator.requestPlaylistTransfer("p1", "Playlist", listOf("s1", "s2"))
        } returns "batch-123"
        val viewModel = buildViewModel()

        val batchId = viewModel.sendPlaylistToWatch("p1", "Playlist", listOf("s1", "s2"))

        assertThat(batchId).isEqualTo("batch-123")
    }

    @Test
    fun `cancelPlaylistTransfer delegates to the coordinator`() {
        every { playlistWatchTransferCoordinator.cancelPlaylistTransfer("batch-123") } returns Unit
        val viewModel = buildViewModel()

        viewModel.cancelPlaylistTransfer("batch-123")

        io.mockk.verify { playlistWatchTransferCoordinator.cancelPlaylistTransfer("batch-123") }
    }

    @Test
    fun `activePlaylistBatchTransfer reflects the only non-terminal batch in the shared store`() = runTest {
        // stateIn(WhileSubscribed) only starts collecting the upstream flow once something
        // subscribes — reading .value without a collector never triggers it, so this needs an
        // actual subscriber (Turbine's test{}), not a bare .value read.
        val viewModel = buildViewModel()

        viewModel.activePlaylistBatchTransfer.test {
            assertThat(awaitItem()).isNull()

            watchTransferStateStore.markBatchStarted("b1", "p1", "Playlist", totalSongCount = 3)

            assertThat(awaitItem()?.batchId).isEqualTo("b1")
        }
    }

    @Test
    fun `refreshWatchAvailability updates isPixelPlayWatchAvailable from the sender`() = runTest {
        coEvery { wearPhoneTransferSender.isPixelPlayWatchAvailable() } returns true
        coEvery { wearPhoneTransferSender.refreshWatchLibraryState() } returns Result.success(Unit)
        val viewModel = buildViewModel()

        viewModel.refreshWatchAvailability()
        advanceUntilIdle()

        assertThat(viewModel.isPixelPlayWatchAvailable.value).isTrue()
    }
}
