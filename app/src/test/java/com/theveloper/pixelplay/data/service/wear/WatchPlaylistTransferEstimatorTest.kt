package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

class WatchPlaylistTransferEstimatorTest {

    private val transcoder = WatchAudioTranscoder(
        application = mockk<Application>(relaxed = true),
        mainDispatcher = Dispatchers.Unconfined,
    )

    private fun song(id: String, mimeType: String?, bitrate: Int?, durationMs: Long = 180_000L) =
        Song.emptySong().copy(id = id, mimeType = mimeType, bitrate = bitrate, duration = durationMs)

    @Test
    fun `passthrough song is sized using its own bitrate`() {
        val passthroughSong = song("s1", "audio/mpeg", bitrate = 128_000, durationMs = 60_000L)

        val bytes = WatchPlaylistTransferEstimator.estimateBytesForSong(passthroughSong, transcoder)

        // 60s * 128_000 bps / 8 = 960_000 bytes
        assertThat(bytes).isEqualTo(960_000L)
    }

    @Test
    fun `transcoded song is sized using the target AAC bitrate, not its source bitrate`() {
        val losslessSong = song("s1", "audio/flac", bitrate = 900_000, durationMs = 60_000L)

        val bytes = WatchPlaylistTransferEstimator.estimateBytesForSong(losslessSong, transcoder)

        // 60s * 128_000 (TARGET_BITRATE_BPS) bps / 8 = 960_000 bytes, not sized off the 900kbps source.
        assertThat(bytes).isEqualTo(960_000L)
    }

    @Test
    fun `estimate only sums pending songs, not the whole playlist`() {
        val alreadyOnWatch = song("on-watch", "audio/mpeg", bitrate = 128_000, durationMs = 60_000L)
        val pending = song("pending", "audio/mpeg", bitrate = 128_000, durationMs = 60_000L)

        val estimate = WatchPlaylistTransferEstimator.estimate(
            allSongs = listOf(alreadyOnWatch, pending),
            pendingSongs = listOf(pending),
            transcoder = transcoder,
        )

        assertThat(estimate.totalSongCount).isEqualTo(2)
        assertThat(estimate.pendingSongCount).isEqualTo(1)
        assertThat(estimate.estimatedBytes).isEqualTo(960_000L)
    }

    @Test
    fun `no pending songs means zero bytes and zero seconds`() {
        val onlySong = song("s1", "audio/mpeg", bitrate = 128_000)

        val estimate = WatchPlaylistTransferEstimator.estimate(
            allSongs = listOf(onlySong),
            pendingSongs = emptyList(),
            transcoder = transcoder,
        )

        assertThat(estimate.pendingSongCount).isEqualTo(0)
        assertThat(estimate.estimatedBytes).isEqualTo(0L)
        assertThat(estimate.estimatedTransferSeconds).isEqualTo(0L)
    }

    @Test
    fun `a small pending transfer still estimates at least one second`() {
        val tinySong = song("s1", "audio/mpeg", bitrate = 128_000, durationMs = 1L)

        val estimate = WatchPlaylistTransferEstimator.estimate(
            allSongs = listOf(tinySong),
            pendingSongs = listOf(tinySong),
            transcoder = transcoder,
        )

        assertThat(estimate.estimatedBytes).isGreaterThan(0L)
        assertThat(estimate.estimatedTransferSeconds).isEqualTo(1L)
    }
}
