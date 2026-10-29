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
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.NetworkStatsHelper
import com.capstone.dataharvester.ui.Dialogs

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
 *  4. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (special — must be granted in Settings)
 *
 * The user must also accept the Terms and Consent Form (RA 10173), stored by
 * ConsentManager. Without it the app cannot reach MainActivity, collect, or upload.
 *
 * Once all granted and consent is given, the "GET STARTED" button becomes active and
 * navigates to MainActivity. The completion state is saved so subsequent launches
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
    private lateinit var batteryStatus: TextView
    private lateinit var consentCheckBox: CheckBox
    private lateinit var consentText: TextView
    private lateinit var getStartedButton: Button
    private lateinit var networkStatsHelper: NetworkStatsHelper
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkStatsHelper = NetworkStatsHelper(this)
        consentManager = ConsentManager(this)

        // If onboarding was completed, consent given and all permissions still granted,
        // skip to dashboard
        if (isOnboardingComplete() && canGetStarted()) {
            navigateToDashboard()
            return
        }

        setContentView(R.layout.activity_onboarding)

        // Bind views
        notifStatus = findViewById(R.id.notifStatus)
        phoneStatus = findViewById(R.id.phoneStatus)
        usageStatus = findViewById(R.id.usageStatus)
        batteryStatus = findViewById(R.id.batteryStatus)
        consentCheckBox = findViewById(R.id.consentCheckBox)
        consentText = findViewById(R.id.consentText)
        getStartedButton = findViewById(R.id.getStartedButton)

        // Click handlers for each permission row
        notifStatus.setOnClickListener { requestNotificationPermission() }
        phoneStatus.setOnClickListener { requestPhoneStatePermission() }
        usageStatus.setOnClickListener { openUsageAccessSettings() }
        batteryStatus.setOnClickListener { requestBatteryOptimization() }

        // Consent: "I have read and agree to the <Terms and Consent Form>"
        val label = getString(R.string.consent_checkbox_label)
        val link = getString(R.string.consent_link_text)
        consentText.text = android.text.SpannableString(label).apply {
            val start = label.indexOf(link)
            if (start >= 0) {
                setSpan(android.text.style.UnderlineSpan(), start, start + link.length, 0)
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        ContextCompat.getColor(this@OnboardingActivity, R.color.accent_blue)
                    ),
                    start, start + link.length, 0
                )
            }
        }
        consentText.setOnClickListener { showConsentDialog() }
        consentCheckBox.isChecked = consentManager.isAccepted()
        consentCheckBox.setOnClickListener {
            if (consentCheckBox.isChecked) {
                // Ticking only counts after reading the form: show it and let I AGREE tick it
                consentCheckBox.isChecked = false
                showConsentDialog()
            } else {
                withdrawConsent()
            }
        }

        // Get Started button
        getStartedButton.setOnClickListener {
            // Re-check at tap time: the button state can lag behind Settings changes
            if (!canGetStarted()) {
                if (!consentManager.isAccepted()) {
                    Toast.makeText(this, R.string.consent_required, Toast.LENGTH_SHORT).show()
                }
                updatePermissionUI()
                return@setOnClickListener
            }
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

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun allPermissionsGranted(): Boolean {
        return isNotificationPermissionGranted() &&
                isPhoneStatePermissionGranted() &&
                isUsageAccessGranted() &&
                isBatteryOptimizationIgnored()
    }

    private fun canGetStarted(): Boolean = allPermissionsGranted() && consentManager.isAccepted()

    // ─── Terms and Consent ────────────────────────────────────────────────

    private fun showConsentDialog() {
        Dialogs.builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(getText(R.string.consent_body))
            .setPositiveButton(R.string.consent_agree) { _, _ -> acceptConsent() }
            .setNegativeButton(R.string.consent_close, null)
            .show()
    }

    private fun acceptConsent() {
        consentManager.accept()
        consentCheckBox.isChecked = true
        updatePermissionUI()
    }

    private fun withdrawConsent() {
        consentManager.withdraw()
        // No collection without consent
        stopService(Intent(this, DataCollectionService::class.java))
        updatePermissionUI()
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

    /**
     * Some phones (Realme/Oppo on Android 15, for example) skip the system popup and
     * open the app's "App battery usage" page instead. There, the "Allow background
     * usage" switch alone keeps the app "Optimized"; only choosing "Unrestricted"
     * (tap the words, not the switch) exempts it, which is what this screen checks.
     * So explain that before opening it.
     */
    private fun requestBatteryOptimization() {
        if (isBatteryOptimizationIgnored()) return

        Dialogs.builder(this)
            .setTitle("Allow unrestricted battery use")
            .setMessage(
                "If a popup asks to let Data Harvester always run in the background, tap Allow.\n\n" +
                "If a battery page opens instead, tap the words \"Allow background usage\" " +
                "(not only the switch) and choose \"Unrestricted\".\n\n" +
                "If you see a list of apps, find Data Harvester and choose " +
                "\"Don't optimize\" or \"Allow\"."
            )
            .setPositiveButton("OPEN SETTINGS") { _, _ -> openBatterySettings() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open Battery Settings", Toast.LENGTH_SHORT).show()
            }
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

        // Battery Optimization
        if (isBatteryOptimizationIgnored()) {
            batteryStatus.text = granted
            batteryStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            batteryStatus.isClickable = false
        } else {
            batteryStatus.text = grantAction
            batteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            batteryStatus.isClickable = true
        }

        // Consent checkbox mirrors the saved acceptance
        consentCheckBox.isChecked = consentManager.isAccepted()

        // Get Started button — only enabled when all granted and consent is given
        getStartedButton.isEnabled = canGetStarted()
        if (canGetStarted()) {
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
