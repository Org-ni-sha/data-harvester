package com.capstone.dataharvester.sync

import android.content.Context
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import androidx.room.withTransaction

sealed class SyncResult {
    data class Success(val usage: Int, val app: Int) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

/** The gateway answered, but with an error (possibly inside a 2xx response). */
class GatewayException(message: String) : IOException(message)

class CloudSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SyncManager"

        /**
         * Held for a whole sync run. The manual upload, SyncWorker and Reset all take it,
         * so two uploads never send the same rows and Reset never deletes mid-upload.
         */
        val syncMutex = Mutex()
    }

    private val client = OkHttpClient()
    private val db = AppDatabase.getInstance(context)

    // Your SQLite Cloud configurations loaded from .env via BuildConfig
    private val gatewayUrl = com.capstone.dataharvester.BuildConfig.GATEWAY_URL
    private val apiKey = com.capstone.dataharvester.BuildConfig.API_KEY
    private val dbName = com.capstone.dataharvester.BuildConfig.DB_NAME
    private val appVersion = com.capstone.dataharvester.BuildConfig.VERSION_NAME

    private fun sqlStr(value: String?): String {
        if (value == null) return "NULL"
        return "'${value.replace("'", "''")}'"
    }

    /**
     * Uploads local unsynced records to the cloud database in batches.
     *
     * A row is marked synced only after the server confirms it exists: each batch is
     * followed by a read-back of its record_uids. HTTP 2xx alone is not trusted (the
     * gateway can report SQL errors inside a 2xx body, and statements before a failing
     * one stay committed). Unconfirmed rows stay pending and are retried; INSERT OR
     * IGNORE on the unique record_uid makes the retry harmless if they did arrive.
     *
     * Returns SyncResult indicating success or failure.
     */
    suspend fun syncPendingData(): SyncResult = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncResult {
        val usageDao = db.usageDao()
        val appUsageDao = db.appUsageDao()
        val deviceIdentityDao = db.deviceIdentityDao()

        var totalUsageSynced = 0
        var totalAppUsageSynced = 0
        var isFirstBatch = true
        var loopCount = 0
        val maxIterations = 500

        while (loopCount < maxIterations) {
            loopCount++

            val unsyncedUsage = usageDao.getUnsyncedBatch(2000)
            val unsyncedAppUsage = appUsageDao.getUnsyncedBatch(2000)

            // Exit loop if no more records to sync
            if (unsyncedUsage.isEmpty() && unsyncedAppUsage.isEmpty() && !isFirstBatch) {
                break
            }
            if (unsyncedUsage.isEmpty() && unsyncedAppUsage.isEmpty() && isFirstBatch) {
                // If it's the first batch and it's empty, we should still check if there are identities to sync
                val unsyncedIdentity = deviceIdentityDao.getAllUnsynced()
                if (unsyncedIdentity.isEmpty()) {
                    return SyncResult.Success(0, 0)
                }
            }

            // 1. Construct bulk SQL script
            val sqlBuilder = StringBuilder()

            // Add usage records
            unsyncedUsage.forEach { record ->
                sqlBuilder.append("INSERT OR IGNORE INTO usage_records (record_uid, local_id, timestamp, datetime_str, hour, minute, day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, network_type, screen_on, battery_level, device_id, signal_strength, is_charging, device_model, network_operator, utc_offset_minutes, interval_start, interval_end, elapsed_ms, measurement_status, active_network_at_sample, collection_version) ")
                sqlBuilder.append("VALUES (${sqlStr(record.recordUid)}, ${record.id}, ${record.timestamp}, ${sqlStr(record.datetimeStr)}, ${record.hour}, ${record.minute}, ${record.dayOfWeek}, ${record.isWeekend}, ${sqlStr(record.timePeriod)}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${record.mbUsed}, ${record.cumulativeMbToday}, ${sqlStr(record.networkType)}, ${record.screenOn}, ${record.batteryLevel}, ${sqlStr(record.deviceId)}, ${record.signalStrength}, ${record.isCharging}, ${sqlStr(record.deviceModel)}, ${sqlStr(record.networkOperator)}, ${record.utcOffsetMinutes}, ${record.intervalStart}, ${record.intervalEnd}, ${record.elapsedMs}, ${sqlStr(record.measurementStatus)}, ${sqlStr(record.activeNetworkAtSample)}, ${sqlStr(record.collectionVersion)});\n")
            }

            // Add app usage records
            unsyncedAppUsage.forEach { record ->
                sqlBuilder.append("INSERT OR IGNORE INTO app_usage_records (record_uid, local_id, timestamp, datetime_str, device_id, package_name, app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time, end_time, is_system_app, utc_offset_minutes, measurement_status, collection_version) ")
                sqlBuilder.append("VALUES (${sqlStr(record.recordUid)}, ${record.id}, ${record.timestamp}, ${sqlStr(record.datetimeStr)}, ${sqlStr(record.deviceId)}, ${sqlStr(record.packageName)}, ${sqlStr(record.appName)}, ${record.uid}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${sqlStr(record.networkType)}, ${sqlStr(record.queryStart)}, ${sqlStr(record.startTime)}, ${sqlStr(record.endTime)}, ${record.isSystemApp}, ${record.utcOffsetMinutes}, ${sqlStr(record.measurementStatus)}, ${sqlStr(record.collectionVersion)});\n")
            }

            var unsyncedIdentity = emptyList<com.capstone.dataharvester.data.DeviceIdentity>()
            if (isFirstBatch) {
                unsyncedIdentity = deviceIdentityDao.getAllUnsynced()
                // One cloud row per install, keyed by the install UUID; no hardware ID is sent
                unsyncedIdentity.forEach { record ->
                    sqlBuilder.append("INSERT INTO device_identity (device_id, device_model, network_provider, app_version, linked_at) ")
                    sqlBuilder.append("VALUES (${sqlStr(record.current_device_id)}, ${sqlStr(record.device_model)}, ${sqlStr(record.network_provider)}, ${sqlStr(appVersion)}, ${record.linked_at}) ")
                    sqlBuilder.append("ON CONFLICT(device_id) DO UPDATE SET device_model = excluded.device_model, network_provider = excluded.network_provider, app_version = excluded.app_version, linked_at = excluded.linked_at;\n")
                }
            }

            val totalSynced = unsyncedUsage.size + unsyncedAppUsage.size
            val deviceId = com.capstone.dataharvester.util.DeviceIdManager(context).getDeviceId()
            val uploadTime = System.currentTimeMillis()

            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val stampFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val datetimeStr = isoFormat.format(java.util.Date(uploadTime))
            val datetimeStamp = stampFormat.format(java.util.Date(uploadTime))

            val payloadSizeBytes = sqlBuilder.toString().toByteArray(Charsets.UTF_8).size
            val payloadSizeKb = payloadSizeBytes / 1024.0

            sqlBuilder.append("INSERT INTO upload_history (device_id, uploaded_timestamp, datetime_str, datetime_stamp, records_uploaded, payload_size_bytes, payload_size_kb, app_version) ")
            sqlBuilder.append("VALUES (${sqlStr(deviceId)}, $uploadTime, ${sqlStr(datetimeStr)}, ${sqlStr(datetimeStamp)}, $totalSynced, $payloadSizeBytes, $payloadSizeKb, ${sqlStr(appVersion)});\n")

            try {
                // 2. Send the batch. A transport error after the server accepted it is
                // fine: the read-back on the retry confirms the rows that arrived
                postSql(sqlBuilder.toString())

                // 3. Acknowledge only what the server confirms it holds
                val ackedUsage = fetchExistingRecordUids("usage_records", unsyncedUsage.map { it.recordUid })
                val ackedApp = fetchExistingRecordUids("app_usage_records", unsyncedAppUsage.map { it.recordUid })

                db.withTransaction {
                    ackedUsage.chunked(500).forEach { chunk -> usageDao.markAsSyncedByRecordUid(chunk) }
                    ackedApp.chunked(500).forEach { chunk -> appUsageDao.markAsSyncedByRecordUid(chunk) }
                    if (isFirstBatch && unsyncedIdentity.isNotEmpty()) {
                        unsyncedIdentity.map { it.id }.chunked(500).forEach { chunk ->
                            deviceIdentityDao.markAsSynced(chunk)
                        }
                    }
                }

                totalUsageSynced += ackedUsage.size
                totalAppUsageSynced += ackedApp.size
                isFirstBatch = false

                val confirmed = ackedUsage.size + ackedApp.size
                if (confirmed < totalSynced) {
                    // The rest stay pending; stop here so the retry starts from them
                    Log.w(TAG, "Server confirmed $confirmed of $totalSynced records — rest kept pending")
                    return SyncResult.Failure("Server confirmed $confirmed of $totalSynced records")
                }
                Log.i(TAG, "Batch synced and confirmed $confirmed records")
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                return SyncResult.Failure(e.message ?: "Unknown error")
            }
        }

        return SyncResult.Success(totalUsageSynced, totalAppUsageSynced)
    }

    /**
     * Which of [recordUids] exist in [table] on the server. Queried in chunks so the
     * IN list and the request stay small.
     */
    private fun fetchExistingRecordUids(table: String, recordUids: List<String>): List<String> =
        recordUids.chunked(500).flatMap { chunk ->
            val sql = "SELECT record_uid FROM $table WHERE record_uid IN (${chunk.joinToString(",") { sqlStr(it) }});"
            parseRecordUids(postSql(sql))
        }

    /**
     * POST [sql] to the gateway and return the parsed JSON body.
     * Throws [GatewayException] for a non-2xx status or an error reported in the body,
     * and IOException for transport failures.
     */
    private fun postSql(sql: String): JSONObject {
        val jsonBody = JSONObject()
        jsonBody.put("database", dbName)
        jsonBody.put("sql", sql)

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(gatewayUrl)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GatewayException("HTTP ${response.code}: $body")
            }
            val json = if (body.isBlank()) JSONObject() else try {
                JSONObject(body)
            } catch (e: Exception) {
                throw GatewayException("Unreadable response: ${body.take(200)}")
            }
            // An error in a 2xx body still means the SQL did not (fully) run
            for (key in listOf("error", "errors")) {
                if (json.has(key) && !json.isNull(key)) {
                    throw GatewayException("Gateway error: ${json.get(key)}")
                }
            }
            return json
        }
    }

    /**
     * record_uid values from a SELECT response. Accepts rows as objects
     * ({"record_uid": "…"}) or as arrays (["…"]), since the exact row shape depends on
     * the gateway; anything unrecognised yields no acknowledgement (rows stay pending).
     */
    private fun parseRecordUids(json: JSONObject): List<String> {
        val data = json.opt("data") as? JSONArray ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            when (val row = data.opt(i)) {
                is JSONObject -> row.optString("record_uid").takeIf { it.isNotEmpty() }
                is JSONArray -> row.optString(0).takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }
}
