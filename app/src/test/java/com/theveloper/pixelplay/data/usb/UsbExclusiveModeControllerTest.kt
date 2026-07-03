package com.theveloper.pixelplay.data.usb

import android.hardware.usb.UsbDeviceConnection
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.usbaudio.UsbAudioSession
import com.theveloper.pixelplay.usbaudio.descriptor.UacCapabilities
import com.theveloper.pixelplay.usbaudio.descriptor.UacVersion
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class UsbExclusiveModeControllerTest {

    private val enabledFlow = MutableStateFlow(false)
    private val attachedFlow = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    private val permissionFlow = MutableSharedFlow<UsbPermissionResult>(extraBufferCapacity = 4)
    private val rememberedFlow = MutableStateFlow<Map<String, UsbRememberedDevice>>(emptyMap())

    private val requestedPermissions = mutableListOf<UsbDeviceInfo>()
    private var sessionsOpened = 0
    private var sessionCloseCount = 0

    private val deviceManager: UsbDeviceManager = mockk(relaxed = true) {
        every { attachedAudioDevices } returns attachedFlow
        every { permissionEvents } returns permissionFlow
        every { requestPermission(any()) } answers { requestedPermissions += firstArg<UsbDeviceInfo>() }
    }

    private val prefs: UserPreferencesRepository = mockk(relaxed = true) {
        every { usbExclusiveModeEnabledFlow } returns enabledFlow
        every { usbRememberedDevicesFlow } returns rememberedFlow
        coEvery { rememberUsbDevice(any(), any()) } answers {
            rememberedFlow.value = rememberedFlow.value +
                (firstArg<String>() to secondArg<UsbRememberedDevice>())
        }
    }

    private var claimResult = true
    private var claimCount = 0

    private val session: UsbAudioSession = mockk(relaxed = true) {
        every { isAlive } returns true
        every { capabilities } returns caps()
        every { claim(any(), any()) } answers { claimCount++; claimResult }
        every { close() } answers { sessionCloseCount++ }
    }

    private fun caps() = UacCapabilities(
        version = UacVersion.UAC1,
        controlInterfaceNumber = 0,
        formats = emptyList(), // not consulted by the controller itself
        volume = null
    )

    private lateinit var scope: CoroutineScope

    private fun device(permission: Boolean, name: String = "/dev/bus/usb/001/002") = UsbDeviceInfo(
        deviceName = name,
        productName = "Test DAC",
        manufacturerName = "ACME",
        vendorId = 0x1234,
        productId = 0x5678,
        serialNumber = if (permission) "S1" else null,
        hasPermission = permission
    )

    /** Minimal but valid UAC1 device: header, terminals, one 44.1/48 kHz alt setting. */
    private fun uac1Blob(): ByteArray {
        fun d(type: Int, vararg payload: Int): List<Byte> =
            (listOf(payload.size + 2, type) + payload.toList()).map { it.toByte() }
        val bytes = mutableListOf<Byte>()
        bytes += d(0x01, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 0, 0, 0, 1)
        bytes += d(0x02, 0, 0, 2, 1, 0, 0xC0, 50)
        bytes += d(0x04, 0, 0, 0, 1, 1, 0, 0)
        bytes += d(0x24, 0x01, 0x00, 0x01, 40, 0, 1, 1)
        bytes += d(0x24, 0x02, 0x01, 0x01, 0x01, 0, 2, 3, 0, 0, 0)
        bytes += d(0x24, 0x03, 0x03, 0x02, 0x03, 0, 0x01, 0)
        bytes += d(0x04, 1, 0, 0, 1, 2, 0, 0)
        bytes += d(0x04, 1, 1, 1, 1, 2, 0, 0)
        bytes += d(0x24, 0x01, 0x01, 1, 0x01, 0x00)
        bytes += d(0x24, 0x02, 0x01, 2, 2, 16, 2, 0x44, 0xAC, 0, 0x80, 0xBB, 0)
        bytes += d(0x05, 0x01, 0x09, 192, 0, 1, 0, 0)
        bytes += d(0x25, 0x01, 0x01, 0, 0, 0)
        return bytes.toByteArray()
    }

    private fun controller(openable: Boolean = true): UsbExclusiveModeController {
        val connection: UsbDeviceConnection = mockk(relaxed = true) {
            every { rawDescriptors } returns uac1Blob()
        }
        every { deviceManager.openConnection(any()) } returns if (openable) connection else null
        scope = CoroutineScope(UnconfinedTestDispatcher())
        return UsbExclusiveModeController(
            usbDeviceManager = deviceManager,
            userPreferencesRepository = prefs,
            scope = scope,
            sessionFactory = { _, _ -> sessionsOpened++; session }
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `disabled stays disabled regardless of devices`() = runTest {
        val controller = controller()
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            attachedFlow.value = listOf(device(permission = true))
            expectNoEvents()
        }
        assertThat(sessionsOpened).isEqualTo(0)
    }

    @Test
    fun `enabled with no device reports NoDevice`() = runTest {
        val controller = controller()
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            enabledFlow.value = true
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.NoDevice)
        }
    }

    @Test
    fun `attach without permission requests it and opens on grant`() = runTest {
        val controller = controller()
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            enabledFlow.value = true
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.NoDevice)

            val dac = device(permission = false)
            attachedFlow.value = listOf(dac)
            assertThat(awaitItem()).isInstanceOf(UsbExclusiveState.PermissionPending::class.java)
            assertThat(requestedPermissions).hasSize(1)

            val granted = device(permission = true)
            attachedFlow.value = listOf(granted)
            permissionFlow.tryEmit(UsbPermissionResult.Granted(granted))

            val ready = awaitItem()
            assertThat(ready).isInstanceOf(UsbExclusiveState.Ready::class.java)
            assertThat(sessionsOpened).isEqualTo(1)
            assertThat(claimCount).isEqualTo(1) // kernel driver detached before probing
            assertThat(controller.activeSession).isNotNull()
        }
        coVerify { prefs.rememberUsbDevice(any(), any()) }
    }

    @Test
    fun `claim failure reports a recoverable error and closes the session once`() = runTest {
        claimResult = false
        val controller = controller()
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            enabledFlow.value = true
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.NoDevice)

            attachedFlow.value = listOf(device(permission = true))
            val error = awaitItem()
            assertThat(error).isInstanceOf(UsbExclusiveState.Error::class.java)
            assertThat((error as UsbExclusiveState.Error).recoverable).isTrue()
        }
        assertThat(sessionCloseCount).isEqualTo(1)
        assertThat(controller.activeSession).isNull()
    }

    @Test
    fun `denial is remembered and surfaced`() = runTest {
        val controller = controller()
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            enabledFlow.value = true
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.NoDevice)

            val dac = device(permission = false)
            attachedFlow.value = listOf(dac)
            assertThat(awaitItem()).isInstanceOf(UsbExclusiveState.PermissionPending::class.java)

            permissionFlow.tryEmit(UsbPermissionResult.Denied(dac))
            assertThat(awaitItem()).isInstanceOf(UsbExclusiveState.PermissionDenied::class.java)
            assertThat(sessionsOpened).isEqualTo(0)
        }
    }

    @Test
    fun `detach while ready closes the session exactly once and reports loss`() = runTest {
        val controller = controller()
        controller.sessionLost.test {
            enabledFlow.value = true
            attachedFlow.value = listOf(device(permission = true))
            // Wait until the session opened.
            while (controller.activeSession == null) kotlinx.coroutines.yield()

            attachedFlow.value = emptyList()
            assertThat(awaitItem().vendorId).isEqualTo(0x1234)
        }
        assertThat(sessionCloseCount).isEqualTo(1)
        assertThat(controller.activeSession).isNull()
        assertThat(controller.state.value).isEqualTo(UsbExclusiveState.NoDevice)
    }

    @Test
    fun `disable while ready closes the session without loss event`() = runTest {
        val controller = controller()
        enabledFlow.value = true
        attachedFlow.value = listOf(device(permission = true))
        while (controller.activeSession == null) kotlinx.coroutines.yield()

        controller.sessionLost.test {
            enabledFlow.value = false
            expectNoEvents()
        }
        assertThat(sessionCloseCount).isEqualTo(1)
        assertThat(controller.state.value).isEqualTo(UsbExclusiveState.Disabled)
    }

    @Test
    fun `unopenable device reports a recoverable error`() = runTest {
        val controller = controller(openable = false)
        controller.state.test {
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.Disabled)
            enabledFlow.value = true
            assertThat(awaitItem()).isEqualTo(UsbExclusiveState.NoDevice)
            attachedFlow.value = listOf(device(permission = true))
            val error = awaitItem()
            assertThat(error).isInstanceOf(UsbExclusiveState.Error::class.java)
            assertThat((error as UsbExclusiveState.Error).recoverable).isTrue()
        }
    }

    @Test
    fun `sink format callbacks move between Ready and Active`() = runTest {
        val controller = controller()
        enabledFlow.value = true
        attachedFlow.value = listOf(device(permission = true))
        while (controller.activeSession == null) kotlinx.coroutines.yield()

        val format = com.theveloper.pixelplay.usbaudio.negotiation.NegotiatedFormat(
            candidate = com.theveloper.pixelplay.usbaudio.descriptor.FormatCandidate(
                interfaceNumber = 1, altSetting = 1, channels = 2, subslotBytes = 2,
                bitResolution = 16, sampleRatesHz = listOf(44_100), endpointAddress = 1,
                maxPacketSize = 192, intervalCode = 1,
                syncType = com.theveloper.pixelplay.usbaudio.descriptor.EndpointSyncType.ADAPTIVE,
                feedbackEndpointAddress = null, clockSourceId = null, uac1SampleRateControl = true
            ),
            sampleRateHz = 44_100,
            conversion = com.theveloper.pixelplay.usbaudio.negotiation.Conversion.BIT_PERFECT
        )
        val source = com.theveloper.pixelplay.usbaudio.negotiation.SourceFormat(44_100, 16, 2, false)

        controller.onSinkFormatChanged(format, source)
        val active = controller.state.value
        assertThat(active).isInstanceOf(UsbExclusiveState.Active::class.java)
        assertThat((active as UsbExclusiveState.Active).conversion.isBitPerfect).isTrue()

        controller.onSinkFormatChanged(null, null)
        assertThat(controller.state.value).isInstanceOf(UsbExclusiveState.Ready::class.java)
    }
}
