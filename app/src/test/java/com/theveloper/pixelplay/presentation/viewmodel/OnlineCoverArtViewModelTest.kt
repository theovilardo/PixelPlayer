package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.coverart.AlbumArtStorage
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtSearchUpdate
import com.theveloper.pixelplay.data.coverart.CoverArtSize
import com.theveloper.pixelplay.data.coverart.CoverArtSource
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.CoverArtSearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Covers the concurrency bookkeeping in [OnlineCoverArtViewModel] that a snapshot of its UI
 * state cannot exercise on its own: cancelling stale work when the picker reopens, and the
 * probe / size-merge logic that keeps a streaming search from clobbering results already on
 * screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class OnlineCoverArtViewModelTest {

    private val repository: CoverArtSearchRepository = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true) {
        every { albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)
    }

    private fun viewModel() = OnlineCoverArtViewModel(repository, preferences)

    private fun candidate(id: String, size: CoverArtSize? = null) = CoverArtCandidate(
        id = id,
        albumTitle = "Album",
        artistName = "Artist",
        thumbnailUrl = "https://example.com/$id-thumb.jpg",
        imageUrl = "https://example.com/$id.jpg",
        source = CoverArtSource.ITUNES,
        score = 0.9f,
        size = size
    )

    @Test
    fun `where a cover will be kept survives the picker opening for another album`() = runTest {
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        coEvery { repository.isWebImageSearchAvailable() } returns false
        val viewModel = viewModel()

        viewModel.start(album = "Album", artist = "Artist")
        advanceUntilIdle()
        // Opening for a different album replaces the search state wholesale, so
        // a setting held in it went back to its default and stayed there -- a
        // preference only re-emits when it changes.
        viewModel.start(album = "Other", artist = "Somebody")
        advanceUntilIdle()

        assertEquals(AlbumArtStorage.AUDIO_FILES, viewModel.albumArtStorage.value)
    }

    @Test
    fun `where a cover will be kept is not claimed before the setting has been read`() = runTest {
        every { preferences.albumArtStorageFlow } returns flow { awaitCancellation() }

        // A default would be a statement about whether the user's own files are
        // about to be written to, made before anything was read.
        assertNull(viewModel().albumArtStorage.value)
    }

    @Test
    fun `starting the same album and artist again drops a stale download without disturbing the results on screen`() =
        runTest {
            val found = candidate("c1")
            every { repository.searchStreaming("Album", "Artist") } returns
                flowOf(CoverArtSearchUpdate(candidates = listOf(found), statuses = emptyList(), isComplete = true))
            coEvery { repository.isWebImageSearchAvailable() } returns false
            coEvery { repository.probeSize(any()) } returns null
            coEvery { repository.downloadCandidate(found) } coAnswers { awaitCancellation() }

            val vm = viewModel()
            vm.start("Album", "Artist")
            advanceUntilIdle()
            vm.onCandidateSelected(found)
            advanceUntilIdle()
            assertEquals("c1", vm.uiState.value.downloadingCandidateId)

            // Reopening the picker for the same song: the download that was still
            // in flight belongs to a sheet the user already left, not to this one.
            vm.start("Album", "Artist")
            advanceUntilIdle()

            assertNull(vm.uiState.value.downloadingCandidateId)
            assertNull(vm.uiState.value.downloadedUri)
            // The search itself was not repeated -- reopening for the same song
            // reuses what is already on screen rather than re-querying the catalogs.
            assertEquals(listOf(found), vm.uiState.value.candidates)
            coVerify(exactly = 1) { repository.searchStreaming("Album", "Artist") }
        }

    @Test
    fun `starting a different album cancels the search still running for the previous one`() = runTest {
        var firstSearchCancelled = false
        every { repository.searchStreaming("Album A", "Artist A") } returns flow<CoverArtSearchUpdate> {
            try {
                awaitCancellation()
            } finally {
                firstSearchCancelled = true
            }
        }
        val foundB = candidate("b1")
        every { repository.searchStreaming("Album B", "Artist B") } returns
            flowOf(CoverArtSearchUpdate(candidates = listOf(foundB), statuses = emptyList(), isComplete = true))
        coEvery { repository.isWebImageSearchAvailable() } returns false
        coEvery { repository.probeSize(any()) } returns null

        val vm = viewModel()
        vm.start("Album A", "Artist A")
        advanceUntilIdle()

        vm.start("Album B", "Artist B")
        advanceUntilIdle()

        assertTrue(firstSearchCancelled)
        assertEquals(listOf(foundB), vm.uiState.value.candidates)
    }

    @Test
    fun `a candidate is only probed once even when it reappears in a later snapshot`() = runTest {
        val found = candidate("c1")
        val updates = Channel<CoverArtSearchUpdate>(Channel.UNLIMITED)
        every { repository.searchStreaming("Album", "Artist") } returns updates.consumeAsFlow()
        coEvery { repository.isWebImageSearchAvailable() } returns false
        coEvery { repository.probeSize(found) } returns CoverArtSize(500, 500, measured = true)

        val vm = viewModel()
        vm.start("Album", "Artist")
        updates.send(CoverArtSearchUpdate(candidates = listOf(found), statuses = emptyList(), isComplete = false))
        advanceUntilIdle()
        updates.send(CoverArtSearchUpdate(candidates = listOf(found), statuses = emptyList(), isComplete = true))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.probeSize(found) }
    }

    @Test
    fun `a later snapshot does not erase a size already measured for a candidate on screen`() = runTest {
        val nominal = candidate("c1")
        val measured = CoverArtSize(500, 500, measured = true)
        val updates = Channel<CoverArtSearchUpdate>(Channel.UNLIMITED)
        every { repository.searchStreaming("Album", "Artist") } returns updates.consumeAsFlow()
        coEvery { repository.isWebImageSearchAvailable() } returns false
        coEvery { repository.probeSize(nominal) } returns measured

        val vm = viewModel()
        vm.start("Album", "Artist")
        updates.send(CoverArtSearchUpdate(candidates = listOf(nominal), statuses = emptyList(), isComplete = false))
        advanceUntilIdle()
        assertEquals(measured, vm.uiState.value.candidates.single().size)

        // The catalog answers again with the same candidate, reporting no size of
        // its own -- the merge must keep the size already measured for it.
        updates.send(CoverArtSearchUpdate(candidates = listOf(nominal), statuses = emptyList(), isComplete = true))
        advanceUntilIdle()

        assertEquals(measured, vm.uiState.value.candidates.single().size)
    }

    @Test
    fun `a web search that failed can be asked for again`() = runTest {
        // webSearched hides the action once used, so marking a failure as used
        // spent the user's one offer of it on a dropped connection -- a request
        // that was never billed against their allowance anyway.
        every { repository.searchStreaming("Album", "Artist") } returns
            flowOf(CoverArtSearchUpdate(candidates = emptyList(), statuses = emptyList(), isComplete = true))
        coEvery { repository.isWebImageSearchAvailable() } returns true
        coEvery { repository.searchWebImages(album = "Album", artist = "Artist") } returns
            Result.failure(java.io.IOException("no connectivity"))

        val vm = viewModel()
        vm.start("Album", "Artist")
        advanceUntilIdle()

        vm.searchWeb()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.webSearched)
        assertFalse(vm.uiState.value.isSearchingWeb)
        assertNotNull(vm.uiState.value.errorRes)
    }

    @Test
    fun `a web search that succeeded is not offered again`() = runTest {
        every { repository.searchStreaming("Album", "Artist") } returns
            flowOf(CoverArtSearchUpdate(candidates = emptyList(), statuses = emptyList(), isComplete = true))
        coEvery { repository.isWebImageSearchAvailable() } returns true
        coEvery { repository.searchWebImages(album = "Album", artist = "Artist") } returns
            Result.success(listOf(candidate("w1")))

        val vm = viewModel()
        vm.start("Album", "Artist")
        advanceUntilIdle()

        vm.searchWeb()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.webSearched)
        assertNull(vm.uiState.value.errorRes)
    }
}
