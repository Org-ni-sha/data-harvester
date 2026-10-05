package com.capstone.dataharvester.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
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
