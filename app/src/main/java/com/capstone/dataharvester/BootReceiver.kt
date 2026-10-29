package com.capstone.dataharvester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver that restarts the DataCollectionService after a device reboot
 * (BOOT_COMPLETED) and after the app itself is updated (MY_PACKAGE_REPLACED, e.g. an
 * OTA update from UpdateManager, which stops the running service).
 *
 * Only restarts the service if it was running before
 * (checked via SharedPreferences flag).
 *
 * If Android refuses the background start, posts a notification the user can tap
 * to resume collection instead of failing silently.
 *
 * Requires: android.permission.RECEIVE_BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val RESUME_CHANNEL_ID = "data_harvester_resume"
        private const val RESUME_NOTIFICATION_ID = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Check if collection was active before reboot / update
        val prefs = context.getSharedPreferences(
            DataCollectionService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val wasCollecting = prefs.getBoolean(DataCollectionService.PREF_IS_COLLECTING, false)

        if (wasCollecting) {
            Log.i(TAG, "${intent.action} — restarting data collection service")
            val serviceIntent = Intent(context, DataCollectionService::class.java)

            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException
                Log.e(TAG, "Not allowed to restart collection from the background", e)
                showResumeNotification(context)
            }
        } else {
            Log.i(TAG, "${intent.action} — collection was stopped, not restarting")
        }
    }

    /** "Tap to resume" notification: opening the app restarts collection from the foreground. */
    private fun showResumeNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(RESUME_CHANNEL_ID, "Collection paused", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, RESUME_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle("Data collection paused")
            .setContentText("Tap to open Data Harvester and resume collection")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(RESUME_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post resume notification (POST_NOTIFICATIONS denied)", e)
        }
    }
}
