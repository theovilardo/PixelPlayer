package com.theveloper.pixelplay.data.usb

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class UsbDeviceInfoTest {

    @Test
    fun `key uses zero-padded hex ids and serial`() {
        assertThat(usbDeviceKey(0x2D87, 0x0001, "ABC123")).isEqualTo("2d87:0001:ABC123")
    }

    @Test
    fun `key tolerates missing serial`() {
        assertThat(usbDeviceKey(0x20B1, 0x300A, null)).isEqualTo("20b1:300a:?")
    }

    @Test
    fun `key treats blank serial as missing`() {
        assertThat(usbDeviceKey(0x20B1, 0x300A, "  ")).isEqualTo("20b1:300a:?")
    }

    @Test
    fun `displayName falls back to ids when product name missing`() {
        val info = UsbDeviceInfo(
            deviceName = "/dev/bus/usb/001/002",
            productName = null,
            manufacturerName = null,
            vendorId = 0x20B1,
            productId = 0x000A,
            serialNumber = null,
            hasPermission = false
        )
        assertThat(info.displayName).isEqualTo("USB Audio (20b1:000a)")
    }

    @Test
    fun `displayName prefers product name`() {
        val info = UsbDeviceInfo(
            deviceName = "/dev/bus/usb/001/002",
            productName = "D10s",
            manufacturerName = "Topping",
            vendorId = 0x152A,
            productId = 0x8750,
            serialNumber = "S1",
            hasPermission = true
        )
        assertThat(info.displayName).isEqualTo("D10s")
    }

    @Test
    fun `remembered device round-trips through json`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = mapOf(
            "152a:8750:S1" to UsbRememberedDevice(label = "Topping D10s", autoResume = true),
            "20b1:000a:?" to UsbRememberedDevice(label = "Old dongle", autoResume = false)
        )
        val decoded: Map<String, UsbRememberedDevice> =
            json.decodeFromString(json.encodeToString(original))
        assertThat(decoded).isEqualTo(original)
    }
}
