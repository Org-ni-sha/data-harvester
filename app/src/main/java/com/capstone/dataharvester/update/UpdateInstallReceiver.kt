package com.capstone.dataharvester.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Result of an install session started by [UpdateManager]. Android first reports
 * PENDING_USER_ACTION with its own confirmation screen, which is shown here; after the
 * participant confirms, a successful install replaces (and restarts) the app, and a
 * failure comes back here with the reason.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    Log.e(TAG, "Installer asked for confirmation without a screen to show")
                    return
                }
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
            else -> {
                Log.e(TAG, "Update not installed: status $status, $message")
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space. Free some space and try again."
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "The update is signed differently from the installed app. Please contact the research team."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The update is not compatible with this phone."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "This phone blocked the installation."
                    else -> "The update could not be installed${message?.let { " ($it)" } ?: ""}."
                }
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            }
        }
    }
}
