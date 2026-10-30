package com.capstone.dataharvester.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for per-app usage records.
 * All queries are suspend functions for use with Kotlin coroutines.
 */
@Dao
interface AppUsageDao {

    /** Insert a single per-app usage record. */
    @Insert
    suspend fun insert(record: AppUsageRecord)

    /** Insert multiple per-app usage records in a single transaction. */
    @Insert
    suspend fun insertAll(records: List<AppUsageRecord>)

    /** Get all records, newest first. */
    @Query("SELECT * FROM app_usage_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<AppUsageRecord>

    /** Get all records, oldest first (for CSV export). */
    @Query("SELECT * FROM app_usage_records ORDER BY timestamp ASC")
    suspend fun getAllAscending(): List<AppUsageRecord>

    /** Get total number of per-app records. */
    @Query("SELECT COUNT(*) FROM app_usage_records")
    suspend fun getCount(): Int

    /** Delete all per-app records (for testing/reset). */
    @Query("DELETE FROM app_usage_records")
    suspend fun deleteAll()

    /** Get per-app records of [studyId] that haven't been synced to the backend yet in batches. */
    @Query("SELECT * FROM app_usage_records WHERE is_synced = 0 AND study_id = :studyId ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsyncedBatch(studyId: String, limit: Int): List<AppUsageRecord>

    /** Mark per-app records as synced after successful backend upload. */
    @Query("UPDATE app_usage_records SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    /** Number of [studyId] records not yet uploaded (shown before a reset). */
    @Query("SELECT COUNT(*) FROM app_usage_records WHERE is_synced = 0 AND study_id = :studyId")
    suspend fun countUnsynced(studyId: String): Int

    /** Records from outside [studyId] (collected before enrollment); never uploaded. */
    @Query("SELECT COUNT(*) FROM app_usage_records WHERE study_id != :studyId")
    suspend fun countOutsideStudy(studyId: String): Int

    /** Mark records the server confirmed, by their per-row upload key. */
    @Query("UPDATE app_usage_records SET is_synced = 1 WHERE record_uid IN (:recordUids)")
    suspend fun markAsSyncedByRecordUid(recordUids: List<String>)

    /** Rows the server refused as invalid: kept here and in exports, never resent (is_synced = 2). */
    @Query("UPDATE app_usage_records SET is_synced = 2 WHERE record_uid IN (:recordUids)")
    suspend fun markRejectedByRecordUid(recordUids: List<String>)
}
