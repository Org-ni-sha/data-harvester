package com.capstone.dataharvester.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing per-app data usage for a single collection interval.
 * Populated via NetworkStatsManager every ~10 minutes by the DataCollectionService.
 *
 * Each row represents one app's data usage during the interval.
 * Both system apps and user-installed apps are tracked, distinguished by [isSystemApp].
 *
 * Column names use snake_case to match the CSV export schema.
 */
@Entity(tableName = "app_usage_records")
data class AppUsageRecord(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Epoch milliseconds when this snapshot was taken */
    val timestamp: Long,

    /** ISO 8601 human-readable datetime string */
    @ColumnInfo(name = "datetime_str")
    val datetimeStr: String,

    /** UUID identifying the device (for multi-device datasets) */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** Android package name (e.g., "com.google.android.youtube") */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** Human-readable app name (e.g., "YouTube") */
    @ColumnInfo(name = "app_name")
    val appName: String,

    /** Linux UID assigned to the app */
    val uid: Int,

    /** Bytes received during this interval */
    @ColumnInfo(name = "bytes_rx")
    val bytesRx: Long,

    /** Bytes transmitted during this interval */
    @ColumnInfo(name = "bytes_tx")
    val bytesTx: Long,

    /** Total bytes (rx + tx) during this interval */
    @ColumnInfo(name = "bytes_total")
    val bytesTotal: Long,

    /** Network type: "MOBILE" or "WIFI" */
    @ColumnInfo(name = "network_type")
    val networkType: String,

    /** 1 if system/pre-installed app, 0 if user-installed */
    @ColumnInfo(name = "is_system_app")
    val isSystemApp: Int
)
