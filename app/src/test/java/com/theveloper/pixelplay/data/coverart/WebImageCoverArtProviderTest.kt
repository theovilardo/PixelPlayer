package com.theveloper.pixelplay.data.coverart

import com.theveloper.pixelplay.data.network.webimage.SerperImageResult
import com.theveloper.pixelplay.data.network.webimage.SerperImageSearchApi
import com.theveloper.pixelplay.data.network.webimage.SerperImageSearchResponse
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebImageCoverArtProviderTest {

    private val serper = mockk<SerperImageSearchApi>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)

    @Test
    fun `buildQuery biases the search towards artwork`() {
        // Measured: without the suffix the engine ranks photographs of the
        // artist above the cover.
        assertEquals(
            "Daft Punk Discovery album cover",
            WebImageCoverArtProvider.buildQuery(album = "Discovery", artist = "Daft Punk")
        )
    }

    @Test
    fun `provider stays out of searches until a key is configured`() = runTest {
        every { preferences.webImageSearchApiKeyFlow } returns flowOf("")

        val provider = provider()

        assertFalse(provider.isAvailable())
        assertTrue(
            provider.search(CoverArtSearchRequest("Discovery", "Daft Punk", 24)).isEmpty()
        )
        coVerify(exactly = 0) { serper.searchImages(any(), any(), any()) }
    }

    @Test
    fun `serper results keep the dimensions the engine reported`() = runTest {
        givenSerperConfigured()
        coEvery { serper.searchImages(any(), any(), any()) } returns SerperImageSearchResponse(
            images = listOf(
                SerperImageResult(
                    title = "CRZKNY - GW VIP",
                    imageUrl = "https://f4.bcbits.test/img/a123_10.jpg",
                    imageWidth = 1200,
                    imageHeight = 1200,
                    thumbnailUrl = "https://f4.bcbits.test/img/a123_16.jpg"
                )
            )
        )

        val candidate = provider()
            .search(CoverArtSearchRequest("GW VIP", "CRZKNY", 24))
            .single()

        assertEquals(CoverArtSource.WEB_IMAGE_SEARCH, candidate.source)
        assertEquals("CRZKNY - GW VIP", candidate.albumTitle)
        assertEquals(CoverArtSize(width = 1200, height = 1200), candidate.size)
    }

    @Test
    fun `insecure image urls are dropped`() = runTest {
        givenSerperConfigured()
        coEvery { serper.searchImages(any(), any(), any()) } returns SerperImageSearchResponse(
            images = listOf(
                SerperImageResult(title = "cleartext", imageUrl = "http://example.test/a.jpg"),
                SerperImageResult(title = "secure", imageUrl = "https://example.test/b.jpg")
            )
        )

        val candidates = provider().search(CoverArtSearchRequest("GW VIP", "CRZKNY", 24))

        assertEquals(listOf("secure"), candidates.map { it.albumTitle })
    }

    private fun givenSerperConfigured() {
        every { preferences.webImageSearchApiKeyFlow } returns flowOf("test-key")
    }

    private fun provider() = WebImageCoverArtProvider(
        serperImageSearchApi = serper,
        userPreferencesRepository = preferences
    )
}
