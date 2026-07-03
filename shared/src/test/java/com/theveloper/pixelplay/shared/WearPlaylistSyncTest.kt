package com.theveloper.pixelplay.shared

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Round-trip serialization tests for the contracts added by the playlist-to-watch
 * transfer feature. Phone and watch each decode with `ignoreUnknownKeys = true`,
 * mirroring how WearCommandReceiver/WearDataListenerService configure their Json
 * instances in the app/wear modules.
 */
class WearPlaylistSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `WearPlaylistSync round-trips through JSON preserving song order`() {
        val original = WearPlaylistSync(
            playlistId = "playlist-42",
            name = "QA Transcode Test",
            songIds = listOf("song-flac", "song-wav", "song-mp3-320", "song-aac-128", "song-ogg"),
        )

        val decoded = json.decodeFromString<WearPlaylistSync>(json.encodeToString(original))

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.songIds).containsExactlyElementsIn(original.songIds).inOrder()
    }

    @Test
    fun `WearPlaylistSync decodes an empty song list`() {
        val original = WearPlaylistSync(playlistId = "empty-playlist", name = "Empty", songIds = emptyList())

        val decoded = json.decodeFromString<WearPlaylistSync>(json.encodeToString(original))

        assertThat(decoded.songIds).isEmpty()
    }

    @Test
    fun `WearLibraryState round-trips freeStorageBytes`() {
        val original = WearLibraryState(
            songIds = listOf("song-1", "song-2"),
            freeStorageBytes = 4_294_967_296L,
        )

        val decoded = json.decodeFromString<WearLibraryState>(json.encodeToString(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `WearLibraryState defaults freeStorageBytes to 0 when absent from an older payload`() {
        // Simulates a watch running an older build that never sent freeStorageBytes.
        val legacyPayload = """{"songIds":["song-1","song-2"]}"""

        val decoded = json.decodeFromString<WearLibraryState>(legacyPayload)

        assertThat(decoded.freeStorageBytes).isEqualTo(0L)
        assertThat(decoded.songIds).containsExactly("song-1", "song-2")
    }

    @Test
    fun `WearTransferProgress serializes STATUS_TRANSCODING as its raw string value`() {
        val original = WearTransferProgress(
            requestId = "req-1",
            songId = "song-flac",
            bytesTransferred = 0L,
            totalBytes = 0L,
            status = WearTransferProgress.STATUS_TRANSCODING,
        )

        val encoded = json.encodeToString(original)

        assertThat(encoded).contains("\"transcoding\"")
        assertThat(json.decodeFromString<WearTransferProgress>(encoded).status)
            .isEqualTo(WearTransferProgress.STATUS_TRANSCODING)
    }
}
