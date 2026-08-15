package com.theveloper.pixelplay.presentation.viewmodel

import android.app.Application
import app.cash.turbine.test
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.WearLocalPlayerRepository
import com.theveloper.pixelplay.data.WearOutputTarget
import com.theveloper.pixelplay.data.WearPerformanceSettingsRepository
import com.theveloper.pixelplay.data.WearPlaybackController
import com.theveloper.pixelplay.data.WearPlaybackStatePersistence
import com.theveloper.pixelplay.data.WearStateRepository
import com.theveloper.pixelplay.data.WearTransferRepository
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalPlaylistEntity
import com.theveloper.pixelplay.data.local.LocalPlaylistSongCrossRef
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.LocalSongEntity
import com.theveloper.pixelplay.shared.WearTransferProgress
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * [WearLocalPlayerRepository], [WearStateRepository], [WearPlaybackController] and
 * [WearTransferRepository] are all constructed for real, not mocked: they're final Kotlin
 * classes, and MockK can only fake a final class through its inline-mocking Java agent — which
 * hangs indefinitely under this sandbox (documented in `WearTransferRepositoryPlaylistSyncTest`
 * and `PlaylistWatchTransferCoordinatorTest` in `:app`).
 *
 * That constrains what `playAll`/`playFrom` can assert: [WearLocalPlayerRepository.playLocalSongs]
 * itself is not verifiable here (it launches a coroutine that tries to bind a real
 * `MediaController` to `WearPlaybackService`, which fails fast — and silently — off-device with
 * no Android runtime present). What *is* real production behavior, reachable without a device, is
 * the guard clause in the ViewModel that decides whether to call it at all, and the
 * `stateRepository.setOutputTarget(WATCH)` call right after it — both are asserted via
 * [WearStateRepository.outputTarget], a real (not mocked) collaborator.
 *
 * Every `stateIn`-backed property here (`playlists`, `playlistDetails`, `playlistSongs`,
 * `playlistIdsReceiving`) delivers its `stateIn` initial value as a first, synchronous event to
 * any new collector — *before* the upstream's real current value has had a chance to run through
 * `WhileSubscribed`'s forwarding coroutine. Tests that assert on the sequence of emissions, or
 * that read `.value` right after establishing a subscription, account for that placeholder
 * explicitly rather than assuming the first `awaitItem()` is already the "real" one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearLocalPlaylistViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainCoroutineExtension = MainCoroutineExtension()
    }

    private val application = mockk<Application>(relaxed = true)
    private val localPlaylistDao = mockk<LocalPlaylistDao>()
    private val localSongDao = mockk<LocalSongDao>()
    private val channelClient = mockk<ChannelClient>()
    private val messageClient = mockk<MessageClient>()
    private val nodeClient = mockk<NodeClient>()

    private val playlistsFlow = MutableStateFlow<List<LocalPlaylistEntity>>(emptyList())
    private val allCrossRefsFlow = MutableStateFlow<List<LocalPlaylistSongCrossRef>>(emptyList())
    private val playlistSongsFlowById = mutableMapOf<String, MutableStateFlow<List<LocalPlaylistSongCrossRef>>>()
    private val allSongsFlow = MutableStateFlow<List<LocalSongEntity>>(emptyList())
    private val tempFiles = mutableListOf<File>()

    private lateinit var stateRepository: WearStateRepository
    private lateinit var transferRepository: WearTransferRepository
    private lateinit var viewModel: WearLocalPlaylistViewModel

    @BeforeEach
    fun setUp() {
        every { localPlaylistDao.observePlaylists() } returns playlistsFlow
        every { localPlaylistDao.observeAllPlaylistSongCrossRefs() } returns allCrossRefsFlow
        every { localPlaylistDao.observePlaylistSongs(any()) } answers {
            val playlistId = firstArg<String>()
            playlistSongsFlowById.getOrPut(playlistId) { MutableStateFlow(emptyList()) }
        }
        every { localSongDao.getAllSongs() } returns allSongsFlow
        // WearTransferRepository's own init block treats any LocalSongEntity whose localPath
        // doesn't resolve to a real, non-empty file as stale and deletes it — irrelevant to what
        // this ViewModel does, but its background collector still runs and would call this on
        // every song() fixture below if we didn't back them with real files (we do, see song()).
        coEvery { localSongDao.deleteById(any()) } just Runs

        stateRepository = WearStateRepository()
        val performanceSettingsRepository = mockk<WearPerformanceSettingsRepository> {
            every { showAlbumArt } returns MutableStateFlow(true)
            every { dynamicColorTheming } returns MutableStateFlow(true)
            every { playButtonAnimation } returns MutableStateFlow(true)
            // Already resolved: these tests are about transfer/playlist behavior, not about the
            // startup window where the toggles aren't known yet.
            every { isResolved } returns MutableStateFlow(true)
        }
        val localPlayerRepository = WearLocalPlayerRepository(
            application,
            localSongDao,
            mockk<WearPlaybackStatePersistence>(),
            performanceSettingsRepository,
        )
        val playbackController = WearPlaybackController(application, stateRepository)
        transferRepository = WearTransferRepository(
            application = application,
            localSongDao = localSongDao,
            localPlaylistDao = localPlaylistDao,
            channelClient = channelClient,
            messageClient = messageClient,
            nodeClient = nodeClient,
            localPlayerRepository = localPlayerRepository,
            stateRepository = stateRepository,
            playbackController = playbackController,
        )

        viewModel = WearLocalPlaylistViewModel(
            localPlaylistDao = localPlaylistDao,
            localSongDao = localSongDao,
            localPlayerRepository = localPlayerRepository,
            stateRepository = stateRepository,
            transferRepository = transferRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    /** Backed by a real, non-empty file so `hasPlayableLocalFile()`-style checks see it as valid. */
    private fun song(id: String): LocalSongEntity {
        val file = File.createTempFile("local-song-$id", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        tempFiles += file
        return LocalSongEntity(
            songId = id,
            title = "Title $id",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            duration = 180_000L,
            mimeType = "audio/mp4",
            fileSize = file.length(),
            bitrate = 128_000,
            sampleRate = 44_100,
            localPath = file.absolutePath,
            transferredAt = 0L,
        )
    }

    private fun crossRef(playlistId: String, songId: String, position: Int, pendingTitle: String = "") =
        LocalPlaylistSongCrossRef(
            playlistId = playlistId,
            songId = songId,
            position = position,
            pendingTitle = pendingTitle,
        )

    /** Subscribes long enough for `WhileSubscribed`'s forwarding coroutine to run and update
     *  `.value` past the `stateIn` placeholder, then lets go — `.value` keeps the real result. */
    private suspend fun warmUp(flow: kotlinx.coroutines.flow.StateFlow<*>) {
        flow.test {
            awaitItem() // stateIn's initial placeholder
            awaitItem() // the real, upstream-derived value
        }
    }

    /**
     * `playAll`/`playFrom` call into [WearLocalPlayerRepository.playLocalSongs], which
     * fire-and-forgets a coroutine on `Dispatchers.Main` that ends up calling
     * `android.net.Uri.fromFile` — unstubbed on a bare JVM, so it NPEs. That NPE is a pure artifact
     * of running off-device (see the class doc) and unrelated to what these two tests actually
     * assert — but `runTest` re-resolves `Dispatchers.Main` at cleanup and drains whatever is
     * queued on it before returning, so the NPE always surfaces by the time `runTest` itself
     * returns, *after* the test body (and its assertions) already ran to completion. Rather than
     * fight `runTest`'s cleanup — every attempt to reroute `Dispatchers.Main` away from it still
     * gets drained, since the lookup happens fresh at cleanup time, not once at start — this names
     * the crash explicitly instead of letting it surface as an unexplained failure.
     */
    private fun expectFireAndForgetPlaybackCrash(body: suspend TestScope.() -> Unit) {
        val error = assertThrows<NullPointerException> { runTest { body() } }
        assertThat(error.message).contains("fromFile")
    }

    @Test
    fun `playlists mirrors the DAO's observePlaylists flow`() = runTest {
        viewModel.playlists.test {
            assertThat(awaitItem()).isEmpty()
            playlistsFlow.value = listOf(LocalPlaylistEntity("p1", "Road trip", 0L, 0L))
            assertThat(awaitItem()).containsExactly(LocalPlaylistEntity("p1", "Road trip", 0L, 0L))
        }
    }

    @Test
    fun `playlistDetails resolves the entity matching the loaded playlistId`() = runTest {
        playlistsFlow.value = listOf(
            LocalPlaylistEntity("p1", "Road trip", 0L, 0L),
            LocalPlaylistEntity("p2", "Gym", 0L, 0L),
        )

        viewModel.playlistDetails.test {
            assertThat(awaitItem()).isNull()
            viewModel.loadPlaylist("p2")
            assertThat(awaitItem()?.playlistId).isEqualTo("p2")
        }
    }

    @Test
    fun `playlistSongs marks songs without a matching local file as unavailable, in sync order`() = runTest {
        playlistSongsFlowById["p1"] = MutableStateFlow(
            listOf(crossRef("p1", "s1", 0), crossRef("p1", "s2", 1), crossRef("p1", "s3", 2))
        )
        allSongsFlow.value = listOf(song("s1"), song("s3")) // s2 hasn't arrived yet

        viewModel.loadPlaylist("p1")
        viewModel.playlistSongs.test {
            awaitItem() // stateIn's initial placeholder (emptyList)
            val items = awaitItem()
            assertThat(items.map { it.songId }).containsExactly("s1", "s2", "s3").inOrder()
            assertThat(items.first { it.songId == "s1" }.isAvailable).isTrue()
            assertThat(items.first { it.songId == "s2" }.isAvailable).isFalse()
            assertThat(items.first { it.songId == "s3" }.isAvailable).isTrue()
        }
    }

    @Test
    fun `displayTitle prefers the real title, then the phone's pending title, then the raw id`() = runTest {
        playlistSongsFlowById["p1"] = MutableStateFlow(
            listOf(
                crossRef("p1", "s1", 0, pendingTitle = "Ignored once available"),
                crossRef("p1", "s2", 1, pendingTitle = "Still transferring"),
                crossRef("p1", "s3", 2), // no pendingTitle — an older phone's sync
            )
        )
        allSongsFlow.value = listOf(song("s1")) // only s1 has actually arrived

        viewModel.loadPlaylist("p1")
        viewModel.playlistSongs.test {
            awaitItem() // stateIn's initial placeholder (emptyList)
            val items = awaitItem()
            assertThat(items.first { it.songId == "s1" }.displayTitle).isEqualTo("Title s1")
            assertThat(items.first { it.songId == "s2" }.displayTitle).isEqualTo("Still transferring")
            assertThat(items.first { it.songId == "s3" }.displayTitle).isEqualTo("s3")
        }
    }

    @Test
    fun `a song flips from pending to available as soon as it lands, without reloading`() = runTest {
        playlistSongsFlowById["p1"] = MutableStateFlow(listOf(crossRef("p1", "s1", 0)))
        viewModel.loadPlaylist("p1")

        viewModel.playlistSongs.test {
            awaitItem() // stateIn's initial placeholder (emptyList)
            assertThat(awaitItem().single().isAvailable).isFalse()
            allSongsFlow.value = listOf(song("s1"))
            assertThat(awaitItem().single().isAvailable).isTrue()
        }
    }

    @Test
    fun `playlistIdsReceiving reports playlists with an actively transferring member`() = runTest {
        allCrossRefsFlow.value = listOf(crossRef("p1", "s1", 0), crossRef("p2", "s2", 0))

        viewModel.playlistIdsReceiving.test {
            // Unlike playlistSongs, the placeholder (emptySet) and the real first combined value
            // (also emptySet, since there's no active transfer yet) are structurally equal —
            // StateFlow dedups them into a single emission, so there's only one item to await here.
            assertThat(awaitItem()).isEmpty()
            transferRepository.onProgressReceived(
                WearTransferProgress(
                    requestId = "r1",
                    songId = "s1",
                    bytesTransferred = 10L,
                    totalBytes = 100L,
                    status = WearTransferProgress.STATUS_TRANSFERRING,
                )
            )
            assertThat(awaitItem()).containsExactly("p1")
        }
    }

    @Test
    fun `a failed transfer no longer counts as receiving once it reaches a terminal state`() = runTest {
        allCrossRefsFlow.value = listOf(crossRef("p1", "s1", 0))

        viewModel.playlistIdsReceiving.test {
            assertThat(awaitItem()).isEmpty() // deduped placeholder, see the test above
            transferRepository.onProgressReceived(
                WearTransferProgress(
                    requestId = "r1",
                    songId = "s1",
                    bytesTransferred = 10L,
                    totalBytes = 100L,
                    status = WearTransferProgress.STATUS_TRANSFERRING,
                )
            )
            assertThat(awaitItem()).containsExactly("p1")

            // The transfer fails — WearTransferRepository deliberately keeps this entry in
            // activeTransfers (DownloadsScreen lists failed transfers under "Transfer issues"),
            // it doesn't remove it. playlistIdsReceiving must stop counting it anyway: mere
            // presence in the map isn't "still receiving" once the status is terminal.
            transferRepository.onProgressReceived(
                WearTransferProgress(
                    requestId = "r1",
                    songId = "s1",
                    bytesTransferred = 10L,
                    totalBytes = 100L,
                    status = WearTransferProgress.STATUS_FAILED,
                    error = "Transfer timed out",
                )
            )
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `playAll switches output to watch when at least one song is available`() = expectFireAndForgetPlaybackCrash {
        playlistSongsFlowById["p1"] = MutableStateFlow(listOf(crossRef("p1", "s1", 0)))
        allSongsFlow.value = listOf(song("s1"))
        viewModel.loadPlaylist("p1")
        warmUp(viewModel.playlistSongs)

        assertThat(stateRepository.outputTarget.value).isEqualTo(WearOutputTarget.PHONE)
        viewModel.playAll()
        assertThat(stateRepository.outputTarget.value).isEqualTo(WearOutputTarget.WATCH)
    }

    @Test
    fun `playAll is a no-op when every song is still pending transfer`() = runTest {
        playlistSongsFlowById["p1"] = MutableStateFlow(listOf(crossRef("p1", "s1", 0)))
        viewModel.loadPlaylist("p1")
        warmUp(viewModel.playlistSongs) // s1 has no matching LocalSongEntity: pending

        viewModel.playAll()
        assertThat(stateRepository.outputTarget.value).isEqualTo(WearOutputTarget.PHONE)
    }

    @Test
    fun `playFrom is a no-op when the requested song is still pending transfer`() = runTest {
        playlistSongsFlowById["p1"] = MutableStateFlow(
            listOf(crossRef("p1", "s1", 0), crossRef("p1", "s2", 1))
        )
        allSongsFlow.value = listOf(song("s1")) // s2 is pending
        viewModel.loadPlaylist("p1")
        warmUp(viewModel.playlistSongs)

        viewModel.playFrom("s2")
        assertThat(stateRepository.outputTarget.value).isEqualTo(WearOutputTarget.PHONE)
    }

    @Test
    fun `playFrom an available song switches output to watch`() = expectFireAndForgetPlaybackCrash {
        playlistSongsFlowById["p1"] = MutableStateFlow(
            listOf(crossRef("p1", "s1", 0), crossRef("p1", "s2", 1))
        )
        allSongsFlow.value = listOf(song("s1"), song("s2"))
        viewModel.loadPlaylist("p1")
        warmUp(viewModel.playlistSongs)

        viewModel.playFrom("s2")
        assertThat(stateRepository.outputTarget.value).isEqualTo(WearOutputTarget.WATCH)
    }
}
