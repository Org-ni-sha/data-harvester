package com.capstone.dataharvester.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val synced = CloudSyncManager(applicationContext).syncPendingData()
            if (synced < 0) {
                // Upload failed or the connection was metered. Retrying re-applies the
                // unmetered constraint, so the next attempt waits for Wi-Fi.
                Log.w(TAG, "Sync did not complete (code $synced) — scheduling retry")
                Result.retry()
            } else {
                Log.i(TAG, "Sync finished — $synced records uploaded")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker crashed", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
