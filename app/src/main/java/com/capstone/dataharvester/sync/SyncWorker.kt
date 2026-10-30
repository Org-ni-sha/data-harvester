package com.capstone.dataharvester.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capstone.dataharvester.data.DatabaseGuard
import com.capstone.dataharvester.util.ConsentManager

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // No uploads without consent; try again on the next scheduled run
        if (!ConsentManager(applicationContext).isAccepted()) return Result.success()
        // Database not openable by this version: nothing to upload until a fixed update
        if (!DatabaseGuard.ensureOpen(applicationContext)) return Result.success()

        return try {
            val syncManager = CloudSyncManager(applicationContext)
            val result = syncManager.syncPendingData()
            when (result) {
                is SyncResult.Success -> Result.success()
                is SyncResult.Failure -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
