package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.deezer.DeezerAlbum
import com.theveloper.pixelplay.data.network.deezer.DeezerAlbumArtist
import com.theveloper.pixelplay.data.network.deezer.DeezerAlbumSearchResponse
import com.theveloper.pixelplay.data.network.deezer.DeezerApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeezerCoverArtProviderTest {

    @Test
    fun `buildQueries tries the advanced syntax before free text`() {
        val queries = DeezerCoverArtProvider.buildQueries(
            album = "Random Access Memories",
            artist = "Daft Punk"
        )

        assertEquals(
            listOf(
                "artist:\"Daft Punk\" album:\"Random Access Memories\"",
                "Daft Punk Random Access Memories"
            ),
            queries
        )
    }

    @Test
    fun `buildQueries handles a missing artist`() {
        val queries = DeezerCoverArtProvider.buildQueries(album = "Homework", artist = "  ")

        assertEquals(listOf("album:\"Homework\"", "Homework"), queries)
    }

    @Test
    fun `buildQueries returns nothing when there is nothing to search for`() {
        assertTrue(DeezerCoverArtProvider.buildQueries(album = " ", artist = "").isEmpty())
    }

    @Test
    fun `search falls back to free text when the advanced query finds nothing`() = runTest {
        val api = mockk<DeezerApiService>()
        val provider = DeezerCoverArtProvider(api)
        coEvery {
            api.searchAlbum("artist:\"Daft Punk\" album:\"Discovery\"", 24)
        } returns DeezerAlbumSearchResponse()
        coEvery {
            api.searchAlbum("Daft Punk Discovery", 24)
        } returns DeezerAlbumSearchResponse(data = listOf(album()))

        val candidates = provider.search(
            CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24)
        )

        assertEquals(1, candidates.size)
        coVerify(exactly = 1) { api.searchAlbum("Daft Punk Discovery", 24) }
    }

    @Test
    fun `search prefers the largest cover and keeps a smaller one for the grid`() = runTest {
        val api = mockk<DeezerApiService>()
        val provider = DeezerCoverArtProvider(api)
        coEvery { api.searchAlbum(any(), any()) } returns DeezerAlbumSearchResponse(
            data = listOf(album())
        )

        val candidate = provider.search(
            CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24)
        ).single()

        assertEquals("DEEZER:302127", candidate.id)
        assertEquals("Discovery", candidate.albumTitle)
        assertEquals("Daft Punk", candidate.artistName)
        assertEquals("https://example.test/1000x1000.jpg", candidate.imageUrl)
        assertEquals("https://example.test/250x250.jpg", candidate.thumbnailUrl)
        assertEquals(CoverArtSource.DEEZER, candidate.source)
        assertEquals(CoverArtSize(width = 1000, height = 1000), candidate.size)
    }

    @Test
    fun `search reports no size when the xl rendition is missing`() = runTest {
        val api = mockk<DeezerApiService>()
        coEvery { api.searchAlbum(any(), any()) } returns DeezerAlbumSearchResponse(
            data = listOf(album().copy(coverXl = null))
        )

        val candidate = DeezerCoverArtProvider(api)
            .search(CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24))
            .single()

        assertNull(candidate.size)
    }

    @Test
    fun `search skips albums that carry no cover at all`() = runTest {
        val api = mockk<DeezerApiService>()
        val provider = DeezerCoverArtProvider(api)
        coEvery { api.searchAlbum(any(), any()) } returns DeezerAlbumSearchResponse(
            data = listOf(
                DeezerAlbum(id = 1L, title = "Coverless"),
                album()
            )
        )

        val candidates = provider.search(
            CoverArtSearchRequest(album = "Discovery", artist = "Daft Punk", limit = 24)
        )

        assertEquals(listOf("DEEZER:302127"), candidates.map { it.id })
    }

    private fun album() = DeezerAlbum(
        id = 302127L,
        title = "Discovery",
        cover = "https://example.test/cover.jpg",
        coverMedium = "https://example.test/250x250.jpg",
        coverBig = "https://example.test/500x500.jpg",
        coverXl = "https://example.test/1000x1000.jpg",
        artist = DeezerAlbumArtist(id = 27L, name = "Daft Punk")
    )
}
