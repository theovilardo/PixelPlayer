package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

/**
 * Covers [WatchAudioTranscoder.requiresTranscoding] only — the pure decision function. The actual
 * encode path (`transcodeIfNeeded` / `runTransform`) drives a real [androidx.media3.transformer.Transformer],
 * which needs a hardware encoder and a Looper thread; that's only verifiable on a device.
 */
class WatchAudioTranscoderTest {

    // requiresTranscoding never touches these — a relaxed mock and any real dispatcher are enough.
    private val transcoder = WatchAudioTranscoder(
        application = mockk<Application>(relaxed = true),
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun song(mimeType: String?, bitrate: Int?) =
        Song.emptySong().copy(mimeType = mimeType, bitrate = bitrate)

    @Test
    fun `a lossless format requires transcoding regardless of bitrate`() {
        assertThat(transcoder.requiresTranscoding(song("audio/flac", bitrate = 128_000))).isTrue()
        assertThat(transcoder.requiresTranscoding(song("audio/flac", bitrate = null))).isTrue()
    }

    @Test
    fun `a lossy source at or under the passthrough bitrate is sent as-is`() {
        assertThat(transcoder.requiresTranscoding(song("audio/mpeg", bitrate = 128_000))).isFalse()
    }

    @Test
    fun `a lossy source at exactly the passthrough bitrate boundary is sent as-is`() {
        assertThat(transcoder.requiresTranscoding(song("audio/mpeg", bitrate = 256_000))).isFalse()
    }

    @Test
    fun `a lossy source over the passthrough bitrate is transcoded down`() {
        assertThat(transcoder.requiresTranscoding(song("audio/mpeg", bitrate = 320_000))).isTrue()
    }

    @Test
    fun `unknown mimeType requires transcoding`() {
        assertThat(transcoder.requiresTranscoding(song(mimeType = null, bitrate = 128_000))).isTrue()
    }

    @Test
    fun `unknown bitrate requires transcoding when nothing else is known about the song`() {
        // The Song-based overload only sees what the library scanned. transcodeIfNeeded probes
        // the file before deciding, so this conservative answer is not what a real unknown-bitrate
        // MP3 gets — see the resolved-bitrate case below.
        assertThat(transcoder.requiresTranscoding(song("audio/mpeg", bitrate = null))).isTrue()
    }

    @Test
    fun `a lossy source whose bitrate was resolved by probing is sent as-is`() {
        assertThat(transcoder.requiresTranscoding("audio/mpeg", bitrateBps = 192_000)).isFalse()
        assertThat(transcoder.requiresTranscoding("audio/mpeg", bitrateBps = 320_000)).isTrue()
    }

    @Test
    fun `mimeType is matched case-insensitively`() {
        assertThat(transcoder.requiresTranscoding(song("AUDIO/MPEG", bitrate = 128_000))).isFalse()
    }

    @Test
    fun `an unprobed lossy source is estimated above the transcode target, not at it`() {
        // It will most likely pass through untouched at its real bitrate, so sizing it at the
        // AAC target would systematically undershoot the confirmation sheet's estimate.
        assertThat(transcoder.estimatedTransferBitrateBps(song("audio/mpeg", bitrate = null)))
            .isGreaterThan(WatchAudioTranscoder.TARGET_BITRATE_BPS)
    }

    @Test
    fun `songs that will be transcoded are estimated at the AAC target bitrate`() {
        assertThat(transcoder.estimatedTransferBitrateBps(song("audio/flac", bitrate = null)))
            .isEqualTo(WatchAudioTranscoder.TARGET_BITRATE_BPS)
        assertThat(transcoder.estimatedTransferBitrateBps(song("audio/mpeg", bitrate = 320_000)))
            .isEqualTo(WatchAudioTranscoder.TARGET_BITRATE_BPS)
    }

    @Test
    fun `a passthrough song with a known bitrate is estimated at that bitrate`() {
        assertThat(transcoder.estimatedTransferBitrateBps(song("audio/mpeg", bitrate = 192_000)))
            .isEqualTo(192_000)
    }
}
