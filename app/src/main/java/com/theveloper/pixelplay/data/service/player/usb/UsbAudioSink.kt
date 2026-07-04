package com.theveloper.pixelplay.data.service.player.usb

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.theveloper.pixelplay.data.service.player.SurroundDownmixProcessor
import com.theveloper.pixelplay.usbaudio.UsbAudioSession
import com.theveloper.pixelplay.usbaudio.negotiation.FormatNegotiator
import com.theveloper.pixelplay.usbaudio.negotiation.NegotiatedFormat
import com.theveloper.pixelplay.usbaudio.negotiation.SourceFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Media3 [AudioSink] that feeds decoded PCM straight to a USB DAC through [UsbAudioSession],
 * bypassing AudioTrack/AudioFlinger entirely.
 *
 * Bit-perfect policy: on the negotiated bit-perfect path samples are only re-justified into
 * the DAC's subslot ([PcmRepacker]) — no resampling, no dithering, no volume scaling
 * ([setVolume] is a no-op). Conversion (downmix via [SurroundDownmixProcessor], resampling
 * via [SonicAudioProcessor] in 16-bit) happens only when the DAC cannot take the source
 * format, and is reported through [onFormatChanged].
 *
 * A dead session (device unplugged) never throws into the player: buffers are swallowed and
 * [onSessionDead] fires so the exclusive-mode controller can pause and fall back gracefully.
 */
@UnstableApi
class UsbAudioSink(
    private val session: UsbAudioSession,
    private val onFormatChanged: (NegotiatedFormat?, SourceFormat?) -> Unit = { _, _ -> },
    private val onSessionDead: () -> Unit = {}
) : AudioSink {

    private var listener: AudioSink.Listener? = null

    private var inputFormat: Format? = null
    private var sourceFormat: SourceFormat? = null
    private var negotiated: NegotiatedFormat? = null
    private var sourceEncoding: PcmRepacker.Encoding? = null

    /** Conversion pipeline, present only on non-bit-perfect paths. */
    private var downmixProcessor: SurroundDownmixProcessor? = null
    private var resampleProcessor: SonicAudioProcessor? = null

    /** Converted wire-format data waiting for ring space (bounded: one input buffer). */
    private var pendingWire: ByteBuffer = EMPTY_BUFFER
    private var wireScratch: ByteBuffer = EMPTY_BUFFER
    private var pcm16Scratch: ByteBuffer = EMPTY_BUFFER

    private var startMediaTimeUs = 0L
    private var startMediaTimeSet = false
    private var flushBaseFrames = 0L
    private var lastPositionUs = Long.MIN_VALUE
    private var draining = false
    private var reportedXruns = 0
    private var sessionDeadReported = false

    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var audioAttributes: AudioAttributes? = null
    private var playing = false

    // ─── Format support ──────────────────────────────────────────────────────

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType != androidx.media3.common.MimeTypes.AUDIO_RAW) {
            // No encoded passthrough over USB: keep the renderers decoding.
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        val supportedEncoding = format.pcmEncoding == C.ENCODING_PCM_16BIT ||
            format.pcmEncoding == C.ENCODING_PCM_24BIT ||
            format.pcmEncoding == C.ENCODING_PCM_32BIT ||
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
        return if (supportedEncoding && format.channelCount in 1..8 && format.sampleRate > 0) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    // ─── Configuration ───────────────────────────────────────────────────────

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (getFormatSupport(inputFormat) != AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            throw AudioSink.ConfigurationException("Unsupported input format", inputFormat)
        }

        val encoding = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> PcmRepacker.Encoding.PCM_16
            C.ENCODING_PCM_24BIT -> PcmRepacker.Encoding.PCM_24
            C.ENCODING_PCM_32BIT -> PcmRepacker.Encoding.PCM_32
            else -> PcmRepacker.Encoding.FLOAT
        }
        val source = SourceFormat(
            sampleRateHz = inputFormat.sampleRate,
            bitDepth = when (encoding) {
                PcmRepacker.Encoding.PCM_16 -> 16
                PcmRepacker.Encoding.PCM_24 -> 24
                else -> 32
            },
            channels = inputFormat.channelCount,
            isFloat = encoding == PcmRepacker.Encoding.FLOAT
        )

        val format = FormatNegotiator.negotiate(source, session.capabilities)
            ?: throw AudioSink.ConfigurationException("No negotiable DAC format", inputFormat)

        val sameStream = negotiated == format && session.currentFormat == format
        if (!sameStream) {
            if (!session.configure(format)) {
                throw AudioSink.ConfigurationException(
                    "DAC rejected ${format.sampleRateHz} Hz alt ${format.candidate.altSetting}: ${session.lastError}",
                    inputFormat
                )
            }
            // A freshly configured stream starts paused (silent PLL priming). Mid-playback
            // format changes — gapless 44.1→96 transitions — must resume it themselves:
            // ExoPlayer only re-calls play() after user-initiated actions.
            if (playing) session.resume()
        }

        this.inputFormat = inputFormat
        this.sourceFormat = source
        this.sourceEncoding = encoding
        this.negotiated = format
        buildConversionPipeline(source, encoding, format)

        pendingWire = EMPTY_BUFFER
        startMediaTimeSet = false
        lastPositionUs = Long.MIN_VALUE
        flushBaseFrames = session.consumedFrames
        draining = false
        sessionDeadReported = false

        Timber.tag(TAG).i(
            "Configured: %d Hz/%d-bit/%dch → DAC alt %d %d Hz/%d-bit (bitPerfect=%b)",
            source.sampleRateHz, source.bitDepth, source.channels,
            format.candidate.altSetting, format.sampleRateHz, format.candidate.bitResolution,
            format.conversion.isBitPerfect
        )
        onFormatChanged(format, source)
    }

    private fun buildConversionPipeline(
        source: SourceFormat,
        encoding: PcmRepacker.Encoding,
        format: NegotiatedFormat
    ) {
        downmixProcessor = null
        resampleProcessor = null

        var stageRate = source.sampleRateHz
        var stageChannels = source.channels
        var stageEncoding = encoding

        if (format.conversion.downmixed && (source.channels == 6 || source.channels == 8) &&
            (encoding == PcmRepacker.Encoding.PCM_16 || encoding == PcmRepacker.Encoding.FLOAT)
        ) {
            val processor = SurroundDownmixProcessor()
            processor.configure(
                AudioProcessor.AudioFormat(
                    stageRate, stageChannels,
                    if (encoding == PcmRepacker.Encoding.FLOAT) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                )
            )
            processor.flush()
            downmixProcessor = processor
            stageChannels = 2
        }

        if (format.conversion.resampled) {
            // Resampling is inherently lossy; Sonic runs in 16-bit, so this already-converted
            // path drops to 16-bit first. The bit-perfect path never comes through here.
            val processor = SonicAudioProcessor()
            processor.setOutputSampleRateHz(format.sampleRateHz)
            processor.configure(
                AudioProcessor.AudioFormat(stageRate, stageChannels, C.ENCODING_PCM_16BIT)
            )
            processor.flush()
            resampleProcessor = processor
            stageEncoding = PcmRepacker.Encoding.PCM_16
        }
    }

    // ─── Data path ───────────────────────────────────────────────────────────

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val format = negotiated ?: return true.also { buffer.position(buffer.limit()) }

        if (!session.isAlive) {
            reportSessionDeadOnce()
            buffer.position(buffer.limit()) // swallow; controller is falling back
            return true
        }

        if (!drainPendingWire()) return false

        if (!startMediaTimeSet) {
            startMediaTimeUs = presentationTimeUs
            startMediaTimeSet = true
        }

        convertToWire(buffer, format)
        drainPendingWire()
        return true
    }

    /** Converts the whole input buffer into [pendingWire] (wire format, direct buffer). */
    private fun convertToWire(buffer: ByteBuffer, format: NegotiatedFormat) {
        val encoding = sourceEncoding ?: return
        val source = sourceFormat ?: return
        var stage: ByteBuffer = buffer
        var stageEncoding = encoding
        var stageChannels = source.channels

        downmixProcessor?.let { processor ->
            stage = pushThrough(processor, stage)
            stageChannels = 2
        }

        resampleProcessor?.let { processor ->
            if (stageEncoding != PcmRepacker.Encoding.PCM_16) {
                val needed = (stage.remaining() / stageEncoding.bytesPerSample) * 2
                pcm16Scratch = ensureCapacity(pcm16Scratch, needed)
                pcm16Scratch.clear()
                PcmRepacker.toPcm16(stage, stageEncoding, pcm16Scratch)
                pcm16Scratch.flip()
                stage = pcm16Scratch
                stageEncoding = PcmRepacker.Encoding.PCM_16
            }
            stage = pushThrough(processor, stage)
        }

        val candidate = format.candidate
        val outBytes = PcmRepacker.outputSize(
            stage.remaining(), stageEncoding, stageChannels, candidate.channels, candidate.subslotBytes
        )
        // Keep whatever wasn't flushed yet and append (bounded: ring backpressure stops the
        // renderer from feeding more than ~2 buffers ahead).
        val keep = pendingWire.remaining()
        wireScratch = ensureCapacity(wireScratch, keep + outBytes)
        wireScratch.clear()
        if (keep > 0) wireScratch.put(pendingWire)
        PcmRepacker.repack(
            stage, stageEncoding, stageChannels, candidate.channels, candidate.subslotBytes, wireScratch
        )
        wireScratch.flip()
        // Swap scratch and pending so the next round reuses the other buffer.
        val previousPending = pendingWire
        pendingWire = wireScratch
        wireScratch = if (previousPending === EMPTY_BUFFER) EMPTY_BUFFER else previousPending

        buffer.position(buffer.limit())
    }

    /**
     * Runs [input] fully through an [AudioProcessor], accumulating every output chunk.
     * Only used on conversion paths (downmix/resample) — never on the bit-perfect path.
     */
    private fun pushThrough(processor: AudioProcessor, input: ByteBuffer): ByteBuffer {
        var accumulator = ByteBuffer.allocate(maxOf(input.remaining() * 2, 4096))
        fun append(chunk: ByteBuffer) {
            if (chunk.remaining() > accumulator.remaining()) {
                val grown = ByteBuffer.allocate((accumulator.position() + chunk.remaining()) * 2)
                accumulator.flip()
                grown.put(accumulator)
                accumulator = grown
            }
            accumulator.put(chunk)
        }
        while (input.hasRemaining()) {
            processor.queueInput(input)
            while (true) {
                val out = processor.output
                if (!out.hasRemaining()) break
                append(out)
            }
        }
        while (true) {
            val out = processor.output
            if (!out.hasRemaining()) break
            append(out)
        }
        accumulator.flip()
        return accumulator.order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Returns true when nothing is left pending. */
    private fun drainPendingWire(): Boolean {
        if (!pendingWire.hasRemaining()) return true
        if (!session.isAlive) {
            reportSessionDeadOnce()
            pendingWire.position(pendingWire.limit())
            return true
        }
        val written = session.write(pendingWire, pendingWire.position(), pendingWire.remaining())
        if (written < 0) {
            reportSessionDeadOnce()
            pendingWire.position(pendingWire.limit())
            return true
        }
        if (written > 0) {
            pendingWire.position(pendingWire.position() + written)
        }
        maybeReportUnderruns()
        return !pendingWire.hasRemaining()
    }

    private fun reportSessionDeadOnce() {
        if (!sessionDeadReported) {
            sessionDeadReported = true
            Timber.tag(TAG).w("USB session dead: %s", session.lastError ?: "device detached")
            onSessionDead()
        }
    }

    private fun maybeReportUnderruns() {
        val xruns = session.xrunCount
        if (xruns > reportedXruns) {
            val bufferSizeMs = UsbAudioSession.DEFAULT_RING_BUFFER_MS.toLong()
            listener?.onUnderrun((xruns - reportedXruns), bufferSizeMs, bufferSizeMs)
            reportedXruns = xruns
        }
    }

    // ─── Position ────────────────────────────────────────────────────────────

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val format = negotiated ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val source = sourceFormat ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (!startMediaTimeSet) return AudioSink.CURRENT_POSITION_NOT_SET

        val playedOutputFrames = (session.playedFrames - flushBaseFrames).coerceAtLeast(0)
        // Map DAC frames back to media time; with resampling the output rate differs from
        // the source rate, so scale through the output rate directly.
        val mediaTimeUs = startMediaTimeUs +
            playedOutputFrames * 1_000_000L / format.sampleRateHz.coerceAtLeast(1)

        // Monotonic within one rebase epoch (seek/flush resets).
        val clamped = if (lastPositionUs != Long.MIN_VALUE) maxOf(lastPositionUs, mediaTimeUs) else mediaTimeUs
        lastPositionUs = clamped
        return clamped
    }

    override fun handleDiscontinuity() {
        // Rebase on the next buffer's presentation time (gapless transitions).
        startMediaTimeSet = false
        lastPositionUs = Long.MIN_VALUE
    }

    // ─── Transport ───────────────────────────────────────────────────────────

    override fun play() {
        playing = true
        session.resume()
    }

    override fun pause() {
        playing = false
        session.pause()
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {
        draining = true
        drainPendingWire()
    }

    override fun isEnded(): Boolean {
        if (!draining) return false
        drainPendingWire()
        return !pendingWire.hasRemaining() && session.bufferedFrames == 0L
    }

    override fun hasPendingData(): Boolean {
        if (!session.isAlive) return false
        drainPendingWire()
        return pendingWire.hasRemaining() || session.bufferedFrames > 0L
    }

    override fun flush() {
        session.flush()
        pendingWire = EMPTY_BUFFER
        downmixProcessor?.flush()
        resampleProcessor?.flush()
        flushBaseFrames = session.consumedFrames
        startMediaTimeSet = false
        lastPositionUs = Long.MIN_VALUE
        draining = false
    }

    override fun reset() {
        flush()
        playing = false
        inputFormat = null
        sourceFormat = null
        sourceEncoding = null
        negotiated = null
        downmixProcessor = null
        resampleProcessor = null
        onFormatChanged(null, null)
        // The session itself is owned by the exclusive-mode controller, not the sink.
    }

    // ─── Bit-perfect no-ops and bookkeeping ──────────────────────────────────

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Speed/pitch processing would break bit-perfect output; exclusive mode plays 1×.
        if (playbackParameters != PlaybackParameters.DEFAULT) {
            Timber.tag(TAG).i("Ignoring playback parameters %s in USB exclusive mode", playbackParameters)
        }
        this.playbackParameters = PlaybackParameters.DEFAULT
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = false // not supported on the bit-perfect path
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes // stored; no AudioTrack to apply them to
    }

    override fun getAudioAttributes(): AudioAttributes? = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) = Unit // no platform audio session

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit // no effects by design

    override fun getAudioTrackBufferSizeUs(): Long =
        if (negotiated != null) UsbAudioSession.DEFAULT_RING_BUFFER_MS * 1000L else C.TIME_UNSET

    override fun enableTunnelingV21() = Unit

    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) {
        // Safety net: ReplayGain/crossfade are disabled upstream while exclusive.
        if (volume != 1f) {
            Timber.tag(TAG).w("Ignoring setVolume(%f) — no volume scaling in bit-perfect path", volume)
        }
    }

    private companion object {
        const val TAG = "UsbAudioSink"
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

        fun ensureCapacity(buffer: ByteBuffer, needed: Int): ByteBuffer =
            if (buffer.capacity() >= needed) buffer
            else ByteBuffer.allocateDirect(Integer.highestOneBit(needed.coerceAtLeast(1)) * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
    }
}
