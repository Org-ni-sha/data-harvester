package com.capstone.dataharvester.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single data usage observation snapshot.
 * One row is inserted every ~2 minutes by the DataCollectionService.
 *
 * Column names use snake_case to match the CSV export schema.
 */
@Entity(tableName = "usage_records")
data class UsageRecord(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Epoch milliseconds — enables efficient range queries in Room */
    val timestamp: Long,

    /** ISO 8601 human-readable datetime string (for CSV readability) */
    @ColumnInfo(name = "datetime_str")
    val datetimeStr: String,

    /** Hour of day (0-23) */
    val hour: Int,

    /** Minute of hour (0-59) */
    val minute: Int,

    /** ISO day of week: 1=Monday … 7=Sunday */
    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int,

    /** 1 if Saturday or Sunday, 0 otherwise */
    @ColumnInfo(name = "is_weekend")
    val isWeekend: Int,

    /** Morning (5-11), Afternoon (12-16), Evening (17-20), Night (21-4) */
    @ColumnInfo(name = "time_period")
    val timePeriod: String,

    /** Bytes received since last snapshot (delta) */
    @ColumnInfo(name = "bytes_rx")
    val bytesRx: Long,

    /** Bytes transmitted since last snapshot (delta) */
    @ColumnInfo(name = "bytes_tx")
    val bytesTx: Long,

    /** Total bytes (rx + tx) this interval */
    @ColumnInfo(name = "bytes_total")
    val bytesTotal: Long,

    /** Total megabytes this interval: bytes_total / 1048576.0 */
    @ColumnInfo(name = "mb_used")
    val mbUsed: Double,

    /** Running total MB for the current calendar day (queried from DB) */
    @ColumnInfo(name = "cumulative_mb_today")
    val cumulativeMbToday: Double,

    /** Current network type: LTE / 5G / 3G / 2G / WIFI / CELLULAR / NONE / UNKNOWN */
    @ColumnInfo(name = "network_type")
    val networkType: String,

    /** 1 if screen was on at collection time, 0 if off */
    @ColumnInfo(name = "screen_on")
    val screenOn: Int,

    /** Battery percentage (0-100) */
    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int,

    /** UUID identifying the device (for multi-device datasets) */
    @ColumnInfo(name = "device_id")
    val deviceId: String = "",

    /** Signal strength in dBm (e.g., -85). -999 if unavailable */
    @ColumnInfo(name = "signal_strength")
    val signalStrength: Int = -999,

    /** 1 if device is charging, 0 if on battery */
    @ColumnInfo(name = "is_charging")
    val isCharging: Int = 0,

    /** Device manufacturer + model (e.g., "Samsung SM-A546E") */
    @ColumnInfo(name = "device_model")
    val deviceModel: String = ""
)
