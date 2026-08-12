package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.shared.WearDataPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the watch performance toggles (Settings -> "Watch") to the Wear Data Layer as a
 * DataItem, not a `MessageClient` message — mirrors [WearStatePublisher]'s `PLAYER_STATE`
 * publishing. A `MessageClient` send only confirms local hand-off, not that the watch received
 * it (the exact bug fixed for playlist sync — see [WearDataPaths.PLAYLIST_SYNC_ACK]'s doc). A
 * DataItem instead syncs durably: if the watch is mid-reconnect when this publishes, it still
 * gets the update once it's back, with no ack/retry machinery needed on our side for that.
 */
@Singleton
class WearPerformanceSettingsPublisher @Inject constructor(
    private val application: Application,
) {
    private val dataClient by lazy { Wearable.getDataClient(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publish(showAlbumArt: Boolean, dynamicColorTheming: Boolean, playButtonAnimation: Boolean) {
        scope.launch {
            try {
                val request = PutDataMapRequest.create(WearDataPaths.WEAR_PERFORMANCE_SETTINGS).apply {
                    dataMap.putBoolean(WearDataPaths.KEY_SHOW_ALBUM_ART, showAlbumArt)
                    dataMap.putBoolean(WearDataPaths.KEY_DYNAMIC_COLOR_THEMING, dynamicColorTheming)
                    dataMap.putBoolean(WearDataPaths.KEY_PLAY_BUTTON_ANIMATION, playButtonAnimation)
                }.asPutDataRequest().setUrgent()

                dataClient.putDataItem(request)
                Timber.tag(TAG).d(
                    "Published performance settings: albumArt=%s dynamicColor=%s playButtonAnim=%s",
                    showAlbumArt,
                    dynamicColorTheming,
                    playButtonAnimation,
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to publish performance settings to watch")
            }
        }
    }

    private companion object {
        const val TAG = "WearPerfSettingsPub"
    }
}
