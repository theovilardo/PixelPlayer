package com.theveloper.pixelplay.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The sweep that runs after every library scan, against the applied-artwork
 * store.
 *
 * Worth its own file because the two were written apart and only agree by
 * convention: the sweep deletes any file it can read a departed song's id out
 * of, and applied covers survive only because their names do not parse as one.
 * Nothing else states that, so nothing else would notice it stopping being true
 * -- and the cost of it stopping being true is every cover the user has chosen,
 * on the next scan, with no way back.
 */
class AlbumArtOrphanSweepTest {

    @Test
    fun sweep_keepsTheCoversOfSongsStillInTheLibrary() = runTest {
        withStore { context ->
            AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 1 }, listOf(11L, 12L))

            AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = setOf(11L, 12L))

            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNotNull()
            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 12L)).isNotNull()
            assertThat(coverFiles(context)).hasSize(1)
        }
    }

    @Test
    fun sweep_keepsTheCoverOfASongMissingFromOneScan() = runTest {
        withStore { context ->
            val cover = ByteArray(64) { 2 }
            AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L))

            // An unmounted card, a moved library, a re-index handing out new
            // ids: the song is out of the database and back a scan later. The
            // extracted cache pays a re-read for guessing wrong here; this pays
            // the only copy of the cover.
            val deleted = AlbumArtCacheManager.cleanOrphanedCacheFiles(
                context,
                validSongIds = setOf(99L),
                now = NOW
            )

            assertThat(deleted).isEqualTo(0)
            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)?.readBytes()).isEqualTo(cover)
        }
    }

    @Test
    fun sweep_dropsTheCoverOfASongGoneSinceLongBeforeTheLastScan() = runTest {
        withStore { context ->
            AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 2 }, listOf(11L))

            AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = setOf(99L), now = NOW)
            AlbumArtCacheManager.cleanOrphanedCacheFiles(
                context,
                validSongIds = setOf(99L),
                now = NOW + AlbumArtCacheManager.APPLIED_ORPHAN_GRACE_MS
            )

            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
            assertThat(coverFiles(context)).isEmpty()
        }
    }

    @Test
    fun sweep_startsTheClockAgainForASongThatCameBack() = runTest {
        withStore { context ->
            val cover = ByteArray(64) { 2 }
            AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L))

            AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = setOf(99L), now = NOW)
            // Back in the library, so what the first sweep noticed says nothing
            // about it any more.
            AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = setOf(11L), now = NOW + 1_000)
            AlbumArtCacheManager.cleanOrphanedCacheFiles(
                context,
                validSongIds = setOf(99L),
                now = NOW + AlbumArtCacheManager.APPLIED_ORPHAN_GRACE_MS + 2_000
            )

            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)?.readBytes()).isEqualTo(cover)
        }
    }

    @Test
    fun sweep_keepsACoverStillHeldByOneOfItsAlbumMates() = runTest {
        withStore { context ->
            val cover = ByteArray(64) { 3 }
            AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))

            AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = setOf(12L), now = NOW)
            AlbumArtCacheManager.cleanOrphanedCacheFiles(
                context,
                validSongIds = setOf(12L),
                now = NOW + AlbumArtCacheManager.APPLIED_ORPHAN_GRACE_MS
            )

            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)).isNull()
            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 12L)?.readBytes()).isEqualTo(cover)
        }
    }

    @Test
    fun sweep_refusesAnEmptySetRatherThanTreatingEverythingAsOrphaned() = runTest {
        withStore { context ->
            val cover = ByteArray(64) { 4 }
            AlbumArtUtils.saveAppliedAlbumArt(context, cover, listOf(11L, 12L))

            // A scan that ran without media permission, before a card mounted,
            // or across a failed migration hands over nothing. Trusting it would
            // delete the only copy of every cover in the library.
            val deleted = AlbumArtCacheManager.cleanOrphanedCacheFiles(context, validSongIds = emptySet())

            assertThat(deleted).isEqualTo(0)
            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 11L)?.readBytes()).isEqualTo(cover)
            assertThat(AlbumArtUtils.getAppliedAlbumArtFile(context, 12L)?.readBytes()).isEqualTo(cover)
        }
    }

    @Test
    fun sweep_doesNotReadASongIdOutOfACoverFilename() = runTest {
        withStore { context ->
            AlbumArtUtils.saveAppliedAlbumArt(context, ByteArray(64) { 5 }, listOf(11L))
            val coverName = coverFiles(context).single().name

            // The sweep deletes by song id, and the cover survives only because
            // its name yields none. Named so that one could be read out of it,
            // every applied cover in the library goes on the next scan.
            assertThat(AlbumArtCacheManager.extractSongIdFromFilename(coverName)).isNull()
        }
    }

    /** Any fixed point; the sweep only ever reads differences from it. */
    private val NOW = 1_700_000_000_000L

    private inline fun withStore(block: (Context) -> Unit) {
        val root = createTempDirectory("orphan-sweep-test").toFile()
        try {
            block(mockk<Context>(relaxed = true).also { every { it.filesDir } returns root })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun coverFiles(context: Context): List<File> =
        AlbumArtUtils.getAppliedArtDir(context)
            .listFiles { file: File -> file.isFile && file.name.startsWith("cover_") }
            ?.toList()
            .orEmpty()
}
