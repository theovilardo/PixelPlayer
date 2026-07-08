package com.theveloper.pixelplay.data.service.player.usb

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

class PcmRepackerTest {

    private fun buffer(vararg bytes: Int): ByteBuffer =
        ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)

    private fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.bytes(): List<Int> {
        val result = ArrayList<Int>(position())
        for (i in 0 until position()) result += get(i).toInt() and 0xFF
        return result
    }

    @Test
    fun `s16 to 24-bit subslot zero-pads the LSB`() {
        // One stereo frame: L=0x1234, R=0xFEDC (-292)
        val input = buffer(0x34, 0x12, 0xDC, 0xFE)
        val output = out(6)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_16, 2, 2, 3, output)
        assertThat(output.bytes()).containsExactly(0x00, 0x34, 0x12, 0x00, 0xDC, 0xFE).inOrder()
    }

    @Test
    fun `s16 to 16-bit subslot is a pass-through`() {
        val input = buffer(0x34, 0x12, 0xDC, 0xFE)
        val output = out(4)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_16, 2, 2, 2, output)
        assertThat(output.bytes()).containsExactly(0x34, 0x12, 0xDC, 0xFE).inOrder()
    }

    @Test
    fun `s24 to 32-bit subslot left-justifies`() {
        // One mono sample 0x123456 → 0x12345600 LE
        val input = buffer(0x56, 0x34, 0x12)
        val output = out(4)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_24, 1, 1, 4, output)
        assertThat(output.bytes()).containsExactly(0x00, 0x56, 0x34, 0x12).inOrder()
    }

    @Test
    fun `s24 negative sample keeps its sign`() {
        // 0x800000 = most negative 24-bit value
        val input = buffer(0x00, 0x00, 0x80)
        assertThat(PcmRepacker.readS32Top(input, 0, PcmRepacker.Encoding.PCM_24))
            .isEqualTo(Int.MIN_VALUE)
    }

    @Test
    fun `float golden values`() {
        fun floatToS32(v: Float): Int {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, v)
            return PcmRepacker.readS32Top(b, 0, PcmRepacker.Encoding.FLOAT)
        }
        assertThat(floatToS32(0f)).isEqualTo(0)
        assertThat(floatToS32(0.5f)).isEqualTo(0x40000000)
        assertThat(floatToS32(-1.0f)).isEqualTo(Int.MIN_VALUE)
        assertThat(floatToS32(1.0f)).isEqualTo(Int.MAX_VALUE) // clamped
        assertThat(floatToS32(2.0f)).isEqualTo(Int.MAX_VALUE) // clipped
    }

    @Test
    fun `24-bit int to float and back is the identity - the bit-perfect claim`() {
        // Every value that a 24-bit source can produce must survive int→float→wire intact.
        val samples = intArrayOf(
            -(1 shl 23), -(1 shl 23) + 1, -1, 0, 1, 42, 0x123456, (1 shl 23) - 1
        )
        for (s24 in samples) {
            val asFloat = s24.toFloat() / (1 shl 23)
            val floatBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, asFloat)
            val s32top = PcmRepacker.readS32Top(floatBytes, 0, PcmRepacker.Encoding.FLOAT)

            val wire = out(3)
            PcmRepacker.writeSubslot(wire, s32top, 3)
            val recovered = (wire.get(0).toInt() and 0xFF) or
                ((wire.get(1).toInt() and 0xFF) shl 8) or
                (wire.get(2).toInt() shl 16) // sign-extends via plain Int shl of signed byte
            assertThat(recovered).isEqualTo(s24)
        }
    }

    @Test
    fun `s32 to 16-bit subslot truncates without dither`() {
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x1234ABCD)
        input.position(0).limit(4)
        val output = out(2)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_32, 1, 1, 2, output)
        assertThat(output.bytes()).containsExactly(0x34, 0x12).inOrder()
    }

    @Test
    fun `mono duplicates into both stereo channels`() {
        val input = buffer(0x34, 0x12)
        val output = out(4)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_16, 1, 2, 2, output)
        assertThat(output.bytes()).containsExactly(0x34, 0x12, 0x34, 0x12).inOrder()
    }

    @Test
    fun `outputSize accounts for channel and subslot changes`() {
        // 10 stereo s16 frames (40 bytes) → stereo 4-byte subslot = 80 bytes
        assertThat(PcmRepacker.outputSize(40, PcmRepacker.Encoding.PCM_16, 2, 2, 4)).isEqualTo(80)
        // 10 mono float frames (40 bytes) → stereo 3-byte subslot = 60 bytes
        assertThat(PcmRepacker.outputSize(40, PcmRepacker.Encoding.FLOAT, 1, 2, 3)).isEqualTo(60)
    }

    @Test
    fun `repack consumes the input buffer`() {
        val input = buffer(0x34, 0x12)
        PcmRepacker.repack(input, PcmRepacker.Encoding.PCM_16, 1, 1, 2, out(2))
        assertThat(input.hasRemaining()).isFalse()
    }

    // ─── Software gain stage ──────────────────────────────────────────────────

    @Test
    fun `unity gain is byte-identical to the no-gain path`() {
        val plain = out(4)
        PcmRepacker.repack(buffer(0x34, 0x12, 0xDC, 0xFE), PcmRepacker.Encoding.PCM_16, 2, 2, 2, plain)
        val unity = out(4)
        PcmRepacker.repack(
            buffer(0x34, 0x12, 0xDC, 0xFE), PcmRepacker.Encoding.PCM_16, 2, 2, 2, unity,
            gainQ16 = PcmRepacker.UNITY_GAIN_Q16
        )
        assertThat(unity.bytes()).isEqualTo(plain.bytes())
    }

    @Test
    fun `half gain halves samples exactly in the 32-bit domain`() {
        // s16 0x4000 (s32top 0x40000000) at gain 0.5 → 0x20000000 → 16-bit wire 0x2000
        val output = out(2)
        PcmRepacker.repack(
            buffer(0x00, 0x40), PcmRepacker.Encoding.PCM_16, 1, 1, 2, output,
            gainQ16 = PcmRepacker.UNITY_GAIN_Q16 / 2
        )
        assertThat(output.bytes()).containsExactly(0x00, 0x20).inOrder()
    }

    @Test
    fun `attenuated 16-bit material keeps fractional bits in a deeper subslot`() {
        // Smallest s16 step (0x0001 → s32top 0x00010000) halved is 0x00008000 — below the
        // 16-bit floor but exactly representable in a 24-bit subslot (0x000080).
        val output = out(3)
        PcmRepacker.repack(
            buffer(0x01, 0x00), PcmRepacker.Encoding.PCM_16, 1, 1, 3, output,
            gainQ16 = PcmRepacker.UNITY_GAIN_Q16 / 2
        )
        assertThat(output.bytes()).containsExactly(0x80, 0x00, 0x00).inOrder()
    }

    @Test
    fun `zero gain silences the output`() {
        val output = out(4)
        PcmRepacker.repack(
            buffer(0x34, 0x12, 0xDC, 0xFE), PcmRepacker.Encoding.PCM_16, 2, 2, 2, output,
            gainQ16 = 0
        )
        assertThat(output.bytes()).containsExactly(0x00, 0x00, 0x00, 0x00).inOrder()
    }

    @Test
    fun `negative samples attenuate toward zero without wrapping`() {
        // Most negative 16-bit sample halved: 0x8000 → s32top MIN_VALUE → ÷2 = 0xC0000000
        val output = out(2)
        PcmRepacker.repack(
            buffer(0x00, 0x80), PcmRepacker.Encoding.PCM_16, 1, 1, 2, output,
            gainQ16 = PcmRepacker.UNITY_GAIN_Q16 / 2
        )
        assertThat(output.bytes()).containsExactly(0x00, 0xC0).inOrder()
    }

    @Test
    fun `gainQ16FromDb golden values`() {
        assertThat(PcmRepacker.gainQ16FromDb(null)).isEqualTo(PcmRepacker.UNITY_GAIN_Q16)
        assertThat(PcmRepacker.gainQ16FromDb(0f)).isEqualTo(PcmRepacker.UNITY_GAIN_Q16)
        assertThat(PcmRepacker.gainQ16FromDb(-90f)).isEqualTo(0)
        assertThat(PcmRepacker.gainQ16FromDb(-120f)).isEqualTo(0)
        // −20 dB is exactly 0.1 linear; −6.02 dB is within one Q16 step of 0.5.
        assertThat(PcmRepacker.gainQ16FromDb(-20f)).isEqualTo((0.1 * 65536).toInt())
        val half = PcmRepacker.gainQ16FromDb(-6.0206f)
        assertThat(half).isAtLeast(32766)
        assertThat(half).isAtMost(32769)
        // Monotonic: deeper attenuation never yields a higher gain.
        var previous = Int.MAX_VALUE
        for (db in 0 downTo -90) {
            val gain = PcmRepacker.gainQ16FromDb(db.toFloat())
            assertThat(gain).isAtMost(previous)
            previous = gain
        }
    }
}
