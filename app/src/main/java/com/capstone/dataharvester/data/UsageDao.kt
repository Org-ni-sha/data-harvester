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
}
