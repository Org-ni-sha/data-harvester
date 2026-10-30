package com.capstone.dataharvester

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capstone.dataharvester.data.DatabaseGuard
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.StudyManager
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes: if collection should be running (consent, enrolled, the user did not
 * stop it) but the service is gone — killed by the system or a vendor battery manager, or
 * stopped by the foreground-service timeout — start it again.
 *
 * Android 12+ blocks starting a foreground service from the background unless the app is
 * exempt from battery optimization (onboarding asks for that). When the start is refused,
 * a notification asks the participant to open the app, which restarts collection.
 * Time without collection is still visible in the data as a GAP row.
 */
class CollectionWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CollectionWatchdog"
        const val WORK_NAME = "CollectionWatchdog"
        private const val NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CollectionWatchdogWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val shouldCollect = context
            .getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(DataCollectionService.PREF_IS_COLLECTING, false)

        if (!shouldCollect || DataCollectionService.isRunning) return Result.success()
        if (!ConsentManager(context).isAccepted() || !StudyManager(context).isEnrolled()) return Result.success()
        // This version cannot open the records (missing migration): restarting would only
        // stop again. The service already told the participant; wait for the fixed update.
        if (!DatabaseGuard.ensureOpen(context)) return Result.success()

        return try {
            ContextCompat.startForegroundService(context, Intent(context, DataCollectionService::class.java))
            Log.i(TAG, "Collection service was not running — restarted")
            Result.success()
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) or similar
            Log.w(TAG, "Could not restart collection from the background", e)
            notifyStopped(context)
            Result.success()
        }
    }

    private fun notifyStopped(context: Context) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, DataCollectionService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Data collection stopped")
            .setContentText("Tap to open Data Harvester and resume collection.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }
}
