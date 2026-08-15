package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.coverart.AutoCoverArtFetcher
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Looks for covers for albums that have none, after a library sync.
 *
 * Nothing here touches audio files, so it needs no write consent and can run
 * unattended; see [AutoCoverArtFetcher] for what it does write.
 */
@HiltWorker
class AutoCoverArtWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val autoCoverArtFetcher: AutoCoverArtFetcher,
    private val userPreferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!userPreferencesRepository.autoAlbumArtEnabledFlow.first()) {
            return Result.success()
        }

        return try {
            val outcome = autoCoverArtFetcher.fetchMissingCovers(isStopped = { isStopped })
            Timber.tag(WORK_NAME).i(
                "Checked ${outcome.albumsChecked} albums, applied ${outcome.coversApplied} covers, " +
                    "${outcome.notFound} without a match"
            )

            // A pass is capped so an unattended run stays bounded, but every album
            // it touched is either covered now or on the not-found list, so
            // chaining another pass makes progress and always terminates.
            if (outcome.reachedLimit && !isStopped) {
                enqueueContinuation(
                    context = applicationContext,
                    unmeteredOnly = userPreferencesRepository.autoAlbumArtUnmeteredOnlyFlow.first()
                )
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            // WorkManager stopping the pass is not a failure to retry: it will
            // be re-run on its own terms, and the albums already resolved were
            // recorded as the pass went.
            throw cancellation
        } catch (error: Exception) {
            Timber.tag(WORK_NAME).w(error, "Automatic cover art pass failed")
            // Covers are a convenience, and a library the catalogs keep failing
            // on would otherwise retry on WorkManager's backoff indefinitely.
            // The next sync queues a fresh pass anyway.
            // runAttemptCount is 0 on the first execution.
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "auto_cover_art_worker"

        /**
         * Attempts a failing pass gets before it is left to the next sync.
         */
        private const val MAX_ATTEMPTS = 3

        /**
         * Queues a pass, keeping any run already under way.
         *
         * Automatic work keeps, the way the rest of the app's background work
         * does; only the user's own "try again" replaces.
         */
        fun enqueue(context: Context, unmeteredOnly: Boolean, replaceRunning: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                buildRequest(unmeteredOnly)
            )
        }

        /**
         * Queues the next pass of a run that hit its cap.
         *
         * Appended to the same name the pass itself runs under, so it queues
         * behind the worker doing the asking rather than beside it. Queueing it
         * under a name of its own would leave two passes able to run at once --
         * one under each name -- both querying the same catalogs and both
         * writing to the same artwork store. REPLACE under the shared name
         * would cancel the asker instead, and OR_REPLACE covers the case where
         * the chain it is appending to has already been cancelled.
         */
        private fun enqueueContinuation(context: Context, unmeteredOnly: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(unmeteredOnly)
            )
        }

        internal fun buildRequest(unmeteredOnly: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AutoCoverArtWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
    }
}
