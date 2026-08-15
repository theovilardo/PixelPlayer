package com.theveloper.pixelplay

import android.app.Application
import com.theveloper.pixelplay.data.WearPerformanceSettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class WearApp : Application() {

    @Inject
    lateinit var performanceSettingsRepository: dagger.Lazy<WearPerformanceSettingsRepository>

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startupScope.launch {
            // The performance toggles otherwise only ever arrive as a push, which a fresh install
            // of this app can never receive: reinstalling wipes the local copy back to the
            // all-enabled defaults, and the phone re-announcing the same values produces a
            // byte-identical DataItem that the Data Layer drops as unchanged. Pulling the
            // published item once per process start is what makes the toggles actually stick.
            // Best-effort: a failure here just leaves this session on the previous values.
            try {
                performanceSettingsRepository.get().refreshFromPhone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh watch performance settings at startup")
            }
        }
    }
}
