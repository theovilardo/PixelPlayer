package com.theveloper.pixelplay.usbaudio.negotiation

import com.theveloper.pixelplay.usbaudio.descriptor.FormatCandidate
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities

/** Decoded PCM as it leaves the player pipeline. */
data class SourceFormat(
    val sampleRateHz: Int,
    /** Bit depth of the integer representation; for float input pass 24 (see [effectiveBitDepth]). */
    val bitDepth: Int,
    val channels: Int,
    val isFloat: Boolean
) {
    /**
     * Float32 out of the decoders carries at most 24 significant bits, and any source that was
     * originally an integer of ≤24 bits survives the int→float→int trip exactly — so floats
     * negotiate as 24-bit for bit-perfect purposes.
     */
    val effectiveBitDepth: Int get() = if (isFloat) 24 else bitDepth
}

/** What had to happen to the samples on the way to the DAC. */
data class Conversion(
    val resampled: Boolean,
    val depthReduced: Boolean,
    val downmixed: Boolean
) {
    val isBitPerfect: Boolean get() = !resampled && !depthReduced && !downmixed

    companion object {
        val BIT_PERFECT = Conversion(resampled = false, depthReduced = false, downmixed = false)
    }
}

data class NegotiatedFormat(
    val candidate: FormatCandidate,
    val sampleRateHz: Int,
    val conversion: Conversion
)

/**
 * Chooses the alt setting + rate for a source format, preferring in order:
 * no downmix, no resampling, no depth reduction, tightest depth fit, closest rate.
 * Pure function — the full decision table lives in FormatNegotiatorTest.
 */
object FormatNegotiator {

    fun negotiate(source: SourceFormat, caps: UacCapabilities): NegotiatedFormat? {
        return caps.formats
            .mapNotNull { candidate -> evaluate(source, candidate) }
            .minWithOrNull(
                compareBy(
                    { if (it.conversion.downmixed) 1 else 0 },
                    { if (it.conversion.resampled) 1 else 0 },
                    { if (it.conversion.depthReduced) 1 else 0 },
                    // Tightest adequate depth (24-bit source → prefer 24 over 32)…
                    { depthOverhead(source, it.candidate) },
                    // …then the least-destructive rate choice.
                    { rateDistance(source.sampleRateHz, it.sampleRateHz) },
                    // Stable tiebreak.
                    { it.candidate.altSetting }
                )
            )
    }

    private fun evaluate(source: SourceFormat, candidate: FormatCandidate): NegotiatedFormat? {
        if (candidate.sampleRatesHz.isEmpty() || candidate.channels <= 0) return null

        val downmixed = source.channels > candidate.channels
        val depthReduced = source.effectiveBitDepth > candidate.bitResolution
        val (rate, resampled) = chooseRate(source.sampleRateHz, candidate.sampleRatesHz)

        return NegotiatedFormat(
            candidate = candidate,
            sampleRateHz = rate,
            conversion = Conversion(resampled = resampled, depthReduced = depthReduced, downmixed = downmixed)
        )
    }

    /**
     * Exact rate when available; otherwise the smallest same-family rate above the source
     * (44.1 kHz material stays on the 44.1 k clock family whenever possible), then the
     * smallest higher rate of any family, then the highest rate below.
     */
    internal fun chooseRate(sourceRate: Int, rates: List<Int>): Pair<Int, Boolean> {
        if (sourceRate in rates) return sourceRate to false

        val sameFamily = rates.filter { sameFamily(it, sourceRate) && it > sourceRate }.minOrNull()
        if (sameFamily != null) return sameFamily to true

        val higher = rates.filter { it > sourceRate }.minOrNull()
        if (higher != null) return higher to true

        return rates.max() to true
    }

    /** 44.1 kHz family (multiples of 11 025) vs 48 kHz family (multiples of 8 000). */
    internal fun sameFamily(a: Int, b: Int): Boolean =
        (a % 11_025 == 0) == (b % 11_025 == 0)

    private fun depthOverhead(source: SourceFormat, candidate: FormatCandidate): Int {
        val overhead = candidate.bitResolution - source.effectiveBitDepth
        // Inadequate depth sorts after any adequate depth; among inadequate, deeper is better.
        return if (overhead >= 0) overhead else 1000 - overhead
    }

    private fun rateDistance(sourceRate: Int, chosenRate: Int): Long {
        if (chosenRate == sourceRate) return 0
        // Upsampling is preferable to downsampling at equal distance.
        val distance = (chosenRate - sourceRate).toLong()
        return if (distance > 0) distance else 1_000_000_000L - distance
    }
}
