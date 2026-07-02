package com.theveloper.pixelplay.usbaudio.negotiation

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.usbaudio.descriptor.EndpointSyncType
import com.theveloper.pixelplay.usbaudio.descriptor.FormatCandidate
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacVersion
import org.junit.jupiter.api.Test

class FormatNegotiatorTest {

    private fun candidate(
        altSetting: Int,
        bitResolution: Int,
        subslotBytes: Int = (bitResolution + 7) / 8,
        channels: Int = 2,
        rates: List<Int> = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    ) = FormatCandidate(
        interfaceNumber = 1,
        altSetting = altSetting,
        channels = channels,
        subslotBytes = subslotBytes,
        bitResolution = bitResolution,
        sampleRatesHz = rates,
        endpointAddress = 0x01,
        maxPacketSize = 1024,
        intervalCode = 1,
        syncType = EndpointSyncType.ASYNCHRONOUS,
        feedbackEndpointAddress = 0x81,
        clockSourceId = 0x29,
        uac1SampleRateControl = false
    )

    private fun caps(vararg formats: FormatCandidate) = UacCapabilities(
        version = UacVersion.UAC2,
        controlInterfaceNumber = 0,
        formats = formats.toList(),
        volume = null
    )

    private val typicalDac = caps(
        candidate(altSetting = 1, bitResolution = 16),
        candidate(altSetting = 2, bitResolution = 32, subslotBytes = 4)
    )

    @Test
    fun `cd audio on a matching dac is bit perfect at 44100`() {
        val result = FormatNegotiator.negotiate(SourceFormat(44_100, 16, 2, isFloat = false), typicalDac)!!
        assertThat(result.sampleRateHz).isEqualTo(44_100)
        assertThat(result.candidate.bitResolution).isEqualTo(16)
        assertThat(result.conversion.isBitPerfect).isTrue()
    }

    @Test
    fun `hi-res float source picks the 32-bit alt setting bit perfectly`() {
        // 24/96 FLAC decoded to float by the FFmpeg renderer
        val result = FormatNegotiator.negotiate(SourceFormat(96_000, 32, 2, isFloat = true), typicalDac)!!
        assertThat(result.sampleRateHz).isEqualTo(96_000)
        assertThat(result.candidate.altSetting).isEqualTo(2)
        assertThat(result.conversion.isBitPerfect).isTrue()
    }

    @Test
    fun `24-bit int source prefers tightest adequate depth`() {
        val dac = caps(
            candidate(altSetting = 1, bitResolution = 24, subslotBytes = 3),
            candidate(altSetting = 2, bitResolution = 32, subslotBytes = 4)
        )
        val result = FormatNegotiator.negotiate(SourceFormat(96_000, 24, 2, isFloat = false), dac)!!
        assertThat(result.candidate.bitResolution).isEqualTo(24)
        assertThat(result.conversion.isBitPerfect).isTrue()
    }

    @Test
    fun `rate the dac lacks resamples within the same clock family upwards`() {
        val dac = caps(candidate(altSetting = 1, bitResolution = 24, rates = listOf(48_000, 88_200, 96_000)))
        val result = FormatNegotiator.negotiate(SourceFormat(44_100, 16, 2, isFloat = false), dac)!!
        // 44.1k material goes to 88.2k (same family), not 48k (nearer but cross-family)
        assertThat(result.sampleRateHz).isEqualTo(88_200)
        assertThat(result.conversion.resampled).isTrue()
        assertThat(result.conversion.isBitPerfect).isFalse()
    }

    @Test
    fun `rate above the dac maximum downsamples to the highest supported`() {
        val dac = caps(candidate(altSetting = 1, bitResolution = 24, rates = listOf(44_100, 48_000, 96_000)))
        val result = FormatNegotiator.negotiate(SourceFormat(192_000, 24, 2, isFloat = false), dac)!!
        assertThat(result.sampleRateHz).isEqualTo(96_000)
        assertThat(result.conversion.resampled).isTrue()
    }

    @Test
    fun `24-bit source on a 16-bit-only dac reports depth reduction`() {
        val dac = caps(candidate(altSetting = 1, bitResolution = 16))
        val result = FormatNegotiator.negotiate(SourceFormat(96_000, 24, 2, isFloat = false), dac)!!
        assertThat(result.candidate.bitResolution).isEqualTo(16)
        assertThat(result.conversion.depthReduced).isTrue()
        assertThat(result.conversion.resampled).isFalse()
    }

    @Test
    fun `surround source on a stereo dac reports downmix`() {
        val result = FormatNegotiator.negotiate(SourceFormat(48_000, 16, 6, isFloat = false), typicalDac)!!
        assertThat(result.conversion.downmixed).isTrue()
        assertThat(result.conversion.resampled).isFalse()
    }

    @Test
    fun `mono source on a stereo dac stays bit perfect`() {
        val result = FormatNegotiator.negotiate(SourceFormat(48_000, 16, 1, isFloat = false), typicalDac)!!
        assertThat(result.conversion.isBitPerfect).isTrue()
    }

    @Test
    fun `no formats yields null`() {
        assertThat(FormatNegotiator.negotiate(SourceFormat(44_100, 16, 2, isFloat = false), caps())).isNull()
    }

    @Test
    fun `prefers exact rate over tighter depth`() {
        val dac = caps(
            candidate(altSetting = 1, bitResolution = 24, rates = listOf(48_000)),
            candidate(altSetting = 2, bitResolution = 32, subslotBytes = 4, rates = listOf(44_100, 48_000))
        )
        val result = FormatNegotiator.negotiate(SourceFormat(44_100, 24, 2, isFloat = false), dac)!!
        assertThat(result.candidate.altSetting).isEqualTo(2)
        assertThat(result.conversion.isBitPerfect).isTrue()
    }

    @Test
    fun `chooseRate helper table`() {
        val rates = listOf(44_100, 48_000, 88_200, 96_000)
        assertThat(FormatNegotiator.chooseRate(44_100, rates)).isEqualTo(44_100 to false)
        assertThat(FormatNegotiator.chooseRate(22_050, rates)).isEqualTo(44_100 to true)
        assertThat(FormatNegotiator.chooseRate(176_400, rates)).isEqualTo(96_000 to true)
        assertThat(FormatNegotiator.chooseRate(32_000, rates)).isEqualTo(48_000 to true)
    }

    @Test
    fun `family helper distinguishes 44k1 and 48k families`() {
        assertThat(FormatNegotiator.sameFamily(44_100, 88_200)).isTrue()
        assertThat(FormatNegotiator.sameFamily(48_000, 96_000)).isTrue()
        assertThat(FormatNegotiator.sameFamily(44_100, 48_000)).isFalse()
        assertThat(FormatNegotiator.sameFamily(22_050, 176_400)).isTrue()
    }
}
