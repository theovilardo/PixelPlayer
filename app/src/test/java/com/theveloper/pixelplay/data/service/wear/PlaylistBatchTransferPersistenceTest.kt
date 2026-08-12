package com.theveloper.pixelplay.data.service.wear

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

class PlaylistBatchTransferPersistenceTest {

    // DataStore's internal write-actor needs a CoroutineScope that outlives any single test
    // method's own `runTest {}` block — a `runTest`-scoped `backgroundScope` gets cancelled the
    // moment that particular runTest call returns, which would tear this down mid-test if it were
    // built in @BeforeEach's own runTest instead of here.
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var tempDir: Path
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var persistence: PlaylistBatchTransferPersistence

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("playlist-batch-persistence-test")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
        )
        persistence = PlaylistBatchTransferPersistence(dataStore)
    }

    @AfterEach
    fun tearDown() {
        dataStoreScope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    private fun intent(
        batchId: String = "batch-1",
        playlistId: String = "p1",
        songIds: List<String> = listOf("s1", "s2"),
    ) = PersistedPlaylistBatchIntent(
        batchId = batchId,
        playlistId = playlistId,
        playlistName = "Road trip",
        songIds = songIds,
        requestedAtMillis = 1_000L,
    )

    @Test
    fun `nothing persisted returns null`() = runTest {
        assertThat(persistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `save then get round-trips the intent`() = runTest {
        val saved = intent()
        persistence.saveInFlightBatch(saved)

        assertThat(persistence.getInFlightBatch()).isEqualTo(saved)
    }

    @Test
    fun `saving a second batch overwrites the first`() = runTest {
        persistence.saveInFlightBatch(intent(batchId = "batch-1", playlistId = "p1"))
        persistence.saveInFlightBatch(intent(batchId = "batch-2", playlistId = "p2"))

        assertThat(persistence.getInFlightBatch()?.batchId).isEqualTo("batch-2")
    }

    @Test
    fun `clearing with the matching batchId removes it`() = runTest {
        persistence.saveInFlightBatch(intent(batchId = "batch-1"))

        persistence.clearInFlightBatch("batch-1")

        assertThat(persistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `clearing with a stale batchId is a no-op, so a newer batch survives`() = runTest {
        persistence.saveInFlightBatch(intent(batchId = "batch-1"))
        persistence.saveInFlightBatch(intent(batchId = "batch-2"))

        // Batch 1's own coordinator finally reaches its terminal state and tries to clear itself,
        // but batch 2 already overwrote the stored intent — must not clear batch 2's.
        persistence.clearInFlightBatch("batch-1")

        assertThat(persistence.getInFlightBatch()?.batchId).isEqualTo("batch-2")
    }

    @Test
    fun `clearing when nothing is stored does not throw`() = runTest {
        persistence.clearInFlightBatch("batch-1")

        assertThat(persistence.getInFlightBatch()).isNull()
    }

    @Test
    fun `malformed stored data is treated as nothing persisted, not a crash`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("wear_playlist_batch_in_flight_v1")] = "{not valid json"
        }

        assertThat(persistence.getInFlightBatch()).isNull()
    }
}
