package com.theveloper.pixelplay.data.service.playback

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import timber.log.Timber

/**
 * Monitors audio device changes and resumes playback after headset reconnection.
 *
 * Extracted from [MusicService] to isolate headset monitoring from the
 * service's media-session lifecycle management.
 */
class HeadsetReconnectMonitor(
    private val audioManager: AudioManager,
    private val engine: DualPlayerEngine,
    private val isResumeEnabled: () -> Boolean,
    private val currentPlayer: () -> Player?,
) {

    private var callback: AudioDeviceCallback? = null
    private var shouldResume = false
    private var lastNoisyPauseRealtimeMs = 0L

    fun register() {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!addedDevices.any(::isReconnectableHeadsetOutput)) return
                maybeResume()
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        callback = deviceCallback
    }

    fun unregister() {
        callback?.let { c ->
            runCatching { audioManager.unregisterAudioDeviceCallback(c) }
        }
        callback = null
        clearResume()
    }

    fun markNoisyPause() {
        shouldResume = true
        lastNoisyPauseRealtimeMs = SystemClock.elapsedRealtime()
        Timber.tag(TAG).d("Marked playback for headset reconnect resume")
    }

    fun clearResume() {
        shouldResume = false
        lastNoisyPauseRealtimeMs = 0L
    }

    private fun maybeResume() {
        if (!isResumeEnabled() || !shouldResume) return

        val elapsedSinceNoisyPause = SystemClock.elapsedRealtime() - lastNoisyPauseRealtimeMs
        if (elapsedSinceNoisyPause > RESUME_WINDOW_MS) {
            clearResume()
            return
        }

        if (!hasReconnectableHeadsetOutput()) return

        val player = currentPlayer() ?: return
        if (
            player.currentMediaItem == null ||
            player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            clearResume()
            return
        }

        Timber.tag(TAG).d("Resuming playback after headset reconnect")
        clearResume()
        player.play()
    }

    private fun hasReconnectableHeadsetOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any(::isReconnectableHeadsetOutput)
    }

    private fun isReconnectableHeadsetOutput(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "HeadsetReconnectMonitor"
        private const val RESUME_WINDOW_MS = 15_000L
    }
}
