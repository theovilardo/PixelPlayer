package com.theveloper.pixelplay.data.coverart

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.CoverArtSearchRepository
import com.theveloper.pixelplay.utils.AlbumArtUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutoCoverArtFetcherTest {

    private val context = mockk<Context>(relaxed = true)
    private val musicDao = mockk<MusicDao>(relaxed = true)
    private val searchRepository = mockk<CoverArtSearchRepository>(relaxed = true)
    private val appArtworkWriter = mockk<AppArtworkWriter>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(AlbumArtUtils)
        every { preferences.albumArtNotFoundIdsFlow } returns flowOf(emptySet())
        every { preferences.allowedDirectoriesFlow } returns flowOf(emptySet())
        // An empty blocked set is what makes DirectoryFilterUtils skip the
        // directory query entirely, which is the case every test but the
        // exclusion ones below wants.
        every { preferences.blockedDirectoriesFlow } returns flowOf(emptySet())
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AlbumArtUtils)
    }

    @Test
    fun `albums that already have artwork are left alone`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns
            mockk<File>(relaxed = true)

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(0, result.albumsChecked)
        coVerify(exactly = 0) { searchRepository.search(any(), any(), any()) }
    }

    @Test
    fun `a cloud album is left alone however art-less it looks from here`() = runTest {
        // A cloud track's cover lives on the server, so givenNoArtwork is the
        // truth as this pass can see it -- and the trap. Applying would replace
        // a cover the user is looking at, and the remove action skips them.
        val cloudAlbum = album(-1L, "Random Access Memories", "Daft Punk")
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns listOf(cloudAlbum)
        coEvery { musicDao.getSongsByAlbumIdOnce(cloudAlbum.id) } returns
            listOf(song(id = -9_000_000_000_001L, albumId = cloudAlbum.id))
        givenNoArtwork()

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(0, result.albumsChecked)
        coVerify(exactly = 0) { searchRepository.search(any(), any(), any()) }
        coVerify(exactly = 0) { appArtworkWriter.apply(any(), any(), any()) }
    }

    @Test
    fun `a weak match is skipped rather than applied unattended`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            listOf(candidate(score = AutoCoverArtFetcher.MIN_AUTO_SCORE - 0.05f))
        )

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(1, result.albumsChecked)
        assertEquals(0, result.coversApplied)
        assertEquals(1, result.notFound)
        coVerify(exactly = 0) { searchRepository.downloadCandidate(any()) }
    }

    @Test
    fun `a cover that downloads to nothing readable counts as a miss`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"), songCount = 3)
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            listOf(candidate(score = 0.95f))
        )
        val downloaded = mockk<Uri>(relaxed = true)
        every { downloaded.path } returns null
        coEvery { searchRepository.downloadCandidate(any()) } returns Result.success(downloaded)

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // Download happened, but nothing readable came back, so it counts as a miss.
        assertEquals(1, result.albumsChecked)
        assertEquals(0, result.coversApplied)
        coVerify(exactly = 1) { searchRepository.downloadCandidate(any()) }
    }

    @Test
    fun `a confident match is applied to every track through the app's artwork store`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"), songCount = 2)
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            listOf(candidate(score = 0.95f))
        )
        givenDownloadedCover()
        coEvery { appArtworkWriter.apply(any(), any(), any()) } returns true

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(1, result.coversApplied)
        // Never into the audio files: embedding needs the user's consent per
        // file, and a background pass has nobody to ask.
        coVerify { appArtworkWriter.apply(any(), listOf(101L, 102L), 1L) }
    }

    @Test
    fun `the search is told the bar a cover has to clear to be applied`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(emptyList())

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // Candidates below the bar are discarded here anyway, so the slow
        // catalog is worth skipping once a direct one has answered this well.
        coVerify {
            searchRepository.search(
                album = "Discovery",
                artist = "Daft Punk",
                confidentMatchScore = AutoCoverArtFetcher.MIN_AUTO_SCORE
            )
        }
    }

    @Test
    fun `albums already known to have no match are not queried again`() = runTest {
        givenLibrary(album(7L, "Nothing", "Nobody"))
        givenNoArtwork()
        every { preferences.albumArtNotFoundIdsFlow } returns flowOf(setOf(7L))

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(0, result.albumsChecked)
        coVerify(exactly = 0) { searchRepository.search(any(), any(), any()) }
    }

    @Test
    fun `a pass that hits its cap reports there is more to do`() = runTest {
        val albums = (1L..3L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(emptyList())

        val capped = fetcher().fetchMissingCovers(albumLimit = 2, perAlbumDelayMs = 0L)
        assertTrue(capped.reachedLimit, "two of three albums processed, so more remain")

        val uncapped = fetcher().fetchMissingCovers(albumLimit = 10, perAlbumDelayMs = 0L)
        assertFalse(uncapped.reachedLimit, "every album was processed")
    }

    @Test
    fun `an excluded folder is left out of the pass, not just the search`() = runTest {
        // Exclusion is the blocked set, not the allowed one -- an empty allow
        // list with something blocked still means "everything except that",
        // and the query matches parent directories, not the roots the user
        // configured, so those have to be expanded first.
        every { preferences.blockedDirectoriesFlow } returns flowOf(setOf("/Music/Skip"))
        coEvery { musicDao.getDistinctParentDirectories() } returns
            listOf("/Music/Keep", "/Music/Skip")
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns emptyList()

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        coVerify {
            musicDao.getAllAlbumsList(
                allowedParentDirs = listOf("/Music/Keep"),
                applyDirectoryFilter = true,
                minTracks = 1
            )
        }
    }

    @Test
    fun `nothing is excluded when nothing is blocked`() = runTest {
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns emptyList()

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // No blocked set means no filter at all -- an empty allowed set here is
        // not "allow nothing", the way it would be misread if the two flows
        // were conflated.
        coVerify {
            musicDao.getAllAlbumsList(
                allowedParentDirs = emptyList(),
                applyDirectoryFilter = false,
                minTracks = 1
            )
        }
        coVerify(exactly = 0) { musicDao.getDistinctParentDirectories() }
    }

    @Test
    fun `albums that found nothing are paced like the ones that did`() = runTest {
        val albums = (1L..3L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(emptyList())

        // The pass runs on Dispatchers.IO, so its waits are real rather than the
        // test scheduler's. Asserted as a floor, which no amount of slowness can
        // break -- only pacing that did not happen at all.
        val pacing = 40L
        val startedNs = System.nanoTime()
        fetcher().fetchMissingCovers(perAlbumDelayMs = pacing)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

        // An album with no match is the album that fell through to the slow
        // catalog to find that out, so it is the last one that should be allowed
        // to skip the wait. Three albums, two gaps between them.
        assertTrue(
            elapsedMs >= pacing * 2,
            "expected at least ${pacing * 2}ms of pacing, took ${elapsedMs}ms"
        )
    }

    @Test
    fun `a search that failed is not remembered as a dead end`() = runTest {
        val albums = (1L..3L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns
            CoverArtSearchOutcome(emptyList(), java.io.IOException("no route to host"))

        val outcome = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // The not-found list is never revisited on its own, so an album put on
        // it because the network was down would go uncovered for good.
        coVerify(exactly = 0) { preferences.addAlbumArtNotFoundIds(any()) }
        assertEquals(0, outcome.notFound)
    }

    @Test
    fun `a search one catalog never answered is not remembered as a dead end`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        givenNoArtwork()
        // The catalogs barely overlap: the one that timed out may be the only
        // one carrying this release, while another offers something unrelated
        // that scores nowhere near the bar. Read as "no cover exists", that
        // puts the album beyond every future pass.
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            candidates = listOf(candidate(score = 0.25f)),
            failure = java.io.IOException("timeout")
        )

        val outcome = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        coVerify(exactly = 0) { preferences.addAlbumArtNotFoundIds(any()) }
        assertEquals(0, outcome.notFound)
    }

    @Test
    fun `a catalog that failed does not hold back a match good enough to apply`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            candidates = listOf(candidate(score = 0.95f)),
            failure = java.io.IOException("timeout")
        )
        givenDownloadedCover()
        coEvery { appArtworkWriter.apply(any(), any(), any()) } returns true

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // What the missing catalog would have added is beside the point once a
        // cover has cleared the bar.
        assertEquals(1, result.coversApplied)
    }

    @Test
    fun `an album with nothing to identify it by is never searched for`() = runTest {
        // Scoring compares what it is given, so with no artist to compare
        // against the title alone decides -- and every "Greatest Hits" in every
        // catalog is then an exact match, applied with nobody watching.
        val unnamed = listOf(
            album(1L, "Greatest Hits", ""),
            album(2L, "Greatest Hits", "<unknown>"),
            album(3L, "", "Daft Punk")
        )
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns unnamed
        unnamed.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(0, result.albumsChecked)
        coVerify(exactly = 0) { searchRepository.search(any(), any(), any()) }
        // Not a dead end either: tagging the album is all it takes to make it
        // answerable, and the not-found list is never revisited on its own.
        coVerify(exactly = 0) { preferences.addAlbumArtNotFoundIds(any()) }
    }

    @Test
    fun `a run of failed searches stops the pass`() = runTest {
        val albums = (1L..20L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns
            CoverArtSearchOutcome(emptyList(), java.io.IOException("no route to host"))

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // Nothing is answering, so there is nothing to learn by asking about
        // every remaining album in the library.
        coVerify(atMost = 6) { searchRepository.search(any(), any(), any()) }
    }

    @Test
    fun `a cover the store failed to write is not counted as applied`() = runTest {
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            listOf(candidate(score = 0.95f))
        )
        givenDownloadedCover()
        // A full disk or a failed write: the download and the search both
        // succeeded, but nothing was actually stored.
        coEvery { appArtworkWriter.apply(any(), any(), any()) } returns false

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // Reported as applied here is exactly the failure that let a pass
        // chain into re-fetching the same albums forever: each one believed
        // it had made progress when nothing had changed.
        assertEquals(0, result.coversApplied)
    }

    @Test
    fun `a run of failed writes stops the pass the same way a run of failed searches does`() = runTest {
        val albums = (1L..20L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(
            listOf(candidate(score = 0.95f))
        )
        givenDownloadedCover()
        coEvery { appArtworkWriter.apply(any(), any(), any()) } returns false

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // A search succeeding does not reset the count of a different kind of
        // failure -- the store failing every time is still a run of failures,
        // and there is nothing to learn by working through the rest of the
        // library asking the same store to fail the same way.
        coVerify(atMost = 6) { searchRepository.search(any(), any(), any()) }
    }

    @Test
    fun `dead ends are remembered as the pass goes, not only when it finishes`() = runTest {
        val albums = (1L..12L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(emptyList())

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // A pass WorkManager stops would otherwise record nothing and be
        // re-queried from the start. Batched because each write rewrites the
        // whole preferences file: twelve dead ends is two batches plus a flush.
        coVerify(exactly = 3) { preferences.addAlbumArtNotFoundIds(any()) }
    }

    @Test
    fun `a pass being taken back stops at the next album`() = runTest {
        val albums = (1L..5L).map { id -> album(id, "Album $id", "Artist $id") }
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns albums
        albums.forEach { album ->
            coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
                listOf(song(id = album.id * 100, albumId = album.id))
        }
        givenNoArtwork()
        coEvery { searchRepository.search(any(), any(), any()) } returns CoverArtSearchOutcome(emptyList())

        // Flipped from under the pass once two albums have been searched, so a
        // flag consulted only before the loop, or only after it, both fail here.
        var searched = 0
        coEvery { searchRepository.search(any(), any(), any()) } answers {
            searched++
            CoverArtSearchOutcome(emptyList())
        }

        val result = fetcher().fetchMissingCovers(
            isStopped = { searched >= 2 },
            perAlbumDelayMs = 0L
        )

        assertEquals(2, result.albumsChecked)

        val secondResult = fetcher().fetchMissingCovers(isStopped = { true }, perAlbumDelayMs = 0L)
        assertEquals(0, secondResult.albumsChecked)
        assertFalse(secondResult.reachedLimit)
    }

    @Test
    fun `dead ends no album answers to any more are dropped`() = runTest {
        every { preferences.albumArtNotFoundIdsFlow } returns flowOf(setOf(1L, 404L))
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns
            mockk<File>(relaxed = true)

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // 404 is gone from the library, 1 is still there, so the set shrinks to
        // the size of the problem rather than the install's history.
        coVerify(exactly = 1) { preferences.setAlbumArtNotFoundIds(setOf(1L)) }
    }

    @Test
    fun `a filtered library leaves the remembered dead ends alone`() = runTest {
        // The listing speaks only for the allowed folders, so albums outside
        // them are absent while still in the library. Pruning against it would
        // send the next pass back to the catalogs for all of them.
        every { preferences.albumArtNotFoundIdsFlow } returns flowOf(setOf(1L, 404L))
        every { preferences.blockedDirectoriesFlow } returns flowOf(setOf("/Music/Skip"))
        coEvery { musicDao.getDistinctParentDirectories() } returns
            listOf("/Music/Keep", "/Music/Skip")
        givenLibrary(album(1L, "Discovery", "Daft Punk"))
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns
            mockk<File>(relaxed = true)

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        // 404 lives in the excluded folder, so its absence here says nothing.
        coVerify(exactly = 0) { preferences.setAlbumArtNotFoundIds(any()) }
    }

    @Test
    fun `an empty library leaves the remembered dead ends alone`() = runTest {
        // Enabling the setting queues a pass without waiting for a sync, and the
        // directory filter can exclude everything, so an empty listing is not
        // evidence that every remembered album is gone. Wiping the set here
        // would send the next pass back to the catalogs for every one of them.
        every { preferences.albumArtNotFoundIdsFlow } returns flowOf(setOf(1L, 2L, 3L))
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns emptyList()

        fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        coVerify(exactly = 0) { preferences.setAlbumArtNotFoundIds(any()) }
    }

    @Test
    fun `an album is left alone when any track still has its own artwork`() = runTest {
        // The cover goes on the whole album and an applied cover outranks
        // extracted art, so judging by track one would cost every other track
        // the artwork it already carries.
        val album = album(1L, "Greatest Hits", "Various")
        givenLibrary(album, songCount = 3)
        val firstTrackId = album.id * 100 + 1
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } answers {
            if (secondArg<Long>() == firstTrackId) null else mockk<File>(relaxed = true)
        }

        val result = fetcher().fetchMissingCovers(perAlbumDelayMs = 0L)

        assertEquals(0, result.albumsChecked)
        coVerify(exactly = 0) { searchRepository.search(any(), any(), any()) }
        coVerify(exactly = 0) { appArtworkWriter.apply(any(), any(), any()) }
    }

    private fun fetcher() = AutoCoverArtFetcher(
        context = context,
        musicDao = musicDao,
        coverArtSearchRepository = searchRepository,
        appArtworkWriter = appArtworkWriter,
        userPreferencesRepository = preferences
    )

    private fun givenLibrary(album: AlbumEntity, songCount: Int = 1) {
        coEvery { musicDao.getAllAlbumsList(any(), any(), any()) } returns listOf(album)
        coEvery { musicDao.getSongsByAlbumIdOnce(album.id) } returns
            (1..songCount).map { index -> song(id = album.id * 100 + index, albumId = album.id) }
    }

    private fun givenNoArtwork() {
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns null
    }

    /** A cover that downloads to a readable file, as the happy path does. */
    private fun givenDownloadedCover() {
        val file = File.createTempFile("cover", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val downloaded = mockk<Uri>(relaxed = true)
        every { downloaded.path } returns file.absolutePath
        coEvery { searchRepository.downloadCandidate(any()) } returns Result.success(downloaded)
    }

    private fun album(id: Long, title: String, artist: String) = AlbumEntity(
        id = id,
        title = title,
        artistName = artist,
        artistId = 1L,
        albumArtUriString = null,
        songCount = 1,
        dateAdded = 0L,
        year = 2001
    )

    private fun song(id: Long, albumId: Long) = mockk<SongEntity>(relaxed = true).also {
        every { it.id } returns id
        every { it.filePath } returns "/music/$albumId/$id.mp3"
    }

    private fun candidate(score: Float) = CoverArtCandidate(
        id = "DEEZER:1",
        albumTitle = "Discovery",
        artistName = "Daft Punk",
        thumbnailUrl = "https://example.test/250.jpg",
        imageUrl = "https://example.test/1000.jpg",
        source = CoverArtSource.DEEZER,
        score = score
    )
}
