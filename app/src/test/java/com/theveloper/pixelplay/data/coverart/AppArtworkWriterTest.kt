package com.theveloper.pixelplay.data.coverart

import android.content.Context
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.media.ImageCacheManager
import com.theveloper.pixelplay.utils.AlbumArtUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppArtworkWriterTest {

    private val context = mockk<Context>(relaxed = true)
    private val musicDao = mockk<MusicDao>(relaxed = true)
    private val albumArtThemeDao = mockk<AlbumArtThemeDao>(relaxed = true)
    private val imageCacheManager = mockk<ImageCacheManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(AlbumArtUtils)
        every { AlbumArtUtils.boundArtworkForStorage(any()) } answers { firstArg() }
        every { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), any()) } returns mockk<File>(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkObject(AlbumArtUtils)

    @Test
    fun `an applied cover goes to the store the eviction sweep never touches`() = runTest {
        writer().apply(bytes = byteArrayOf(1, 2, 3), songIds = listOf(11L, 12L), albumId = 5L)

        // The extracted cache is re-derivable and gets swept once it grows past
        // its limit. An applied cover has no second copy to re-derive from.
        verify { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), listOf(11L, 12L)) }
        verify(exactly = 0) { AlbumArtUtils.saveAlbumArtToCache(any(), any(), any()) }
    }

    @Test
    fun `a cloud track is never pointed at an applied cover`() = runTest {
        // Cloud tracks have a negative id and no local store, so writing their
        // row would trade a working remote cover for one pointing at nothing.
        // Both callers filter; the invariant belongs here all the same.
        val stored = writer().apply(bytes = byteArrayOf(1, 2, 3), songIds = listOf(-9_000_000_000_001L))

        assertEquals(false, stored)
        verify(exactly = 0) { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), any()) }
        coVerify(exactly = 0) { musicDao.updateSongAlbumArt(any(), any()) }
    }

    @Test
    fun `a mixed album only claims the tracks it can actually write`() = runTest {
        val stored = writer().apply(
            bytes = byteArrayOf(1, 2, 3),
            songIds = listOf(11L, -9_000_000_000_001L),
            albumId = 5L
        )

        assertEquals(true, stored)
        verify { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), listOf(11L)) }
        coVerify(exactly = 0) { musicDao.updateSongAlbumArt(-9_000_000_000_001L, any()) }
    }

    @Test
    fun `the album is stored in one pass rather than once per track`() = runTest {
        writer().apply(bytes = byteArrayOf(1, 2, 3), songIds = (1L..10L).toList())

        // The store keeps a single copy of the cover for the whole album, which
        // it can only do when it is handed the album rather than a track.
        verify(exactly = 1) { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), any()) }
    }

    @Test
    fun `the image is decoded and re-encoded once for the whole album`() = runTest {
        writer().apply(bytes = byteArrayOf(1, 2, 3), songIds = (1L..10L).toList())

        // Bounding is a full bitmap decode, scale and WebP encode, and every
        // track of an album is being given the same image.
        verify(exactly = 1) { AlbumArtUtils.boundArtworkForStorage(any()) }
    }

    @Test
    fun `rows are pointed at the cover and stale palettes dropped`() = runTest {
        val purged = slot<List<String>>()
        albumHolds(5L, 11L, 12L)

        writer().apply(bytes = byteArrayOf(1), songIds = listOf(11L, 12L), albumId = 5L)

        coVerify { musicDao.updateSongAlbumArt(11L, "pixelplay_local_art://song/11") }
        coVerify { musicDao.updateSongAlbumArt(12L, "pixelplay_local_art://song/12") }
        coVerify { musicDao.updateAlbumArt(5L, "pixelplay_local_art://song/11") }
        // The artwork URI does not change when a cover is replaced, so a palette
        // keyed by it would otherwise survive as the previous cover's colours.
        coVerify { albumArtThemeDao.deleteThemesByUris(capture(purged)) }
        assertEquals(
            listOf("pixelplay_local_art://song/11", "pixelplay_local_art://song/12"),
            purged.captured
        )
    }

    @Test
    fun `the rendered caches are dropped without deleting the cover itself`() = runTest {
        writer().apply(bytes = byteArrayOf(1), songIds = listOf(11L))

        verify { imageCacheManager.invalidateRenderedCoverArt("pixelplay_local_art://song/11") }
        verify(exactly = 0) { imageCacheManager.invalidateCoverArtCaches(any()) }
    }

    @Test
    fun `the album row follows only when every one of its tracks is covered`() = runTest {
        albumHolds(5L, 11L, 12L, 13L)

        writer().apply(bytes = byteArrayOf(1), songIds = listOf(11L, 12L), albumId = 5L)

        // Two tracks of three is not the album getting a new cover, and claiming
        // the row would change the album everywhere it is shown.
        coVerify(exactly = 0) { musicDao.updateAlbumArt(any(), any()) }
        coVerify { musicDao.updateSongAlbumArt(11L, "pixelplay_local_art://song/11") }
    }

    @Test
    fun `a cloud track in the album does not hold the album row back`() = runTest {
        albumHolds(5L, 11L, 12L, -9_000_000_000_001L)

        writer().apply(bytes = byteArrayOf(1), songIds = listOf(11L, 12L), albumId = 5L)

        // The cloud track is one this writer will never give a cover to, so
        // counting it would leave the album permanently short of its own track
        // list -- the row keeping its placeholder while every local track of
        // the album draws the new cover.
        coVerify { musicDao.updateAlbumArt(5L, "pixelplay_local_art://song/11") }
    }

    @Test
    fun `removing a cover leaves cloud tracks and their album row alone`() = runTest {
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns null
        albumHolds(5L, 11L, 12L)

        writer().removeApplied(songIds = listOf(-9_000_000_000_001L), albumId = 5L)

        // A cloud track never had an applied cover to take back, and letting it
        // answer for the album would null out a row covering tracks this call
        // never touched.
        verify(exactly = 0) { AlbumArtUtils.clearAppliedArtForSong(any(), any()) }
        coVerify(exactly = 0) { musicDao.updateSongAlbumArt(any(), any()) }
        coVerify(exactly = 0) { musicDao.updateAlbumArt(any(), any()) }
    }

    @Test
    fun `nothing is written for an empty selection`() = runTest {
        writer().apply(bytes = byteArrayOf(1), songIds = emptyList(), albumId = 5L)

        verify(exactly = 0) { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), any()) }
        coVerify(exactly = 0) { musicDao.updateAlbumArt(any(), any()) }
    }

    @Test
    fun `removing an applied cover leaves the audio files alone`() = runTest {
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns null
        albumHolds(5L, 11L, 12L)

        writer().removeApplied(songIds = listOf(11L, 12L), albumId = 5L)

        verify { AlbumArtUtils.clearAppliedArtForSong(any(), 11L) }
        verify { AlbumArtUtils.clearAppliedArtForSong(any(), 12L) }
        // Undoing an apply must not reach the tag editor: the cover never went
        // into the file, so nothing there has to change to take it back.
        verify(exactly = 0) { AlbumArtUtils.saveAppliedAlbumArt(any(), any(), any()) }
    }

    @Test
    fun `a song with nothing left underneath is pointed at no artwork`() = runTest {
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns null
        albumHolds(5L, 11L)

        writer().removeApplied(songIds = listOf(11L), albumId = 5L)

        // Left pointing at the local artwork scheme it would resolve to a blank
        // rather than to the placeholder the rest of the app draws.
        coVerify { musicDao.updateSongAlbumArt(11L, null) }
        coVerify { musicDao.updateAlbumArt(5L, null) }
    }

    @Test
    fun `art still in the file comes back into view`() = runTest {
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit
        every { AlbumArtUtils.ensureAlbumArtCachedFile(any(), any(), any(), any()) } returns
            mockk<File>(relaxed = true)
        albumHolds(5L, 11L)

        writer().removeApplied(songIds = listOf(11L), albumId = 5L)

        coVerify { musicDao.updateSongAlbumArt(11L, "pixelplay_local_art://song/11") }
    }

    private fun albumHolds(albumId: Long, vararg songIds: Long) {
        coEvery { musicDao.getSongsByAlbumIdOnce(albumId) } returns songIds.map { songId ->
            mockk<SongEntity>(relaxed = true).also { every { it.id } returns songId }
        }
    }

    private fun writer() = AppArtworkWriter(
        context = context,
        musicDao = musicDao,
        albumArtThemeDao = albumArtThemeDao,
        imageCacheManager = imageCacheManager
    )
}
