package com.theveloper.pixelplay.di

import android.app.Application
import androidx.room.Room
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.theveloper.pixelplay.data.local.LocalPlaylistDao
import com.theveloper.pixelplay.data.local.LocalSongDao
import com.theveloper.pixelplay.data.local.WearMusicDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WearModule {

    @Provides
    @Singleton
    fun provideDataClient(application: Application): DataClient =
        Wearable.getDataClient(application)

    @Provides
    @Singleton
    fun provideMessageClient(application: Application): MessageClient =
        Wearable.getMessageClient(application)

    @Provides
    @Singleton
    fun provideNodeClient(application: Application): NodeClient =
        Wearable.getNodeClient(application)

    @Provides
    @Singleton
    fun provideChannelClient(application: Application): ChannelClient =
        Wearable.getChannelClient(application)

    @Provides
    @Singleton
    fun provideWearMusicDatabase(application: Application): WearMusicDatabase =
        Room.databaseBuilder(
            application,
            WearMusicDatabase::class.java,
            "wear_music.db"
        ).addMigrations(
            WearMusicDatabase.MIGRATION_1_2,
            WearMusicDatabase.MIGRATION_2_3,
            WearMusicDatabase.MIGRATION_3_4,
            WearMusicDatabase.MIGRATION_4_5,
            WearMusicDatabase.MIGRATION_5_6,
        ).build()

    @Provides
    @Singleton
    fun provideLocalSongDao(database: WearMusicDatabase): LocalSongDao =
        database.localSongDao()

    @Provides
    @Singleton
    fun provideLocalPlaylistDao(database: WearMusicDatabase): LocalPlaylistDao =
        database.localPlaylistDao()
}
