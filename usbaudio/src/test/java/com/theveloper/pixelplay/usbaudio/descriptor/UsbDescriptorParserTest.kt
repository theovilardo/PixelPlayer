package com.theveloper.pixelplay.usbaudio.descriptor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UsbDescriptorParserTest {

    private fun parseOk(raw: ByteArray): UacTopology {
        val result = UsbDescriptorParser.parse(raw)
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        return (result as ParseResult.Success).topology
    }

    // ─── UAC2 ────────────────────────────────────────────────────────────────

    @Test
    fun `uac2 dac parses version and control interface`() {
        val topology = parseOk(uac2AsyncDacDescriptors())
        assertThat(topology.version).isEqualTo(UacVersion.UAC2)
        assertThat(topology.controlInterfaceNumber).isEqualTo(0)
    }

    @Test
    fun `uac2 dac exposes both playback alt settings`() {
        val topology = parseOk(uac2AsyncDacDescriptors())
        assertThat(topology.playbackAltSettings).hasSize(2)

        val alt1 = topology.playbackAltSettings[0]
        assertThat(alt1.interfaceNumber).isEqualTo(1)
        assertThat(alt1.altSetting).isEqualTo(1)
        assertThat(alt1.channels).isEqualTo(2)
        assertThat(alt1.subslotBytes).isEqualTo(2)
        assertThat(alt1.bitResolution).isEqualTo(16)
        assertThat(alt1.sampleRatesHz).isEmpty() // UAC2: rates live behind the clock RANGE request
        assertThat(alt1.terminalLink).isEqualTo(0x01)

        val alt2 = topology.playbackAltSettings[1]
        assertThat(alt2.altSetting).isEqualTo(2)
        assertThat(alt2.subslotBytes).isEqualTo(4)
        assertThat(alt2.bitResolution).isEqualTo(32)
    }

    @Test
    fun `uac2 endpoints carry sync type and explicit feedback`() {
        val topology = parseOk(uac2AsyncDacDescriptors())
        val alt = topology.playbackAltSettings[0]
        assertThat(alt.dataEndpoint.address).isEqualTo(0x01)
        assertThat(alt.dataEndpoint.syncType).isEqualTo(EndpointSyncType.ASYNCHRONOUS)
        assertThat(alt.dataEndpoint.maxPacketSize).isEqualTo(512)
        assertThat(alt.feedbackEndpoint).isNotNull()
        assertThat(alt.feedbackEndpoint!!.address).isEqualTo(0x81)
        assertThat(alt.feedbackEndpoint!!.isInput).isTrue()
    }

    @Test
    fun `uac2 clock source resolves through the terminal link`() {
        val topology = parseOk(uac2AsyncDacDescriptors())
        assertThat(topology.clockSources).hasSize(1)
        val clock = topology.clockSourceFor(topology.playbackAltSettings[0])
        assertThat(clock).isNotNull()
        assertThat(clock!!.id).isEqualTo(0x29)
        assertThat(clock.frequencyProgrammable).isTrue()
    }

    @Test
    fun `uac2 feature unit exposes programmable master volume and mute`() {
        val topology = parseOk(uac2AsyncDacDescriptors())
        val unit = topology.featureUnitFor(topology.playbackAltSettings[0])
        assertThat(unit).isNotNull()
        assertThat(unit!!.id).isEqualTo(0x0B)
        assertThat(unit.volumeChannels).contains(0)
        assertThat(unit.muteChannels).contains(0)
    }

    // ─── UAC1 ────────────────────────────────────────────────────────────────

    @Test
    fun `uac1 dongle parses version, rates and depths from descriptors`() {
        val topology = parseOk(uac1DongleDescriptors())
        assertThat(topology.version).isEqualTo(UacVersion.UAC1)
        assertThat(topology.playbackAltSettings).hasSize(2)

        val alt16 = topology.playbackAltSettings[0]
        assertThat(alt16.bitResolution).isEqualTo(16)
        assertThat(alt16.subslotBytes).isEqualTo(2)
        assertThat(alt16.channels).isEqualTo(2)
        assertThat(alt16.sampleRatesHz).containsExactly(44_100, 48_000).inOrder()
        assertThat(alt16.uac1SampleRateControl).isTrue()
        assertThat(alt16.dataEndpoint.syncType).isEqualTo(EndpointSyncType.ADAPTIVE)
        assertThat(alt16.feedbackEndpoint).isNull()

        val alt24 = topology.playbackAltSettings[1]
        assertThat(alt24.bitResolution).isEqualTo(24)
        assertThat(alt24.subslotBytes).isEqualTo(3)
    }

    @Test
    fun `uac1 feature unit with single-byte controls parses master volume`() {
        val topology = parseOk(uac1DongleDescriptors())
        val unit = topology.featureUnitFor(topology.playbackAltSettings[0])
        assertThat(unit).isNotNull()
        assertThat(unit!!.id).isEqualTo(0x02)
        assertThat(unit.volumeChannels).containsExactly(0)
        assertThat(unit.muteChannels).containsExactly(0)
    }

    // ─── Composite / negative ────────────────────────────────────────────────

    @Test
    fun `composite device with leading hid interface still finds the audio function`() {
        val topology = parseOk(compositeHidPlusUac1Descriptors())
        assertThat(topology.version).isEqualTo(UacVersion.UAC1)
        assertThat(topology.playbackAltSettings).hasSize(2)
    }

    @Test
    fun `mass storage device fails with a reason`() {
        val result = UsbDescriptorParser.parse(massStorageDescriptors())
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `truncated blob fails without throwing`() {
        val full = uac2AsyncDacDescriptors()
        for (cut in intArrayOf(1, 5, 17, 30, full.size / 2)) {
            val result = UsbDescriptorParser.parse(full.copyOfRange(0, cut))
            assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        }
    }

    @Test
    fun `garbage bytes fail without throwing`() {
        val garbage = ByteArray(64) { (it * 37 + 11).toByte() }
        val result = UsbDescriptorParser.parse(garbage)
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `zero-length descriptor cannot cause an infinite loop`() {
        val raw = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val result = UsbDescriptorParser.parse(raw)
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `empty input fails`() {
        assertThat(UsbDescriptorParser.parse(ByteArray(0)))
            .isInstanceOf(ParseResult.Failure::class.java)
    }
}
