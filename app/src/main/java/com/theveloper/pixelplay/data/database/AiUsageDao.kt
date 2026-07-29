package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiUsageDao {
    @Query("SELECT * FROM ai_usage")
    suspend fun getAllUsagesOnce(): List<AiUsageEntity>

    @Query("SELECT COUNT(*) FROM ai_usage")
    suspend fun getUsageCount(): Int

    @Insert
    suspend fun insertUsage(usage: AiUsageEntity)

    @Insert
    suspend fun insertAll(usages: List<AiUsageEntity>)

    @Query("SELECT * FROM ai_usage ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentUsages(limit: Int): Flow<List<AiUsageEntity>>

    @Query("SELECT SUM(promptTokens) FROM ai_usage")
    fun getTotalPromptTokens(): Flow<Int?>

    @Query("SELECT SUM(outputTokens) FROM ai_usage")
    fun getTotalOutputTokens(): Flow<Int?>

    @Query("SELECT SUM(thoughtTokens) FROM ai_usage")
    fun getTotalThoughtTokens(): Flow<Int?>

    @Query("DELETE FROM ai_usage")
    suspend fun clearUsage()
}
