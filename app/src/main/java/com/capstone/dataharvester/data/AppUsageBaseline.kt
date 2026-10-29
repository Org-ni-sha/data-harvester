package com.capstone.dataharvester.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * Last cumulative per-app reading, one row per (uid, transport).
 *
 * NetworkStatsManager stores per-UID history in buckets about 2 hours long, so a
 * 10-minute query cannot be answered exactly. The collector instead asks for the
 * cumulative usage since [anchor] (a UTC midnight, which is a bucket boundary) and
 * stores the difference from the reading saved here.
 *
 * A transport with no rows has no baseline yet. A transport whose rows all have
 * uid = [MARKER_UID] has a baseline, but no app had used it yet.
 */
@Entity(tableName = "app_usage_baseline", primaryKeys = ["uid", "transport"])
data class AppUsageBaseline(
    val uid: Int,

    /** "MOBILE" or "WIFI" */
    val transport: String,

    /** Epoch millis (UTC midnight) the cumulative values are counted from */
    val anchor: Long,

    val rx: Long,

    val tx: Long,

    /** Wall-clock epoch millis when this reading was taken */
    @ColumnInfo(name = "taken_at")
    val takenAt: Long
) {
    companion object {
        /** Not a real UID: marks that a baseline exists even when no app had used the transport */
        const val MARKER_UID = Int.MIN_VALUE
    }
}

@Dao
interface AppUsageBaselineDao {

    @Query("SELECT * FROM app_usage_baseline WHERE transport = :transport")
    suspend fun getAll(transport: String): List<AppUsageBaseline>

    @Query("DELETE FROM app_usage_baseline WHERE transport = :transport")
    suspend fun deleteTransport(transport: String)

    @Insert
    suspend fun insertAll(baselines: List<AppUsageBaseline>)

    /** Replace one transport's readings with [baselines] (call inside the row-insert transaction). */
    @Transaction
    suspend fun replaceAll(transport: String, baselines: List<AppUsageBaseline>) {
        deleteTransport(transport)
        insertAll(baselines)
    }

    @Query("DELETE FROM app_usage_baseline")
    suspend fun clear()
}
