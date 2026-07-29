package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.database.AiUsageDao
import com.theveloper.pixelplay.data.database.AiUsageEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiUsageBackupHandler @Inject constructor(
    private val aiUsageDao: AiUsageDao,
    private val gson: Gson
) : BackupModuleHandler {
    override val section: BackupSection = BackupSection.AI_USAGE_LOGS

    override suspend fun export(): String {
        val logs = aiUsageDao.getAllUsagesOnce()
        return gson.toJson(logs)
    }

    override suspend fun countEntries(): Int {
        return aiUsageDao.getUsageCount()
    }

    override suspend fun snapshot(): String {
        return export()
    }

    override suspend fun restore(payload: String) {
        val type = object : TypeToken<List<AiUsageEntity>>() {}.type
        val logs: List<AiUsageEntity> = gson.fromJson(payload, type)
        
        // We additive restore AI logs or clear? Usually for logs we additive restore 
        // but BackupModuleHandler documentation says "Clear existing data and restore".
        aiUsageDao.clearUsage()
        if (logs.isNotEmpty()) {
            aiUsageDao.insertAll(logs)
        }
    }

    override suspend fun rollback(snapshot: String) {
        restore(snapshot)
    }
}
