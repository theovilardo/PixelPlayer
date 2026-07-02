package com.theveloper.pixelplay.usbaudio.descriptor

/**
 * Control-transfer abstraction mirroring `UsbDeviceConnection.controlTransfer(...)` so the
 * prober can run against the Java USB API in production and canned payloads in tests.
 * Returns the number of bytes transferred, or a negative value on failure.
 */
fun interface UsbControlTransfer {
    operator fun invoke(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray): Int
}

/**
 * Turns a parsed [UacTopology] into [UacCapabilities].
 *
 * UAC1 devices advertise their sample rates directly in the Type I format descriptors.
 * UAC2 devices instead expose them through a RANGE request on the clock source's sampling
 * frequency control — a plain EP0 class request that needs no claimed interface, so this
 * runs from Kotlin before the native driver ever touches the device.
 */
object UacCapabilityProber {

    /** Rates worth reporting; arbitrary in-between values are unusable for playback anyway. */
    internal val STANDARD_RATES = listOf(
        8000, 11025, 16000, 22050, 32000, 44100, 48000, 64000,
        88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
    )

    // UAC2 class request codes (spec §5.2.2)
    private const val REQ_CUR = 0x01
    private const val REQ_RANGE = 0x02

    /** Clock source control selector: sampling frequency (UAC2 §A.17.1). */
    private const val CS_SAM_FREQ_CONTROL = 0x01

    /** bmRequestType: device-to-host | class | interface. */
    private const val REQUEST_TYPE_AC_GET = 0xA1

    fun probe(topology: UacTopology, controlTransfer: UsbControlTransfer): UacCapabilities {
        val formats = topology.playbackAltSettings.mapNotNull { alt ->
            val rates = when (topology.version) {
                UacVersion.UAC1 -> uac1Rates(alt)
                UacVersion.UAC2 -> uac2Rates(topology, alt, controlTransfer)
            }
            if (rates.isEmpty()) return@mapNotNull null
            FormatCandidate(
                interfaceNumber = alt.interfaceNumber,
                altSetting = alt.altSetting,
                channels = alt.channels,
                subslotBytes = alt.subslotBytes,
                bitResolution = alt.bitResolution,
                sampleRatesHz = rates,
                endpointAddress = alt.dataEndpoint.address,
                maxPacketSize = alt.dataEndpoint.maxPacketSize,
                intervalCode = alt.dataEndpoint.intervalCode,
                syncType = alt.dataEndpoint.syncType,
                feedbackEndpointAddress = alt.feedbackEndpoint?.address,
                clockSourceId = topology.clockSourceFor(alt)?.id,
                uac1SampleRateControl = alt.uac1SampleRateControl
            )
        }

        return UacCapabilities(
            version = topology.version,
            controlInterfaceNumber = topology.controlInterfaceNumber,
            formats = formats,
            volume = resolveVolume(topology)
        )
    }

    private fun resolveVolume(topology: UacTopology): VolumeCapability? {
        val alt = topology.playbackAltSettings.firstOrNull() ?: return null
        val unit = topology.featureUnitFor(alt) ?: return null
        if (0 !in unit.volumeChannels) return null
        return VolumeCapability(
            featureUnitId = unit.id,
            hasMasterVolume = true,
            hasMasterMute = 0 in unit.muteChannels
        )
    }

    private fun uac1Rates(alt: StreamingAltSetting): List<Int> = when {
        alt.sampleRatesHz.isNotEmpty() -> alt.sampleRatesHz.sorted()
        alt.continuousRateRange != null -> STANDARD_RATES.filter { it in alt.continuousRateRange }
        else -> emptyList()
    }

    private fun uac2Rates(
        topology: UacTopology,
        alt: StreamingAltSetting,
        controlTransfer: UsbControlTransfer
    ): List<Int> {
        val clock = topology.clockSourceFor(alt) ?: return emptyList()
        val index = (clock.id shl 8) or topology.controlInterfaceNumber
        val value = CS_SAM_FREQ_CONTROL shl 8

        // First learn the subrange count, then fetch the full layout:
        // wNumSubRanges + N * (dMIN, dMAX, dRES).
        val countBuffer = ByteArray(2)
        val countRead = controlTransfer(REQUEST_TYPE_AC_GET, REQ_RANGE, value, index, countBuffer)
        if (countRead < 2) return emptyList()
        val subRanges = u16(countBuffer, 0)
        if (subRanges <= 0 || subRanges > 256) return emptyList()

        val buffer = ByteArray(2 + subRanges * 12)
        val read = controlTransfer(REQUEST_TYPE_AC_GET, REQ_RANGE, value, index, buffer)
        if (read < buffer.size) return emptyList()

        val rates = sortedSetOf<Int>()
        for (i in 0 until subRanges) {
            val base = 2 + i * 12
            val min = u32(buffer, base)
            val max = u32(buffer, base + 4)
            val res = u32(buffer, base + 8)
            if (min <= 0 || max < min) continue
            when {
                min == max -> if (min <= Int.MAX_VALUE) rates += min.toInt()
                res <= 0L -> STANDARD_RATES.filterTo(rates) { it >= min && it <= max }
                else -> STANDARD_RATES.filterTo(rates) { it >= min && it <= max && (it - min) % res == 0L }
            }
        }
        return rates.toList()
    }

    /** Reads the clock's current rate (diagnostics; the driver programs it explicitly anyway). */
    fun currentRateHz(
        topology: UacTopology,
        clockSourceId: Int,
        controlTransfer: UsbControlTransfer
    ): Int? {
        val index = (clockSourceId shl 8) or topology.controlInterfaceNumber
        val buffer = ByteArray(4)
        val read = controlTransfer(REQUEST_TYPE_AC_GET, REQ_CUR, CS_SAM_FREQ_CONTROL shl 8, index, buffer)
        if (read < 4) return null
        val rate = u32(buffer, 0)
        return if (rate in 1..Int.MAX_VALUE.toLong()) rate.toInt() else null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
