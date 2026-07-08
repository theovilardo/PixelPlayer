package com.theveloper.pixelplay.data.service.player.usb

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.usbaudio.UsbAudioSession
import com.theveloper.pixelplay.usbaudio.descriptor.EndpointSyncType
import com.theveloper.pixelplay.usbaudio.descriptor.FormatCandidate
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacVersion
import com.theveloper.pixelplay.usbaudio.descriptor.VolumeCapability
import com.theveloper.pixelplay.usbaudio.negotiation.NegotiatedFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UsbAudioSinkTest {

    private val capabilities = UacCapabilities(
        version = UacVersion.UAC2,
        controlInterfaceNumber = 0,
        formats = listOf(
            FormatCandidate(
                interfaceNumber = 1, altSetting = 1, channels = 2, subslotBytes = 2,
                bitResolution = 16, sampleRatesHz = listOf(44_100, 48_000, 96_000),
                endpointAddress = 0x01, maxPacketSize = 512, intervalCode = 1,
                syncType = EndpointSyncType.ASYNCHRONOUS, feedbackEndpointAddress = 0x81,
                clockSourceId = 0x29, uac1SampleRateControl = false
            ),
            FormatCandidate(
                interfaceNumber = 1, altSetting = 2, channels = 2, subslotBytes = 4,
                bitResolution = 32, sampleRatesHz = listOf(44_100, 48_000, 96_000),
                endpointAddress = 0x01, maxPacketSize = 1024, intervalCode = 1,
                syncType = EndpointSyncType.ASYNCHRONOUS, feedbackEndpointAddress = 0x81,
                clockSourceId = 0x29, uac1SampleRateControl = false
            )
        ),
        // Hardware volume present → the sink negotiates for minimal conversion (matching
        // depth) instead of preferring the deepest subslot for the software gain stage.
        volume = VolumeCapability(featureUnitId = 0x0A, hasMasterVolume = true, hasMasterMute = true)
    )

    private lateinit var session: UsbAudioSession
    private var alive = true
    private var acceptBytes = Int.MAX_VALUE
    private val written = mutableListOf<ByteArray>()
    private var playedFrames = 0L
    private var consumedFrames = 0L
    private var bufferedFrames = 0L
    private var configuredFormat: NegotiatedFormat? = null

    @BeforeEach
    fun setUp() {
        alive = true
        acceptBytes = Int.MAX_VALUE
        written.clear()
        playedFrames = 0L
        consumedFrames = 0L
        bufferedFrames = 0L
        configuredFormat = null

        session = mockk(relaxed = true) {
            every { capabilities } returns this@UsbAudioSinkTest.capabilities
            every { softwareGainQ16 } returns UsbAudioSession.UNITY_GAIN_Q16
            every { isAlive } answers { alive }
            every { currentFormat } answers { configuredFormat }
            every { configure(any(), any()) } answers {
                configuredFormat = firstArg()
                true
            }
            every { configure(any()) } answers {
                configuredFormat = firstArg()
                true
            }
            every { playedFrames } answers { this@UsbAudioSinkTest.playedFrames }
            every { consumedFrames } answers { this@UsbAudioSinkTest.consumedFrames }
            every { bufferedFrames } answers { this@UsbAudioSinkTest.bufferedFrames }
            every { xrunCount } returns 0
            every { lastError } returns null
            every { write(any(), any(), any()) } answers {
                val buffer = firstArg<ByteBuffer>()
                val offset = secondArg<Int>()
                val size = thirdArg<Int>()
                if (!alive) -1
                else {
                    val accepted = minOf(size, acceptBytes)
                    val copy = ByteArray(accepted)
                    val dup = buffer.duplicate()
                    dup.position(offset)
                    dup.get(copy)
                    if (accepted > 0) written += copy
                    accepted
                }
            }
        }
    }

    private fun sink(
        onFormatChanged: (NegotiatedFormat?, com.theveloper.pixelplay.usbaudio.negotiation.SourceFormat?) -> Unit = { _, _ -> },
        onSessionDead: () -> Unit = {}
    ) = UsbAudioSink(session, onFormatChanged, onSessionDead)

    private fun pcm16Format(rate: Int = 44_100, channels: Int = 2): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelCount(channels)
        .build()

    private fun directBuffer(vararg bytes: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.forEach { buffer.put(it.toByte()) }
        buffer.flip()
        return buffer
    }

    @Test
    fun `rejects encoded formats so renderers keep decoding`() {
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        assertThat(sink().getFormatSupport(aac)).isEqualTo(AudioSink.SINK_FORMAT_UNSUPPORTED)
    }

    @Test
    fun `supports pcm 16 24 32 and float`() {
        for (encoding in intArrayOf(
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT
        )) {
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelCount(2)
                .build()
            assertThat(sink().getFormatSupport(format))
                .isEqualTo(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        }
    }

    @Test
    fun `configure negotiates a bit-perfect format and reports it`() {
        var reported: NegotiatedFormat? = null
        val sink = sink(onFormatChanged = { format, _ -> reported = format })

        sink.configure(pcm16Format(rate = 44_100), 0, null)

        assertThat(configuredFormat).isNotNull()
        assertThat(configuredFormat!!.sampleRateHz).isEqualTo(44_100)
        assertThat(configuredFormat!!.candidate.bitResolution).isEqualTo(16)
        assertThat(configuredFormat!!.conversion.isBitPerfect).isTrue()
        assertThat(reported).isEqualTo(configuredFormat)
    }

    @Test
    fun `handleBuffer repacks and writes to the session`() {
        val sink = sink()
        sink.configure(pcm16Format(), 0, null)

        val consumed = sink.handleBuffer(directBuffer(0x34, 0x12, 0xDC, 0xFE), 0L, 1)

        assertThat(consumed).isTrue()
        assertThat(written.single().toList().map { it.toInt() and 0xFF })
            .containsExactly(0x34, 0x12, 0xDC, 0xFE).inOrder()
    }

    @Test
    fun `full ring backpressures without losing data`() {
        val sink = sink()
        sink.configure(pcm16Format(), 0, null)

        acceptBytes = 2 // ring only takes 2 bytes per write call
        assertThat(sink.handleBuffer(directBuffer(0x01, 0x02, 0x03, 0x04), 0L, 1)).isTrue()

        // Next buffer can't go in until pending wire data drains.
        acceptBytes = 0
        val next = directBuffer(0x05, 0x06, 0x07, 0x08)
        assertThat(sink.handleBuffer(next, 1000L, 1)).isFalse()
        assertThat(next.remaining()).isEqualTo(4) // untouched

        acceptBytes = Int.MAX_VALUE
        assertThat(sink.handleBuffer(next, 1000L, 1)).isTrue()
        assertThat(written.flatMap { it.toList() }.map { it.toInt() and 0xFF })
            .containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08).inOrder()
    }

    @Test
    fun `position advances with played frames and rebases on flush`() {
        val sink = sink()
        sink.configure(pcm16Format(rate = 44_100), 0, null)

        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET)

        sink.handleBuffer(directBuffer(0, 0, 0, 0), 500_000L, 1)
        playedFrames = 44_100 // one second played
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(500_000L + 1_000_000L)

        // Seek: flush snapshots consumed frames as the new base.
        consumedFrames = 44_100
        sink.flush()
        playedFrames = 44_100
        sink.handleBuffer(directBuffer(0, 0, 0, 0), 30_000_000L, 1)
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(30_000_000L)
    }

    @Test
    fun `isEnded only after drain completes`() {
        val sink = sink()
        sink.configure(pcm16Format(), 0, null)
        sink.handleBuffer(directBuffer(0, 0, 0, 0), 0L, 1)

        bufferedFrames = 10
        sink.playToEndOfStream()
        assertThat(sink.isEnded()).isFalse()

        bufferedFrames = 0
        assertThat(sink.isEnded()).isTrue()
    }

    @Test
    fun `dead session swallows buffers and notifies once`() {
        var deadCalls = 0
        val sink = sink(onSessionDead = { deadCalls++ })
        sink.configure(pcm16Format(), 0, null)

        alive = false
        val buffer = directBuffer(1, 2, 3, 4)
        assertThat(sink.handleBuffer(buffer, 0L, 1)).isTrue()
        assertThat(buffer.hasRemaining()).isFalse()
        assertThat(sink.handleBuffer(directBuffer(5, 6), 0L, 1)).isTrue()
        assertThat(deadCalls).isEqualTo(1)
    }

    @Test
    fun `format change while playing resumes the new stream`() {
        val sink = sink()
        sink.configure(pcm16Format(rate = 44_100), 0, null)
        sink.play()
        io.mockk.verify(exactly = 1) { session.resume() }

        // Gapless transition into a different rate: nothing re-calls play(), so the
        // sink itself must resume the freshly configured (paused) stream.
        sink.configure(pcm16Format(rate = 96_000), 0, null)
        io.mockk.verify(exactly = 2) { session.resume() }
    }

    @Test
    fun `format change while paused stays paused`() {
        val sink = sink()
        sink.configure(pcm16Format(rate = 44_100), 0, null)
        sink.configure(pcm16Format(rate = 96_000), 0, null)
        io.mockk.verify(exactly = 0) { session.resume() }
    }

    @Test
    fun `volume changes are ignored on the bit-perfect path`() {
        val sink = sink()
        sink.configure(pcm16Format(), 0, null)
        sink.setVolume(0.5f) // must not touch the session or the PCM path
        sink.handleBuffer(directBuffer(0x34, 0x12, 0xDC, 0xFE), 0L, 1)
        assertThat(written.single().toList().map { it.toInt() and 0xFF })
            .containsExactly(0x34, 0x12, 0xDC, 0xFE).inOrder()
    }

    @Test
    fun `hi-res float input picks the 32-bit alt setting`() {
        val sink = sink()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(96_000)
            .setChannelCount(2)
            .build()
        sink.configure(format, 0, null)

        assertThat(configuredFormat!!.candidate.subslotBytes).isEqualTo(4)
        assertThat(configuredFormat!!.sampleRateHz).isEqualTo(96_000)
        assertThat(configuredFormat!!.conversion.isBitPerfect).isTrue()

        // 0.5f left channel, -1.0f right → left-justified 32-bit wire samples
        val floats = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        floats.putFloat(0.5f).putFloat(-1.0f)
        floats.flip()
        sink.handleBuffer(floats, 0L, 1)

        val wire = ByteBuffer.wrap(written.single()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(wire.int).isEqualTo(0x40000000)
        assertThat(wire.int).isEqualTo(Int.MIN_VALUE)
    }
}
