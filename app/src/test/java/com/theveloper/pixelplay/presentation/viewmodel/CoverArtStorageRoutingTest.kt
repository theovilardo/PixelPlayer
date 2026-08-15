package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.coverart.AlbumArtStorage
import com.theveloper.pixelplay.data.coverart.AppArtworkWriter
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.media.ImageCacheManager
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.unmockkObject
import io.mockk.mockkObject
import io.mockk.coEvery
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.data.media.AudioMetadata
import com.theveloper.pixelplay.data.media.AudioMetadataReader
import com.theveloper.pixelplay.data.media.SongMetadataEditResult
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Where a cover ends up is a decision about the user's own files: one setting
 * chooses between rewriting the audio file's tags and keeping the image inside
 * the app. Getting it backwards either writes to files the user asked us not to
 * touch, or silently stops writing to files they expect us to.
 */
class CoverArtStorageRoutingTest {

    private val songMetadataEditor = mockk<SongMetadataEditor>(relaxed = true)
    private val appArtworkWriter = mockk<AppArtworkWriter>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val libraryStateHolder = mockk<LibraryStateHolder>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(AlbumArtUtils)
        // mockkObject spies rather than stubs, so without this the applied-store
        // lookups below would read the real filesystem through a mock Context.
        // No applied cover is the default; the tests that care say otherwise.
        every { AlbumArtUtils.getAppliedAlbumArtFile(any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() = unmockkObject(AlbumArtUtils)

    @Test
    fun `keeping covers in the app never reaches the file writer`() = runTest {
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)

        holder().saveBatchMetadata(
            songs = listOf(song()),
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify { appArtworkWriter.apply(any(), listOf(11L), any()) }
        coVerify(exactly = 0) {
            songMetadataEditor.editSongMetadata(
                songId = any(),
                newTitle = any(),
                newArtist = any(),
                newAlbum = any(),
                newGenre = any(),
                newLyrics = any(),
                newTrackNumber = any(),
                newDiscNumber = any()
            )
        }
    }

    @Test
    fun `choosing audio files does not divert the cover into the app`() = runTest {
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)

        holder().saveBatchMetadata(
            songs = listOf(song()),
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        // The cover has to stay on the path that embeds it into the file; the
        // app store is not a fallback for it.
        coVerify(exactly = 0) { appArtworkWriter.apply(any(), any(), any()) }
    }

    @Test
    fun `deleting a cover is a file edit whatever the storage setting says`() = runTest {
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)

        holder().saveBatchMetadata(
            songs = listOf(song()),
            coverArtUpdate = CoverArtUpdate(isDeletion = true),
            cb = callbacks()
        )
        settleRealDispatch()

        // Removing embedded art means rewriting the file. Writing an app-side
        // copy instead would leave the art the user deleted still in the file.
        coVerify(exactly = 0) { appArtworkWriter.apply(any(), any(), any()) }
        coVerify(exactly = 0) { appArtworkWriter.removeApplied(any(), any()) }
        coVerify(exactly = 1) { editorSawDeletionFor(11L) }
    }

    @Test
    fun `deleting a cover the app is holding never rewrites the audio files`() = runTest {
        // The applied cover is the one on screen, so it is the one to remove.
        // Handing the deletion to the tag writer strips the file's own artwork
        // instead -- the very thing that should come back into view.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)
        every { AlbumArtUtils.getAppliedAlbumArtFile(any(), any()) } returns mockk(relaxed = true)

        holder().saveBatchMetadata(
            songs = listOf(song(id = "11"), song(id = "12")),
            coverArtUpdate = CoverArtUpdate(isDeletion = true),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.removeApplied(listOf(11L, 12L), 5L) }
        coVerify(exactly = 0) {
            songMetadataEditor.editSongMetadata(
                songId = any(),
                newTitle = any(),
                newArtist = any(),
                newAlbum = any(),
                newGenre = any(),
                newLyrics = any(),
                newTrackNumber = any(),
                newDiscNumber = any()
            )
        }
    }

    @Test
    fun `a selection mixing applied and embedded covers is sorted out track by track`() = runTest {
        // Only the track whose cover lives in the file has anything for the tag
        // writer to remove. Letting the deletion through for the other one
        // would take artwork the user never asked to lose.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)
        every { AlbumArtUtils.getAppliedAlbumArtFile(any(), 11L) } returns mockk(relaxed = true)
        every { AlbumArtUtils.getAppliedAlbumArtFile(any(), 12L) } returns null

        holder().saveBatchMetadata(
            songs = listOf(song(id = "11"), song(id = "12")),
            coverArtUpdate = CoverArtUpdate(isDeletion = true),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.removeApplied(listOf(11L), any()) }
        coVerify(exactly = 0) { editorSawDeletionFor(11L) }
        coVerify(exactly = 1) { editorSawDeletionFor(12L) }
    }

    @Test
    fun `a tag edit alongside a cover still keeps the cover out of the file`() = runTest {
        // APP_ONLY means the cover never touches the file, not "unless
        // something else is also being edited": a tag change alongside it falls
        // through to the per-song save, which routes the cover there instead.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)

        holder().saveBatchMetadata(
            songs = listOf(song()),
            title = "Renamed",
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.apply(any(), listOf(11L), any()) }
        coVerify {
            songMetadataEditor.editSongMetadata(
                songId = 11L,
                newTitle = "Renamed",
                newArtist = any(),
                newAlbum = any(),
                newAlbumArtist = any(),
                newComposer = any(),
                newGenre = any(),
                newLyrics = any(),
                newTrackNumber = any(),
                newDiscNumber = any(),
                newReplayGainTrackGainDb = any(),
                newReplayGainAlbumGainDb = any(),
                // Not the cover: it went to the app store above, and the "preserve
                // existing embedded artwork" fallback only fires when nothing was
                // routed anywhere, which is not this case.
                coverArtUpdate = null
            )
        }
    }

    @Test
    fun `a tag edit alongside a cover applies it once for the whole album`() = runTest {
        // The writer can only see an apply covering a whole album when handed
        // every track's id together. Fanned out across the per-song saves it
        // never did: tracks drew the new cover while the album kept its old
        // art, and the same bytes were re-encoded once per track.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)

        holder().saveBatchMetadata(
            songs = listOf(song(id = "11"), song(id = "12"), song(id = "13")),
            title = "Renamed",
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.apply(any(), listOf(11L, 12L, 13L), 5L) }
        coVerify(exactly = 1) { appArtworkWriter.apply(any(), any(), any()) }
    }

    @Test
    fun `a cover spanning two albums is applied once per album`() = runTest {
        // Grouping by album is what lets each row follow. Writing the batch as
        // one call would hand the writer ids from two albums and an album id it
        // could not name, leaving both rows behind.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)

        holder().saveBatchMetadata(
            songs = listOf(
                song(id = "11", albumId = 5L),
                song(id = "12", albumId = 5L),
                song(id = "21", albumId = 9L)
            ),
            title = "Renamed",
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.apply(any(), listOf(11L, 12L), 5L) }
        coVerify(exactly = 1) { appArtworkWriter.apply(any(), listOf(21L), 9L) }
        coVerify(exactly = 2) { appArtworkWriter.apply(any(), any(), any()) }
    }

    @Test
    fun `applying a cover to the album does not strip the composer off its tracks`() = runTest {
        // An album-wide apply sends nothing but the cover, so composer arrives
        // null -- and Song carries none to fall back on. Turned into "" the
        // editor reads it as "delete this tag", taking the composer off every
        // track of a classical album for a purely visual action.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any(), readArtwork = false) } returns
            audioMetadata(composer = "Gustav Mahler")

        try {
            holder().saveBatchMetadata(
                songs = listOf(song()),
                coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
                cb = callbacks()
            )
            settleRealDispatch()

            coVerify {
                songMetadataEditor.editSongMetadata(
                    songId = 11L,
                    newTitle = any(), newArtist = any(), newAlbum = any(),
                    newAlbumArtist = any(),
                    // The file's own composer, read back and handed straight
                    // through, rather than the blank that would delete it.
                    newComposer = "Gustav Mahler",
                    newGenre = any(), newLyrics = any(),
                    newTrackNumber = any(), newDiscNumber = any(),
                    newReplayGainTrackGainDb = any(), newReplayGainAlbumGainDb = any(),
                    coverArtUpdate = any()
                )
            }
        } finally {
            unmockkObject(AudioMetadataReader)
        }
    }

    @Test
    fun `applying a cover to the album writes each file's own tags back, not the library's`() = runTest {
        // The tag write replaces the whole set, so every other field is filled
        // in on the way past -- and from Song that means the library's rendering
        // over the file's own tags: displayArtist's ", " join over "A; B", a
        // filename-derived title over a real one. On every track of the album.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any(), readArtwork = false) } returns audioMetadata(
            title = "Take Five",
            artist = "Dave Brubeck; Paul Desmond",
            album = "Time Out",
            genre = "Jazz",
            trackNumber = 3
        )
        val song = song().also {
            every { it.title } returns "03 take five"
            every { it.displayArtist } returns "Dave Brubeck, Paul Desmond"
            every { it.album } returns "Time Out"
            every { it.genre } returns "Jazz"
            every { it.trackNumber } returns 3
        }

        try {
            holder().saveBatchMetadata(
                songs = listOf(song),
                coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
                cb = callbacks()
            )
            settleRealDispatch()

            coVerify {
                songMetadataEditor.editSongMetadata(
                    songId = 11L,
                    newTitle = "Take Five",
                    newArtist = "Dave Brubeck; Paul Desmond",
                    newAlbum = any(), newAlbumArtist = any(), newComposer = any(),
                    newGenre = any(), newLyrics = any(),
                    newTrackNumber = any(), newDiscNumber = any(),
                    newReplayGainTrackGainDb = any(), newReplayGainAlbumGainDb = any(),
                    coverArtUpdate = any()
                )
            }
        } finally {
            unmockkObject(AudioMetadataReader)
        }
    }

    @Test
    fun `a save that is not editing lyrics leaves the library's copy of them alone`() = runTest {
        // The lyrics handed to the tag write are the file's own, precisely so
        // that a cover apply does not embed lyrics this app fetched into its
        // own database. Read back as the new value of the field, though, a file
        // that carries none would clear the database of the ones it has.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        mockkObject(AudioMetadataReader)
        every { AudioMetadataReader.read(any(), readArtwork = false) } returns audioMetadata()
        coEvery {
            songMetadataEditor.editSongMetadata(
                songId = any(), newTitle = any(), newArtist = any(), newAlbum = any(),
                newAlbumArtist = any(), newComposer = any(), newGenre = any(), newLyrics = any(),
                newTrackNumber = any(), newDiscNumber = any(),
                newReplayGainTrackGainDb = any(), newReplayGainAlbumGainDb = any(),
                coverArtUpdate = any()
            )
        } returns SongMetadataEditResult(success = true, updatedAlbumArtUri = null)
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit
        val song = song().also { every { it.lyrics } returns "fetched into the app's database" }

        try {
            holder().saveBatchMetadata(
                songs = listOf(song),
                coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
                cb = callbacks()
            )
            settleRealDispatch()

            coVerify(exactly = 0) { musicRepository.resetLyrics(any()) }
            coVerify(exactly = 0) { musicRepository.updateLyrics(any(), any()) }
        } finally {
            unmockkObject(AudioMetadataReader)
        }
    }

    @Test
    fun `a cloud track in an album of local ones is not told a cover was stored for it`() = runTest {
        // The writer refuses a negative id. Answering per album hands that
        // refusal back as a success, and the save re-points the track at a local
        // artwork URI with no file behind it, replacing a working remote cover.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.APP_ONLY)
        val cloudId = "-9000000000001"
        coEvery { appArtworkWriter.apply(any(), any(), any()) } returns true
        val updated = mutableListOf<Song>()
        every { libraryStateHolder.updateSong(capture(updated)) } returns Unit

        holder().saveBatchMetadata(
            songs = listOf(realSong(id = "11"), realSong(id = cloudId)),
            title = "Renamed",
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        coVerify(exactly = 1) { appArtworkWriter.apply(any(), listOf(11L), 5L) }
        // The local track does follow the cover it was given, so an empty list
        // here would be the assertion below passing for the wrong reason.
        assertThat(updated.map { it.id }).contains("11")
        assertThat(updated.filter { it.id == cloudId }).isEmpty()
    }

    @Test
    fun `a cover written into the file replaces one the app was holding`() = runTest {
        // The applied store answers first, as the most recent thing the user
        // chose. Applying under AUDIO_FILES makes the file that instead, so the
        // old applied cover has to stand down or it keeps winning.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        coEvery {
            songMetadataEditor.editSongMetadata(
                songId = any(), newTitle = any(), newArtist = any(), newAlbum = any(),
                newAlbumArtist = any(), newComposer = any(), newGenre = any(), newLyrics = any(),
                newTrackNumber = any(), newDiscNumber = any(),
                newReplayGainTrackGainDb = any(), newReplayGainAlbumGainDb = any(),
                coverArtUpdate = any()
            )
        } returns SongMetadataEditResult(success = true, updatedAlbumArtUri = "content://updated")
        every { AlbumArtUtils.clearAppliedArtForSong(any(), any()) } returns Unit

        holder().saveBatchMetadata(
            songs = listOf(song()),
            coverArtUpdate = CoverArtUpdate(bytes = byteArrayOf(1, 2, 3)),
            cb = callbacks()
        )
        settleRealDispatch()

        verify { AlbumArtUtils.clearAppliedArtForSong(any(), 11L) }
    }

    @Test
    fun `re-saving the file's own existing artwork does not disturb an applied cover`() = runTest {
        // No new cover was chosen here -- coverArtUpdate is null, so saveMetadata
        // preserves whatever the file already has, which is not a decision to
        // stop trusting the applied one.
        every { preferences.albumArtStorageFlow } returns flowOf(AlbumArtStorage.AUDIO_FILES)
        coEvery {
            songMetadataEditor.editSongMetadata(
                songId = any(), newTitle = any(), newArtist = any(), newAlbum = any(),
                newAlbumArtist = any(), newComposer = any(), newGenre = any(), newLyrics = any(),
                newTrackNumber = any(), newDiscNumber = any(),
                newReplayGainTrackGainDb = any(), newReplayGainAlbumGainDb = any(),
                coverArtUpdate = any()
            )
        } returns SongMetadataEditResult(success = true, updatedAlbumArtUri = null)

        holder().saveBatchMetadata(
            songs = listOf(song()),
            title = "Renamed",
            coverArtUpdate = null,
            cb = callbacks()
        )
        settleRealDispatch()

        verify(exactly = 0) { AlbumArtUtils.clearAppliedArtForSong(any(), any()) }
    }

    private fun MetadataEditStateHolder.saveBatchMetadata(
        songs: List<Song>,
        coverArtUpdate: CoverArtUpdate?,
        cb: MetadataEditCallbacks,
        title: String? = null
    ) = saveBatchMetadata(
        songs = songs,
        title = title,
        artist = null,
        album = null,
        albumArtist = null,
        composer = null,
        genre = null,
        lyrics = null,
        trackNumber = null,
        discNumber = null,
        replayGainTrackGainDb = null,
        replayGainAlbumGainDb = null,
        coverArtUpdate = coverArtUpdate,
        cb = cb
    )

    private fun holder() = MetadataEditStateHolder(
        songMetadataEditor = songMetadataEditor,
        musicRepository = musicRepository,
        imageCacheManager = mockk<ImageCacheManager>(relaxed = true),
        themeStateHolder = mockk<ThemeStateHolder>(relaxed = true),
        playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true).also {
            every { it.stablePlayerState } returns
                MutableStateFlow(mockk<StablePlayerState>(relaxed = true))
        },
        libraryStateHolder = libraryStateHolder,
        multiSelectionStateHolder = mockk<MultiSelectionStateHolder>(relaxed = true),
        albumArtThemeDao = mockk<AlbumArtThemeDao>(relaxed = true),
        appArtworkWriter = appArtworkWriter,
        userPreferencesRepository = preferences,
        context = mockk<Context>(relaxed = true)
    )

    private fun kotlinx.coroutines.test.TestScope.callbacks() = MetadataEditCallbacks(
        scope = this,
        getUiState = { mockk(relaxed = true) },
        updateUiState = {},
        getSelectedSongForInfo = { null },
        setSelectedSongForInfo = {},
        sendToast = {},
        reloadLyricsForCurrentSong = {}
    )

    /**
     * saveBatchMetadata's body runs a suspend function under
     * withContext(Dispatchers.IO) -- a real dispatcher, outside the test
     * scheduler's control. advanceUntilIdle() only drains work scheduled on
     * the virtual clock, so a coroutine parked on the real dispatcher can
     * resume and re-post its continuation *after* advanceUntilIdle() has
     * already returned, landing an assertion before the work it is checking
     * for has actually happened. A short bounded wait for the real dispatcher
     * to hand the continuation back, redraining the virtual scheduler each
     * time, catches that instead of racing it.
     */
    private suspend fun TestScope.settleRealDispatch() {
        advanceUntilIdle()
        repeat(10) {
            withContext(Dispatchers.Default) { Thread.sleep(15) }
            advanceUntilIdle()
        }
    }

    private fun audioMetadata(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        composer: String? = null,
        lyrics: String? = null,
        trackNumber: Int? = null
    ) = AudioMetadata(
        title = title, artist = artist, albumArtist = null, album = album,
        genre = genre, composer = composer, lyrics = lyrics, durationMs = null,
        trackNumber = trackNumber, discNumber = null, year = null, bitrate = null,
        sampleRate = null, artwork = null
    )

    /**
     * A tag write for [songId] carrying the cover deletion, which is the write
     * that strips the file's own artwork. Spelled out in full because the
     * editor's unnamed parameters would otherwise be matched against their
     * declared defaults rather than against anything.
     */
    private suspend fun MockKMatcherScope.editorSawDeletionFor(songId: Long): SongMetadataEditResult =
        songMetadataEditor.editSongMetadata(
            songId = songId,
            newTitle = any(),
            newArtist = any(),
            newAlbum = any(),
            newAlbumArtist = any(),
            newComposer = any(),
            newGenre = any(),
            newLyrics = any(),
            newTrackNumber = any(),
            newDiscNumber = any(),
            newReplayGainTrackGainDb = any(),
            newReplayGainAlbumGainDb = any(),
            coverArtUpdate = matchNullable { it?.isDeletion == true }
        )

    /**
     * A real [Song] rather than a mock, for the tests that read what the holder
     * wrote back into one: `copy` on a mock yields another mock, so the field
     * under test would never carry the value being asserted on.
     */
    private fun realSong(id: String, albumId: Long = 5L) = Song(
        id = id,
        title = "Track",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = albumId,
        path = "/music/track.mp3",
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = "https://cdn.example/cover.jpg",
        duration = 1_000L,
        mimeType = "audio/mpeg",
        bitrate = null,
        sampleRate = null
    )

    private fun song(id: String = "11", albumId: Long = 5L) = mockk<Song>(relaxed = true).also {
        every { it.id } returns id
        every { it.albumId } returns albumId
        every { it.path } returns "/music/track.mp3"
    }
}
