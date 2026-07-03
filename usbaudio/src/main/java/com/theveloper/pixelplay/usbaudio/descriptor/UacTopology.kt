package com.theveloper.pixelplay.usbaudio.descriptor

/**
 * Model of the audio function of a USB device, extracted from its raw configuration
 * descriptors. Pure data — no Android types — so everything here is JVM unit-testable.
 */

enum class UacVersion { UAC1, UAC2 }

enum class EndpointSyncType { NONE, ASYNCHRONOUS, ADAPTIVE, SYNCHRONOUS }

/** An isochronous endpoint of a streaming alt setting. */
data class IsoEndpoint(
    /** bEndpointAddress including the direction bit (0x80 = IN). */
    val address: Int,
    val maxPacketSize: Int,
    /** Raw bInterval code: period = 2^(bInterval-1) (micro)frames. */
    val intervalCode: Int,
    val syncType: EndpointSyncType,
    /** True when bmAttributes usage bits mark this as an explicit feedback endpoint. */
    val isFeedback: Boolean
) {
    val isInput: Boolean get() = address and 0x80 != 0
}

/** One playback format the DAC offers (a non-zero alt setting of an AS interface). */
data class StreamingAltSetting(
    val interfaceNumber: Int,
    val altSetting: Int,
    /** bTerminalLink from AS_GENERAL — the terminal this stream feeds. */
    val terminalLink: Int,
    val channels: Int,
    /** Bytes each sample occupies on the wire (bSubframeSize / bSubslotSize). */
    val subslotBytes: Int,
    /** Meaningful bits within the subslot (bBitResolution). */
    val bitResolution: Int,
    /**
     * Discrete rates from the descriptor. UAC1 Type I carries them here; UAC2 leaves this
     * empty — rates live behind a clock-source RANGE request (see UacCapabilityProber).
     */
    val sampleRatesHz: List<Int>,
    /** UAC1 continuous-range form (bSamFreqType == 0), null otherwise. */
    val continuousRateRange: IntRange?,
    /** The isochronous data OUT endpoint of this alt setting. */
    val dataEndpoint: IsoEndpoint,
    /** Explicit feedback IN endpoint, when the data endpoint is asynchronous. */
    val feedbackEndpoint: IsoEndpoint?,
    /** UAC1 EP_GENERAL bmAttributes bit 0 — sampling-frequency control on the endpoint. */
    val uac1SampleRateControl: Boolean
)

/** UAC2 clock source entity (CLOCK_SOURCE descriptor). */
data class ClockSource(
    val id: Int,
    val attributes: Int,
    val controls: Int
) {
    /** Sampling-frequency control is host-programmable (bmControls bits 0-1 == 0b11). */
    val frequencyProgrammable: Boolean get() = controls and 0x03 == 0x03
}

/**
 * UAC2 clock selector (CLOCK_SELECTOR descriptor). XMOS-style DACs typically route the
 * streaming terminal's clock through one of these, feeding a 44.1 kHz-family and a
 * 48 kHz-family clock source on separate pins.
 */
data class ClockSelector(
    val id: Int,
    /** Upstream entity IDs; pin numbers are 1-based indices into this list. */
    val pinSourceIds: List<Int>,
    val controls: Int
)

/** UAC2 clock multiplier (CLOCK_MULTIPLIER descriptor) — passed through when resolving. */
data class ClockMultiplier(
    val id: Int,
    val sourceId: Int
)

/** The clock entities reachable from a streaming alt setting's terminal. */
data class ClockPath(
    /** The selector encountered first on the path, if any (the one to program). */
    val selector: ClockSelector?,
    /** All reachable clock sources, in pin order when behind a selector. */
    val sources: List<ClockSource>,
    /** For each source id: the 1-based selector pin that routes to it. */
    val pinBySourceId: Map<Int, Int>
)

/** Feature unit with per-channel mute/volume capabilities (channel 0 = master). */
data class FeatureUnit(
    val id: Int,
    val sourceId: Int,
    /** Channel indices (0 = master) whose VOLUME control is host-programmable. */
    val volumeChannels: List<Int>,
    /** Channel indices (0 = master) whose MUTE control is host-programmable. */
    val muteChannels: List<Int>
)

data class AudioTerminal(
    val id: Int,
    val terminalType: Int,
    /** bSourceID for output terminals, null for input terminals. */
    val sourceId: Int?,
    /** UAC2 bCSourceID, null on UAC1. */
    val clockSourceId: Int?,
    val isInput: Boolean
) {
    companion object {
        /** wTerminalType for a USB streaming terminal (the host-facing side). */
        const val TYPE_USB_STREAMING = 0x0101
    }
}

/** Everything the app needs to know about a device's audio function. */
data class UacTopology(
    val version: UacVersion,
    val controlInterfaceNumber: Int,
    val terminals: List<AudioTerminal>,
    val clockSources: List<ClockSource>,
    val clockSelectors: List<ClockSelector> = emptyList(),
    val clockMultipliers: List<ClockMultiplier> = emptyList(),
    val featureUnits: List<FeatureUnit>,
    /** Playback alt settings (iso OUT), across all AudioStreaming interfaces. */
    val playbackAltSettings: List<StreamingAltSetting>
) {
    /**
     * Resolves the clock entities feeding [alt] through its terminal link (UAC2 only),
     * walking selectors and multipliers transitively. Cycle-guarded — malformed
     * descriptors cannot loop it.
     */
    fun clockPathFor(alt: StreamingAltSetting): ClockPath? {
        if (version != UacVersion.UAC2) return null
        val terminal = terminals.firstOrNull { it.id == alt.terminalLink } ?: return null
        val startId = terminal.clockSourceId ?: return null

        val visited = mutableSetOf<Int>()
        var firstSelector: ClockSelector? = null
        val sources = mutableListOf<ClockSource>()
        val pinBySourceId = mutableMapOf<Int, Int>()

        fun visit(entityId: Int, pin: Int?) {
            if (!visited.add(entityId)) return
            clockSources.firstOrNull { it.id == entityId }?.let { source ->
                sources += source
                if (pin != null) pinBySourceId[source.id] = pin
                return
            }
            clockSelectors.firstOrNull { it.id == entityId }?.let { selector ->
                if (firstSelector == null) firstSelector = selector
                selector.pinSourceIds.forEachIndexed { index, upstreamId ->
                    // Pins are 1-based; nested selectors inherit the outer pin.
                    visit(upstreamId, pin ?: (index + 1))
                }
                return
            }
            clockMultipliers.firstOrNull { it.id == entityId }?.let { multiplier ->
                visit(multiplier.sourceId, pin)
            }
        }
        visit(startId, null)

        return if (sources.isEmpty()) null else ClockPath(firstSelector, sources, pinBySourceId)
    }

    /** Convenience: the primary clock source feeding [alt] (first on the path). */
    fun clockSourceFor(alt: StreamingAltSetting): ClockSource? =
        clockPathFor(alt)?.sources?.firstOrNull()

    /**
     * Feature unit controlling the playback path of [alt]: the unit fed directly by the
     * stream's USB-streaming input terminal, or failing that any unit with a master volume.
     */
    fun featureUnitFor(alt: StreamingAltSetting): FeatureUnit? =
        featureUnits.firstOrNull { it.sourceId == alt.terminalLink && it.volumeChannels.isNotEmpty() }
            ?: featureUnits.firstOrNull { 0 in it.volumeChannels }
}

sealed interface ParseResult {
    data class Success(val topology: UacTopology) : ParseResult
    data class Failure(val reason: String) : ParseResult
}
