package com.theveloper.pixelplay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WearPlaybackStatePersistenceTest {

    // Same reasoning as PlaylistBatchTransferPersistenceTest in :app: DataStore's internal
    // write-actor needs a scope that outlives any single test method's own runTest {} block.
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var tempDir: Path
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var persistence: WearPlaybackStatePersistence

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("wear-playback-state-persistence-test")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
        )
        persistence = WearPlaybackStatePersistence(dataStore)
    }

    @AfterEach
    fun tearDown() {
        dataStoreScope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    private fun state(
        queueSongIds: List<String> = listOf("s1", "s2"),
        currentIndex: Int = 0,
        positionMs: Long = 12_345L,
        updatedAtMillis: Long = 1_000L,
    ) = PersistedLocalPlaybackState(
        queueSongIds = queueSongIds,
        currentIndex = currentIndex,
        positionMs = positionMs,
        updatedAtMillis = updatedAtMillis,
    )

    @Test
    fun `nothing persisted returns null`() = runTest {
        assertThat(persistence.read()).isNull()
    }

    @Test
    fun `save then read round-trips the state`() = runTest {
        val saved = state()
        persistence.save(saved)

        assertThat(persistence.read()).isEqualTo(saved)
    }

    @Test
    fun `saving again overwrites the previous state`() = runTest {
        persistence.save(state(currentIndex = 0, positionMs = 1_000L))
        persistence.save(state(currentIndex = 1, positionMs = 5_000L))

        val read = persistence.read()
        assertThat(read?.currentIndex).isEqualTo(1)
        assertThat(read?.positionMs).isEqualTo(5_000L)
    }

    @Test
    fun `clearing removes the stored state`() = runTest {
        persistence.save(state())

        persistence.clear()

        assertThat(persistence.read()).isNull()
    }

    @Test
    fun `clearing when nothing is stored does not throw`() = runTest {
        persistence.clear()

        assertThat(persistence.read()).isNull()
    }

    @Test
    fun `malformed stored data is treated as nothing persisted, not a crash`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("wear_local_playback_state_v1")] = "{not valid json"
        }

        assertThat(persistence.read()).isNull()
    }
}
