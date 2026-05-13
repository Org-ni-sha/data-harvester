package com.capstone.dataharvester

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.util.CsvExporter
import com.capstone.dataharvester.util.DeviceIdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard activity — the main screen after onboarding.
 *
 * Features:
 *  - Device ID display (UUID + model)
 *  - Live status indicator with colored dot
 *  - Stats grid: record counts, today's usage, last record time
 *  - Start / Stop collection (side by side)
 *  - Export All CSV (both main + per-app, fixed filenames, overwrite)
 *  - Reset Data (confirmation dialog, clears both tables)
 *
 * Auto-refreshes stats every 10 seconds.
 * All permission handling is done in OnboardingActivity.
 */
class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var recordCountText: TextView
    private lateinit var appRecordCountText: TextView
    private lateinit var todayUsageText: TextView
    private lateinit var lastRecordText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var deviceModelText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportAllButton: Button
    private lateinit var resetButton: Button

    // Helpers
    private lateinit var deviceIdManager: DeviceIdManager

    // Coroutine scope for UI updates
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        exportAllButton = findViewById(R.id.exportAllButton)
        resetButton = findViewById(R.id.resetButton)

        // Make the status dot circular
        applyCircleDot()

        // Display device identity
        displayDeviceInfo()

        // Button click listeners
        startButton.setOnClickListener { startCollection() }
        stopButton.setOnClickListener { stopCollection() }
        exportAllButton.setOnClickListener { exportAllCsv() }
        resetButton.setOnClickListener { confirmReset() }

        // Restore collection state
        restoreCollectionState()

        // Initial UI update + start auto-refresh
        updateStats()
        startAutoRefresh()
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

    private fun displayDeviceInfo() {
        val deviceId = deviceIdManager.getDeviceId()
        val deviceModel = deviceIdManager.getDeviceModel()

        deviceIdText.text = "ID: $deviceId"
        deviceModelText.text = "Model: $deviceModel"
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

    private fun confirmReset() {
        mainScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val mainCount = withContext(Dispatchers.IO) { db.usageDao().getCount() }
            val appCount = withContext(Dispatchers.IO) { db.appUsageDao().getCount() }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("⚠️ Reset All Data?")
                .setMessage(
                    "This will permanently delete all collected records:\n\n" +
                    "• $mainCount usage records\n" +
                    "• $appCount per-app records\n\n" +
                    "This action cannot be undone."
                )
                .setPositiveButton("RESET") { _, _ -> performReset() }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun performReset() {
        mainScope.launch {
            try {
                // Stop collection first if running
                stopService(Intent(this@MainActivity, DataCollectionService::class.java))
                updateStatusUI(isCollecting = false)

                val db = AppDatabase.getInstance(this@MainActivity)

                withContext(Dispatchers.IO) {
                    // Clear both tables
                    db.usageDao().deleteAll()
                    db.appUsageDao().deleteAll()
                }

                // Reset TrafficStats baselines in SharedPreferences
                val prefs = getSharedPreferences(
                    DataCollectionService.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putBoolean(DataCollectionService.PREF_IS_COLLECTING, false)
                    .apply()

                // Refresh UI
                updateStats()

                Toast.makeText(
                    this@MainActivity,
                    "All data has been reset",
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
}