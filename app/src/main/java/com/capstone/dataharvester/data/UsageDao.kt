package com.capstone.dataharvester.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for usage records.
 * All queries are suspend functions for use with Kotlin coroutines.
 */
@Dao
interface UsageDao {

    /** Insert a single usage record. */
    @Insert
    suspend fun insert(record: UsageRecord)

    /** Get all records, newest first. */
    @Query("SELECT * FROM usage_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<UsageRecord>

    /** Get all records, oldest first (for CSV export). */
    @Query("SELECT * FROM usage_records ORDER BY timestamp ASC")
    suspend fun getAllAscending(): List<UsageRecord>

    /** Get the most recent record (for delta computation). */
    @Query("SELECT * FROM usage_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): UsageRecord?

    /** Get total number of records. */
    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun getCount(): Int

    /**
     * Get sum of mb_used for today.
     * @param datePrefix Date string prefix in "yyyy-MM-dd" format
     */
    @Query("SELECT COALESCE(SUM(mb_used), 0.0) FROM usage_records WHERE datetime_str LIKE :datePrefix || '%'")
    suspend fun getTodaySum(datePrefix: String): Double

    /** Get records within a timestamp range (for filtered exports). */
    @Query("SELECT * FROM usage_records WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<UsageRecord>

    /** Delete all records (for testing/reset). */
    @Query("DELETE FROM usage_records")
    suspend fun deleteAll()

    /** Get records of [studyId] that haven't been synced to the backend yet in batches. */
    @Query("SELECT * FROM usage_records WHERE is_synced = 0 AND study_id = :studyId ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsyncedBatch(studyId: String, limit: Int): List<UsageRecord>

    /** Mark records as synced after successful backend upload. */
    @Query("UPDATE usage_records SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)

    /** Number of [studyId] records not yet uploaded (shown before a reset). */
    @Query("SELECT COUNT(*) FROM usage_records WHERE is_synced = 0 AND study_id = :studyId")
    suspend fun countUnsynced(studyId: String): Int

    /** Records from outside [studyId] (collected before enrollment); never uploaded. */
    @Query("SELECT COUNT(*) FROM usage_records WHERE study_id != :studyId")
    suspend fun countOutsideStudy(studyId: String): Int

    /** Mark records the server confirmed, by their per-row upload key. */
    @Query("UPDATE usage_records SET is_synced = 1 WHERE record_uid IN (:recordUids)")
    suspend fun markAsSyncedByRecordUid(recordUids: List<String>)

    /** Rows the server refused as invalid: kept here and in exports, never resent (is_synced = 2). */
    @Query("UPDATE usage_records SET is_synced = 2 WHERE record_uid IN (:recordUids)")
    suspend fun markRejectedByRecordUid(recordUids: List<String>)
}
