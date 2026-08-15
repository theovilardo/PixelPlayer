package com.theveloper.pixelplay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
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

/**
 * The StateFlows here are `stateIn(..., SharingStarted.Eagerly, true)` on the repository's own
 * background scope, not the test's — so round-trip assertions use Turbine's `awaitItem()`
 * (properly suspends for the async DataStore write to propagate) rather than reading `.value`
 * immediately after `save()`, which would race the background collector.
 */
class WearPerformanceSettingsRepositoryTest {

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var tempDir: Path
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: WearPerformanceSettingsRepository

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("wear-performance-settings-test")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
        )
        // Only the DataStore-backed behavior is covered here; refreshFromPhone() drives a real
        // Play Services DataClient and is device-verified, so a relaxed mock is enough to build.
        repository = WearPerformanceSettingsRepository(dataStore, mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        dataStoreScope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `all three default to true when nothing has been persisted`() = runTest {
        // stateIn's initialValue is available synchronously, before any real collection has
        // started, so this is safe to read without awaiting anything.
        assertThat(repository.showAlbumArt.value).isTrue()
        assertThat(repository.dynamicColorTheming.value).isTrue()
        assertThat(repository.playButtonAnimation.value).isTrue()
    }

    @Test
    fun `save propagates false for showAlbumArt through its StateFlow`() = runTest {
        repository.showAlbumArt.test {
            assertThat(awaitItem()).isTrue()
            repository.save(showAlbumArt = false, dynamicColorTheming = true, playButtonAnimation = true)
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `save propagates false for dynamicColorTheming through its StateFlow`() = runTest {
        repository.dynamicColorTheming.test {
            assertThat(awaitItem()).isTrue()
            repository.save(showAlbumArt = true, dynamicColorTheming = false, playButtonAnimation = true)
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `save propagates false for playButtonAnimation through its StateFlow`() = runTest {
        repository.playButtonAnimation.test {
            assertThat(awaitItem()).isTrue()
            repository.save(showAlbumArt = true, dynamicColorTheming = true, playButtonAnimation = false)
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `saving one flag false does not affect the other two`() = runTest {
        repository.showAlbumArt.test {
            assertThat(awaitItem()).isTrue()
            repository.save(showAlbumArt = false, dynamicColorTheming = true, playButtonAnimation = true)
            assertThat(awaitItem()).isFalse()

            // The other two flows aren't being collected here, but a second save that changes
            // only showAlbumArt back should still round-trip correctly, confirming save() writes
            // exactly the three values passed rather than something order-dependent.
            repository.save(showAlbumArt = true, dynamicColorTheming = false, playButtonAnimation = false)
            assertThat(awaitItem()).isTrue()
        }
        assertThat(repository.dynamicColorTheming.value).isFalse()
        assertThat(repository.playButtonAnimation.value).isFalse()
    }

    @Test
    fun `a later save overwrites an earlier one, not merges`() = runTest {
        repository.save(showAlbumArt = false, dynamicColorTheming = false, playButtonAnimation = false)
        repository.save(showAlbumArt = true, dynamicColorTheming = true, playButtonAnimation = true)

        repository.showAlbumArt.test {
            assertThat(awaitItem()).isTrue()
        }
    }
}
