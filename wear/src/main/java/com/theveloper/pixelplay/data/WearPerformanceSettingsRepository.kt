package com.theveloper.pixelplay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    suspend fun save(showAlbumArt: Boolean, dynamicColorTheming: Boolean, playButtonAnimation: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_ALBUM_ART] = showAlbumArt
            prefs[Keys.DYNAMIC_COLOR_THEMING] = dynamicColorTheming
            prefs[Keys.PLAY_BUTTON_ANIMATION] = playButtonAnimation
        }
    }

    private object Keys {
        val SHOW_ALBUM_ART = booleanPreferencesKey("wear_perf_show_album_art")
        val DYNAMIC_COLOR_THEMING = booleanPreferencesKey("wear_perf_dynamic_color_theming")
        val PLAY_BUTTON_ANIMATION = booleanPreferencesKey("wear_perf_play_button_animation")
    }
}
