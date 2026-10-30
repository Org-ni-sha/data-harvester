package com.capstone.dataharvester.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room entity representing a single data usage observation snapshot.
 * One row is inserted every 30 seconds by the DataCollectionService.
 *
 * Column names use snake_case to match the CSV export schema.
 */
@Entity(
    tableName = "usage_records",
    indices = [
        Index(value = ["record_uid"], unique = true),
        Index(value = ["study_id", "is_synced"])
    ]
)
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

    /**
     * Network the interval's bytes belong to: LTE / 5G / 3G / 2G / CELLULAR / WIFI / NONE / UNKNOWN.
     * The counter measures mobile bytes only, so any interval with mobile bytes gets a mobile
     * label; WIFI only when no mobile byte moved, so dropping WIFI rows never drops mobile usage.
     */
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
    val deviceModel: String = "",
    
    /** Network operator/carrier of the active SIM */
    @ColumnInfo(name = "network_operator", defaultValue = "Unknown")
    val networkOperator: String = "Unknown",
    
    /** Timezone offset from UTC in minutes */
    @ColumnInfo(name = "utc_offset_minutes", defaultValue = "480")
    val utcOffsetMinutes: Int = 480,

    /** 0 = pending upload, 1 = confirmed by the server, 2 = rejected by the server as invalid (kept, never resent) */
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false,

    /** Wall-clock epoch millis the interval started (previous committed tick); 0 for legacy rows */
    @ColumnInfo(name = "interval_start", defaultValue = "0")
    val intervalStart: Long = 0L,

    /** Wall-clock epoch millis the interval ended (= timestamp); 0 for legacy rows */
    @ColumnInfo(name = "interval_end", defaultValue = "0")
    val intervalEnd: Long = 0L,

    /** Interval length from SystemClock.elapsedRealtime (immune to clock changes); -1 if unknown */
    @ColumnInfo(name = "elapsed_ms", defaultValue = "-1")
    val elapsedMs: Long = -1L,

    /** OK / BASELINE / COUNTER_RESET / UNSUPPORTED / GAP / INTERRUPTED; LEGACY for rows from before v11 */
    @ColumnInfo(name = "measurement_status", defaultValue = "LEGACY")
    val measurementStatus: String = "LEGACY",

    /** Raw active network when the tick fired (context only; network_type is the cleaning label) */
    @ColumnInfo(name = "active_network_at_sample", defaultValue = "")
    val activeNetworkAtSample: String = "",

    /** App version that collected the row; "legacy" for rows from before v11 */
    @ColumnInfo(name = "collection_version", defaultValue = "legacy")
    val collectionVersion: String = "legacy",

    /**
     * Version of the measurement rules that produced the row (MeasurementRules.SCHEMA_VERSION):
     * 0 = before v11 (unknown rules), 1 = app 1.6.3, 2 = 1.6.4 (mobile label for any mobile
     * byte, INTERRUPTED, per-app GAP / CLOCK_ERROR). Rules can change without the app version
     * meaning anything to analysis, so filter or split on this instead.
     */
    @ColumnInfo(name = "schema_version", defaultValue = "0")
    val schemaVersion: Int = 0,

    /** Study the row was collected for; "" = before enrollment (never uploaded) */
    @ColumnInfo(name = "study_id", defaultValue = "")
    val studyId: String = "",

    /** Random per-row id: the cloud's only uniqueness key and the upload acknowledgement key */
    @ColumnInfo(name = "record_uid", defaultValue = "")
    val recordUid: String = UUID.randomUUID().toString()

)
