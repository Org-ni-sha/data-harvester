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
}
