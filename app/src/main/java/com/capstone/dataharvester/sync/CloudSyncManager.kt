package com.capstone.dataharvester.sync

import android.content.Context
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import androidx.room.withTransaction

sealed class SyncResult {
    data class Success(val usage: Int, val app: Int) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

class CloudSyncManager(private val context: Context) {

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
     * Returns SyncResult indicating success or failure.
     */
    suspend fun syncPendingData(): SyncResult {
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
                sqlBuilder.append("INSERT OR IGNORE INTO usage_records (local_id, timestamp, datetime_str, hour, minute, day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, network_type, screen_on, battery_level, device_id, signal_strength, is_charging, device_model, network_operator, utc_offset_minutes) ")
                sqlBuilder.append("VALUES (${record.id}, ${record.timestamp}, ${sqlStr(record.datetimeStr)}, ${record.hour}, ${record.minute}, ${record.dayOfWeek}, ${record.isWeekend}, ${sqlStr(record.timePeriod)}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${record.mbUsed}, ${record.cumulativeMbToday}, ${sqlStr(record.networkType)}, ${record.screenOn}, ${record.batteryLevel}, ${sqlStr(record.deviceId)}, ${record.signalStrength}, ${record.isCharging}, ${sqlStr(record.deviceModel)}, ${sqlStr(record.networkOperator)}, ${record.utcOffsetMinutes});\n")
            }
            
            // Add app usage records
            unsyncedAppUsage.forEach { record ->
                sqlBuilder.append("INSERT OR IGNORE INTO app_usage_records (local_id, timestamp, datetime_str, device_id, package_name, app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time, end_time, is_system_app, utc_offset_minutes) ")
                sqlBuilder.append("VALUES (${record.id}, ${record.timestamp}, ${sqlStr(record.datetimeStr)}, ${sqlStr(record.deviceId)}, ${sqlStr(record.packageName)}, ${sqlStr(record.appName)}, ${record.uid}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${sqlStr(record.networkType)}, ${sqlStr(record.queryStart)}, ${sqlStr(record.startTime)}, ${sqlStr(record.endTime)}, ${record.isSystemApp}, ${record.utcOffsetMinutes});\n")
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
            
            val jsonBody = org.json.JSONObject()
            jsonBody.put("database", dbName)
            jsonBody.put("sql", sqlBuilder.toString())
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(gatewayUrl)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .build()
                
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i("SyncManager", "Batch synced $totalSynced records successfully!")
                        
                        db.withTransaction {
                            if (unsyncedUsage.isNotEmpty()) {
                                unsyncedUsage.map { it.id }.chunked(500).forEach { chunk ->
                                    usageDao.markAsSynced(chunk)
                                }
                            }
                            if (unsyncedAppUsage.isNotEmpty()) {
                                unsyncedAppUsage.map { it.id }.chunked(500).forEach { chunk ->
                                    appUsageDao.markAsSynced(chunk)
                                }
                            }
                            if (isFirstBatch && unsyncedIdentity.isNotEmpty()) {
                                unsyncedIdentity.map { it.id }.chunked(500).forEach { chunk ->
                                    deviceIdentityDao.markAsSynced(chunk)
                                }
                            }
                        }
                        
                        totalUsageSynced += unsyncedUsage.size
                        totalAppUsageSynced += unsyncedAppUsage.size
                        isFirstBatch = false
                    } else {
                        val errorBody = response.body?.string()
                        Log.e("SyncManager", "Failed to sync: Code ${response.code} - $errorBody")
                        return SyncResult.Failure("HTTP ${response.code}: $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error during sync", e)
                return SyncResult.Failure(e.message ?: "Unknown error")
            }
        }
        
        return SyncResult.Success(totalUsageSynced, totalAppUsageSynced)
    }
}
