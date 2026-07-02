package com.theveloper.pixelplay.usbaudio.descriptor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UacCapabilityProberTest {

    private fun topology(raw: ByteArray): UacTopology =
        (UsbDescriptorParser.parse(raw) as ParseResult.Success).topology

    /** Encodes a UAC2 RANGE response: wNumSubRanges + N × (dMIN, dMAX, dRES), little-endian. */
    private fun rangeResponse(vararg subRanges: Triple<Long, Long, Long>): ByteArray {
        val out = ByteArray(2 + subRanges.size * 12)
        out[0] = (subRanges.size and 0xFF).toByte()
        out[1] = ((subRanges.size shr 8) and 0xFF).toByte()
        subRanges.forEachIndexed { i, (min, max, res) ->
            var offset = 2 + i * 12
            for (value in longArrayOf(min, max, res)) {
                for (b in 0 until 4) out[offset + b] = ((value shr (8 * b)) and 0xFF).toByte()
                offset += 4
            }
        }
        return out
    }

    private fun cannedTransfer(response: ByteArray) = UsbControlTransfer { _, request, value, index, buffer ->
        // RANGE (0x02) on the sampling-frequency control of clock 0x29, AC interface 0.
        assertThat(request).isEqualTo(0x02)
        assertThat(value).isEqualTo(0x0100)
        assertThat(index).isEqualTo((0x29 shl 8) or 0x00)
        val n = minOf(buffer.size, response.size)
        response.copyInto(buffer, endIndex = n)
        n
    }

    @Test
    fun `uac2 discrete subranges resolve to their exact rates`() {
        val response = rangeResponse(
            Triple(44_100L, 44_100L, 0L),
            Triple(48_000L, 48_000L, 0L),
            Triple(96_000L, 96_000L, 0L),
            Triple(192_000L, 192_000L, 0L),
            Triple(768_000L, 768_000L, 0L)
        )
        val caps = UacCapabilityProber.probe(topology(uac2AsyncDacDescriptors()), cannedTransfer(response))

        assertThat(caps.version).isEqualTo(UacVersion.UAC2)
        assertThat(caps.formats).hasSize(2)
        assertThat(caps.formats[0].sampleRatesHz)
            .containsExactly(44_100, 48_000, 96_000, 192_000, 768_000).inOrder()
        assertThat(caps.formats[1].bitResolution).isEqualTo(32)
        assertThat(caps.volume).isNotNull()
        assertThat(caps.volume!!.hasMasterMute).isTrue()
    }

    @Test
    fun `uac2 continuous subrange intersects the standard rate table`() {
        val response = rangeResponse(Triple(44_100L, 192_000L, 0L))
        val caps = UacCapabilityProber.probe(topology(uac2AsyncDacDescriptors()), cannedTransfer(response))

        assertThat(caps.formats[0].sampleRatesHz)
            .containsExactly(44_100, 48_000, 64_000, 88_200, 96_000, 176_400, 192_000).inOrder()
    }

    @Test
    fun `uac2 stepped subrange honours the resolution`() {
        // 44100..176400 step 44100 → 44100, 88200, 176400 (132300 is not a standard rate)
        val response = rangeResponse(Triple(44_100L, 176_400L, 44_100L))
        val caps = UacCapabilityProber.probe(topology(uac2AsyncDacDescriptors()), cannedTransfer(response))

        assertThat(caps.formats[0].sampleRatesHz).containsExactly(44_100, 88_200, 176_400).inOrder()
    }

    @Test
    fun `uac2 failed control transfer yields no formats rather than throwing`() {
        val failing = UsbControlTransfer { _, _, _, _, _ -> -1 }
        val caps = UacCapabilityProber.probe(topology(uac2AsyncDacDescriptors()), failing)
        assertThat(caps.formats).isEmpty()
    }

    @Test
    fun `uac1 rates come from descriptors and need no control transfers`() {
        val neverCalled = UsbControlTransfer { _, _, _, _, _ ->
            throw AssertionError("UAC1 probing must not touch the device")
        }
        val caps = UacCapabilityProber.probe(topology(uac1DongleDescriptors()), neverCalled)

        assertThat(caps.version).isEqualTo(UacVersion.UAC1)
        assertThat(caps.formats).hasSize(2)
        assertThat(caps.formats[0].sampleRatesHz).containsExactly(44_100, 48_000).inOrder()
        assertThat(caps.formats[0].uac1SampleRateControl).isTrue()
        assertThat(caps.formats[1].subslotBytes).isEqualTo(3)
        assertThat(caps.allSampleRatesHz).containsExactly(44_100, 48_000).inOrder()
        assertThat(caps.allBitResolutions).containsExactly(16, 24).inOrder()
        assertThat(caps.volume).isNotNull()
    }
}
