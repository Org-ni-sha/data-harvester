package com.capstone.dataharvester.sync

import android.content.Context
import android.util.Log
import com.capstone.dataharvester.data.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class CloudSyncManager(private val context: Context) {

    private val client = OkHttpClient()
    private val db = AppDatabase.getInstance(context)

    // Your SQLite Cloud configurations loaded from .env via BuildConfig
    private val gatewayUrl = com.capstone.dataharvester.BuildConfig.GATEWAY_URL
    private val apiKey = com.capstone.dataharvester.BuildConfig.API_KEY
    private val dbName = com.capstone.dataharvester.BuildConfig.DB_NAME 

    /**
     * Uploads local unsynced records to the cloud database.
     * Returns a Pair of (usageRecordsSynced, appUsageRecordsSynced) or null if sync failed.
     */
    suspend fun syncPendingData(): Pair<Int, Int>? {
        val usageDao = db.usageDao()
        val appUsageDao = db.appUsageDao()

        val unsyncedUsage = usageDao.getUnsyncedRecords()
        val unsyncedAppUsage = appUsageDao.getUnsyncedRecords()

        if (unsyncedUsage.isEmpty() && unsyncedAppUsage.isEmpty()) return Pair(0, 0)

        // 1. Construct bulk SQL script
        val sqlBuilder = StringBuilder()
        
        // Add usage records
        unsyncedUsage.forEach { record ->
            sqlBuilder.append("INSERT INTO usage_records (timestamp, datetime_str, hour, minute, day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, network_type, screen_on, battery_level, device_id, signal_strength, is_charging, device_model) ")
            sqlBuilder.append("VALUES (${record.timestamp}, '${record.datetimeStr}', ${record.hour}, ${record.minute}, ${record.dayOfWeek}, ${record.isWeekend}, '${record.timePeriod}', ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, ${record.mbUsed}, ${record.cumulativeMbToday}, '${record.networkType}', ${record.screenOn}, ${record.batteryLevel}, '${record.deviceId}', ${record.signalStrength}, ${record.isCharging}, '${record.deviceModel}');\n")
        }

        // Add app usage records (with quote escaping for package and app names)
        unsyncedAppUsage.forEach { record ->
            val escapedPackageName = record.packageName.replace("'", "''")
            val escapedAppName = record.appName.replace("'", "''")
            sqlBuilder.append("INSERT INTO app_usage_records (timestamp, datetime_str, device_id, package_name, app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, is_system_app) ")
            sqlBuilder.append("VALUES (${record.timestamp}, '${record.datetimeStr}', '${record.deviceId}', '$escapedPackageName', '$escapedAppName', ${record.uid}, ${record.bytesRx}, ${record.bytesTx}, ${record.bytesTotal}, '${record.networkType}', '${record.queryStart}', ${record.isSystemApp});\n")
        }

        // Add device_identity (syncing locally tracked identity)
        val deviceIdentityDao = db.deviceIdentityDao()
        val unsyncedIdentity = deviceIdentityDao.getAllUnsynced()
        unsyncedIdentity.forEach { record ->
            val prevDeviceStr = if (record.previous_device_id != null) "'${record.previous_device_id}'" else "NULL"
            
            // Automation: If local previous_device_id is NULL (e.g. fresh reinstall), fallback to checking the cloud DB for the last known device_id of this hardware
            sqlBuilder.append("INSERT OR REPLACE INTO device_identity (current_device_id, previous_device_id, hardware_id, device_model, network_provider, linked_at) ")
            sqlBuilder.append("VALUES ('${record.current_device_id}', COALESCE($prevDeviceStr, (SELECT current_device_id FROM device_identity WHERE hardware_id = '${record.hardware_id}' AND current_device_id != '${record.current_device_id}' ORDER BY linked_at DESC LIMIT 1)), '${record.hardware_id}', '${record.device_model}', '${record.network_provider}', ${record.linked_at});\n")
        }

        // Calculate payload sizes and log upload statistics in upload_history table
        val totalSynced = unsyncedUsage.size + unsyncedAppUsage.size
        val deviceId = com.capstone.dataharvester.util.DeviceIdManager(context).getDeviceId()
        val uploadTime = System.currentTimeMillis()

        // Generate date/time formats
        val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val stampFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val datetimeStr = isoFormat.format(java.util.Date(uploadTime))
        val datetimeStamp = stampFormat.format(java.util.Date(uploadTime))
        
        // Calculate size of SQL query script payload
        val payloadSizeBytes = sqlBuilder.toString().toByteArray(Charsets.UTF_8).size
        val payloadSizeKb = payloadSizeBytes

        sqlBuilder.append("INSERT INTO upload_history (device_id, uploaded_timestamp, datetime_str, datetime_stamp, records_uploaded, payload_size_bytes, payload_size_kb) ")
        sqlBuilder.append("VALUES ('$deviceId', $uploadTime, '$datetimeStr', '$datetimeStamp', $totalSynced, $payloadSizeBytes, $payloadSizeKb);\n")

        // 2. Perform HTTP request formatting as JSON (using native Android JSONObject)
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

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i("SyncManager", "Synced $totalSynced records successfully!")
                    
                    // 3. Mark as synced in local DB
                    if (unsyncedUsage.isNotEmpty()) {
                        val ids = unsyncedUsage.map { it.id }
                        usageDao.markAsSynced(ids)
                    }
                    if (unsyncedAppUsage.isNotEmpty()) {
                        val appIds = unsyncedAppUsage.map { it.id }
                        appUsageDao.markAsSynced(appIds)
                    }
                    if (unsyncedIdentity.isNotEmpty()) {
                        val identityIds = unsyncedIdentity.map { it.id }
                        deviceIdentityDao.markAsSynced(identityIds)
                    }
                    
                    // Fetch historical counts to persist locally
                    fetchHistoricalCount()
                    
                    Pair(unsyncedUsage.size, unsyncedAppUsage.size)
                } else {
                    Log.e("SyncManager", "Failed to sync: Code ${response.code} - ${response.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error during sync", e)
            null
        }
    }
    
    private suspend fun fetchHistoricalCount() {
        val deviceIdManager = com.capstone.dataharvester.util.DeviceIdManager(context)
        val hardwareId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        
        // Includes both current_device_id and previous_device_id in case users manually updated it
        val deviceIdsQuery = "SELECT current_device_id FROM device_identity WHERE hardware_id = '$hardwareId' UNION SELECT previous_device_id FROM device_identity WHERE hardware_id = '$hardwareId' AND previous_device_id IS NOT NULL"
        val sql = "SELECT (SELECT COUNT(*) FROM usage_records WHERE device_id IN ($deviceIdsQuery)) as usage_count, (SELECT COUNT(*) FROM app_usage_records WHERE device_id IN ($deviceIdsQuery)) as app_count;"
        
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
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to fetch historical count", e)
        }
    }
}
