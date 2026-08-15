package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.itunes.ItunesAlbum
import com.theveloper.pixelplay.data.network.itunes.ItunesApiService
import com.theveloper.pixelplay.data.network.itunes.ItunesSearchResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItunesCoverArtProviderTest {

    @Test
    fun `buildTerm joins artist and album the way a person would type it`() {
        assertEquals(
            "Daft Punk Discovery",
            ItunesCoverArtProvider.buildTerm(album = "Discovery", artist = "Daft Punk")
        )
        assertEquals("Discovery", ItunesCoverArtProvider.buildTerm(album = "Discovery", artist = " "))
        assertNull(ItunesCoverArtProvider.buildTerm(album = " ", artist = ""))
    }

    @Test
    fun `resizeArtwork rewrites the size segment`() {
        val url = "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/source/100x100bb.jpg"

        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/source/1200x1200bb.jpg",
            ItunesCoverArtProvider.resizeArtwork(url, 1200)
        )
    }

    @Test
    fun `resizeArtwork leaves urls without a size segment untouched`() {
        val url = "https://example.test/cover.jpg"

        assertEquals(url, ItunesCoverArtProvider.resizeArtwork(url, 1200))
    }

    @Test
    fun `search maps albums to candidates with a nominal size`() = runTest {
        val api = mockk<ItunesApiService>()
        coEvery { api.searchAlbums(any(), any(), any(), any()) } returns ItunesSearchResponse(
            resultCount = 1,
            results = listOf(
                ItunesAlbum(
                    collectionId = 697194953L,
                    collectionName = "Random Access Memories",
                    artistName = "Daft Punk",
                    artworkUrl100 = "https://example.test/source/100x100bb.jpg"
                )
            )
        )

        val candidate = ItunesCoverArtProvider(api)
            .search(CoverArtSearchRequest(album = "Random Access Memories", artist = "Daft Punk", limit = 24))
            .single()

        assertEquals("ITUNES:697194953", candidate.id)
        assertEquals("https://example.test/source/1200x1200bb.jpg", candidate.imageUrl)
        assertEquals("https://example.test/source/300x300bb.jpg", candidate.thumbnailUrl)
        assertEquals(CoverArtSize(width = 1200, height = 1200), candidate.size)
        assertTrue(candidate.size?.measured == false)
    }

    @Test
    fun `search drops results without artwork or title`() = runTest {
        val api = mockk<ItunesApiService>()
        coEvery { api.searchAlbums(any(), any(), any(), any()) } returns ItunesSearchResponse(
            results = listOf(
                ItunesAlbum(collectionId = 1L, collectionName = "No Artwork"),
                ItunesAlbum(collectionId = 2L, artworkUrl100 = "https://example.test/source/100x100bb.jpg")
            )
        )

        val candidates = ItunesCoverArtProvider(api)
            .search(CoverArtSearchRequest(album = "Whatever", artist = "Someone", limit = 24))

        assertTrue(candidates.isEmpty())
    }
}
