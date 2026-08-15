package com.theveloper.pixelplay.data.service.wear

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A playlist batch transfer request, in just enough detail to resume it after the phone process
 * dies mid-transfer — a realistic outcome for a transfer that can run tens of minutes over
 * Bluetooth, not a theoretical one (see the plan's §R-06). [songIds] is the original request, not
 * whatever subset was still pending when the process died: [PlaylistWatchTransferCoordinator]
 * already re-derives which of them are still needed by asking the watch what it already has, the
 * same way it does for a fresh, non-resumed send.
 */
@Serializable
data class PersistedPlaylistBatchIntent(
    val batchId: String,
    val playlistId: String,
    val playlistName: String,
    val songIds: List<String>,
    val requestedAtMillis: Long,
)

/**
 * Persists at most one in-flight playlist batch intent — deliberately not the rest of
 * [PhoneWatchTransferStateStore]'s state (per-song byte progress, reachable nodes, ...), which is
 * UI-only, cheap to rebuild, and churns too fast to persist sensibly. Only the intent — "this
 * playlist batch was requested and hadn't finished" — needs to survive a process restart.
 *
 * Reuses the app's single shared `DataStore<Preferences>` (see [com.theveloper.pixelplay.di.AppModule])
 * rather than a dedicated file, matching the existing `*PreferencesRepository` convention.
 */
@Singleton
class PlaylistBatchTransferPersistence @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun saveInFlightBatch(intent: PersistedPlaylistBatchIntent) {
        dataStore.edit { preferences ->
            preferences[Keys.IN_FLIGHT_BATCH] = json.encodeToString(intent)
        }
    }

    /**
     * No-ops if [batchId] isn't the one currently stored: a newer batch (e.g. the user sent
     * another playlist while this one was still finishing up) may already have overwritten it,
     * and clearing unconditionally here would drop that newer, still-in-flight intent instead.
     */
    suspend fun clearInFlightBatch(batchId: String) {
        dataStore.edit { preferences ->
            val stored = preferences[Keys.IN_FLIGHT_BATCH]?.let(::decode)
            if (stored?.batchId == batchId) {
                preferences.remove(Keys.IN_FLIGHT_BATCH)
            }
        }
    }

    suspend fun getInFlightBatch(): PersistedPlaylistBatchIntent? {
        val stored = dataStore.data.first()[Keys.IN_FLIGHT_BATCH] ?: return null
        return decode(stored)
    }

    private fun decode(raw: String): PersistedPlaylistBatchIntent? = try {
        json.decodeFromString<PersistedPlaylistBatchIntent>(raw)
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed to decode persisted playlist batch intent, discarding it")
        null
    }

    private object Keys {
        val IN_FLIGHT_BATCH = stringPreferencesKey("wear_playlist_batch_in_flight_v1")
    }

    private companion object {
        const val TAG = "PlaylistBatchPersist"
    }
}
