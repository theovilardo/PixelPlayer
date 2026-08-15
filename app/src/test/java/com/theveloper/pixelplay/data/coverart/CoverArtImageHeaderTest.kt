package com.theveloper.pixelplay.data.coverart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoverArtImageHeaderTest {

    @Test
    fun `reads dimensions from a baseline jpeg`() {
        val jpeg = jpegHeader(width = 1400, height = 1400)

        assertEquals(1400 to 1400, CoverArtImageHeader.readDimensions(jpeg))
    }

    @Test
    fun `skips jpeg metadata segments before the frame header`() {
        // A JFIF app segment and an oversized EXIF blob in front of the frame,
        // which is what catalogs actually serve.
        val jpeg = jpegHeader(
            width = 600,
            height = 900,
            leadingSegments = listOf(
                segment(marker = 0xE0, payloadSize = 14),
                segment(marker = 0xE1, payloadSize = 4000)
            )
        )

        assertEquals(600 to 900, CoverArtImageHeader.readDimensions(jpeg))
    }

    @Test
    fun `walks past the fill bytes a jpeg may pad its markers with`() {
        // 0xFF repeated before a marker is legal padding. Taken for a marker of
        // its own, the two bytes after it are read as a segment length and the
        // walk jumps to an arbitrary offset, so the frame header is never found
        // and the picker shows "size unknown" for an image it could measure.
        val jpeg = jpegHeader(
            width = 800,
            height = 1200,
            leadingSegments = listOf(
                segment(marker = 0xE0, payloadSize = 14),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            )
        )

        assertEquals(800 to 1200, CoverArtImageHeader.readDimensions(jpeg))
    }

    @Test
    fun `reads dimensions from a png`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52
        ) + intBe(1000) + intBe(1000) + ByteArray(8)

        assertEquals(1000 to 1000, CoverArtImageHeader.readDimensions(png))
    }

    @Test
    fun `reads dimensions from a lossy webp`() {
        val webp = ByteArray(30)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBP".toByteArray().copyInto(webp, 8)
        "VP8 ".toByteArray().copyInto(webp, 12)
        // 14 bit little endian dimensions at the end of the frame header.
        webp[26] = (500 and 0xFF).toByte()
        webp[27] = (500 shr 8).toByte()
        webp[28] = (500 and 0xFF).toByte()
        webp[29] = (500 shr 8).toByte()

        assertEquals(500 to 500, CoverArtImageHeader.readDimensions(webp))
    }

    @Test
    fun `returns null for a truncated prefix`() {
        assertNull(CoverArtImageHeader.readDimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `returns null for a non image payload`() {
        assertNull(CoverArtImageHeader.readDimensions("<html>404</html>".toByteArray()))
    }

    private fun jpegHeader(
        width: Int,
        height: Int,
        leadingSegments: List<ByteArray> = emptyList()
    ): ByteArray {
        val start = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val frame = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(), // SOF0
            0x00, 0x11, // segment length
            0x08 // sample precision
        ) + shortBe(height) + shortBe(width) + ByteArray(6)

        return start + leadingSegments.fold(ByteArray(0)) { acc, seg -> acc + seg } + frame
    }

    private fun segment(marker: Int, payloadSize: Int): ByteArray {
        val length = payloadSize + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte()) + shortBe(length) + ByteArray(payloadSize)
    }

    private fun shortBe(value: Int) =
        byteArrayOf((value shr 8).toByte(), (value and 0xFF).toByte())

    private fun intBe(value: Int) = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        (value and 0xFF).toByte()
    )
}
