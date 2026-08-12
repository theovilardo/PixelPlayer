package com.theveloper.pixelplay.di

import javax.inject.Qualifier

/**
 * Qualifier for Deezer Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeezerRetrofit

/**
 * Qualifier for Fast OkHttpClient (Short timeouts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FastOkHttpClient

/**
 * Qualifier for Gson instance configured for backup serialization.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupGson

/**
 * Qualifier for application-lifetime coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * Qualifier for the IO [kotlinx.coroutines.CoroutineDispatcher]. Injected rather than referenced
 * as `Dispatchers.IO` directly so tests can substitute a `TestDispatcher` (implements
 * `AND-CONC-03`). Most of this codebase predates this convention and still references
 * `Dispatchers.IO` directly — only use this qualifier in new code.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for the Main [kotlinx.coroutines.CoroutineDispatcher]. Same rationale as
 * [IoDispatcher] — new code only.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
