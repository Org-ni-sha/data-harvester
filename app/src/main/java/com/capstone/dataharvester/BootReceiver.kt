package com.capstone.dataharvester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that listens for BOOT_COMPLETED to restart the
 * DataCollectionService after a device reboot.
 *
 * Only restarts the service if it was running before the reboot
 * (checked via SharedPreferences flag).
 *
 * Requires: android.permission.RECEIVE_BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Check if collection was active before reboot
        val prefs = context.getSharedPreferences(
            DataCollectionService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val wasCollecting = prefs.getBoolean(DataCollectionService.PREF_IS_COLLECTING, false)

        if (wasCollecting) {
            Log.i(TAG, "Device rebooted — restarting data collection service")
            val serviceIntent = Intent(context, DataCollectionService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Log.i(TAG, "Device rebooted — collection was stopped, not restarting")
        }
    }
}
