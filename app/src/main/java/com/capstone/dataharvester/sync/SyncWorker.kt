package com.capstone.dataharvester.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val syncManager = CloudSyncManager(applicationContext)
            syncManager.syncPendingData()
            // Do the same for appUsageDao...
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
