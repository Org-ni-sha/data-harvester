package com.capstone.dataharvester.sync

import com.capstone.dataharvester.data.AppUsageRecord
import com.capstone.dataharvester.data.DeviceIdentity
import com.capstone.dataharvester.data.UsageRecord

/**
 * The cloud columns of each uploaded row, in one place: the ingest endpoint receives
 * these maps as JSON and the direct-gateway fallback turns the same maps into INSERTs,
 * so the two paths cannot drift apart. Keys match the Dataharvester_v2 columns and
 * api/_lib/records.js on the server.
 */
object UploadPayload {

    fun usage(r: UsageRecord): LinkedHashMap<String, Any> = linkedMapOf(
        "record_uid" to r.recordUid,
        "local_id" to r.id,
        "timestamp" to r.timestamp,
        "datetime_str" to r.datetimeStr,
        "hour" to r.hour,
        "minute" to r.minute,
        "day_of_week" to r.dayOfWeek,
        "is_weekend" to r.isWeekend,
        "time_period" to r.timePeriod,
        "bytes_rx" to r.bytesRx,
        "bytes_tx" to r.bytesTx,
        "bytes_total" to r.bytesTotal,
        "mb_used" to r.mbUsed,
        "cumulative_mb_today" to r.cumulativeMbToday,
        "network_type" to r.networkType,
        "screen_on" to r.screenOn,
        "battery_level" to r.batteryLevel,
        "device_id" to r.deviceId,
        "signal_strength" to r.signalStrength,
        "is_charging" to r.isCharging,
        "device_model" to r.deviceModel,
        "network_operator" to r.networkOperator,
        "utc_offset_minutes" to r.utcOffsetMinutes,
        "interval_start" to r.intervalStart,
        "interval_end" to r.intervalEnd,
        "elapsed_ms" to r.elapsedMs,
        "measurement_status" to r.measurementStatus,
        "active_network_at_sample" to r.activeNetworkAtSample,
        "collection_version" to r.collectionVersion,
        "schema_version" to r.schemaVersion,
        "study_id" to r.studyId
    )

    fun app(r: AppUsageRecord): LinkedHashMap<String, Any> = linkedMapOf(
        "record_uid" to r.recordUid,
        "local_id" to r.id,
        "timestamp" to r.timestamp,
        "datetime_str" to r.datetimeStr,
        "device_id" to r.deviceId,
        "package_name" to r.packageName,
        "app_name" to r.appName,
        "uid" to r.uid,
        "bytes_rx" to r.bytesRx,
        "bytes_tx" to r.bytesTx,
        "bytes_total" to r.bytesTotal,
        "network_type" to r.networkType,
        "query_start" to r.queryStart,
        "start_time" to r.startTime,
        "end_time" to r.endTime,
        "is_system_app" to r.isSystemApp,
        "utc_offset_minutes" to r.utcOffsetMinutes,
        "measurement_status" to r.measurementStatus,
        "collection_version" to r.collectionVersion,
        "schema_version" to r.schemaVersion,
        "study_id" to r.studyId
    )

    /** One cloud row per install, keyed by the install UUID; no hardware ID is sent. */
    fun identity(r: DeviceIdentity, appVersion: String, studyId: String, enrolledAt: Long): LinkedHashMap<String, Any> =
        linkedMapOf(
            "device_id" to r.current_device_id,
            "device_model" to r.device_model,
            "network_provider" to r.network_provider,
            "app_version" to appVersion,
            "linked_at" to r.linked_at,
            "study_id" to studyId,
            "enrolled_at" to enrolledAt
        )
}
