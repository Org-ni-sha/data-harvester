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
                sqlBuilder.append("INSERT INTO usage_records (timestamp, datetime_str, hour, minute, day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, network_type, screen_on, battery_level, device_id, signal_strength, is_charging, device_model) ")
                sqlBuilder.append("VALUES (${record.timestamp}, '${record.datetimeStr}', ${record.hour}, ${record.minute}, ${record.dayOfWeek}, ${record.isWeekend}, '${record.timePeriod}', ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${record.mbUsed}, ${record.cumulativeMbToday}, '${record.networkType}', ${record.screenOn}, ${record.batteryLevel}, '${record.deviceId}', ${record.signalStrength}, ${record.isCharging}, '${record.deviceModel}');\n")
            }
            
            // Add app usage records
            unsyncedAppUsage.forEach { record ->
                val escapedPackageName = record.packageName.replace("'", "''")
                val escapedAppName = record.appName.replace("'", "''")
                sqlBuilder.append("INSERT INTO app_usage_records (timestamp, datetime_str, device_id, package_name, app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, is_system_app) ")
                sqlBuilder.append("VALUES (${record.timestamp}, '${record.datetimeStr}', '${record.deviceId}', '$escapedPackageName', '$escapedAppName', ${record.uid}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, '${record.networkType}', '${record.queryStart}', ${record.isSystemApp});\n")
            }
            
            var unsyncedIdentity = emptyList<com.capstone.dataharvester.data.DeviceIdentity>()
            if (isFirstBatch) {
                unsyncedIdentity = deviceIdentityDao.getAllUnsynced()
                unsyncedIdentity.forEach { record ->
                    val prevDeviceStr = if (record.previous_device_id != null) "'${record.previous_device_id}'" else "NULL"
                    sqlBuilder.append("INSERT OR REPLACE INTO device_identity (current_device_id, previous_device_id, hardware_id, device_model, network_provider, linked_at) ")
                    sqlBuilder.append("VALUES ('${record.current_device_id}', COALESCE($prevDeviceStr, (SELECT current_device_id FROM device_identity WHERE hardware_id = '${record.hardware_id}' AND current_device_id != '${record.current_device_id}' ORDER BY linked_at DESC LIMIT 1)), '${record.hardware_id}', '${record.device_model}', '${record.network_provider}', ${record.linked_at});\n")
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
            val payloadSizeKb = payloadSizeBytes
            
            sqlBuilder.append("INSERT INTO upload_history (device_id, uploaded_timestamp, datetime_str, datetime_stamp, records_uploaded, payload_size_bytes, payload_size_kb) ")
            sqlBuilder.append("VALUES ('$deviceId', $uploadTime, '$datetimeStr', '$datetimeStamp', $totalSynced, $payloadSizeBytes, $payloadSizeKb);\n")
            
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
                val response = client.newCall(request).execute()
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
            } catch (e: Exception) {
                Log.e("SyncManager", "Error during sync", e)
                return SyncResult.Failure(e.message ?: "Unknown error")
            }
        }
        
        // Fetch historical counts after all batches succeed
        fetchHistoricalCount()
        
        return SyncResult.Success(totalUsageSynced, totalAppUsageSynced)
    }
    
    private suspend fun fetchHistoricalCount() {
        val deviceIdManager = com.capstone.dataharvester.util.DeviceIdManager(context)
        val deviceModel = deviceIdManager.getDeviceModel()
        
        // Sum up all records that match this exact device_model, regardless of their device_id or hardware_id
        val sql = "SELECT (SELECT COUNT(*) FROM usage_records WHERE device_model = '$deviceModel') as usage_count, (SELECT COUNT(*) FROM app_usage_records WHERE device_id IN (SELECT DISTINCT device_id FROM usage_records WHERE device_model = '$deviceModel')) as app_count;"
        
        val jsonBody = org.json.JSONObject()
        jsonBody.put("database", dbName)
        jsonBody.put("sql", sql)
        
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
                    val responseStr = response.body?.string()
                    android.util.Log.i("SyncManager", "Historical count response: $responseStr")
                    if (responseStr != null) {
                        val json = org.json.JSONObject(responseStr)
                        val dataArray = json.optJSONArray("data")
                        if (dataArray != null && dataArray.length() > 0) {
                            val row = dataArray.getJSONObject(0)
                            val usageCount = row.optInt("usage_count", 0)
                            val appCount = row.optInt("app_count", 0)
                            
                            val prefs = context.getSharedPreferences("historical_counts", android.content.Context.MODE_PRIVATE)
                            prefs.edit()
                                .putInt("historic_usage_count", usageCount)
                                .putInt("historic_app_usage_count", appCount)
                                .apply()
                            android.util.Log.i("SyncManager", "Saved historical counts: $usageCount, $appCount")
                        }
                    }
                } else {
                    android.util.Log.e("SyncManager", "Failed to fetch historical count: Code ${response.code} - ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncManager", "Failed to fetch historical count", e)
        }
    }
}
