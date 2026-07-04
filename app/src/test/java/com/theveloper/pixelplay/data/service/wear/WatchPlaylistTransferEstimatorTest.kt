package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import io.mockk.mockk
import org.junit.jupiter.api.Test

@UnstableApi
class WatchPlaylistTransferEstimatorTest {

    private val transcoder = WatchAudioTranscoder(mockk<Application>(relaxed = true))

    private fun song(
        id: String,
        durationMs: Long,
        mimeType: String?,
        bitrate: Int?,
    ) = Song(
        id = id,
        title = "Song $id",
        artist = "Test Artist",
        artistId = 1L,
        album = "Test Album",
        albumId = 1L,
        path = "/sdcard/Music/$id.file",
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = null,
        duration = durationMs,
        mimeType = mimeType,
        bitrate = bitrate,
        sampleRate = 44_100,
    )

    @Test
    fun `passthrough song is sized using its own bitrate`() {
        val passthroughSong = song("1", durationMs = 60_000L, mimeType = "audio/mpeg", bitrate = 128_000)

        val bytes = WatchPlaylistTransferEstimator.estimateBytesForSong(passthroughSong, transcoder)

        assertThat(bytes).isEqualTo(60L * 128_000L / 8L)
    }

    @Test
    fun `transcoded song is sized using the target AAC bitrate, not its source bitrate`() {
        val flacSong = song("1", durationMs = 60_000L, mimeType = "audio/flac", bitrate = 900_000)

        val bytes = WatchPlaylistTransferEstimator.estimateBytesForSong(flacSong, transcoder)

        assertThat(bytes).isEqualTo(60L * WatchAudioTranscoder.TARGET_BITRATE_BPS.toLong() / 8L)
    }

    @Test
    fun `estimate only sums pending songs, not the whole playlist`() {
        val allSongs = listOf(
            song("1", durationMs = 60_000L, mimeType = "audio/mpeg", bitrate = 128_000),
            song("2", durationMs = 60_000L, mimeType = "audio/mpeg", bitrate = 128_000),
        )
        val pendingSongs = listOf(allSongs[1])

        val estimate = WatchPlaylistTransferEstimator.estimate(allSongs, pendingSongs, transcoder)

        assertThat(estimate.totalSongCount).isEqualTo(2)
        assertThat(estimate.pendingSongCount).isEqualTo(1)
        assertThat(estimate.estimatedBytes).isEqualTo(60L * 128_000L / 8L)
    }

    @Test
    fun `no pending songs means zero bytes and zero seconds`() {
        val allSongs = listOf(song("1", durationMs = 60_000L, mimeType = "audio/mpeg", bitrate = 128_000))

        val estimate = WatchPlaylistTransferEstimator.estimate(allSongs, emptyList(), transcoder)

        assertThat(estimate.estimatedBytes).isEqualTo(0L)
        assertThat(estimate.estimatedTransferSeconds).isEqualTo(0L)
    }

    @Test
    fun `a small pending transfer still estimates at least one second`() {
        val tinySong = song("1", durationMs = 1_000L, mimeType = "audio/mpeg", bitrate = 32_000)

        val estimate = WatchPlaylistTransferEstimator.estimate(listOf(tinySong), listOf(tinySong), transcoder)

        assertThat(estimate.estimatedTransferSeconds).isAtLeast(1L)
    }
}
