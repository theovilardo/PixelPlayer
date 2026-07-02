package com.theveloper.pixelplay.usbaudio.descriptor

/**
 * Fully resolved playback capabilities of a DAC: what [UacCapabilityProber] hands to the
 * format negotiator once descriptor parsing and (for UAC2) clock-rate probing are done.
 */
data class UacCapabilities(
    val version: UacVersion,
    val controlInterfaceNumber: Int,
    val formats: List<FormatCandidate>,
    /** Hardware volume via a UAC feature unit, or null → fixed line-level output. */
    val volume: VolumeCapability?,
    /** Hook for DSD-over-PCM support detection (stretch goal); always NONE for now. */
    val dsdSupport: DsdSupport = DsdSupport.NONE
) {
    /** Every sample rate the device can play, across all formats. */
    val allSampleRatesHz: List<Int>
        get() = formats.flatMap { it.sampleRatesHz }.distinct().sorted()

    /** Every bit resolution the device offers. */
    val allBitResolutions: List<Int>
        get() = formats.map { it.bitResolution }.distinct().sorted()
}

enum class DsdSupport { NONE, DOP }

/** One playable configuration: an alt setting with its resolved rates. */
data class FormatCandidate(
    val interfaceNumber: Int,
    val altSetting: Int,
    val channels: Int,
    /** Bytes per sample on the wire (2, 3 or 4). */
    val subslotBytes: Int,
    /** Meaningful bits (16, 24 or 32). */
    val bitResolution: Int,
    val sampleRatesHz: List<Int>,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val intervalCode: Int,
    val syncType: EndpointSyncType,
    val feedbackEndpointAddress: Int?,
    /** UAC2 clock source to program for this stream; null on UAC1. */
    val clockSourceId: Int?,
    /** UAC1 endpoint supports SET_CUR sampling frequency. */
    val uac1SampleRateControl: Boolean
)

data class VolumeCapability(
    val featureUnitId: Int,
    val hasMasterVolume: Boolean,
    val hasMasterMute: Boolean
)
