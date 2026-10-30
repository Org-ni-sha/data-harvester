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
import com.capstone.dataharvester.data.DatabaseGuard
import com.capstone.dataharvester.data.UsageRecord
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.DeviceIdManager
import com.capstone.dataharvester.util.DeviceInfoHelper
import com.capstone.dataharvester.util.MeasurementRules
import com.capstone.dataharvester.util.NetworkStatsHelper
import com.capstone.dataharvester.util.PrimaryCollector
import com.capstone.dataharvester.util.NetworkStatsHelper.Transport
import com.capstone.dataharvester.util.StudyManager
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
        private const val UPDATE_NEEDED_NOTIFICATION_ID = 3
        const val COLLECTION_INTERVAL_MS = MeasurementRules.COLLECTION_INTERVAL_MS // 30 seconds
        const val APP_COLLECTION_INTERVAL_MS = MeasurementRules.APP_COLLECTION_INTERVAL_MS // 10 minutes

        // Minimum interval between network-switch-triggered collections (debounce)
        const val MIN_SWITCH_INTERVAL_MS = 5_000L // 5 seconds

        // SharedPreferences keys
        /** True while this process runs the collection loop; read by CollectionWatchdogWorker */
        @Volatile
        var isRunning = false
            private set

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

    // Set when the service stops without the user asking (foreground-service timeout, or
    // a database that needs a fixed update), so onDestroy keeps PREF_IS_COLLECTING and the
    // next restart resumes collection
    private var keepCollectingFlag = false

    // Coroutine scope for database operations (IO dispatcher)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // One per-app tick at a time: two overlapping ticks would read the same per-app
    // checkpoint and count the same bytes twice (primary ticks lock in PrimaryCollector)
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

        // Rows are only collected for the official study: an install that has not passed
        // "Start Official Collection" (e.g. a boot restart right after updating) waits for it
        if (!StudyManager(this).isEnrolled()) {
            Log.w(TAG, "Not enrolled in the official study — not collecting")
            prefs.edit().putBoolean(PREF_IS_COLLECTING, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        // Record start time for runtime display
        startTimeMs = System.currentTimeMillis()

        // Mark as collecting in prefs (for UI state restoration)
        prefs.edit().putBoolean(PREF_IS_COLLECTING, true).apply()
        isRunning = true

        // Open (and migrate) the database first: if this version cannot open it, stop
        // without touching the records and wait for a fixed update
        serviceScope.launch {
            if (DatabaseGuard.ensureOpen(this@DataCollectionService)) {
                handler.post { startLoops() }
            } else {
                Log.e(TAG, "Database unavailable (${DatabaseGuard.problem}) — collection paused until an update")
                notifyUpdateNeeded()
                keepCollectingFlag = true
                stopSelf()
            }
        }
        return START_STICKY // Restart service if killed by the system
    }

    private fun startLoops() {
        if (!isRunning) return // stopped while the database was opening

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
    }

    /**
     * Android 15+ calls this when a time-limited foreground service type runs out.
     * specialUse is not time-limited, so this is a safety net: stop cleanly instead of
     * being killed. PREF_IS_COLLECTING stays true; the next start records the gap.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (type $fgsType) — stopping cleanly")
        keepCollectingFlag = true
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(collectionRunnable)
        handler.removeCallbacks(appCollectionRunnable)
        unregisterNetworkCallback()
        serviceScope.cancel()
        if (!keepCollectingFlag) {
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
     * Checkpoint to start from when Room has none: the pre-v11 SharedPreferences
     * baseline (removed once the first tick has committed its Room checkpoint).
     */
    private fun prefsCheckpoint() = CollectionCheckpoint(
        mobileRx = prefs.getLong(PREF_LAST_CUMULATIVE_RX, -1L),
        mobileTx = prefs.getLong(PREF_LAST_CUMULATIVE_TX, -1L)
    )

    /**
     * Single collection tick. Called every 30 seconds by the Handler, and on a network
     * switch with [switchedFrom] = the network the closing interval ran on.
     * Reads all data sources, computes features, and inserts a record.
     */
    private fun collectData(switchedFrom: String? = null) {
        serviceScope.launch {
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

    private suspend fun collectDataLocked(switchedFrom: String?) {
        val db = AppDatabase.getInstance(this@DataCollectionService)

        // ── 1. Read device state (before the lock; none of it depends on the checkpoint) ──
        val batteryLevel = deviceHelper.getBatteryLevel()
        val screenOn = deviceHelper.isScreenOn()
        val activeNetwork = deviceHelper.getNetworkType()
        val signalStrength = deviceHelper.getSignalStrength()
        val charging = deviceHelper.isCharging()
        val deviceModel = deviceHelper.getDeviceModel()
        val deviceId = deviceIdManager.getDeviceId()
        val networkOperator = deviceHelper.getNetworkProvider()
        val studyId = StudyManager(this@DataCollectionService).studyId()
        val cellularSeen = cellularSeenThisInterval

        // The network the closing interval ran on (a switch closes it under the old one)
        val intervalNetwork = when (switchedFrom) {
            null -> activeNetwork
            "WIFI" -> "WIFI"
            "MOBILE" -> deviceHelper.getCellularRadioType()
            "NONE" -> "NONE"
            else -> activeNetwork
        }

        // ── 2. Checkpoint → counters → delta → row + checkpoint, under the collection lock ──
        val committed = PrimaryCollector(db) { prefsCheckpoint() }.tick(
            now = {
                val wall = System.currentTimeMillis()
                PrimaryCollector.Now(SystemClock.elapsedRealtime(), wall, dateOnlyFormat().format(Date(wall)))
            },
            readCounters = { trafficHelper.readMobileCounters() },
            buildRecord = { tick, now, cumulativeMbToday ->
                val cal = Calendar.getInstance().apply { timeInMillis = now.wall }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                // Calendar day (Sun=1..Sat=7) → ISO day (Mon=1..Sun=7)
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
                UsageRecord(
                    timestamp = now.wall,
                    datetimeStr = isoFormat().format(Date(now.wall)),
                    hour = hour,
                    minute = cal.get(Calendar.MINUTE),
                    dayOfWeek = dayOfWeek,
                    isWeekend = if (dayOfWeek >= 6) 1 else 0,
                    timePeriod = when (hour) {
                        in 5..11 -> "Morning"
                        in 12..16 -> "Afternoon"
                        in 17..20 -> "Evening"
                        else -> "Night"
                    },
                    bytesRx = tick.bytesRx,
                    bytesTx = tick.bytesTx,
                    bytesTotal = tick.bytesTotal,
                    mbUsed = tick.bytesTotal / 1048576.0,
                    cumulativeMbToday = cumulativeMbToday,
                    // Cleaning drops WIFI rows, so WIFI must hold no mobile bytes
                    networkType = MeasurementRules.primaryNetworkLabel(
                        intervalNetwork, cellularSeen, tick.bytesTotal
                    ) { deviceHelper.getCellularRadioType() },
                    screenOn = if (screenOn) 1 else 0,
                    batteryLevel = batteryLevel,
                    deviceId = deviceId,
                    signalStrength = signalStrength,
                    isCharging = if (charging) 1 else 0,
                    deviceModel = deviceModel,
                    networkOperator = networkOperator,
                    utcOffsetMinutes = java.util.TimeZone.getDefault().getOffset(now.wall) / 60000,
                    intervalStart = tick.intervalStart,
                    intervalEnd = now.wall,
                    elapsedMs = tick.elapsedMs,
                    measurementStatus = tick.status,
                    activeNetworkAtSample = activeNetwork,
                    collectionVersion = BuildConfig.VERSION_NAME,
                    schemaVersion = MeasurementRules.SCHEMA_VERSION,
                    studyId = studyId
                )
            }
        )

        // The Room checkpoint now exists: the pre-v11 prefs copy is no longer needed
        if (prefs.contains(PREF_LAST_CUMULATIVE_RX)) {
            prefs.edit()
                .remove(PREF_LAST_CUMULATIVE_RX)
                .remove(PREF_LAST_CUMULATIVE_TX)
                .remove(PREF_LAST_APP_COLLECTION_TIME) // per-app baselines restart in app_usage_baseline
                .apply()
        }
        // The next interval starts on the current network
        cellularSeenThisInterval = isNonWifiData(currentNetworkType)

        // ── 3. Update notification with current stats ──
        val record = committed.record
        val count = db.usageDao().getCount()
        val appCount = db.appUsageDao().getCount()
        val runtime = formatElapsedTime(System.currentTimeMillis() - startTimeMs)
        updateNotification(
            "$count records | Apps: $appCount | Today: ${"%.1f".format(record.cumulativeMbToday)} MB | $runtime"
        )

        Log.i(
            TAG,
            "Record #$count [${record.measurementStatus}${switchedFrom?.let { ", switch from $it" } ?: ""}]: " +
            "+${"%.3f".format(record.mbUsed)} MB (${record.bytesTotal} bytes, ${record.elapsedMs}ms) | " +
            "${record.networkType} (active $activeNetwork) | Signal: ${signalStrength}dBm | " +
            "Charging: $charging | Today: ${"%.1f".format(record.cumulativeMbToday)} MB"
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
     * stored as a zero-byte COUNTER_RESET row; long or backwards spans are flagged
     * by [MeasurementRules.appStatus].
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
        val studyId = StudyManager(this).studyId()
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
                // A span far over 10 minutes (collection stopped, Doze) keeps its real total
                // but is flagged GAP, so it is not mistaken for an ordinary sample
                measurementStatus = MeasurementRules.appStatus(since, until, counterReset = reset),
                collectionVersion = BuildConfig.VERSION_NAME,
                schemaVersion = MeasurementRules.SCHEMA_VERSION,
                studyId = studyId
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

    /** Separate from the collection notification, which disappears when the service stops. */
    private fun notifyUpdateNeeded() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Data collection paused")
            .setContentText("Your records are safe. Open Data Harvester to install the fixed update.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(UPDATE_NEEDED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification (POST_NOTIFICATIONS denied)", e)
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
