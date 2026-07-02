package com.theveloper.pixelplay.usbaudio.descriptor

/**
 * Builds raw descriptor blobs, byte-exact per the UAC1/UAC2 specs, modeled on `lsusb -v`
 * dumps of real hardware. Kept as code (not binary resources) so each field is reviewable.
 */
internal class DescriptorBlob {
    private val bytes = mutableListOf<Byte>()

    /** Appends one descriptor; bLength is derived from the payload automatically. */
    fun descriptor(type: Int, vararg payload: Int): DescriptorBlob {
        bytes += (payload.size + 2).toByte()
        bytes += type.toByte()
        payload.forEach { bytes += it.toByte() }
        return this
    }

    fun raw(vararg values: Int): DescriptorBlob {
        values.forEach { bytes += it.toByte() }
        return this
    }

    fun build(): ByteArray = bytes.toByteArray()
}

internal fun lo(v: Int) = v and 0xFF
internal fun hi(v: Int) = (v shr 8) and 0xFF
internal fun rate3(hz: Int) = intArrayOf(hz and 0xFF, (hz shr 8) and 0xFF, (hz shr 16) and 0xFF)

private fun deviceAndConfigHeader(blob: DescriptorBlob) {
    // Standard device descriptor (18 bytes)
    blob.descriptor(
        0x01,
        0x00, 0x02, // bcdUSB 2.00
        0x00, 0x00, 0x00, // class/subclass/protocol (per interface)
        0x40, // bMaxPacketSize0
        0x2A, 0x15, // idVendor
        0x50, 0x87, // idProduct
        0x00, 0x01, // bcdDevice
        0x01, 0x02, 0x03, // iManufacturer/iProduct/iSerialNumber
        0x01 // bNumConfigurations
    )
    // Configuration descriptor (9 bytes; wTotalLength unchecked by the parser)
    blob.descriptor(
        0x02,
        0x00, 0x00, // wTotalLength (not validated)
        0x02, // bNumInterfaces
        0x01, // bConfigurationValue
        0x00, // iConfiguration
        0xC0, // bmAttributes
        0x32 // bMaxPower
    )
}

/**
 * UAC2 async DAC modeled on a typical Topping/SMSL XMOS design:
 * clock source 0x29 (programmable), feature unit 0x0B with master volume+mute,
 * AS interface 1 with alt1 = 16-bit/2ch/subslot2 and alt2 = 32-bit/2ch/subslot4,
 * async iso OUT endpoint 0x01 + explicit feedback IN endpoint 0x81.
 */
internal fun uac2AsyncDacDescriptors(): ByteArray {
    val blob = DescriptorBlob()
    deviceAndConfigHeader(blob)

    // Interface 0 alt 0: AudioControl (class 1, subclass 1, protocol 0x20 = UAC2)
    blob.descriptor(0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x20, 0x00)
    // AC HEADER: bcdADC 2.00
    blob.descriptor(0x24, 0x01, 0x00, 0x02, 0x0A, 0x48, 0x00, 0x00)
    // CLOCK_SOURCE id 0x29: internal programmable clock, freq control host-programmable (0b11)
    blob.descriptor(0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00)
    // INPUT_TERMINAL id 0x01: USB streaming (0x0101), clock 0x29, 2 channels
    blob.descriptor(
        0x24, 0x02, 0x01, lo(0x0101), hi(0x0101), 0x00, 0x29, 0x02,
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    // FEATURE_UNIT id 0x0B fed by terminal 0x01: master mute+volume programmable (0x0000000F)
    blob.descriptor(
        0x24, 0x06, 0x0B, 0x01,
        0x0F, 0x00, 0x00, 0x00, // master: mute 0b11, volume 0b11
        0x00, 0x00, 0x00, 0x00, // ch1
        0x00, 0x00, 0x00, 0x00, // ch2
        0x00
    )
    // OUTPUT_TERMINAL id 0x03: headphones (0x0302), source 0x0B, clock 0x29
    blob.descriptor(0x24, 0x03, 0x03, lo(0x0302), hi(0x0302), 0x00, 0x0B, 0x29, 0x00, 0x00, 0x00)

    // Interface 1 alt 0: AudioStreaming, zero-bandwidth
    blob.descriptor(0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x20, 0x00)

    // Interface 1 alt 1: 16-bit
    blob.descriptor(0x04, 0x01, 0x01, 0x02, 0x01, 0x02, 0x20, 0x00)
    // AS_GENERAL: terminal link 0x01, FORMAT_TYPE_I, bmFormats PCM, 2 channels
    blob.descriptor(
        0x24, 0x01, 0x01, 0x05, 0x01, 0x01, 0x00, 0x00, 0x00,
        0x02, 0x03, 0x00, 0x00, 0x00, 0x00
    )
    // FORMAT_TYPE: subslot 2, resolution 16
    blob.descriptor(0x24, 0x02, 0x01, 0x02, 0x10)
    // Iso OUT endpoint 0x01, asynchronous (0x05), 512 bytes, bInterval 1
    blob.descriptor(0x05, 0x01, 0x05, lo(512), hi(512), 0x01)
    // CS EP_GENERAL
    blob.descriptor(0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
    // Feedback IN endpoint 0x81 (iso, usage=feedback → 0x11), 4 bytes, bInterval 4
    blob.descriptor(0x05, 0x81, 0x11, 0x04, 0x00, 0x04)

    // Interface 1 alt 2: 32-bit subslot (24/32-bit material)
    blob.descriptor(0x04, 0x01, 0x02, 0x02, 0x01, 0x02, 0x20, 0x00)
    blob.descriptor(
        0x24, 0x01, 0x01, 0x05, 0x01, 0x01, 0x00, 0x00, 0x00,
        0x02, 0x03, 0x00, 0x00, 0x00, 0x00
    )
    blob.descriptor(0x24, 0x02, 0x01, 0x04, 0x20)
    blob.descriptor(0x05, 0x01, 0x05, lo(1024), hi(1024), 0x01)
    blob.descriptor(0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
    blob.descriptor(0x05, 0x81, 0x11, 0x04, 0x00, 0x04)

    return blob.build()
}

/**
 * UAC1 dongle modeled on an Apple-style USB-C adapter: adaptive iso OUT, two alt settings
 * (16-bit and 24-bit), discrete 44.1/48 kHz rates in the format descriptors, endpoint
 * sampling-frequency control, feature unit with master mute+volume.
 */
internal fun uac1DongleDescriptors(): ByteArray {
    val blob = DescriptorBlob()
    deviceAndConfigHeader(blob)

    // Interface 0 alt 0: AudioControl (UAC1 protocol 0)
    blob.descriptor(0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00)
    // AC HEADER: bcdADC 1.00, 1 streaming interface (nr 1)
    blob.descriptor(0x24, 0x01, 0x00, 0x01, 0x28, 0x00, 0x01, 0x01)
    // INPUT_TERMINAL id 0x01: USB streaming, 2 channels
    blob.descriptor(0x24, 0x02, 0x01, lo(0x0101), hi(0x0101), 0x00, 0x02, 0x03, 0x00, 0x00, 0x00)
    // FEATURE_UNIT id 0x02, source 0x01, bControlSize 1, master mute+volume (0x03)
    blob.descriptor(0x24, 0x06, 0x02, 0x01, 0x01, 0x03, 0x00, 0x00, 0x00)
    // OUTPUT_TERMINAL id 0x03: headphones, source = feature unit 0x02
    blob.descriptor(0x24, 0x03, 0x03, lo(0x0302), hi(0x0302), 0x00, 0x02, 0x00)

    // Interface 1 alt 0: AudioStreaming zero-bandwidth
    blob.descriptor(0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00)

    // Interface 1 alt 1: 16-bit, 44.1/48
    blob.descriptor(0x04, 0x01, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00)
    // AS_GENERAL: link 0x01, delay 1, wFormatTag PCM
    blob.descriptor(0x24, 0x01, 0x01, 0x01, 0x01, 0x00)
    // FORMAT_TYPE I: 2ch, subframe 2, 16-bit, 2 discrete rates
    blob.descriptor(
        0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x02,
        *rate3(44_100), *rate3(48_000)
    )
    // 9-byte audio-class iso endpoint: OUT 0x01, adaptive (0x09), 192 bytes, interval 1
    blob.descriptor(0x05, 0x01, 0x09, lo(192), hi(192), 0x01, 0x00, 0x00)
    // CS EP_GENERAL: sampling frequency control supported (bit 0)
    blob.descriptor(0x25, 0x01, 0x01, 0x00, 0x00, 0x00)

    // Interface 1 alt 2: 24-bit, 44.1/48
    blob.descriptor(0x04, 0x01, 0x02, 0x01, 0x01, 0x02, 0x00, 0x00)
    blob.descriptor(0x24, 0x01, 0x01, 0x01, 0x01, 0x00)
    blob.descriptor(
        0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x02,
        *rate3(44_100), *rate3(48_000)
    )
    blob.descriptor(0x05, 0x01, 0x09, lo(288), hi(288), 0x01, 0x00, 0x00)
    blob.descriptor(0x25, 0x01, 0x01, 0x00, 0x00, 0x00)

    return blob.build()
}

/** Composite device: HID interface first, then the same UAC1 audio function. */
internal fun compositeHidPlusUac1Descriptors(): ByteArray {
    val blob = DescriptorBlob()
    deviceAndConfigHeader(blob)
    // Interface 2: HID (class 3)
    blob.descriptor(0x04, 0x02, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00)
    // HID descriptor (type 0x21) — must be skipped cleanly
    blob.descriptor(0x21, 0x11, 0x01, 0x00, 0x01, 0x22, 0x40, 0x00)
    // Interrupt IN endpoint — not iso, must be ignored
    blob.descriptor(0x05, 0x83, 0x03, 0x08, 0x00, 0x0A)

    val audio = uac1DongleDescriptors()
    // Skip device+config header of the second blob (18 + 9 bytes)
    return blob.build() + audio.copyOfRange(27, audio.size)
}

/** A mass-storage-only device: no audio function at all. */
internal fun massStorageDescriptors(): ByteArray {
    val blob = DescriptorBlob()
    deviceAndConfigHeader(blob)
    blob.descriptor(0x04, 0x00, 0x00, 0x02, 0x08, 0x06, 0x50, 0x00)
    blob.descriptor(0x05, 0x81, 0x02, lo(512), hi(512), 0x00)
    blob.descriptor(0x05, 0x02, 0x02, lo(512), hi(512), 0x00)
    return blob.build()
}
