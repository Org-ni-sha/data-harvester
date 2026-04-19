package com.capstone.dataharvester

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.util.CsvExporter
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
 * Single-screen activity with:
 *  - Status indicator (Collecting / Stopped)
 *  - Record count, today's usage, last record time
 *  - Start / Stop / Export CSV buttons
 *
 * The UI auto-refreshes every 10 seconds to show updated stats.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
    }

    // Views
    private lateinit var statusText: TextView
    private lateinit var recordCountText: TextView
    private lateinit var todayUsageText: TextView
    private lateinit var lastRecordText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button

    // Coroutine scope for UI updates
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        statusText = findViewById(R.id.statusText)
        recordCountText = findViewById(R.id.recordCountText)
        todayUsageText = findViewById(R.id.todayUsageText)
        lastRecordText = findViewById(R.id.lastRecordText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        exportButton = findViewById(R.id.exportButton)

        // Button click listeners
        startButton.setOnClickListener { startCollection() }
        stopButton.setOnClickListener { stopCollection() }
        exportButton.setOnClickListener { exportCsv() }

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Restore collection state from SharedPreferences
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

    // ─── Collection Control ────────────────────────────────────────────────

    private fun startCollection() {
        val intent = Intent(this, DataCollectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateStatusUI(isCollecting = true)
        Toast.makeText(this, "✅ Collection started", Toast.LENGTH_SHORT).show()
    }

    private fun stopCollection() {
        stopService(Intent(this, DataCollectionService::class.java))

        updateStatusUI(isCollecting = false)
        Toast.makeText(this, "⏹ Collection stopped", Toast.LENGTH_SHORT).show()
    }

    // ─── CSV Export ────────────────────────────────────────────────────────

    private fun exportCsv() {
        exportButton.isEnabled = false
        exportButton.text = "Exporting..."

        mainScope.launch {
            try {
                val exporter = CsvExporter(this@MainActivity)
                val count = withContext(Dispatchers.IO) { exporter.getExportableCount() }

                if (count == 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "No records to export",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val file = withContext(Dispatchers.IO) { exporter.exportToCsv() }

                Toast.makeText(
                    this@MainActivity,
                    "✅ Exported $count records to Downloads/${file.name}",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "❌ Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                exportButton.isEnabled = true
                exportButton.text = "📤 EXPORT CSV"
            }
        }
    }

    // ─── UI Updates ────────────────────────────────────────────────────────

    private fun updateStats() {
        mainScope.launch {
            try {
                val dao = AppDatabase.getInstance(this@MainActivity).usageDao()
                val count = withContext(Dispatchers.IO) { dao.getCount() }
                val last = withContext(Dispatchers.IO) { dao.getLast() }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val todayMb = withContext(Dispatchers.IO) { dao.getTodaySum(dateStr) }

                recordCountText.text = "Records: %,d".format(count)
                todayUsageText.text = "Today: %.1f MB".format(todayMb)
                lastRecordText.text = if (last != null) {
                    "Last: ${last.datetimeStr}"
                } else {
                    "Last: No records yet"
                }
            } catch (e: Exception) {
                recordCountText.text = "Records: --"
                todayUsageText.text = "Today: -- MB"
                lastRecordText.text = "Last: Error reading database"
            }
        }
    }

    private fun updateStatusUI(isCollecting: Boolean) {
        if (isCollecting) {
            statusText.text = "Status: ● Collecting"
            statusText.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            statusText.text = "Status: ○ Stopped"
            statusText.setTextColor(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
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

    // ─── Permissions ───────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "⚠️ Notification permission denied — service notification may not show",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}