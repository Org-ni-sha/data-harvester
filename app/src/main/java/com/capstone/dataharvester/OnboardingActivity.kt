package com.capstone.dataharvester

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.capstone.dataharvester.util.NetworkStatsHelper

/**
 * Onboarding / permission gate screen.
 *
 * Shown on first launch and whenever permissions have been revoked.
 * The user must grant all required permissions before proceeding to the dashboard.
 *
 * Permissions required:
 *  1. POST_NOTIFICATIONS (Android 13+ runtime permission)
 *  2. READ_PHONE_STATE (runtime permission for signal strength)
 *  3. PACKAGE_USAGE_STATS (special — must be granted in Settings)
 *
 * Once all granted, the "GET STARTED" button becomes active and navigates
 * to MainActivity. The completion state is saved so subsequent launches
 * skip this screen (unless a permission is revoked).
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val PERMISSION_REQUEST_CODE = 200
    }

    private lateinit var notifStatus: TextView
    private lateinit var phoneStatus: TextView
    private lateinit var usageStatus: TextView
    private lateinit var getStartedButton: Button
    private lateinit var networkStatsHelper: NetworkStatsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkStatsHelper = NetworkStatsHelper(this)

        // If onboarding was completed and all permissions still granted, skip to dashboard
        if (isOnboardingComplete() && allPermissionsGranted()) {
            navigateToDashboard()
            return
        }

        setContentView(R.layout.activity_onboarding)

        // Bind views
        notifStatus = findViewById(R.id.notifStatus)
        phoneStatus = findViewById(R.id.phoneStatus)
        usageStatus = findViewById(R.id.usageStatus)
        getStartedButton = findViewById(R.id.getStartedButton)

        // Click handlers for each permission row
        notifStatus.setOnClickListener { requestNotificationPermission() }
        phoneStatus.setOnClickListener { requestPhoneStatePermission() }
        usageStatus.setOnClickListener { openUsageAccessSettings() }

        // Get Started button
        getStartedButton.setOnClickListener {
            markOnboardingComplete()
            navigateToDashboard()
        }

        // Initial permission check
        updatePermissionUI()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from Settings
        if (::notifStatus.isInitialized) {
            updatePermissionUI()
        }
    }

    // ─── Permission Checks ────────────────────────────────────────────────

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed pre-Android 13
        }
    }

    private fun isPhoneStatePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isUsageAccessGranted(): Boolean {
        return networkStatsHelper.hasUsageAccessPermission()
    }

    private fun allPermissionsGranted(): Boolean {
        return isNotificationPermissionGranted() &&
                isPhoneStatePermissionGranted() &&
                isUsageAccessGranted()
    }

    // ─── Permission Requests ──────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (isNotificationPermissionGranted()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check if we can still show the system dialog
            val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
            val hasAskedBefore = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("asked_notification", false)

            if (!hasAskedBefore || canAsk) {
                // First time or user didn't select "Don't ask again"
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("asked_notification", true).apply()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                // User denied permanently — redirect to app Settings
                openAppSettings("Enable Notifications in app settings")
            }
        }
    }

    private fun requestPhoneStatePermission() {
        if (isPhoneStatePermissionGranted()) return

        val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.READ_PHONE_STATE
        )
        val hasAskedBefore = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("asked_phone_state", false)

        if (!hasAskedBefore || canAsk) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("asked_phone_state", true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PERMISSION_REQUEST_CODE
            )
        } else {
            openAppSettings("Enable Phone permission in app settings")
        }
    }

    private fun openAppSettings(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun openUsageAccessSettings() {
        if (isUsageAccessGranted()) return

        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                this,
                "Find \"Data Harvester\" and enable access",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── UI Updates ───────────────────────────────────────────────────────

    private fun updatePermissionUI() {
        val granted = "✓ GRANTED"
        val grantAction = "GRANT"

        // Notification
        if (isNotificationPermissionGranted()) {
            notifStatus.text = granted
            notifStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            notifStatus.isClickable = false
        } else {
            notifStatus.text = grantAction
            notifStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            notifStatus.isClickable = true
        }

        // Phone State
        if (isPhoneStatePermissionGranted()) {
            phoneStatus.text = granted
            phoneStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            phoneStatus.isClickable = false
        } else {
            phoneStatus.text = grantAction
            phoneStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            phoneStatus.isClickable = true
        }

        // Usage Access
        if (isUsageAccessGranted()) {
            usageStatus.text = granted
            usageStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            usageStatus.isClickable = false
        } else {
            usageStatus.text = grantAction
            usageStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            usageStatus.isClickable = true
        }

        // Get Started button — only enabled when all granted
        getStartedButton.isEnabled = allPermissionsGranted()
        if (allPermissionsGranted()) {
            getStartedButton.alpha = 1.0f
        } else {
            getStartedButton.alpha = 0.4f
        }
    }

    // ─── Permission Result ────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionUI()
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────

    private fun navigateToDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isOnboardingComplete(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    private fun markOnboardingComplete() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
    }
}
