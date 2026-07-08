package com.theveloper.pixelplay.data.service.player.usb

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.usb.UsbExclusiveModeController

/**
 * Wraps the session player while USB exclusive mode is engaged so the phone's volume keys
 * control the DAC instead of doing nothing (there is no AudioTrack in exclusive mode).
 *
 * Advertising [DeviceInfo.PLAYBACK_TYPE_REMOTE] makes the media session route volume-key
 * presses to [setDeviceVolume]/[increaseDeviceVolume]/[decreaseDeviceVolume], which map to
 * the DAC's hardware feature unit when it has one, or to the software gain stage otherwise.
 * The wrapper is created fresh on every exclusive-mode transition (players are rebuilt),
 * so the advertised DeviceInfo is always current.
 */
@UnstableApi
class UsbDeviceVolumePlayer(
    player: Player,
    private val controller: UsbExclusiveModeController
) : ForwardingPlayer(player) {

    private val remoteDeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMinVolume(0)
        .setMaxVolume(controller.deviceVolumeMaxSteps)
        .build()

    override fun getDeviceInfo(): DeviceInfo =
        if (controller.deviceVolumeAvailable()) remoteDeviceInfo else super.getDeviceInfo()

    override fun getDeviceVolume(): Int = controller.deviceVolumeSteps()

    override fun isDeviceMuted(): Boolean =
        controller.deviceVolumeAvailable() && controller.deviceVolumeSteps() == 0

    override fun setDeviceVolume(volume: Int, flags: Int) {
        controller.setDeviceVolumeSteps(volume)
    }

    @Deprecated("Deprecated in Player")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    override fun increaseDeviceVolume(flags: Int) {
        controller.adjustDeviceVolume(+1)
    }

    @Deprecated("Deprecated in Player")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    override fun decreaseDeviceVolume(flags: Int) {
        controller.adjustDeviceVolume(-1)
    }

    @Deprecated("Deprecated in Player")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        controller.setDeviceMuted(muted)
    }

    @Deprecated("Deprecated in Player")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)
}
