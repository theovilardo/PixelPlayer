package com.theveloper.pixelplay.data.service.player.usb

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.usb.UsbExclusiveModeController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class UsbDeviceVolumePlayerTest {

    private val inner: Player = mockk(relaxed = true) {
        every { availableCommands } returns Player.Commands.EMPTY
        every { deviceInfo } returns DeviceInfo.UNKNOWN
    }

    private var volumeAvailable = true
    private val controller: UsbExclusiveModeController = mockk(relaxed = true) {
        every { deviceVolumeMaxSteps } returns 30
        every { deviceVolumeAvailable() } answers { volumeAvailable }
        every { deviceVolumeSteps() } returns 15
    }

    private fun player() = UsbDeviceVolumePlayer(inner, controller)

    @Test
    fun `advertises the device-volume commands the inner player lacks`() {
        // Without these the media session downgrades to VOLUME_CONTROL_FIXED and the
        // phone's volume keys never reach the DAC. Player.Commands is inert under the
        // mockable android jar (SparseBooleanArray stubs), so assert the builder wiring.
        val added = mutableListOf<Int>()
        val built: Player.Commands = mockk()
        val builder: Player.Commands.Builder = mockk {
            every { addAll(*anyIntVararg()) } answers {
                added += (invocation.args.first() as IntArray).toList()
                self as Player.Commands.Builder
            }
            every { build() } returns built
        }
        val innerCommands: Player.Commands = mockk { every { buildUpon() } returns builder }
        every { inner.availableCommands } returns innerCommands

        assertThat(player().availableCommands).isSameInstanceAs(built)
        assertThat(added).containsExactly(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
        )

        // isCommandAvailable consults the augmented set, not the inner player.
        every { built.contains(Player.COMMAND_GET_DEVICE_VOLUME) } returns true
        assertThat(player().isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)).isTrue()
    }

    @Test
    fun `reports a remote device sized to the controller step scale`() {
        val info = player().deviceInfo
        assertThat(info.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(info.maxVolume).isEqualTo(30)
        assertThat(player().deviceVolume).isEqualTo(15)
    }

    @Test
    fun `falls back to the inner player's device info when volume is unavailable`() {
        volumeAvailable = false // fixed line-level output
        assertThat(player().deviceInfo).isEqualTo(DeviceInfo.UNKNOWN)
    }

    @Test
    fun `volume operations route to the controller`() {
        val player = player()
        player.setDeviceVolume(20, 0)
        player.increaseDeviceVolume(0)
        player.decreaseDeviceVolume(0)
        player.setDeviceMuted(true, 0)
        verify { controller.setDeviceVolumeSteps(20) }
        verify { controller.adjustDeviceVolume(1) }
        verify { controller.adjustDeviceVolume(-1) }
        verify { controller.setDeviceMuted(true) }
    }
}
