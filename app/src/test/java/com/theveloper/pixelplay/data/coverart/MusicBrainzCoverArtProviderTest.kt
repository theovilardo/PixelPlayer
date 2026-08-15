package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.coverartarchive.CoverArtArchiveApiService
import com.theveloper.pixelplay.data.network.coverartarchive.CoverArtArchiveImage
import com.theveloper.pixelplay.data.network.coverartarchive.CoverArtArchiveResponse
import com.theveloper.pixelplay.data.network.coverartarchive.CoverArtArchiveThumbnails
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzApiService
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzArtistCredit
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzRelease
import com.theveloper.pixelplay.data.network.coverartarchive.MusicBrainzReleaseSearchResponse
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicBrainzCoverArtProviderTest {

    @Test
    fun `buildQuery uses fielded lucene syntax`() {
        assertEquals(
            "release:\"Discovery\" AND artist:\"Daft Punk\"",
            MusicBrainzCoverArtProvider.buildQuery(album = "Discovery", artist = "Daft Punk")
        )
        assertEquals(
            "release:\"Discovery\"",
            MusicBrainzCoverArtProvider.buildQuery(album = "Discovery", artist = "")
        )
        assertNull(MusicBrainzCoverArtProvider.buildQuery(album = " ", artist = " "))
    }

    @Test
    fun `buildQuery strips lucene operators that would break the parse`() {
        val query = MusicBrainzCoverArtProvider.buildQuery(
            album = "Album: \"Deluxe\" (2011) +bonus",
            artist = "AC/DC"
        )

        assertEquals("release:\"Album Deluxe 2011 bonus\" AND artist:\"AC DC\"", query)
    }

    @Test
    fun `search returns only releases that actually have artwork`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()

        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse(
                releases = listOf(
                    release("mbid-with-art", "Discovery"),
                    release("mbid-without-art", "Discovery")
                )
            )
        coEvery { archive.getReleaseCoverArt("mbid-with-art") } returns CoverArtArchiveResponse(
            images = listOf(
                CoverArtArchiveImage(
                    isFront = true,
                    image = "https://coverartarchive.test/full.jpg",
                    thumbnails = CoverArtArchiveThumbnails(
                        size250 = "https://coverartarchive.test/250.jpg",
                        size1200 = "https://coverartarchive.test/1200.jpg"
                    )
                )
            )
        )
        coEvery { archive.getReleaseCoverArt("mbid-without-art") } throws IOException("404")

        val candidates = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24))

        val candidate = candidates.single()
        assertEquals("COVER_ART_ARCHIVE:mbid-with-art", candidate.id)
        assertEquals("https://coverartarchive.test/1200.jpg", candidate.imageUrl)
        assertEquals("https://coverartarchive.test/250.jpg", candidate.thumbnailUrl)
        assertEquals("Daft Punk", candidate.artistName)
    }

    @Test
    fun `search upgrades the archive http urls to https`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()

        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse(releases = listOf(release("mbid", "Discovery")))
        // The Archive embeds http:// links even though every one serves over TLS.
        coEvery { archive.getReleaseCoverArt("mbid") } returns CoverArtArchiveResponse(
            images = listOf(
                CoverArtArchiveImage(
                    isFront = true,
                    image = "http://coverartarchive.org/release/mbid/123.jpg",
                    thumbnails = CoverArtArchiveThumbnails(
                        small = "http://coverartarchive.org/release/mbid/123-250.jpg",
                        large = "http://coverartarchive.org/release/mbid/123-500.jpg"
                    )
                )
            )
        )

        val candidate = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24))
            .single()

        assertEquals("https://coverartarchive.org/release/mbid/123.jpg", candidate.imageUrl)
        assertEquals("https://coverartarchive.org/release/mbid/123-250.jpg", candidate.thumbnailUrl)
    }

    @Test
    fun `search reads the named thumbnail keys used by older archive entries`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()

        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse(releases = listOf(release("mbid", "Discovery")))
        coEvery { archive.getReleaseCoverArt("mbid") } returns CoverArtArchiveResponse(
            images = listOf(
                CoverArtArchiveImage(
                    isFront = true,
                    image = "https://coverartarchive.org/release/mbid/123.jpg",
                    // Older entries carry only small/large, no numeric keys.
                    thumbnails = CoverArtArchiveThumbnails(
                        small = "https://coverartarchive.org/release/mbid/123-250.jpg",
                        large = "https://coverartarchive.org/release/mbid/123-500.jpg"
                    )
                )
            )
        )

        val candidate = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24))
            .single()

        // The grid must not be handed the multi-megabyte original.
        assertEquals("https://coverartarchive.org/release/mbid/123-250.jpg", candidate.thumbnailUrl)
        assertEquals("https://coverartarchive.org/release/mbid/123.jpg", candidate.imageUrl)
    }

    @Test
    fun `search prefers the 1200px rendition when the entry has numeric keys`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()

        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse(releases = listOf(release("mbid", "Discovery")))
        coEvery { archive.getReleaseCoverArt("mbid") } returns CoverArtArchiveResponse(
            images = listOf(
                CoverArtArchiveImage(
                    isFront = true,
                    image = "https://coverartarchive.org/release/mbid/123.jpg",
                    thumbnails = CoverArtArchiveThumbnails(
                        size250 = "https://coverartarchive.org/release/mbid/123-250.jpg",
                        size500 = "https://coverartarchive.org/release/mbid/123-500.jpg",
                        size1200 = "https://coverartarchive.org/release/mbid/123-1200.jpg"
                    )
                )
            )
        )

        val candidate = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24))
            .single()

        assertEquals("https://coverartarchive.org/release/mbid/123-1200.jpg", candidate.imageUrl)
        assertEquals("https://coverartarchive.org/release/mbid/123-250.jpg", candidate.thumbnailUrl)
    }

    @Test
    fun `search reports no size because the archive serves whatever was uploaded`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()

        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse(releases = listOf(release("mbid", "Homework")))
        coEvery { archive.getReleaseCoverArt("mbid") } returns CoverArtArchiveResponse(
            images = listOf(
                CoverArtArchiveImage(
                    isFront = true,
                    image = "https://coverartarchive.test/full.jpg",
                    thumbnails = CoverArtArchiveThumbnails(size1200 = "https://coverartarchive.test/1200.jpg")
                )
            )
        )

        val candidate = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Homework", artist = "Daft Punk", limit = 24))
            .single()

        assertNull(candidate.size)
    }

    @Test
    fun `search returns nothing when musicbrainz has no releases`() = runTest {
        val musicBrainz = mockk<MusicBrainzApiService>()
        val archive = mockk<CoverArtArchiveApiService>()
        coEvery { musicBrainz.searchReleases(any(), any(), any()) } returns
            MusicBrainzReleaseSearchResponse()

        val candidates = MusicBrainzCoverArtProvider(musicBrainz, archive)
            .search(CoverArtSearchRequest(album = "Nothing", artist = "Nobody", limit = 24))

        assertTrue(candidates.isEmpty())
    }

    private fun release(id: String, title: String) = MusicBrainzRelease(
        id = id,
        title = title,
        artistCredit = listOf(MusicBrainzArtistCredit(name = "Daft Punk"))
    )
}
