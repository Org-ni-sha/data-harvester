package com.capstone.dataharvester

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.CollectionLocks
import com.capstone.dataharvester.ui.Dialogs
import com.capstone.dataharvester.ui.Dialogs.withDangerButton
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.CsvExporter
import com.capstone.dataharvester.util.DeviceIdManager
import com.capstone.dataharvester.util.DeviceInfoHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.capstone.dataharvester.sync.CloudSyncManager
import com.capstone.dataharvester.sync.SyncResult
import com.capstone.dataharvester.sync.SyncWorker
import com.capstone.dataharvester.update.UpdateManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import java.util.concurrent.TimeUnit

/**
 * Dashboard activity — the main screen after onboarding.
 *
 * Features:
 *  - Device ID display (UUID + model)
 *  - Live status indicator with colored dot
 *  - Stats grid: record counts, today's usage, last record time
 *  - Start / Stop collection (side by side)
 *  - Export All CSV (both main + per-app, fixed filenames, overwrite)
 *  - Reset Data (optional upload first, then confirmation; clears this phone's tables only)
 *
 * Auto-refreshes stats every 10 seconds.
 * All permission handling is done in OnboardingActivity.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Unique name of the periodic upload; Reset cancels and re-enqueues it */
        const val SYNC_WORK_NAME = "CloudSQLiteSync"
    }

    // Views
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var recordCountText: TextView
    private lateinit var appRecordCountText: TextView
    private lateinit var todayUsageText: TextView
    private lateinit var lastRecordText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var deviceModelText: TextView
    private lateinit var networkProviderText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var uploadButton: Button
    private lateinit var exportAllButton: Button
    private lateinit var resetButton: Button

    // Helpers
    private lateinit var deviceIdManager: DeviceIdManager

    // Coroutine scope for UI updates
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The dashboard can also be opened from the collection notification, so
        // check consent here too: without it, send the user back to onboarding
        if (!ConsentManager(this).isAccepted()) {
            Toast.makeText(this, R.string.consent_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Initialize helpers
        deviceIdManager = DeviceIdManager(this)

        // Bind views
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        recordCountText = findViewById(R.id.recordCountText)
        appRecordCountText = findViewById(R.id.appRecordCountText)
        todayUsageText = findViewById(R.id.todayUsageText)
        lastRecordText = findViewById(R.id.lastRecordText)
        deviceIdText = findViewById(R.id.deviceIdText)
        deviceModelText = findViewById(R.id.deviceModelText)
        networkProviderText = findViewById(R.id.networkProviderText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)  
        uploadButton = findViewById(R.id.uploadButton)
        exportAllButton = findViewById(R.id.exportAllButton)
        resetButton = findViewById(R.id.resetButton)

        // Make the status dot circular
        applyCircleDot()

        // Display device identity and get network provider
        val currentNetworkProvider = displayDeviceInfo()
        
        // Register device identity in database.
        // No hardware identifier (ANDROID_ID) is read: the hardware_id column is kept only so
        // the cloud's ON CONFLICT(hardware_id) upsert still works, and holds the random
        // install UUID instead.
        mainScope.launch {
            val currentDeviceId = deviceIdManager.getDeviceId()
            val deviceModel = deviceIdManager.getDeviceModel()

            val db = AppDatabase.getInstance(this@MainActivity)
            val dao = db.deviceIdentityDao()

            val existing = withContext(Dispatchers.IO) { dao.getLatestByDeviceId(currentDeviceId) }
            if (existing == null || existing.network_provider != currentNetworkProvider || existing.device_model != deviceModel) {
                withContext(Dispatchers.IO) {
                    dao.insert(
                        com.capstone.dataharvester.data.DeviceIdentity(
                            current_device_id = currentDeviceId,
                            previous_device_id = null,
                            hardware_id = currentDeviceId,
                            device_model = deviceModel,
                            network_provider = currentNetworkProvider,
                            linked_at = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // Button click listeners
        startButton.setOnClickListener { startCollection() }
        stopButton.setOnClickListener { stopCollection() }
        uploadButton.setOnClickListener { syncToCloud() }
        exportAllButton.setOnClickListener { exportAllCsv() }
        resetButton.setOnClickListener { confirmReset() }

        // Restore collection state
        restoreCollectionState()

        // Initial UI update + start auto-refresh
        updateStats()
        startAutoRefresh()

        // Schedule cloud synchronization
        setupCloudSync()

        // Check for remote app updates and show success notification if just updated
        val updateManager = UpdateManager(this)
        updateManager.checkAndNotifyIfUpdated()
        updateManager.checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        updateStats()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    // ─── Device Info Display ──────────────────────────────────────────────

    private fun displayDeviceInfo(): String {
        val deviceId = deviceIdManager.getDeviceId()
        val deviceModel = deviceIdManager.getDeviceModel()

        deviceIdText.text = "ID: $deviceId"
        deviceModelText.text = "Model: $deviceModel"
        
        val deviceHelper = DeviceInfoHelper(this)
        val networkProvider = deviceHelper.getNetworkProvider()

        networkProviderText.text = "Provider: $networkProvider"
        return networkProvider
    }

    // ─── Collection Control ────────────────────────────────────────────────

    private fun startCollection() {
        val intent = Intent(this, DataCollectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateStatusUI(isCollecting = true)
        Toast.makeText(this, "Collection started", Toast.LENGTH_SHORT).show()
    }

    private fun stopCollection() {
        stopService(Intent(this, DataCollectionService::class.java))

        updateStatusUI(isCollecting = false)
        Toast.makeText(this, "Collection stopped", Toast.LENGTH_SHORT).show()
    }

    // ─── Export All CSV ────────────────────────────────────────────────────

    private fun exportAllCsv() {
        exportAllButton.isEnabled = false
        exportAllButton.text = "Exporting..."

        mainScope.launch {
            try {
                val exporter = CsvExporter(this@MainActivity)
                val mainCount = withContext(Dispatchers.IO) { exporter.getExportableCount() }
                val appCount = withContext(Dispatchers.IO) { exporter.getAppExportableCount() }

                if (mainCount == 0 && appCount == 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "No records to export",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val (exportedMain, exportedApp) = withContext(Dispatchers.IO) {
                    exporter.exportAll()
                }

                Toast.makeText(
                    this@MainActivity,
                    "Exported $exportedMain records + $exportedApp app records to Downloads/",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                exportAllButton.isEnabled = true
                exportAllButton.text = "📤  EXPORT ALL CSV"
            }
        }
    }

    // ─── Reset Data ────────────────────────────────────────────────────────

    /**
     * Step 1 of Reset: offer to upload pending records first. Closing this dialog
     * (back, tap outside) cancels the reset. Skipped when nothing is pending.
     * Reset only deletes data on this phone; the cloud databases are never touched.
     */
    private fun confirmReset() {
        mainScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val pending = withContext(Dispatchers.IO) {
                db.usageDao().countUnsynced() + db.appUsageDao().countUnsynced()
            }
            if (pending == 0) {
                showResetConfirmation(uploadError = null)
                return@launch
            }

            Dialogs.builder(this@MainActivity)
                .setTitle("Upload before resetting?")
                .setMessage(
                    "%,d records have not been uploaded to the cloud yet.\n\n".format(pending) +
                    "Upload them first so they are not lost?"
                )
                .setPositiveButton("UPLOAD FIRST") { _, _ -> uploadThenConfirmReset() }
                .setNegativeButton("SKIP UPLOAD") { _, _ -> showResetConfirmation(uploadError = null) }
                .setCancelable(true)
                .show()
        }
    }

    /** Upload pending records, then always continue to the reset confirmation. */
    private fun uploadThenConfirmReset() {
        val progress = Dialogs.progress(
            this,
            "Uploading…",
            "Sending the records that are not uploaded yet to the cloud."
        )

        mainScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!ConsentManager(this@MainActivity).isAccepted()) {
                    SyncResult.Failure("Consent not given")
                } else {
                    CloudSyncManager(this@MainActivity).syncPendingData()
                }
            }
            progress.dismiss()
            updateStats()
            showResetConfirmation(uploadError = (result as? SyncResult.Failure)?.reason)
        }
    }

    /**
     * Step 2 of Reset: always shown, whichever choice was made in step 1. States how
     * many records are still not uploaded (those are lost) and lets the user also
     * delete the exported CSV files, which are kept by default as a recovery copy.
     */
    private fun showResetConfirmation(uploadError: String?) {
        mainScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val mainCount = withContext(Dispatchers.IO) { db.usageDao().getCount() }
            val appCount = withContext(Dispatchers.IO) { db.appUsageDao().getCount() }
            val pending = withContext(Dispatchers.IO) {
                db.usageDao().countUnsynced() + db.appUsageDao().countUnsynced()
            }

            val deleteExports = CheckBox(this@MainActivity).apply {
                text = "Also delete exported CSV files"
                isChecked = false
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_body))
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(this@MainActivity, R.color.accent_blue),
                        ContextCompat.getColor(this@MainActivity, R.color.dialog_secondary_button)
                    )
                )
            }
            val container = FrameLayout(this@MainActivity).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(deleteExports)
            }

            Dialogs.builder(this@MainActivity)
                .setTitle("⚠️ Reset All Data?")
                .setMessage(buildString {
                    append("This will permanently delete the records stored on this phone:\n\n")
                    append("• %,d usage records\n".format(mainCount))
                    append("• %,d per-app records\n".format(appCount))
                    if (uploadError != null) append("\nUpload failed: $uploadError\n")
                    if (pending > 0) {
                        append("\n⚠ %,d records are NOT uploaded and will be lost.\n".format(pending))
                    } else {
                        append("\nAll records are uploaded; the cloud copy is not affected.\n")
                    }
                    append("\nThis action cannot be undone.")
                })
                .setView(container)
                .setPositiveButton("RESET") { _, _ -> performReset(deleteExports.isChecked) }
                .setNegativeButton("CANCEL", null)
                .setCancelable(true)
                .show()
                .withDangerButton()
        }
    }

    private fun performReset(deleteExports: Boolean) {
        mainScope.launch {
            try {
                // Stop collection and uploads first
                stopService(Intent(this@MainActivity, DataCollectionService::class.java))
                updateStatusUI(isCollecting = false)
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork(SYNC_WORK_NAME).await()

                val db = AppDatabase.getInstance(this@MainActivity)

                withContext(Dispatchers.IO) {
                    // Wait for an upload or a collector tick that is still running
                    CloudSyncManager.syncMutex.withLock {
                        CollectionLocks.db.withLock {
                            db.withTransaction {
                                db.usageDao().deleteAll()
                                db.appUsageDao().deleteAll()
                                // Checkpoints too, so the next run starts a fresh baseline
                                db.checkpointDao().clear()
                                db.appUsageBaselineDao().clear()
                            }
                        }
                    }

                    // Exported CSVs are a recovery copy: only deleted when asked
                    if (deleteExports) CsvExporter(this@MainActivity).deleteExportedFiles()
                }

                getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(DataCollectionService.PREF_IS_COLLECTING, false)
                    // Pre-v11 checkpoint keys, in case the collector never ran on this version
                    .remove(DataCollectionService.PREF_LAST_CUMULATIVE_RX)
                    .remove(DataCollectionService.PREF_LAST_CUMULATIVE_TX)
                    .remove(DataCollectionService.PREF_LAST_APP_COLLECTION_TIME)
                    .apply()

                // Remove the cloud-total cache written by older versions
                getSharedPreferences("historical_counts", Context.MODE_PRIVATE).edit().clear().apply()

                // Re-enqueue the periodic upload cancelled above
                setupCloudSync()

                // Refresh UI
                updateStats()

                Toast.makeText(
                    this@MainActivity,
                    "All data on this phone has been reset",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Reset failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── UI Updates ────────────────────────────────────────────────────────

    private fun updateStats() {
        mainScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MainActivity)
                val dao = db.usageDao()
                val appDao = db.appUsageDao()

                val count = withContext(Dispatchers.IO) { dao.getCount() }
                val appCount = withContext(Dispatchers.IO) { appDao.getCount() }
                val last = withContext(Dispatchers.IO) { dao.getLast() }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val todayMb = withContext(Dispatchers.IO) { dao.getTodaySum(dateStr) }
                
                // Records currently stored on this phone only (not the cloud total)
                recordCountText.text = "%,d".format(count)
                appRecordCountText.text = "%,d".format(appCount)
                
                todayUsageText.text = "%.1f MB".format(todayMb)
                lastRecordText.text = if (last != null) {
                    last.datetimeStr.substringAfter("T").substringBefore(".")
                } else {
                    "—"
                }
            } catch (e: Exception) {
                recordCountText.text = "—"
                appRecordCountText.text = "—"
                todayUsageText.text = "— MB"
                lastRecordText.text = "Error"
            }
        }
    }

    private fun updateStatusUI(isCollecting: Boolean) {
        if (isCollecting) {
            statusText.text = "Collecting"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            applyDotColor(R.color.status_active)
            startButton.isEnabled = false
            startButton.alpha = 0.4f
            stopButton.isEnabled = true
            stopButton.alpha = 1.0f
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
            applyDotColor(R.color.status_stopped)
            startButton.isEnabled = true
            startButton.alpha = 1.0f
            stopButton.isEnabled = false
            stopButton.alpha = 0.4f
        }
    }

    private fun applyCircleDot() {
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(ContextCompat.getColor(this, R.color.status_stopped))
        statusDot.background = dot
    }

    private fun applyDotColor(colorRes: Int) {
        val dot = statusDot.background as? GradientDrawable
        dot?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun restoreCollectionState() {
        val prefs = getSharedPreferences(
            DataCollectionService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val isCollecting = prefs.getBoolean(DataCollectionService.PREF_IS_COLLECTING, false)
        updateStatusUI(isCollecting)

        // Collection should be running but Android may not have let it restart in the
        // background (e.g. after a reboot): restart it now, from the foreground.
        // Starting an already running service is harmless.
        if (isCollecting) {
            ContextCompat.startForegroundService(this, Intent(this, DataCollectionService::class.java))
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = mainScope.launch {
            while (isActive) {
                delay(10_000)
                updateStats()
            }
        }
    }

    private fun setupCloudSync() {
        // Only sync when device is connected to any network (WiFi or Mobile Data)
        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Schedule to run every 1 hour. Automated upload
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        // Queue the work. UPDATE ensures that the new interval is applied if already scheduled.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }
    
    private fun syncToCloud() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            Toast.makeText(this@MainActivity, "Please connect to the internet to upload.", Toast.LENGTH_SHORT).show()
            return
        }

        uploadButton.isEnabled = false
        uploadButton.text = "Uploading..."
        
        mainScope.launch {
            val syncManager = CloudSyncManager(this@MainActivity)
            val result = withContext(Dispatchers.IO) {
                syncManager.syncPendingData()
            }
            
            when (result) {
                is com.capstone.dataharvester.sync.SyncResult.Success -> {
                    val totalSynced = result.usage + result.app
                    if (totalSynced > 0) {
                        Toast.makeText(this@MainActivity, "Uploaded %,d (%,d usage, %,d app usage) records successfully!".format(totalSynced, result.usage, result.app), Toast.LENGTH_SHORT).show()
                        updateStats() // Update the stats count on screen
                    } else {
                        Toast.makeText(this@MainActivity, "No new unsynced records to upload.", Toast.LENGTH_SHORT).show()
                    }
                }
                is com.capstone.dataharvester.sync.SyncResult.Failure -> {
                    Toast.makeText(this@MainActivity, "Upload failed! ${result.reason}. Please try again. Check internet connection and logs.", Toast.LENGTH_LONG).show()
                }
            }
            
            uploadButton.isEnabled = true
            uploadButton.text = "☁️ UPLOAD TO CLOUD"
        }
    }

}