package com.theveloper.pixelplay.data.service.player.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Pure PCM repacking for the USB wire format — the only place samples are ever transformed
 * on the bit-perfect path, and by design it only ever:
 *  - re-justifies samples into the DAC's subslot (left-justified per UAC Type I), which is
 *    lossless zero-padding when the target depth is >= the source depth;
 *  - duplicates a mono channel / zero-fills missing channels;
 *  - truncates (no dither, by policy) when the DAC genuinely offers less depth.
 *
 * Internally every sample becomes a 32-bit MSB-aligned value ("s32top"); writing the top
 * `subslotBytes` bytes little-endian then produces the correct left-justified wire sample
 * for any 16/24/32-bit subslot.
 */
object PcmRepacker {

    enum class Encoding(val bytesPerSample: Int) {
        PCM_16(2),
        /** 3-byte packed little-endian (Media3 C.ENCODING_PCM_24BIT). */
        PCM_24(3),
        PCM_32(4),
        FLOAT(4)
    }

    /** Unity gain in Q16 fixed point — the bit-perfect passthrough value. */
    const val UNITY_GAIN_Q16: Int = 1 shl 16

    /** Converts an attenuation in dB (≤ 0) to Q16 gain; null/0 dB → [UNITY_GAIN_Q16]. */
    fun gainQ16FromDb(db: Float?): Int = when {
        db == null || db >= 0f -> UNITY_GAIN_Q16
        db <= -90f -> 0
        else -> (Math.pow(10.0, db / 20.0) * UNITY_GAIN_Q16).toInt()
    }

    /** Bytes [repack] will produce for [inputBytes] of source data. */
    fun outputSize(inputBytes: Int, source: Encoding, sourceChannels: Int, targetChannels: Int, subslotBytes: Int): Int {
        val frameBytes = source.bytesPerSample * sourceChannels
        if (frameBytes == 0) return 0
        val frames = inputBytes / frameBytes
        return frames * targetChannels * subslotBytes
    }

    /**
     * Converts everything between `input.position()` and `input.limit()` into the wire format,
     * appending to [output] (which must have enough space — see [outputSize]) in little-endian
     * order. Channel handling: equal counts copy through; mono duplicates into the first two
     * target channels; missing channels are silence; excess source channels are dropped
     * (callers downmix 5.1/7.1 upstream — dropping is only the last-resort fallback).
     *
     * [gainQ16] is the software-volume stage for DACs without a hardware volume control:
     * at [UNITY_GAIN_Q16] samples pass through untouched (the bit-perfect path takes no
     * multiplication); below unity, scaling happens here in the 32-bit domain — before
     * subslot packing — so attenuated 16-bit material keeps full fidelity into a
     * 24/32-bit subslot.
     */
    fun repack(
        input: ByteBuffer,
        source: Encoding,
        sourceChannels: Int,
        targetChannels: Int,
        subslotBytes: Int,
        output: ByteBuffer,
        gainQ16: Int = UNITY_GAIN_Q16
    ) {
        val in_ = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = source.bytesPerSample * sourceChannels
        val frames = (in_.limit() - in_.position()) / frameBytes

        val applyGain = gainQ16 != UNITY_GAIN_Q16
        val copyChannels = minOf(sourceChannels, targetChannels)
        for (frame in 0 until frames) {
            val frameBase = in_.position() + frame * frameBytes
            for (channel in 0 until targetChannels) {
                val sourceChannel = when {
                    channel < copyChannels -> channel
                    sourceChannels == 1 && channel == 1 -> 0 // mono → duplicate into R
                    else -> -1 // silence
                }
                var s32top = if (sourceChannel >= 0) {
                    readS32Top(in_, frameBase + sourceChannel * source.bytesPerSample, source)
                } else {
                    0
                }
                if (applyGain) {
                    s32top = ((s32top.toLong() * gainQ16) shr 16).toInt()
                }
                writeSubslot(output, s32top, subslotBytes)
            }
        }
        input.position(input.limit())
    }

    /**
     * Converts to interleaved 16-bit PCM with the same channel count (truncating, no dither).
     * Only used on the already-lossy resample path, never on the bit-perfect path.
     */
    fun toPcm16(input: ByteBuffer, source: Encoding, output: ByteBuffer) {
        val in_ = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        var offset = in_.position()
        while (offset + source.bytesPerSample <= in_.limit()) {
            val s32top = readS32Top(in_, offset, source)
            output.putShort((s32top shr 16).toShort())
            offset += source.bytesPerSample
        }
        input.position(input.limit())
    }

    /** Reads one sample as a 32-bit MSB-aligned signed value. */
    internal fun readS32Top(buffer: ByteBuffer, offset: Int, source: Encoding): Int = when (source) {
        Encoding.PCM_16 -> buffer.getShort(offset).toInt() shl 16
        Encoding.PCM_24 -> {
            val u = (buffer.get(offset).toInt() and 0xFF) or
                ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
                ((buffer.get(offset + 2).toInt() and 0xFF) shl 16)
            // Sign-extend from bit 23, then left-justify.
            (u shl 8)
        }
        Encoding.PCM_32 -> buffer.getInt(offset)
        Encoding.FLOAT -> {
            val v = buffer.getFloat(offset)
            // Scaling by 2^31 is exponent-only, so ≤24-bit integer material stays exact.
            (v.toDouble() * 2147483648.0).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        }
    }

    /** Writes the top [subslotBytes] bytes of [s32top], little-endian (left-justified sample). */
    internal fun writeSubslot(output: ByteBuffer, s32top: Int, subslotBytes: Int) {
        when (subslotBytes) {
            2 -> output.putShort((s32top shr 16).toShort())
            3 -> {
                val v = s32top ushr 8
                output.put((v and 0xFF).toByte())
                output.put(((v shr 8) and 0xFF).toByte())
                output.put(((v shr 16) and 0xFF).toByte())
            }
            4 -> output.putInt(s32top)
            else -> throw IllegalArgumentException("Unsupported subslot size: $subslotBytes")
        }
    }
}
