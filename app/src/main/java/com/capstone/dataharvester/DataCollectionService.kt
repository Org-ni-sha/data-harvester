package com.capstone.dataharvester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.UsageRecord
import com.capstone.dataharvester.util.DeviceInfoHelper
import com.capstone.dataharvester.util.TrafficStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Foreground Service that collects mobile data usage every 2 minutes.
 *
 * How it works:
 *  1. Uses a Handler with postDelayed() to run a collection tick every 2 minutes
 *  2. Each tick reads TrafficStats byte counters, device state (battery, screen, network)
 *  3. Computes interval deltas and temporal features (hour, day_of_week, etc.)
 *  4. Inserts one UsageRecord row into Room database
 *  5. Updates the persistent notification with current stats
 *
 * Reboot handling:
 *  - Last known cumulative TrafficStats values are saved to SharedPreferences
 *  - On restart, if current TrafficStats < saved values → reboot detected → delta = 0
 *  - BootReceiver restarts this service after device reboot
 *
 * Error handling:
 *  - Each tick is wrapped in try-catch — failures skip the tick, don't crash the service
 *  - SecurityException (permission revoked) stops the service
 *  - Returns START_STICKY so Android restarts the service if killed
 */
class DataCollectionService : Service() {

    companion object {
        const val TAG = "DataCollectionService"
        const val CHANNEL_ID = "data_harvester_channel"
        const val NOTIFICATION_ID = 1
        const val COLLECTION_INTERVAL_MS = 30_000L // 30 seconds sa ni sya karun 120_000L 2 minutes

        // SharedPreferences keys for reboot recovery
        const val PREFS_NAME = "data_harvester_prefs"
        const val PREF_LAST_CUMULATIVE_RX = "last_cumulative_rx"
        const val PREF_LAST_CUMULATIVE_TX = "last_cumulative_tx"
        const val PREF_IS_COLLECTING = "is_collecting"
    }

    private lateinit var handler: Handler
    private lateinit var trafficHelper: TrafficStatsHelper
    private lateinit var deviceHelper: DeviceInfoHelper
    private lateinit var prefs: SharedPreferences

    // Coroutine scope for database operations (IO dispatcher)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Date formatters (reused to avoid repeated allocation)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** The repeating collection task */
    private val collectionRunnable = object : Runnable {
        override fun run() {
            collectData()
            handler.postDelayed(this, COLLECTION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        trafficHelper = TrafficStatsHelper()
        deviceHelper = DeviceInfoHelper(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Restore last known cumulative values for reboot detection
        val lastRx = prefs.getLong(PREF_LAST_CUMULATIVE_RX, -1L)
        val lastTx = prefs.getLong(PREF_LAST_CUMULATIVE_TX, -1L)
        trafficHelper.initializeFromSavedState(lastRx, lastTx)

        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as a foreground service with a persistent notification
        val notification = buildNotification("Starting data collection...")
        startForeground(NOTIFICATION_ID, notification)

        // Mark as collecting in prefs (for UI state restoration)
        prefs.edit().putBoolean(PREF_IS_COLLECTING, true).apply()

        // Start the 2-minute collection loop
        handler.removeCallbacks(collectionRunnable) // Prevent duplicate callbacks
        handler.post(collectionRunnable)

        Log.i(TAG, "Data collection started (interval: ${COLLECTION_INTERVAL_MS / 1000}s)")
        return START_STICKY // Restart service if killed by the system
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(collectionRunnable)
        serviceScope.cancel()
        prefs.edit().putBoolean(PREF_IS_COLLECTING, false).apply()
        Log.i(TAG, "Data collection stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Core Collection Logic ─────────────────────────────────────────────

    /**
     * Single collection tick. Called every 2 minutes by the Handler.
     * Reads all data sources, computes features, and inserts a record.
     */
    private fun collectData() {
        serviceScope.launch {
            try {
                val dao = AppDatabase.getInstance(this@DataCollectionService).usageDao()

                // ── 1. Get usage delta from TrafficStats ──
                val delta = trafficHelper.getUsageDelta()

                // ── 2. Save cumulative values for reboot recovery ──
                prefs.edit()
                    .putLong(PREF_LAST_CUMULATIVE_RX, trafficHelper.getCurrentCumulativeRx())
                    .putLong(PREF_LAST_CUMULATIVE_TX, trafficHelper.getCurrentCumulativeTx())
                    .apply()

                // ── 3. Read device state ──
                val batteryLevel = deviceHelper.getBatteryLevel()
                val screenOn = deviceHelper.isScreenOn()
                val networkType = deviceHelper.getNetworkType()

                // ── 4. Compute temporal features ──
                val now = Calendar.getInstance()
                val timestamp = now.timeInMillis
                val datetimeStr = isoFormat.format(now.time)
                val dateStr = dateOnlyFormat.format(now.time)

                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)

                // Convert Calendar day (Sun=1..Sat=7) to ISO day (Mon=1..Sun=7)
                val dayOfWeek = run {
                    val calDay = now.get(Calendar.DAY_OF_WEEK)
                    if (calDay == Calendar.SUNDAY) 7 else calDay - 1
                }
                val isWeekend = if (dayOfWeek >= 6) 1 else 0

                val timePeriod = when (hour) {
                    in 5..11 -> "Morning"
                    in 12..16 -> "Afternoon"
                    in 17..20 -> "Evening"
                    else -> "Night"
                }

                // ── 5. Compute cumulative MB today from Room ──
                val mbUsedThisTick = delta.bytesTotal / 1048576.0
                val cumulativeMbToday = dao.getTodaySum(dateStr) + mbUsedThisTick

                // ── 6. Build and insert the record ──
                val record = UsageRecord(
                    timestamp = timestamp,
                    datetimeStr = datetimeStr,
                    hour = hour,
                    minute = minute,
                    dayOfWeek = dayOfWeek,
                    isWeekend = isWeekend,
                    timePeriod = timePeriod,
                    bytesRx = delta.bytesRx,
                    bytesTx = delta.bytesTx,
                    bytesTotal = delta.bytesTotal,
                    mbUsed = mbUsedThisTick,
                    cumulativeMbToday = cumulativeMbToday,
                    networkType = networkType,
                    screenOn = if (screenOn) 1 else 0,
                    batteryLevel = batteryLevel
                )

                dao.insert(record)

                // ── 7. Update notification with current stats ──
                val count = dao.getCount()
                updateNotification(
                    "📊 $count records | Today: ${"%.1f".format(cumulativeMbToday)} MB"
                )

                // Log for debugging
                if (delta.wasReboot) {
                    Log.i(TAG, "Record #$count inserted (reboot detected, delta=0)")
                } else {
                    Log.i(
                        TAG,
                        "Record #$count: +${"%.3f".format(mbUsedThisTick)} MB " +
                        "(${delta.bytesTotal} bytes) | Today: ${"%.1f".format(cumulativeMbToday)} MB"
                    )
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error — stopping service", e)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Collection tick failed — skipping this interval", e)
            }
        }
    }

    // ─── Notification Management ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Data Collection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when data collection is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Data Harvester")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Data Harvester")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
