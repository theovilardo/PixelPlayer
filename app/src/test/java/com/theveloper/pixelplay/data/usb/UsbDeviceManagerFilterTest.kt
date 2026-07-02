package com.theveloper.pixelplay.data.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class UsbDeviceManagerFilterTest {

    private fun device(vararg interfaces: Pair<Int, Int>): UsbDevice {
        val mocks = interfaces.map { (cls, subCls) ->
            mockk<UsbInterface> {
                every { interfaceClass } returns cls
                every { interfaceSubclass } returns subCls
            }
        }
        return mockk {
            every { interfaceCount } returns mocks.size
            mocks.forEachIndexed { index, itf -> every { getInterface(index) } returns itf }
        }
    }

    @Test
    fun `uac dac with control and streaming interfaces matches`() {
        // Typical UAC2 DAC: AudioControl (1,1) + AudioStreaming (1,2)
        val dac = device(1 to 1, 1 to 2)
        assertThat(hasAudioStreamingInterface(dac)).isTrue()
    }

    @Test
    fun `mass storage device does not match`() {
        val msc = device(8 to 6)
        assertThat(hasAudioStreamingInterface(msc)).isFalse()
    }

    @Test
    fun `audio control only device does not match`() {
        // Control interface without streaming (e.g. HID volume knob with audio control)
        val controlOnly = device(1 to 1)
        assertThat(hasAudioStreamingInterface(controlOnly)).isFalse()
    }

    @Test
    fun `composite audio plus hid matches`() {
        // DACs commonly expose HID (3,0) for buttons alongside audio
        val composite = device(1 to 1, 1 to 2, 3 to 0)
        assertThat(hasAudioStreamingInterface(composite)).isTrue()
    }

    @Test
    fun `device with no interfaces does not match`() {
        val empty = device()
        assertThat(hasAudioStreamingInterface(empty)).isFalse()
    }
}
