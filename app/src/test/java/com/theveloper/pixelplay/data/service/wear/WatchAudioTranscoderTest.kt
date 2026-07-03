package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@UnstableApi
class WatchAudioTranscoderTest {

    private val transcoder = WatchAudioTranscoder(mockk<Application>(relaxed = true))

    private fun song(mimeType: String?, bitrate: Int?) = Song(
        id = "song-1",
        title = "Test Song",
        artist = "Test Artist",
        artistId = 1L,
        album = "Test Album",
        albumId = 1L,
        path = "/sdcard/Music/test.file",
        contentUriString = "content://media/external/audio/media/1",
        albumArtUriString = null,
        duration = 30_000L,
        mimeType = mimeType,
        bitrate = bitrate,
        sampleRate = 44_100,
    )

    @ParameterizedTest(name = "{0} @ {1}bps requires transcoding")
    @CsvSource(
        "audio/flac, 0",
        "audio/x-flac, 0",
        "audio/wav, 0",
        "audio/x-wav, 0",
        "audio/mpeg, 320000",
        "audio/aac, 320000",
    )
    fun `lossless or over-budget lossy sources require transcoding`(mimeType: String, bitrate: Int) {
        assertThat(transcoder.requiresTranscoding(song(mimeType, bitrate))).isTrue()
    }

    @ParameterizedTest(name = "{0} @ {1}bps is passthrough")
    @CsvSource(
        "audio/mpeg, 128000",
        "audio/mp4, 128000",
        "audio/aac, 256000",
        "audio/mp4a-latm, 192000",
        "audio/ogg, 128000",
        "audio/opus, 96000",
    )
    fun `lossy sources at or under the target bitrate are passthrough`(mimeType: String, bitrate: Int) {
        assertThat(transcoder.requiresTranscoding(song(mimeType, bitrate))).isFalse()
    }

    @Test
    fun `unknown mimeType requires transcoding`() {
        assertThat(transcoder.requiresTranscoding(song(mimeType = null, bitrate = 128_000))).isTrue()
    }

    @Test
    fun `unknown bitrate requires transcoding even for an otherwise eligible lossy mimeType`() {
        assertThat(transcoder.requiresTranscoding(song(mimeType = "audio/mpeg", bitrate = null))).isTrue()
    }

    @Test
    fun `mimeType is matched case-insensitively`() {
        assertThat(transcoder.requiresTranscoding(song(mimeType = "AUDIO/MPEG", bitrate = 128_000))).isFalse()
    }
}
