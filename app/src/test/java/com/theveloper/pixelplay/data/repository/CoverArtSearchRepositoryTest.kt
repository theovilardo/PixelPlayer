package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtProvider
import com.theveloper.pixelplay.data.coverart.CoverArtSearchRequest
import com.theveloper.pixelplay.data.coverart.CoverArtProviderStatus
import com.theveloper.pixelplay.data.coverart.CoverArtSearchUpdate
import com.theveloper.pixelplay.data.coverart.CoverArtSource
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverArtSearchRepositoryTest {

    @Test
    fun `search skips the network when there is nothing to search for`() = runTest {
        val provider = FakeProvider(results = listOf(candidate("a", "Discovery", "Daft Punk")))
        val repository = repositoryOf(provider)

        val result = repository.search(album = "  ", artist = "")

        assertEquals(emptyList<CoverArtCandidate>(), result.candidates)
        assertFalse(provider.wasQueried, "providers should not be queried for a blank search")
    }

    @Test
    fun `search merges providers, drops duplicate images and ranks the best match first`() = runTest {
        val exact = candidate("exact", "Random Access Memories", "Daft Punk")
        val duplicate = exact.copy(id = "duplicate")
        val weaker = candidate("weaker", "Discovery", "Daft Punk")
        val repository = repositoryOf(
            FakeProvider(results = listOf(weaker, exact)),
            FakeProvider(results = listOf(duplicate))
        )

        val ranked = repository
            .search(album = "Random Access Memories", artist = "Daft Punk")
            .candidates

        assertEquals(listOf("exact", "weaker"), ranked.map { it.id })
    }

    @Test
    fun `search queries providers concurrently rather than one after another`() = runTest {
        // Each provider blocks until every provider has been entered, so this
        // only completes if they really do run at the same time.
        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        val allEntered = CompletableDeferred<Unit>()
        val providerCount = 3

        val providers = (1..providerCount).map { index ->
            object : CoverArtProvider {
                override val source = CoverArtSource.DEEZER
                override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
                    if (entered.incrementAndGet() == providerCount) allEntered.complete(Unit)
                    allEntered.await()
                    return listOf(candidate("c$index", "Homework", "Daft Punk"))
                }
            }
        }

        val results = repositoryOf(*providers.toTypedArray())
            .search(album = "Homework", artist = "Daft Punk")
            .candidates

        assertEquals(providerCount, entered.get())
        assertEquals(providerCount, results.size)
    }

    @Test
    fun `search still returns results when one provider fails`() = runTest {
        val healthy = FakeProvider(results = listOf(candidate("ok", "Homework", "Daft Punk")))
        val repository = repositoryOf(FakeProvider(failure = IllegalStateException("boom")), healthy)

        val result = repository.search(album = "Homework", artist = "Daft Punk")

        assertEquals(listOf("ok"), result.candidates.map { it.id })
    }

    @Test
    fun `search reports the catalog that failed even when another answered`() = runTest {
        val healthy = FakeProvider(results = listOf(candidate("ok", "Homework", "Daft Punk")))
        val repository = repositoryOf(FakeProvider(failure = IllegalStateException("boom")), healthy)

        val result = repository.search(album = "Homework", artist = "Daft Punk")

        // What arrived is worth using; what the caller must not conclude is
        // that the rest of the catalogs had nothing. An unattended pass reads
        // that as "this album has no cover anywhere" and remembers it for good.
        assertEquals("boom", result.failure?.message)
    }

    @Test
    fun `search reports the failure when every provider fails`() = runTest {
        val repository = repositoryOf(
            FakeProvider(failure = IllegalStateException("first")),
            FakeProvider(failure = IllegalStateException("second"))
        )

        val result = repository.search(album = "Homework", artist = "Daft Punk")

        assertTrue(result.candidates.isEmpty())
        assertEquals("first", result.failure?.message)
    }

    @Test
    fun `searchStreaming emits as each catalog answers instead of waiting for all`() = runTest {
        val slowGate = CompletableDeferred<Unit>()
        val fast = FakeProvider(results = listOf(candidate("fast", "Homework", "Daft Punk")))
        val slow = object : CoverArtProvider {
            override val source = CoverArtSource.DEEZER
            override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
                slowGate.await()
                return listOf(candidate("slow", "Homework", "Daft Punk"))
            }
        }

        repositoryOf(fast, slow)
            .searchStreaming(album = "Homework", artist = "Daft Punk")
            .test {
                // First snapshot announces both catalogs as pending, before any
                // answer, then the fast one lands on its own.
                assertEquals(emptyList<String>(), awaitItem().candidates.map { it.id })
                assertEquals(listOf("fast"), awaitItem().candidates.map { it.id })

                slowGate.complete(Unit)

                assertEquals(setOf("fast", "slow"), awaitItem().candidates.map { it.id }.toSet())
                awaitComplete()
            }
    }

    @Test
    fun `searchStreaming reports each catalog as it answers`() = runTest {
        val statuses = mutableListOf<List<CoverArtProviderStatus>>()

        repositoryOf(
            FakeProvider(results = listOf(candidate("a", "Homework", "Daft Punk"))),
            FakeProvider(failure = IllegalStateException("boom"))
        ).searchStreaming(album = "Homework", artist = "Daft Punk").collect { statuses += it.statuses }

        // Everything pending up front, then resolved one by one.
        assertTrue(statuses.first().all { it.isSearching })
        val settled = statuses.last()
        assertTrue(settled.none { it.isSearching })
        assertEquals(1, settled.first().resultCount)
        assertTrue(settled.last().failed)
    }

    @Test
    fun `searchStreaming reports the failure only when nothing was found at all`() = runTest {
        val snapshots = mutableListOf<CoverArtSearchUpdate>()

        repositoryOf(
            FakeProvider(failure = IllegalStateException("boom")),
            FakeProvider(results = listOf(candidate("ok", "Homework", "Daft Punk")))
        ).searchStreaming(album = "Homework", artist = "Daft Punk").collect { snapshots += it }

        assertTrue(snapshots.last().isComplete)
        assertNull(snapshots.last().failure, "a healthy catalog answered, so this is not a failure")

        val allFailed = mutableListOf<CoverArtSearchUpdate>()
        repositoryOf(
            FakeProvider(failure = IllegalStateException("first")),
            FakeProvider(failure = IllegalStateException("second"))
        ).searchStreaming(album = "Homework", artist = "Daft Punk").collect { allFailed += it }

        assertEquals("first", allFailed.last().failure?.message)
    }

    @Test
    fun `a confident direct match settles the search without the slow catalog`() = runTest {
        val slow = FakeProvider(
            results = listOf(candidate("slow", "Discovery", "Daft Punk")),
            source = CoverArtSource.COVER_ART_ARCHIVE
        )
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("fast", "Discovery", "Daft Punk"))),
            slow
        )

        val ranked = repository
            .search(album = "Discovery", artist = "Daft Punk", confidentMatchScore = 0.7f)
            .candidates

        assertEquals(listOf("fast"), ranked.map { it.id })
        // Reaching the Cover Art Archive means a MusicBrainz query plus a lookup
        // per release, and it is what makes an unattended pass take seconds an
        // album. An exact match is already in hand.
        assertFalse(slow.wasQueried, "the slow catalog should not have been consulted")
    }

    @Test
    fun `a weak direct match still falls back to the slow catalog`() = runTest {
        val slow = FakeProvider(
            results = listOf(candidate("slow", "Discovery", "Daft Punk")),
            source = CoverArtSource.COVER_ART_ARCHIVE
        )
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("unrelated", "Trans-Europe Express", "Kraftwerk"))),
            slow
        )

        val ranked = repository
            .search(album = "Discovery", artist = "Daft Punk", confidentMatchScore = 0.7f)
            .candidates

        assertTrue(slow.wasQueried, "nothing good enough was found, so keep looking")
        assertEquals("slow", ranked.first().id)
    }

    @Test
    fun `without a confidence bar every catalog is queried at once`() = runTest {
        val slow = FakeProvider(
            results = listOf(candidate("slow", "Discovery", "Daft Punk")),
            source = CoverArtSource.COVER_ART_ARCHIVE
        )
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("fast", "Discovery", "Daft Punk"))),
            slow
        )

        // A person choosing a cover is shown everything, however good the first
        // answer looked.
        val ranked = repository.search(album = "Discovery", artist = "Daft Punk").candidates

        assertTrue(slow.wasQueried)
        assertEquals(setOf("fast", "slow"), ranked.map { it.id }.toSet())
    }

    @Test
    fun `a staged search survives a direct catalog failing`() = runTest {
        val repository = repositoryOf(
            FakeProvider(failure = IllegalStateException("boom")),
            FakeProvider(
                results = listOf(candidate("slow", "Discovery", "Daft Punk")),
                source = CoverArtSource.COVER_ART_ARCHIVE
            )
        )

        val result = repository
            .search(album = "Discovery", artist = "Daft Punk", confidentMatchScore = 0.7f)

        assertEquals(listOf("slow"), result.candidates.map { it.id })
    }

    @Test
    fun `a staged search reports the failure of every catalog`() = runTest {
        val repository = repositoryOf(
            FakeProvider(failure = IllegalStateException("first")),
            FakeProvider(
                failure = IllegalStateException("second"),
                source = CoverArtSource.COVER_ART_ARCHIVE
            )
        )

        val result = repository
            .search(album = "Discovery", artist = "Daft Punk", confidentMatchScore = 0.7f)

        assertTrue(result.candidates.isEmpty())
        assertEquals("first", result.failure?.message)
    }

    @Test
    fun `a catalog search never spends a metered web request`() = runTest {
        val web = FakeProvider(
            results = listOf(webResult()),
            source = CoverArtSource.WEB_IMAGE_SEARCH
        )
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("catalog", "Discovery", "Daft Punk"))),
            web
        )

        val ranked = repository.search(album = "Discovery", artist = "Daft Punk").candidates

        // Web requests come out of the user's own monthly allowance, so they are
        // spent on the album they were asked for and nothing else. This is the
        // path the unattended fetcher runs on.
        assertEquals(listOf("catalog"), ranked.map { it.id })
        assertFalse(web.wasQueried, "the web engine must not be queried by a catalog search")
    }

    @Test
    fun `a catalog search does not announce the web engine as a pending source`() = runTest {
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("catalog", "Discovery", "Daft Punk"))),
            FakeProvider(results = listOf(webResult()), source = CoverArtSource.WEB_IMAGE_SEARCH)
        )

        val update = repository.searchStreaming(album = "Discovery", artist = "Daft Punk").first()

        assertEquals(listOf(CoverArtSource.DEEZER), update.statuses.map { it.source })
    }

    @Test
    fun `an explicit web search returns the engine's own results and order`() = runTest {
        val catalog = FakeProvider(results = listOf(candidate("catalog", "Discovery", "Daft Punk")))
        val repository = repositoryOf(
            catalog,
            FakeProvider(
                // A web result has a page title and no artist, which the catalog
                // scorer would rate close to zero and discard.
                results = listOf(webResult("WEB:1"), webResult("WEB:2")),
                source = CoverArtSource.WEB_IMAGE_SEARCH
            )
        )

        val found = repository.searchWebImages(album = "Discovery", artist = "Daft Punk").getOrThrow()

        assertEquals(listOf("WEB:1", "WEB:2"), found.map { it.id })
        assertFalse(catalog.wasQueried, "the catalogs have already had their turn")
    }

    @Test
    fun `web search reports itself unavailable until an engine is configured`() = runTest {
        val catalogOnly = repositoryOf(FakeProvider(results = emptyList()))
        assertFalse(catalogOnly.isWebImageSearchAvailable())

        val unconfigured = repositoryOf(
            FakeProvider(source = CoverArtSource.WEB_IMAGE_SEARCH, available = false)
        )
        assertFalse(unconfigured.isWebImageSearchAvailable())

        val configured = repositoryOf(
            FakeProvider(source = CoverArtSource.WEB_IMAGE_SEARCH, available = true)
        )
        assertTrue(configured.isWebImageSearchAvailable())
    }

    @Test
    fun `providers the user has not configured are left out entirely`() = runTest {
        val unavailable = FakeProvider(results = emptyList(), available = false)
        val repository = repositoryOf(
            FakeProvider(results = listOf(candidate("ok", "Homework", "Daft Punk"))),
            unavailable
        )

        val update = repository.searchStreaming(album = "Homework", artist = "Daft Punk").first()

        assertEquals(1, update.statuses.size)
        assertFalse(unavailable.wasQueried)
    }

    private fun repositoryOf(vararg providers: CoverArtProvider) = CoverArtSearchRepository(
        context = mockk<Context>(relaxed = true),
        providers = providers.toList(),
        okHttpClient = mockk<OkHttpClient>(relaxed = true)
    )

    private fun webResult(id: String = "WEB:1") = CoverArtCandidate(
        id = id,
        albumTitle = "daft punk discovery vinyl reissue - record shop",
        artistName = "",
        thumbnailUrl = "https://example.test/$id/250.jpg",
        imageUrl = "https://example.test/$id/1000.jpg",
        source = CoverArtSource.WEB_IMAGE_SEARCH
    )

    private fun candidate(id: String, album: String, artist: String) = CoverArtCandidate(
        id = id,
        albumTitle = album,
        artistName = artist,
        thumbnailUrl = "https://example.test/$id/250.jpg",
        imageUrl = "https://example.test/$id/1000.jpg",
        source = CoverArtSource.DEEZER
    )

    private class FakeProvider(
        private val results: List<CoverArtCandidate> = emptyList(),
        private val failure: Throwable? = null,
        override val source: CoverArtSource = CoverArtSource.DEEZER,
        private val available: Boolean = true
    ) : CoverArtProvider {
        var wasQueried: Boolean = false
            private set

        override suspend fun isAvailable(): Boolean = available

        override suspend fun search(request: CoverArtSearchRequest): List<CoverArtCandidate> {
            wasQueried = true
            failure?.let { throw it }
            return results
        }
    }
}
