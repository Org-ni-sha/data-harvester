package com.capstone.dataharvester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.room.withTransaction
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.AppUsageBaseline
import com.capstone.dataharvester.data.AppUsageRecord
import com.capstone.dataharvester.data.CollectionCheckpoint
import com.capstone.dataharvester.data.CollectionLocks
import com.capstone.dataharvester.data.UsageRecord
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.DeviceIdManager
import com.capstone.dataharvester.util.DeviceInfoHelper
import com.capstone.dataharvester.util.NetworkStatsHelper
import com.capstone.dataharvester.util.NetworkStatsHelper.Transport
import com.capstone.dataharvester.util.TrafficStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Foreground Service that collects mobile data usage at two intervals:
 *
 * 1. **Primary collection** (every 30 seconds + on network switch):
 *    Uses TrafficStats for device-wide data usage + sensor readings
 *    (battery, screen, network type, signal strength, charging state)
 *
 * 2. **Per-app collection** (every 10 minutes + on network switch):
 *    Uses NetworkStatsManager for per-app (per-UID) data usage breakdown.
 *    Requires PACKAGE_USAGE_STATS permission granted via Settings.
 *    Reads cumulative usage since UTC midnight and stores the difference from the
 *    previous reading (NetworkStats buckets are ~2 h, too coarse for 10-min queries).
 *
 * How it works:
 *  1. Uses a Handler with postDelayed() to run collection ticks
 *  2. Each tick reads TrafficStats byte counters, device state
 *  3. Computes interval deltas, interval duration, quality status and temporal features
 *  4. Inserts one UsageRecord row AND the new checkpoint in one Room transaction
 *  5. Every 10 minutes, queries NetworkStatsManager for per-app usage (per transport)
 *  6. On network switch (WiFi ↔ Mobile), immediately closes the primary interval under
 *     the old network and snapshots per-app data
 *  7. Updates the persistent notification with current stats
 *
 * Reboot handling:
 *  - Checkpoints (counter baseline, last tick time, per-app baselines) live in Room
 *  - A reboot is detected from elapsedRealtime (boot time moved) or counters going backwards
 *  - BootReceiver restarts this service after device reboot and after an app update
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
        const val COLLECTION_INTERVAL_MS = 30_000L // 30 seconds (change to 120_000L for 2 minutes)
        const val APP_COLLECTION_INTERVAL_MS = 600_000L // 10 minutes for per-app collection

        // Minimum interval between network-switch-triggered collections (debounce)
        const val MIN_SWITCH_INTERVAL_MS = 5_000L // 5 seconds

        // An interval longer than this (Doze, service killed, collection stopped) is a GAP
        const val GAP_THRESHOLD_MS = 2 * COLLECTION_INTERVAL_MS + 15_000L

        // Mobile bytes above this in an interval spent on Wi-Fi are real mobile usage
        // (Wi-Fi assist, MMS, carrier apps), so the row gets a mobile label
        const val MOBILE_NOISE_BYTES = 4_096L

        // Wall-clock vs. elapsedRealtime drift above this between two ticks = reboot
        private const val BOOT_TIME_TOLERANCE_MS = 60_000L

        // SharedPreferences keys
        const val PREFS_NAME = "data_harvester_prefs"
        const val PREF_IS_COLLECTING = "is_collecting"

        // Checkpoints kept in SharedPreferences before v11; read once and moved into Room
        const val PREF_LAST_CUMULATIVE_RX = "last_cumulative_rx"
        const val PREF_LAST_CUMULATIVE_TX = "last_cumulative_tx"
        const val PREF_LAST_APP_COLLECTION_TIME = "last_app_collection_time"

        private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private fun dateOnlyFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private lateinit var handler: Handler
    private lateinit var trafficHelper: TrafficStatsHelper
    private lateinit var deviceHelper: DeviceInfoHelper
    private lateinit var networkStatsHelper: NetworkStatsHelper
    private lateinit var deviceIdManager: DeviceIdManager
    private lateinit var prefs: SharedPreferences

    // Track when service started (for runtime display in notification)
    private var startTimeMs: Long = 0L

    // Set by onTimeout so onDestroy keeps PREF_IS_COLLECTING for the next restart
    private var stoppedByTimeout = false

    // Coroutine scope for database operations (IO dispatcher)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // One tick at a time: two overlapping ticks would read the same counter
    // baseline / per-app checkpoint and count the same bytes twice
    private val collectMutex = Mutex()
    private val appCollectMutex = Mutex()

    // ─── Network Switch Detection ──────────────────────────────────────────
    // Tracks current network type so we can detect WiFi ↔ Mobile switches,
    // close the primary interval under the old network and trigger an
    // immediate per-app snapshot for clean network labels.
    @Volatile private var currentNetworkType: String? = null
    private var lastNetworkSwitchCollectionTime: Long = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // True once a non-Wi-Fi network was the default during the open interval; such an
    // interval is never labelled WIFI
    @Volatile private var cellularSeenThisInterval = false

    /** The repeating primary collection task (TrafficStats, every 30s) */
    private val collectionRunnable = object : Runnable {
        override fun run() {
            collectData()
            handler.postDelayed(this, COLLECTION_INTERVAL_MS)
        }
    }

    /** The repeating per-app collection task (NetworkStatsManager, every 10 min) */
    private val appCollectionRunnable = object : Runnable {
        override fun run() {
            collectAppData()
            handler.postDelayed(this, APP_COLLECTION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        trafficHelper = TrafficStatsHelper()
        deviceHelper = DeviceInfoHelper(this)
        networkStatsHelper = NetworkStatsHelper(this)
        deviceIdManager = DeviceIdManager(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        createNotificationChannel()
        Log.i(TAG, "Service created (device_id: ${deviceIdManager.getDeviceId()})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as a foreground service with a persistent notification.
        // specialUse (Android 14+): continuous measurement has no runtime cap and may be
        // restarted from BOOT_COMPLETED, unlike dataSync (6 h/day cap on Android 15+)
        val notification = buildNotification("Starting data collection...")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        // No collection without consent (e.g. a boot restart after consent was withdrawn).
        // startForeground above must still run first, or Android kills the app.
        if (!ConsentManager(this).isAccepted()) {
            Log.w(TAG, "Consent not given — not collecting")
            prefs.edit().putBoolean(PREF_IS_COLLECTING, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        // Record start time for runtime display
        startTimeMs = System.currentTimeMillis()

        // Mark as collecting in prefs (for UI state restoration)
        prefs.edit().putBoolean(PREF_IS_COLLECTING, true).apply()

        // Start the primary collection loop (TrafficStats, every 30s)
        handler.removeCallbacks(collectionRunnable) // Prevent duplicate callbacks
        handler.post(collectionRunnable)

        // Start the per-app collection loop (NetworkStatsManager, every 10 min)
        handler.removeCallbacks(appCollectionRunnable)
        // Delay first per-app collection by 30s to avoid startup spike
        handler.postDelayed(appCollectionRunnable, 30_000L)

        // Register network switch callback for clean labels (re-registering on a
        // repeated start would otherwise leave two callbacks firing)
        unregisterNetworkCallback()
        registerNetworkCallback()

        Log.i(TAG, "Data collection started " +
                "(primary: ${COLLECTION_INTERVAL_MS / 1000}s, " +
                "per-app: ${APP_COLLECTION_INTERVAL_MS / 1000}s, " +
                "network-switch: enabled)")
        return START_STICKY // Restart service if killed by the system
    }

    /**
     * Android 15+ calls this when a time-limited foreground service type runs out.
     * specialUse is not time-limited, so this is a safety net: stop cleanly instead of
     * being killed. PREF_IS_COLLECTING stays true; the next start records the gap.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (type $fgsType) — stopping cleanly")
        stoppedByTimeout = true
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(collectionRunnable)
        handler.removeCallbacks(appCollectionRunnable)
        unregisterNetworkCallback()
        serviceScope.cancel()
        if (!stoppedByTimeout) {
            prefs.edit().putBoolean(PREF_IS_COLLECTING, false).apply()
        }
        Log.i(TAG, "Data collection stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Network Switch Detection ──────────────────────────────────────────

    /**
     * Register a ConnectivityManager callback that detects WiFi ↔ Mobile switches.
     * On a switch it immediately closes the primary interval under the OLD network
     * (so mobile bytes before joining Wi-Fi are never labelled WIFI) and triggers a
     * per-app snapshot.
     *
     * The callback debounces rapid switches (min 5s between triggered collections).
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Initialize current network type BEFORE registering callback
        // so the initial onAvailable doesn't trigger a false switch
        currentNetworkType = detectCurrentNetworkType(cm)
        cellularSeenThisInterval = isNonWifiData(currentNetworkType)
        Log.i(TAG, "Initial network type: $currentNetworkType")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val newType = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> "OTHER"
                }
                onDefaultNetworkChanged(newType)
            }

            override fun onLost(network: Network) {
                onDefaultNetworkChanged("NONE")
            }
        }

        // registerDefaultNetworkCallback is API 24+; minSdk is 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
        networkCallback = callback
    }

    private fun onDefaultNetworkChanged(newType: String) {
        val previousType = currentNetworkType
        currentNetworkType = newType
        if (previousType == null || previousType == newType) return

        // Debounce: don't trigger if we just triggered recently
        val now = System.currentTimeMillis()
        if (now - lastNetworkSwitchCollectionTime > MIN_SWITCH_INTERVAL_MS) {
            lastNetworkSwitchCollectionTime = now
            Log.i(TAG, "Network switch: $previousType → $newType — closing interval, per-app snapshot")
            // The open interval ran on previousType; close it under that label
            collectData(switchedFrom = previousType)
            if (previousType != "NONE") collectAppData()
        } else if (isNonWifiData(newType)) {
            // Not closed by a tick: the open interval now mixes networks
            cellularSeenThisInterval = true
        }
    }

    /** A default network other than Wi-Fi that can carry traffic (no network moves no counters). */
    private fun isNonWifiData(type: String?) = type != null && type != "WIFI" && type != "NONE"

    /**
     * Unregister the network switch callback.
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }

    /**
     * Detect the current network type without registering a callback.
     * Used to initialize [currentNetworkType] before callback registration.
     */
    private fun detectCurrentNetworkType(cm: ConnectivityManager): String {
        val network = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(network) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            else -> "OTHER"
        }
    }

    // ─── Core Collection Logic ─────────────────────────────────────────────

    /**
     * The primary checkpoint, moving the pre-v11 SharedPreferences baseline into Room
     * the first time (afterwards the prefs keys are gone and Room is the only copy).
     */
    private suspend fun loadCheckpoint(db: AppDatabase): CollectionCheckpoint {
        db.checkpointDao().get()?.let { return it }

        val handedOver = CollectionCheckpoint(
            mobileRx = prefs.getLong(PREF_LAST_CUMULATIVE_RX, -1L),
            mobileTx = prefs.getLong(PREF_LAST_CUMULATIVE_TX, -1L)
        )
        db.checkpointDao().upsert(handedOver)
        prefs.edit()
            .remove(PREF_LAST_CUMULATIVE_RX)
            .remove(PREF_LAST_CUMULATIVE_TX)
            .remove(PREF_LAST_APP_COLLECTION_TIME) // per-app baselines restart in app_usage_baseline
            .apply()
        return handedOver
    }

    /**
     * Single collection tick. Called every 30 seconds by the Handler, and on a network
     * switch with [switchedFrom] = the network the closing interval ran on.
     * Reads all data sources, computes features, and inserts a record.
     */
    private fun collectData(switchedFrom: String? = null) {
        serviceScope.launch {
            collectMutex.withLock {
                CollectionLocks.db.withLock {
                    try {
                        collectDataLocked(switchedFrom)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission error — stopping service", e)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Collection tick failed — bytes carry over to the next tick", e)
                    }
                }
            }
        }
    }

    private suspend fun collectDataLocked(switchedFrom: String?) {
        val db = AppDatabase.getInstance(this@DataCollectionService)
        val dao = db.usageDao()
        val cp = loadCheckpoint(db)
        trafficHelper.initializeFromSavedState(cp.mobileRx, cp.mobileTx)

        // ── 1. Interval timing and reboot detection ──
        val now = Calendar.getInstance()
        val timestamp = now.timeInMillis
        val nowElapsed = SystemClock.elapsedRealtime()
        val hasPreviousTick = cp.lastTickElapsed >= 0 && cp.lastTickWall > 0
        // Boot time (wall clock minus uptime) moves only on a reboot (or a large clock change)
        val rebooted = hasPreviousTick && (
            nowElapsed < cp.lastTickElapsed ||
            abs((timestamp - nowElapsed) - (cp.lastTickWall - cp.lastTickElapsed)) > BOOT_TIME_TOLERANCE_MS
        )
        val elapsedMs = if (hasPreviousTick && !rebooted) nowElapsed - cp.lastTickElapsed else -1L

        // ── 2. Get usage delta from TrafficStats ──
        val delta = trafficHelper.getUsageDelta(forceReset = rebooted)
        val status = when {
            delta.status != TrafficStatsHelper.Status.OK -> delta.status.name
            elapsedMs < 0 || elapsedMs > GAP_THRESHOLD_MS -> "GAP" // real bytes, long or unknown span
            else -> "OK"
        }

        // ── 3. Read device state ──
        val batteryLevel = deviceHelper.getBatteryLevel()
        val screenOn = deviceHelper.isScreenOn()
        val activeNetwork = deviceHelper.getNetworkType()
        val signalStrength = deviceHelper.getSignalStrength()
        val charging = deviceHelper.isCharging()
        val deviceModel = deviceHelper.getDeviceModel()
        val deviceId = deviceIdManager.getDeviceId()

        // ── 4. Label the interval (cleaning drops WIFI rows, so WIFI must hold no mobile bytes) ──
        val intervalNetwork = when (switchedFrom) {
            null -> activeNetwork
            "WIFI" -> "WIFI"
            "MOBILE" -> deviceHelper.getCellularRadioType()
            "NONE" -> "NONE"
            else -> activeNetwork
        }
        val networkType = if (
            intervalNetwork == "WIFI" &&
            (cellularSeenThisInterval || delta.bytesTotal > MOBILE_NOISE_BYTES)
        ) {
            deviceHelper.getCellularRadioType()
        } else {
            intervalNetwork
        }

        // ── 5. Compute temporal features ──
        val datetimeStr = isoFormat().format(now.time)
        val dateStr = dateOnlyFormat().format(now.time)
        val utcOffsetMinutes = java.util.TimeZone.getDefault().getOffset(timestamp) / 60000
        val networkOperator = deviceHelper.getNetworkProvider()
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

        val mbUsedThisTick = delta.bytesTotal / 1048576.0

        // ── 6. Insert the record and the new checkpoint in ONE transaction ──
        // A crash leaves either both or neither, so a restart never recounts saved bytes
        val cumulativeMbToday = db.withTransaction {
            val cumulative = dao.getTodaySum(dateStr) + mbUsedThisTick
            dao.insert(
                UsageRecord(
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
                    cumulativeMbToday = cumulative,
                    networkType = networkType,
                    screenOn = if (screenOn) 1 else 0,
                    batteryLevel = batteryLevel,
                    deviceId = deviceId,
                    signalStrength = signalStrength,
                    isCharging = if (charging) 1 else 0,
                    deviceModel = deviceModel,
                    networkOperator = networkOperator,
                    utcOffsetMinutes = utcOffsetMinutes,
                    intervalStart = if (hasPreviousTick && !rebooted) cp.lastTickWall else timestamp,
                    intervalEnd = timestamp,
                    elapsedMs = elapsedMs,
                    measurementStatus = status,
                    activeNetworkAtSample = activeNetwork,
                    collectionVersion = BuildConfig.VERSION_NAME
                )
            )
            db.checkpointDao().upsert(
                CollectionCheckpoint(
                    // UNSUPPORTED readings are -1: keep the previous baseline
                    mobileRx = if (delta.currentRx >= 0) delta.currentRx else cp.mobileRx,
                    mobileTx = if (delta.currentTx >= 0) delta.currentTx else cp.mobileTx,
                    lastTickElapsed = nowElapsed,
                    lastTickWall = timestamp
                )
            )
            cumulative
        }
        trafficHelper.commit(delta)
        // The next interval starts on the current network
        cellularSeenThisInterval = isNonWifiData(currentNetworkType)

        // ── 7. Update notification with current stats ──
        val count = dao.getCount()
        val appCount = db.appUsageDao().getCount()
        val runtime = formatElapsedTime(System.currentTimeMillis() - startTimeMs)
        updateNotification(
            "$count records | Apps: $appCount | Today: ${"%.1f".format(cumulativeMbToday)} MB | $runtime"
        )

        // Log for debugging
        Log.i(
            TAG,
            "Record #$count [$status${switchedFrom?.let { ", switch from $it" } ?: ""}]: " +
            "+${"%.3f".format(mbUsedThisTick)} MB (${delta.bytesTotal} bytes, ${elapsedMs}ms) | " +
            "$networkType (active $activeNetwork) | Signal: ${signalStrength}dBm | " +
            "Charging: $charging | Today: ${"%.1f".format(cumulativeMbToday)} MB"
        )
    }

    // ─── Per-App Collection Logic ──────────────────────────────────────────

    /**
     * Per-app collection tick. Called every 10 minutes by the Handler,
     * and also triggered immediately on network switches (WiFi ↔ Mobile).
     *
     * Mobile and Wi-Fi are handled independently: each has its own baselines, and a
     * failed query keeps only that transport's baseline for a retry.
     */
    private fun collectAppData() {
        serviceScope.launch {
            appCollectMutex.withLock {
                CollectionLocks.db.withLock {
                    if (!networkStatsHelper.hasUsageAccessPermission()) {
                        Log.w(TAG, "Per-app collection skipped — Usage Access not granted")
                        return@withLock
                    }
                    val now = System.currentTimeMillis()
                    for (transport in Transport.values()) {
                        try {
                            collectTransport(transport, now)
                        } catch (e: Exception) {
                            Log.e(TAG, "Per-app ${transport.name} tick failed — baseline kept for retry", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * One transport's per-app tick: read cumulative usage since UTC midnight, subtract
     * the stored baseline, and save rows + new baseline in one transaction.
     */
    private suspend fun collectTransport(transport: Transport, now: Long) {
        val db = AppDatabase.getInstance(this@DataCollectionService)
        val baselineDao = db.appUsageBaselineDao()
        val previous = baselineDao.getAll(transport.name)
        val anchor = NetworkStatsHelper.utcMidnight(now)
        val previousAnchor = previous.firstOrNull()?.anchor
        val previousTakenAt = previous.firstOrNull()?.takenAt ?: now
        val previousByUid = previous.filter { it.uid != AppUsageBaseline.MARKER_UID }.associateBy { it.uid }
        val records = mutableListOf<AppUsageRecord>()

        // New UTC day: close everything since the last reading up to this midnight. That
        // range lies wholly in the past and on bucket boundaries, so it is exact
        val todayBaseline: Map<Int, AppUsageBaseline>
        val todaySince: Long
        if (previousAnchor != null && previousAnchor != anchor) {
            val closing = networkStatsHelper.queryCumulative(transport, previousAnchor, anchor)
                ?: run { Log.w(TAG, "${transport.name} day-close query failed — retrying next tick"); return }
            records += diffToRecords(transport, closing, previousByUid, previousTakenAt, anchor)
            // Today's cumulative starts from zero at midnight
            todayBaseline = emptyMap()
            todaySince = anchor
        } else {
            todayBaseline = previousByUid
            todaySince = previousTakenAt
        }

        // Future end time: the bucket in progress counts in full instead of a fraction
        val current = networkStatsHelper.queryCumulative(transport, anchor, now + NetworkStatsHelper.DAY_MS)
            ?: run { Log.w(TAG, "${transport.name} query failed — baseline kept for retry"); return }

        // First reading ever (or after Reset): store the baseline only
        if (previous.isNotEmpty()) {
            records += diffToRecords(transport, current, todayBaseline, todaySince, now)
        }

        val newBaseline = current.map { (uid, sum) ->
            AppUsageBaseline(uid, transport.name, anchor, sum[0], sum[1], now)
        } + AppUsageBaseline(AppUsageBaseline.MARKER_UID, transport.name, anchor, 0L, 0L, now)

        db.withTransaction {
            if (records.isNotEmpty()) db.appUsageDao().insertAll(records)
            baselineDao.replaceAll(transport.name, newBaseline)
        }

        Log.i(TAG, "Per-app ${transport.name}: inserted ${records.size} records " +
                "(${records.count { it.isSystemApp == 1 }} system, " +
                "${records.count { it.isSystemApp == 0 }} user)" +
                if (previous.isEmpty()) " — first reading, baseline stored" else "")
    }

    /**
     * Turn cumulative readings into rows for the span [since, until].
     * A UID missing from [baseline] had no usage at the baseline reading, so its whole
     * cumulative is new. A reading below its baseline (stats wiped, UID reused) is
     * stored as a zero-byte COUNTER_RESET row.
     */
    private fun diffToRecords(
        transport: Transport,
        current: Map<Int, LongArray>,
        baseline: Map<Int, AppUsageBaseline>,
        since: Long,
        until: Long
    ): List<AppUsageRecord> {
        val iso = isoFormat()
        val sinceStr = iso.format(Date(since))
        val untilStr = iso.format(Date(until))
        val deviceId = deviceIdManager.getDeviceId()
        val utcOffsetMinutes = java.util.TimeZone.getDefault().getOffset(until) / 60000

        return current.mapNotNull { (uid, sum) ->
            val base = baseline[uid]
            val dRx = sum[0] - (base?.rx ?: 0L)
            val dTx = sum[1] - (base?.tx ?: 0L)
            val reset = dRx < 0 || dTx < 0
            if (!reset && dRx + dTx == 0L) return@mapNotNull null

            val app = networkStatsHelper.resolveApp(uid)
            AppUsageRecord(
                timestamp = until,
                datetimeStr = untilStr,
                deviceId = deviceId,
                packageName = app.packageName,
                appName = app.appName,
                uid = uid,
                bytesRx = if (reset) 0L else dRx,
                bytesTx = if (reset) 0L else dTx,
                bytesTotal = if (reset) 0L else dRx + dTx,
                networkType = transport.name,
                queryStart = sinceStr,
                startTime = sinceStr,
                endTime = untilStr,
                isSystemApp = if (app.isSystemApp) 1 else 0,
                utcOffsetMinutes = utcOffsetMinutes,
                measurementStatus = if (reset) "COUNTER_RESET" else "OK",
                collectionVersion = BuildConfig.VERSION_NAME
            )
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

    /**
     * Format elapsed milliseconds into a human-readable runtime string.
     * Examples: "0m 30s", "5m 0s", "1h 23m", "2h 5m"
     */
    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m ${seconds}s"
        }
    }
}
