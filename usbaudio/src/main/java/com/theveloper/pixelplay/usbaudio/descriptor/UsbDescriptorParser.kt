package com.theveloper.pixelplay.usbaudio.descriptor

/**
 * Parses the raw USB descriptor blob (`UsbDeviceConnection.getRawDescriptors()`) into a
 * [UacTopology]: the audio-control entities (terminals, clock sources, feature units) and every
 * playback alt setting with its wire format and endpoints.
 *
 * Supports USB Audio Class 1.0 (bcdADC 0x0100) and 2.0 (bcdADC 0x0200). Never throws on
 * malformed input — any structural problem yields [ParseResult.Failure].
 */
object UsbDescriptorParser {

    // Standard descriptor types
    private const val DT_CONFIG = 0x02
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_CS_INTERFACE = 0x24
    private const val DT_CS_ENDPOINT = 0x25

    // Audio interface subclasses
    private const val SUBCLASS_AUDIOCONTROL = 0x01
    private const val SUBCLASS_AUDIOSTREAMING = 0x02
    private const val CLASS_AUDIO = 0x01

    // AC class-specific descriptor subtypes (shared numbering between UAC1/UAC2 where noted)
    private const val AC_HEADER = 0x01
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
    private const val AC_FEATURE_UNIT = 0x06
    private const val AC2_CLOCK_SOURCE = 0x0A

    // AS class-specific descriptor subtypes
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02

    private const val FORMAT_TYPE_I = 0x01
    /** UAC1 wFormatTag / UAC2 bmFormats bit 0: linear PCM. */
    private const val UAC1_FORMAT_PCM = 0x0001

    fun parse(raw: ByteArray): ParseResult {
        val descriptors = splitDescriptors(raw)
            ?: return ParseResult.Failure("Malformed descriptor chain")
        if (descriptors.none { it.type == DT_CONFIG }) {
            return ParseResult.Failure("No configuration descriptor")
        }

        var version: UacVersion? = null
        var controlInterface: Int? = null
        val terminals = mutableListOf<AudioTerminal>()
        val clockSources = mutableListOf<ClockSource>()
        val featureUnits = mutableListOf<FeatureUnit>()
        val altSettings = mutableListOf<StreamingAltSetting>()

        // Walk state: the interface descriptor we are currently "inside".
        var currentInterface = -1
        var currentAlt = 0
        var currentClass = -1
        var currentSubclass = -1

        // Accumulators for the streaming alt setting being assembled.
        var asGeneral: AsGeneral? = null
        var asFormat: AsFormat? = null
        var asDataEndpoint: IsoEndpoint? = null
        var asFeedbackEndpoint: IsoEndpoint? = null
        var asRateControl = false

        fun flushAltSetting() {
            val general = asGeneral
            val format = asFormat
            val endpoint = asDataEndpoint
            if (general != null && format != null && endpoint != null && !endpoint.isInput) {
                altSettings += StreamingAltSetting(
                    interfaceNumber = general.interfaceNumber,
                    altSetting = general.altSetting,
                    terminalLink = general.terminalLink,
                    channels = general.channels ?: format.channels ?: 0,
                    subslotBytes = format.subslotBytes,
                    bitResolution = format.bitResolution,
                    sampleRatesHz = format.discreteRates,
                    continuousRateRange = format.continuousRange,
                    dataEndpoint = endpoint,
                    feedbackEndpoint = asFeedbackEndpoint,
                    uac1SampleRateControl = asRateControl
                )
            }
            asGeneral = null
            asFormat = null
            asDataEndpoint = null
            asFeedbackEndpoint = null
            asRateControl = false
        }

        for (d in descriptors) {
            when (d.type) {
                DT_INTERFACE -> {
                    flushAltSetting()
                    if (d.bytes.size < 9) continue
                    currentInterface = d.u8(2)
                    currentAlt = d.u8(3)
                    currentClass = d.u8(5)
                    currentSubclass = d.u8(6)
                }

                DT_CS_INTERFACE -> when {
                    currentClass == CLASS_AUDIO && currentSubclass == SUBCLASS_AUDIOCONTROL -> {
                        when (d.u8(2)) {
                            AC_HEADER -> {
                                if (d.bytes.size < 5) continue
                                version = when (d.u16(3)) {
                                    0x0100 -> UacVersion.UAC1
                                    0x0200 -> UacVersion.UAC2
                                    else -> version
                                }
                                controlInterface = currentInterface
                            }

                            AC_INPUT_TERMINAL -> {
                                if (d.bytes.size < 8) continue
                                terminals += AudioTerminal(
                                    id = d.u8(3),
                                    terminalType = d.u16(4),
                                    sourceId = null,
                                    clockSourceId = if (version == UacVersion.UAC2 && d.bytes.size > 7) d.u8(7) else null,
                                    isInput = true
                                )
                            }

                            AC_OUTPUT_TERMINAL -> {
                                if (d.bytes.size < 9) continue
                                terminals += AudioTerminal(
                                    id = d.u8(3),
                                    terminalType = d.u16(4),
                                    sourceId = d.u8(7),
                                    clockSourceId = if (version == UacVersion.UAC2 && d.bytes.size > 8) d.u8(8) else null,
                                    isInput = false
                                )
                            }

                            AC_FEATURE_UNIT -> parseFeatureUnit(d, version)?.let { featureUnits += it }

                            AC2_CLOCK_SOURCE -> {
                                if (version == UacVersion.UAC2 && d.bytes.size >= 8) {
                                    clockSources += ClockSource(
                                        id = d.u8(3),
                                        attributes = d.u8(4),
                                        controls = d.u8(5)
                                    )
                                }
                            }
                        }
                    }

                    currentClass == CLASS_AUDIO && currentSubclass == SUBCLASS_AUDIOSTREAMING -> {
                        when (d.u8(2)) {
                            AS_GENERAL -> {
                                asGeneral = parseAsGeneral(d, version, currentInterface, currentAlt)
                            }

                            AS_FORMAT_TYPE -> {
                                asFormat = parseFormatType(d, version)
                            }
                        }
                    }
                }

                DT_ENDPOINT -> {
                    if (currentClass != CLASS_AUDIO || currentSubclass != SUBCLASS_AUDIOSTREAMING) continue
                    if (d.bytes.size < 7) continue
                    val attributes = d.u8(3)
                    if (attributes and 0x03 != 0x01) continue // isochronous only
                    val endpoint = IsoEndpoint(
                        address = d.u8(2),
                        maxPacketSize = d.u16(4),
                        intervalCode = d.u8(6),
                        syncType = when ((attributes shr 2) and 0x03) {
                            1 -> EndpointSyncType.ASYNCHRONOUS
                            2 -> EndpointSyncType.ADAPTIVE
                            3 -> EndpointSyncType.SYNCHRONOUS
                            else -> EndpointSyncType.NONE
                        },
                        isFeedback = (attributes shr 4) and 0x03 == 0x01
                    )
                    if (endpoint.isFeedback && endpoint.isInput) {
                        asFeedbackEndpoint = endpoint
                    } else if (!endpoint.isInput) {
                        asDataEndpoint = endpoint
                    }
                }

                DT_CS_ENDPOINT -> {
                    // UAC1 EP_GENERAL: bmAttributes bit 0 = sampling frequency control.
                    if (currentClass == CLASS_AUDIO && currentSubclass == SUBCLASS_AUDIOSTREAMING &&
                        d.bytes.size >= 4 && d.u8(2) == 0x01
                    ) {
                        asRateControl = d.u8(3) and 0x01 != 0
                    }
                }
            }
        }
        flushAltSetting()

        val resolvedVersion = version
            ?: return ParseResult.Failure("No audio-control header (not a UAC device?)")
        val acInterface = controlInterface
            ?: return ParseResult.Failure("No audio-control interface")
        if (altSettings.isEmpty()) {
            return ParseResult.Failure("No playback (iso OUT) alt settings found")
        }

        return ParseResult.Success(
            UacTopology(
                version = resolvedVersion,
                controlInterfaceNumber = acInterface,
                terminals = terminals,
                clockSources = clockSources,
                featureUnits = featureUnits,
                playbackAltSettings = altSettings
            )
        )
    }

    // ─── Class-specific pieces ────────────────────────────────────────────────

    private class AsGeneral(
        val interfaceNumber: Int,
        val altSetting: Int,
        val terminalLink: Int,
        val channels: Int?,
        val isPcm: Boolean
    )

    private class AsFormat(
        val channels: Int?,
        val subslotBytes: Int,
        val bitResolution: Int,
        val discreteRates: List<Int>,
        val continuousRange: IntRange?
    )

    private fun parseAsGeneral(
        d: Descriptor,
        version: UacVersion?,
        interfaceNumber: Int,
        altSetting: Int
    ): AsGeneral? = when (version) {
        UacVersion.UAC1 -> {
            if (d.bytes.size < 7) null
            else AsGeneral(
                interfaceNumber = interfaceNumber,
                altSetting = altSetting,
                terminalLink = d.u8(3),
                channels = null, // UAC1 channels come from the format-type descriptor
                isPcm = d.u16(5) == UAC1_FORMAT_PCM
            ).takeIf { it.isPcm }
        }

        UacVersion.UAC2 -> {
            if (d.bytes.size < 16) null
            else AsGeneral(
                interfaceNumber = interfaceNumber,
                altSetting = altSetting,
                terminalLink = d.u8(3),
                channels = d.u8(10),
                isPcm = d.u32(6) and 0x1L != 0L
            ).takeIf { it.isPcm }
        }

        null -> null
    }

    private fun parseFormatType(d: Descriptor, version: UacVersion?): AsFormat? = when (version) {
        UacVersion.UAC1 -> {
            if (d.bytes.size < 8 || d.u8(3) != FORMAT_TYPE_I) null
            else {
                val freqType = d.u8(7)
                var discrete = emptyList<Int>()
                var continuous: IntRange? = null
                if (freqType == 0) {
                    if (d.bytes.size >= 14) continuous = d.u24(8)..d.u24(11)
                } else {
                    val rates = mutableListOf<Int>()
                    for (i in 0 until freqType) {
                        val offset = 8 + i * 3
                        if (offset + 3 > d.bytes.size) break
                        rates += d.u24(offset)
                    }
                    discrete = rates
                }
                AsFormat(
                    channels = d.u8(4),
                    subslotBytes = d.u8(5),
                    bitResolution = d.u8(6),
                    discreteRates = discrete,
                    continuousRange = continuous
                )
            }
        }

        UacVersion.UAC2 -> {
            if (d.bytes.size < 6 || d.u8(3) != FORMAT_TYPE_I) null
            else AsFormat(
                channels = null, // UAC2 channels come from AS_GENERAL
                subslotBytes = d.u8(4),
                bitResolution = d.u8(5),
                discreteRates = emptyList(), // behind the clock-source RANGE request
                continuousRange = null
            )
        }

        null -> null
    }

    private fun parseFeatureUnit(d: Descriptor, version: UacVersion?): FeatureUnit? {
        return when (version) {
            UacVersion.UAC1 -> {
                if (d.bytes.size < 7) return null
                val controlSize = d.u8(5)
                if (controlSize <= 0) return null
                val channelCount = (d.bytes.size - 7) / controlSize // includes master (ch 0)
                val volume = mutableListOf<Int>()
                val mute = mutableListOf<Int>()
                for (ch in 0 until channelCount) {
                    val offset = 6 + ch * controlSize
                    if (offset >= d.bytes.size) break
                    val controls = d.u8(offset)
                    if (controls and 0x02 != 0) volume += ch
                    if (controls and 0x01 != 0) mute += ch
                }
                FeatureUnit(id = d.u8(3), sourceId = d.u8(4), volumeChannels = volume, muteChannels = mute)
            }

            UacVersion.UAC2 -> {
                if (d.bytes.size < 10) return null
                val channelCount = (d.bytes.size - 6) / 4 // includes master (ch 0)
                val volume = mutableListOf<Int>()
                val mute = mutableListOf<Int>()
                for (ch in 0 until channelCount) {
                    val offset = 5 + ch * 4
                    if (offset + 4 > d.bytes.size) break
                    val controls = d.u32(offset)
                    // 2 bits per control: 0b11 = host-programmable. Mute = bits 0-1, volume = 2-3.
                    if ((controls shr 2) and 0x3L == 0x3L) volume += ch
                    if (controls and 0x3L == 0x3L) mute += ch
                }
                FeatureUnit(id = d.u8(3), sourceId = d.u8(4), volumeChannels = volume, muteChannels = mute)
            }

            null -> null
        }
    }

    // ─── Low-level walking ────────────────────────────────────────────────────

    private class Descriptor(val type: Int, val bytes: ByteArray) {
        fun u8(i: Int): Int = bytes[i].toInt() and 0xFF
        fun u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        fun u32(i: Int): Long =
            u8(i).toLong() or (u8(i + 1).toLong() shl 8) or
                (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
    }

    /** Splits the blob into descriptors by bLength, or null if the chain is inconsistent. */
    private fun splitDescriptors(raw: ByteArray): List<Descriptor>? {
        val out = mutableListOf<Descriptor>()
        var offset = 0
        while (offset < raw.size) {
            if (offset + 2 > raw.size) return null
            val length = raw[offset].toInt() and 0xFF
            if (length < 2 || offset + length > raw.size) return null
            out += Descriptor(
                type = raw[offset + 1].toInt() and 0xFF,
                bytes = raw.copyOfRange(offset, offset + length)
            )
            offset += length
        }
        return out
    }
}
