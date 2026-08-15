package com.theveloper.pixelplay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Watch-side DataStore, separate from the phone's — the two processes never share one file. */
val Context.wearDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_settings")

/**
 * The watch's local-playback queue and position, in just enough detail to restore it, paused,
 * after the hosting process dies mid-playback.
 *
 * Confirmed on-device this session, not a theoretical concern: the app's process was recycled
 * during a system-wide low-memory episode while a fitness-tracking app ran alongside local
 * playback (PID changed across ~90s in which 23 other system processes were also killed for
 * memory). `WearPlaybackService` being a foreground `MediaSessionService` makes that less likely,
 * not impossible — Wear OS watches have very little RAM to begin with.
 */
@Serializable
data class PersistedLocalPlaybackState(
    val queueSongIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val updatedAtMillis: Long,
)

/**
 * Persists at most one in-flight local-playback queue — mirrors
 * `PlaylistBatchTransferPersistence` in `:app` (same DataStore-backed, single-slot,
 * JSON-via-kotlinx.serialization shape), adapted to the watch's own DataStore since `:wear` and
 * `:app` are separate processes with no shared storage.
 */
@Singleton
class WearPlaybackStatePersistence @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(state: PersistedLocalPlaybackState) {
        dataStore.edit { preferences ->
            preferences[Keys.LOCAL_PLAYBACK_STATE] = json.encodeToString(state)
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(Keys.LOCAL_PLAYBACK_STATE) }
    }

    suspend fun read(): PersistedLocalPlaybackState? {
        val stored = dataStore.data.first()[Keys.LOCAL_PLAYBACK_STATE] ?: return null
        return try {
            json.decodeFromString<PersistedLocalPlaybackState>(stored)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to decode persisted local playback state, discarding it")
            null
        }
    }

    private object Keys {
        val LOCAL_PLAYBACK_STATE = stringPreferencesKey("wear_local_playback_state_v1")
    }

    private companion object {
        const val TAG = "WearPlaybackPersist"
    }
}

/**
 * Whether a persisted queue is still worth restoring.
 *
 * Requires at least one song id (an empty queue is nothing to resume) and caps how stale the
 * snapshot can be: recovering from a crash a few minutes or hours ago is the point of this
 * (§R-06-adjacent — the phone-side batch-transfer persistence uses the same "was genuinely
 * in-flight" reasoning); silently resurrecting whatever was playing days ago the next time the
 * app happens to open would be surprising rather than helpful. There's no on-device data to
 * calibrate the exact cutoff, so this picks a conservative, generously-long window instead of a
 * precisely-tuned one.
 */
internal fun isPersistedLocalPlaybackStateRestorable(
    state: PersistedLocalPlaybackState,
    nowMillis: Long,
    maxAgeMillis: Long = 6 * 60 * 60 * 1000L,
): Boolean {
    if (state.queueSongIds.isEmpty()) return false
    if (state.currentIndex !in state.queueSongIds.indices) return false
    val ageMillis = nowMillis - state.updatedAtMillis
    return ageMillis in 0..maxAgeMillis
}
