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
 * Qualifier for the iTunes Search Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ItunesRetrofit

/**
 * Qualifier for the MusicBrainz Retrofit instance and the client behind it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MusicBrainzRetrofit

/**
 * Qualifier for the Cover Art Archive Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoverArtArchiveRetrofit

/**
 * Qualifier for the client cover art images are fetched with.
 *
 * The URLs come from third-party search results and are checked for HTTPS
 * before the request is made; this client refuses to be redirected off it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoverArtImageClient
