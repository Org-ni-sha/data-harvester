package com.capstone.dataharvester.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.AppUsageRecord
import com.capstone.dataharvester.data.UsageRecord
import com.capstone.dataharvester.util.DeviceIdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CloudSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SyncManager"

        /** Upload failed; the caller should retry later. */
        const val RESULT_FAILED = -1

        /** Skipped because the active connection is metered (mobile data). */
        const val RESULT_METERED = -2

        /** Rows per HTTP request. Keeps each payload to a few hundred KB. */
        private const val BATCH_SIZE = 500

        /**
         * Ids per markAsSynced() call. Room binds one variable per id and SQLite
         * caps those at 999 on older Android builds, so stay well under it
         * independently of [BATCH_SIZE].
         */
        private const val MAX_IDS_PER_UPDATE = 400

        /**
         * Upper bound on batches per run. Guards against spinning, and keeps a run
         * inside WorkManager's ~10 minute execution window at roughly 400 KB per
         * batch. A large backlog drains across successive runs; progress is kept
         * because rows are marked synced batch by batch.
         */
        private const val MAX_BATCHES_PER_RUN = 60
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val db = AppDatabase.getInstance(context)

    // Your SQLite Cloud configurations
    private val gatewayUrl = "https://caqj7nc1dk.g4.gateway.sqlite.cloud/v2/weblite/sql"
    private val apiKey = "pyJnSCHaiLuFXxvad4y65KtyEX99ni1H0Ut6AcMDz10"
    private val dbName = "DATAra_harvester.sqlite"

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Uploads local unsynced records to the cloud database in batches, continuing
     * until the backlog is drained, a batch fails, or [MAX_BATCHES_PER_RUN] is hit.
     *
     * Rows are marked synced only after the server confirms the insert, so a failed
     * batch is simply retried on the next run.
     *
     * @return number of records uploaded, or [RESULT_FAILED] / [RESULT_METERED].
     */
    suspend fun syncPendingData(): Int {
        if (!isUnmeteredConnection()) {
            Log.i(TAG, "Sync skipped — active connection is metered")
            return RESULT_METERED
        }

        val usageDao = db.usageDao()
        val appUsageDao = db.appUsageDao()

        val pending = usageDao.getUnsyncedCount() + appUsageDao.getUnsyncedCount()
        if (pending == 0) return 0
        Log.i(TAG, "Starting sync — $pending records pending")

        var totalSynced = 0

        repeat(MAX_BATCHES_PER_RUN) {
            val usage = usageDao.getUnsyncedRecords(BATCH_SIZE)
            val appUsage = appUsageDao.getUnsyncedRecords(BATCH_SIZE)

            if (usage.isEmpty() && appUsage.isEmpty()) {
                Log.i(TAG, "Sync complete — $totalSynced records uploaded")
                return totalSynced
            }

            // CoroutineWorker runs doWork() on Dispatchers.Default; the HTTP call blocks.
            val uploaded = withContext(Dispatchers.IO) { uploadBatch(usage, appUsage) }
            if (!uploaded) {
                // Rows in this batch stay unsynced; report failure so the worker retries.
                Log.e(TAG, "Batch failed after $totalSynced records — will retry later")
                return RESULT_FAILED
            }

            usage.map { it.id }.chunked(MAX_IDS_PER_UPDATE).forEach { usageDao.markAsSynced(it) }
            appUsage.map { it.id }.chunked(MAX_IDS_PER_UPDATE).forEach { appUsageDao.markAsSynced(it) }

            totalSynced += usage.size + appUsage.size
            Log.i(TAG, "Batch uploaded — $totalSynced/$pending records")
        }

        Log.i(TAG, "Batch ceiling reached — $totalSynced uploaded, remainder next run")
        return totalSynced
    }

    /**
     * True only on an unmetered connection (typically Wi-Fi).
     *
     * TrafficStats measures device-wide usage, so uploading over mobile data would
     * both bill the respondent's prepaid load and contaminate the data being collected.
     */
    private fun isUnmeteredConnection(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read network state", e)
            false
        }
    }

    /** Builds one SQL script for the batch and POSTs it. Returns true on success. */
    private fun uploadBatch(usage: List<UsageRecord>, appUsage: List<AppUsageRecord>): Boolean {
        val sql = StringBuilder()

        usage.forEach { r ->
            sql.append("INSERT INTO usage_records (timestamp, datetime_str, hour, minute, day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, network_type, screen_on, battery_level, device_id, signal_strength, is_charging, device_model) ")
            sql.append("VALUES (${r.timestamp}, ${q(r.datetimeStr)}, ${r.hour}, ${r.minute}, ${r.dayOfWeek}, ${r.isWeekend}, ${q(r.timePeriod)}, ${r.bytesRx}, ${r.bytesTx}, ${r.bytesTotal}, ${r.mbUsed}, ${r.cumulativeMbToday}, ${q(r.networkType)}, ${r.screenOn}, ${r.batteryLevel}, ${q(r.deviceId)}, ${r.signalStrength}, ${r.isCharging}, ${q(r.deviceModel)});\n")
        }

        appUsage.forEach { r ->
            sql.append("INSERT INTO app_usage_records (timestamp, datetime_str, device_id, package_name, app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, is_system_app) ")
            sql.append("VALUES (${r.timestamp}, ${q(r.datetimeStr)}, ${q(r.deviceId)}, ${q(r.packageName)}, ${q(r.appName)}, ${r.uid}, ${r.bytesRx}, ${r.bytesTx}, ${r.bytesTotal}, ${q(r.networkType)}, ${q(r.queryStart)}, ${r.isSystemApp});\n")
        }

        val payloadSizeBytes = sql.toString().toByteArray(Charsets.UTF_8).size
        val uploadTime = System.currentTimeMillis()
        val deviceId = DeviceIdManager(context).getDeviceId()

        sql.append("INSERT INTO upload_history (device_id, uploaded_timestamp, datetime_str, datetime_stamp, records_uploaded, payload_size_bytes, payload_size_kb) ")
        sql.append("VALUES (${q(deviceId)}, $uploadTime, ${q(isoFormat.format(Date(uploadTime)))}, ${q(stampFormat.format(Date(uploadTime)))}, ${usage.size + appUsage.size}, $payloadSizeBytes, ${payloadSizeBytes / 1024});\n")

        val body = JSONObject()
            .put("database", dbName)
            .put("sql", sql.toString())
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(gatewayUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Upload rejected: ${response.code} - ${response.body?.string()}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            false
        }
    }

    /**
     * Quote a value as a SQL string literal, escaping embedded single quotes.
     *
     * Every string field goes through this: one unescaped apostrophe (an app name
     * or an OEM device_model) would fail its batch on every retry, permanently
     * blocking the rest of the backlog behind it.
     */
    private fun q(value: String): String = "'" + value.replace("'", "''") + "'"
}
