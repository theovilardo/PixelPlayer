package com.theveloper.pixelplay.data.coverart

/**
 * Reads image dimensions out of the first bytes of a file.
 *
 * Catalogs do not report how big their images actually are, and downloading two
 * dozen full covers just to show a resolution would be absurd, so the picker
 * asks each host for a small prefix of the image and parses the header here.
 *
 * Pure and allocation-light on purpose: JPEG, PNG, WebP and GIF cover every
 * format the supported catalogs serve.
 */
object CoverArtImageHeader {

    /** Bytes worth requesting: enough for a JPEG to reach its first SOF marker. */
    const val PROBE_BYTES: Int = 32 * 1024

    /**
     * Returns the pixel dimensions encoded in [bytes], or null when the prefix
     * is too short, damaged, or in a format this parser does not handle.
     */
    fun readDimensions(bytes: ByteArray): Pair<Int, Int>? = when {
        isJpeg(bytes) -> readJpeg(bytes)
        isPng(bytes) -> readPng(bytes)
        isWebp(bytes) -> readWebp(bytes)
        isGif(bytes) -> readGif(bytes)
        else -> null
    }

    private fun isJpeg(bytes: ByteArray) =
        bytes.size >= 2 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8

    private fun isPng(bytes: ByteArray) =
        bytes.size >= 8 && bytes.u8(0) == 0x89 && bytes.u8(1) == 0x50 &&
            bytes.u8(2) == 0x4E && bytes.u8(3) == 0x47

    private fun isWebp(bytes: ByteArray) =
        bytes.size >= 16 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"

    private fun isGif(bytes: ByteArray) =
        bytes.size >= 10 && bytes.ascii(0, 3) == "GIF"

    /**
     * Walks the JPEG marker chain to the first Start Of Frame, which is where
     * the dimensions live. Skips over the metadata segments (EXIF, ICC, XMP)
     * that publishers like to put in front of the image data.
     */
    private fun readJpeg(bytes: ByteArray): Pair<Int, Int>? {
        var offset = 2
        while (offset + 9 < bytes.size) {
            if (bytes.u8(offset) != 0xFF) {
                offset++
                continue
            }

            val marker = bytes.u8(offset + 1)
            when {
                // Any number of 0xFF bytes may pad the space before a marker.
                // Read as a marker of its own, the two bytes after it are taken
                // for a segment length and the walk lands somewhere arbitrary.
                marker == 0xFF -> {
                    offset++
                }
                // Standalone markers carry no payload.
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
                    offset += 2
                }
                // End of image without a frame header: nothing left to find.
                marker == 0xD9 -> return null
                // SOF0..SOF15, excluding the DHT/JPG/DAC markers interleaved in that range.
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                    val height = bytes.u16(offset + 5)
                    val width = bytes.u16(offset + 7)
                    return if (width > 0 && height > 0) width to height else null
                }
                else -> {
                    val segmentLength = bytes.u16(offset + 2)
                    if (segmentLength < 2) return null
                    offset += 2 + segmentLength
                }
            }
        }
        return null
    }

    private fun readPng(bytes: ByteArray): Pair<Int, Int>? {
        // 8 byte signature, 4 byte chunk length, "IHDR", then width and height.
        if (bytes.size < 24 || bytes.ascii(12, 4) != "IHDR") return null
        val width = bytes.u32(16)
        val height = bytes.u32(20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun readWebp(bytes: ByteArray): Pair<Int, Int>? = when (bytes.ascii(12, 4)) {
        "VP8 " -> {
            // Lossy: 3 byte frame tag, 3 byte start code, then 14 bit dimensions.
            if (bytes.size < 30) null
            else {
                val width = bytes.u16le(26) and 0x3FFF
                val height = bytes.u16le(28) and 0x3FFF
                if (width > 0 && height > 0) width to height else null
            }
        }

        "VP8L" -> {
            // Lossless: signature byte, then 14 bit width and height packed together.
            if (bytes.size < 25 || bytes.u8(20) != 0x2F) null
            else {
                val packed = bytes.u32le(21)
                val width = (packed and 0x3FFF) + 1
                val height = ((packed shr 14) and 0x3FFF) + 1
                width to height
            }
        }

        "VP8X" -> {
            // Extended: 4 byte flags, then 24 bit canvas dimensions minus one.
            if (bytes.size < 30) null
            else {
                val width = bytes.u24le(24) + 1
                val height = bytes.u24le(27) + 1
                width to height
            }
        }

        else -> null
    }

    private fun readGif(bytes: ByteArray): Pair<Int, Int>? {
        val width = bytes.u16le(6)
        val height = bytes.u16le(8)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u16le(index: Int): Int = (u8(index + 1) shl 8) or u8(index)

    private fun ByteArray.u24le(index: Int): Int =
        (u8(index + 2) shl 16) or (u8(index + 1) shl 8) or u8(index)

    private fun ByteArray.u32(index: Int): Int =
        (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)

    private fun ByteArray.u32le(index: Int): Int =
        (u8(index + 3) shl 24) or (u8(index + 2) shl 16) or (u8(index + 1) shl 8) or u8(index)

    private fun ByteArray.ascii(index: Int, length: Int): String? {
        if (index + length > size) return null
        return buildString(length) {
            for (i in index until index + length) append(this@ascii.u8(i).toChar())
        }
    }
}
