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

    // Your SQLite Cloud configurations
    private val gatewayUrl = "https://caqj7nc1dk.g4.gateway.sqlite.cloud/v2/weblite/sql"
    private val apiKey = "pyJnSCHaiLuFXxvad4y65KtyEX99ni1H0Ut6AcMDz10"
    private val dbName = "DATAra_harvester.sqlite" 

    /**
     * Uploads local unsynced records to the cloud database.
     * Returns the number of synced records, or -1 if the sync failed.
     */
    suspend fun syncPendingData(): Int {
        val usageDao = db.usageDao()
        val appUsageDao = db.appUsageDao()

        val unsyncedUsage = usageDao.getUnsyncedRecords()
        val unsyncedAppUsage = appUsageDao.getUnsyncedRecords()

        if (unsyncedUsage.isEmpty() && unsyncedAppUsage.isEmpty()) return 0

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
                    val totalSynced = unsyncedUsage.size + unsyncedAppUsage.size
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
                    totalSynced
                } else {
                    Log.e("SyncManager", "Failed to sync: Code ${response.code} - ${response.body?.string()}")
                    -1
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error during sync", e)
            -1
        }
    }
}
