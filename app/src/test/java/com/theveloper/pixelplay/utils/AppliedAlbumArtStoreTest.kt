package com.theveloper.pixelplay.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test

/**
 * Exercises the applied-artwork store against a real filesystem, because what
 * is worth pinning is what the files end up being: a cover every track of an
 * album resolves to, stored once, with tracks that stay independent of each
 * other and nothing left behind when one is replaced.
 */
class AppliedAlbumArtStoreTest {

    @Test
    fun saveAppliedAlbumArt_givesEveryTrackOfTheAlbumTheCover() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val cover = ByteArray(64) { it.toByte() }

        AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L, 13L))

        listOf(11L, 12L, 13L).forEach { songId ->
            val file = AlbumArtUtils.getAppliedAlbumArtFile(context, songId)
            assertThat(file).isNotNull()
            assertThat(file!!.readBytes()).isEqualTo(cover)
        }
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_storesOneImageForTheWholeAlbum() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)

        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 3 }, (1L..20L).toList())

        assertThat(coverFiles(context)).hasSize(1)
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_replacingOneTrackLeavesTheRestOfTheAlbumAlone() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val albumCover = ByteArray(64) { 1 }
        val trackCover = ByteArray(64) { 2 }
        AlbumArtUtils.saveAppliedAlbumArt(context, albumCover, listOf(11L, 12L, 13L))

        AlbumArtUtils.saveAppliedAlbumArt(context, trackCover, listOf(12L))

        assertThat(appliedBytes(context, 12L)).isEqualTo(trackCover)
        assertThat(appliedBytes(context, 11L)).isEqualTo(albumCover)
        assertThat(appliedBytes(context, 13L)).isEqualTo(albumCover)
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_replacingTheAlbumsCoverReplacesItForEveryTrack() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 1 }, listOf(11L, 12L))
        val replacement = ByteArray(64) { 9 }

        AlbumArtUtils.saveAppliedAlbumArt(context, replacement, listOf(11L, 12L))

        listOf(11L, 12L).forEach { songId ->
            assertThat(appliedBytes(context, songId)).isEqualTo(replacement)
        }
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_replacingTheAlbumsCoverLeavesTheOldImageBehindNowhere() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 1 }, listOf(11L, 12L))

        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 9 }, listOf(11L, 12L))

        assertThat(coverFiles(context)).hasSize(1)
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_reapplyingTheSameCoverRewritesTheSameFile() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val cover = ByteArray(64) { 4 }
        AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))
        val firstNames = coverFiles(context).map { it.name }

        AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))

        assertThat(coverFiles(context).map { it.name }).isEqualTo(firstNames)
        root.deleteRecursively()
    }

    @Test
    fun clearCacheForSong_leavesAppliedArtWhereItIs() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val cover = ByteArray(64) { 7 }
        AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))

        // Every metadata save invalidates artwork and lands here. Everything it
        // drops can be read back out of the audio file -- an applied cover
        // cannot, so it must survive an edit that had nothing to do with it.
        AlbumArtUtils.clearCacheForSong(context, 11L)

        assertThat(appliedBytes(context, 11L)).isEqualTo(cover)
        assertThat(appliedBytes(context, 12L)).isEqualTo(cover)
        assertThat(coverFiles(context)).hasSize(1)
        root.deleteRecursively()
    }

    @Test
    fun clearAppliedArtForSong_dropsOneTrackWithoutTouchingTheRestOfTheAlbum() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val cover = ByteArray(64) { 7 }
        AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))

        AlbumArtUtils.clearAppliedArtForSong(context, 11L)

        assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
        assertThat(appliedBytes(context, 12L)).isEqualTo(cover)
        root.deleteRecursively()
    }

    @Test
    fun clearAppliedArtForSong_removesTheImageOnceTheLastTrackIsGone() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 7 }, listOf(11L, 12L))

        // The collection is handed off rather than run on the caller's thread,
        // so the image goes when the sweep gets to it, not when the call returns.
        AlbumArtUtils.clearAppliedArtForSong(context, 11L)
        assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
        assertThat(appliedBytes(context, 12L)).isNotNull()

        AlbumArtUtils.clearAppliedArtForSong(context, 12L)
        assertThat(eventually { coverFiles(context).isEmpty() }).isTrue()
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_writesNothingForEmptyBytes() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)

        val written = AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(0), listOf(11L))

        assertThat(written).isNull()
        assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
        root.deleteRecursively()
    }

    @Test
    fun deleteUnreferencedAppliedCovers_dropsAPointerWhoseImageIsGone() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 3 }, listOf(11L))
        coverFiles(context).forEach { it.delete() }

        AlbumArtUtils.deleteUnreferencedAppliedCovers(context)

        // Left in place, the pointer would adopt whatever was written under that
        // key next -- which is the same cover for anyone applying the same image.
        val pointers = AlbumArtUtils.getAppliedArtDir(context)
            .listFiles { file: File -> file.name.endsWith(".ref") }
            .orEmpty()
        assertThat(pointers).isEmpty()
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_keepsTracksApartWhenTheyBelongToNoCommonAlbum() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        val first = ByteArray(64) { 1 }
        val second = ByteArray(64) { 2 }

        AlbumArtUtils.saveAppliedAlbumArt(context, first, listOf(11L))
        AlbumArtUtils.saveAppliedAlbumArt(context, second, listOf(12L))

        assertThat(appliedBytes(context, 11L)).isEqualTo(first)
        assertThat(appliedBytes(context, 12L)).isEqualTo(second)
        root.deleteRecursively()
    }

    @Test
    fun getAppliedAlbumArtFile_isNullWhenTheImageWentMissingUnderTheSong() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)
        AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 5 }, listOf(11L))

        coverFiles(context).forEach { it.delete() }

        assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
        root.deleteRecursively()
    }

    @Test
    fun saveAppliedAlbumArt_writesNothingForAnEmptySelection() {
        val root = createTempDirectory("applied-art-test").toFile()
        val context = contextWith(root)

        val written = AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(8), emptyList())

        assertThat(written).isNull()
        assertThat(AlbumArtUtils.getAppliedArtDir(context).listFiles()).isEmpty()
        root.deleteRecursively()
    }

    /** Polls [condition] briefly, for the collection that runs off-thread. */
    private fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun appliedBytes(context: Context, songId: Long): ByteArray? =
        AlbumArtUtils.getAppliedAlbumArtFile(context, songId)?.readBytes()

    private fun coverFiles(context: Context): List<File> =
        AlbumArtUtils.getAppliedArtDir(context)
            .listFiles { file: File -> file.isFile && file.name.startsWith("cover_") }
            ?.toList()
            .orEmpty()

    private fun contextWith(filesDir: File): Context = mockk<Context>(relaxed = true).also {
        every { it.filesDir } returns filesDir
    }
}
