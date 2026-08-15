package com.theveloper.pixelplay.data

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.theveloper.pixelplay.shared.WearDataPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch-side durable cache of the performance toggles configured from the phone's Settings ->
 * "Reloj" screen. [WearDataListenerService] writes into this whenever a
 * `WearDataPaths.WEAR_PERFORMANCE_SETTINGS` DataItem syncs in; [WearLocalPlayerRepository] and the
 * player UI read it reactively. Caching locally (rather than querying the phone live) is the
 * whole point — these need to apply during standalone local playback, exactly when the phone may
 * not be reachable at all.
 *
 * All three default to `true` — current behavior preserved for a watch that's never received a
 * sync (e.g. right after this feature ships, before the user opens the new phone settings screen).
 *
 * Only meaningful during local playback (`WearOutputTarget.WATCH`) — see call sites for why
 * remote-controller mode ignores these entirely.
 */
@Singleton
class WearPerformanceSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val dataClient: DataClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val showAlbumArt: StateFlow<Boolean> = dataStore.data
        .map { it[Keys.SHOW_ALBUM_ART] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val dynamicColorTheming: StateFlow<Boolean> = dataStore.data
        .map { it[Keys.DYNAMIC_COLOR_THEMING] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val playButtonAnimation: StateFlow<Boolean> = dataStore.data
        .map { it[Keys.PLAY_BUTTON_ANIMATION] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * Reads the phone's current settings DataItem directly and applies it, instead of waiting for
     * a push into [WearDataListenerService].
     *
     * Load-bearing, not a nicety. `onDataChanged` only fires when a DataItem's *content* changes,
     * and the phone re-announcing the same values is byte-identical, so the Data Layer drops it
     * silently. A watch whose local copy is out of sync therefore has no way back on its own —
     * and reinstalling the watch app (every debug build!) wipes this DataStore back to the
     * all-`true` defaults, which is exactly that state: the phone still shows the toggles off,
     * the watch behaves as if they were on, and nothing short of the user toggling something on
     * the phone would ever reconcile them.
     *
     * Reading the DataItem is a local Play Services call — the DataItem is already mirrored on
     * the watch — so this works with the phone out of range, and does nothing at all if the user
     * has never opened the phone's watch settings (no DataItem, defaults stand).
     */
    suspend fun refreshFromPhone() {
        // Wildcard host to match the item whichever node published it, and FILTER_PREFIX rather
        // than an exact literal match so the lookup can't miss over a trailing-path detail.
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path(WearDataPaths.WEAR_PERFORMANCE_SETTINGS)
            .build()
        val buffer = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
        try {
            val dataMap = buffer.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap } ?: run {
                Timber.tag(TAG).d("No performance settings DataItem published by the phone yet")
                return
            }
            val showAlbumArt = dataMap.getBoolean(WearDataPaths.KEY_SHOW_ALBUM_ART, true)
            val dynamicColorTheming = dataMap.getBoolean(WearDataPaths.KEY_DYNAMIC_COLOR_THEMING, true)
            val playButtonAnimation = dataMap.getBoolean(WearDataPaths.KEY_PLAY_BUTTON_ANIMATION, true)
            save(showAlbumArt, dynamicColorTheming, playButtonAnimation)
            Timber.tag(TAG).i(
                "Performance settings refreshed from phone: albumArt=%s dynamicColor=%s playButtonAnim=%s",
                showAlbumArt,
                dynamicColorTheming,
                playButtonAnimation,
            )
        } finally {
            buffer.release()
        }
    }

    suspend fun save(showAlbumArt: Boolean, dynamicColorTheming: Boolean, playButtonAnimation: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_ALBUM_ART] = showAlbumArt
            prefs[Keys.DYNAMIC_COLOR_THEMING] = dynamicColorTheming
            prefs[Keys.PLAY_BUTTON_ANIMATION] = playButtonAnimation
        }
    }

    private companion object {
        const val TAG = "WearPerfSettings"
    }

    private object Keys {
        val SHOW_ALBUM_ART = booleanPreferencesKey("wear_perf_show_album_art")
        val DYNAMIC_COLOR_THEMING = booleanPreferencesKey("wear_perf_dynamic_color_theming")
        val PLAY_BUTTON_ANIMATION = booleanPreferencesKey("wear_perf_play_button_animation")
    }
}
