package com.capstone.dataharvester.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Single-row table holding the primary collector's checkpoint.
 *
 * Lives in Room (not SharedPreferences) so it is committed in the SAME transaction
 * as the usage row it belongs to: after a crash, either both exist or neither does,
 * and a restart can never count already-saved bytes again.
 */
@Entity(tableName = "collection_checkpoint")
data class CollectionCheckpoint(
    @PrimaryKey
    val id: Int = 0,

    /** TrafficStats mobile RX/TX readings of the last committed tick (-1 = no baseline yet) */
    @ColumnInfo(name = "mobile_rx")
    val mobileRx: Long = -1L,

    @ColumnInfo(name = "mobile_tx")
    val mobileTx: Long = -1L,

    /** SystemClock.elapsedRealtime() of the last committed tick; monotonic, resets on reboot */
    @ColumnInfo(name = "last_tick_elapsed")
    val lastTickElapsed: Long = -1L,

    /** Wall-clock epoch millis of the last committed tick */
    @ColumnInfo(name = "last_tick_wall")
    val lastTickWall: Long = -1L,

    /**
     * elapsedRealtime / wall clock of the reading that set [mobileRx]/[mobileTx]. Differs
     * from the last tick only after UNSUPPORTED ticks; the next row's interval starts here,
     * so its bytes and its duration cover the same span.
     */
    @ColumnInfo(name = "counter_elapsed", defaultValue = "-1")
    val counterElapsed: Long = -1L,

    @ColumnInfo(name = "counter_wall", defaultValue = "-1")
    val counterWall: Long = -1L
)

@Dao
interface CheckpointDao {

    @Query("SELECT * FROM collection_checkpoint WHERE id = 0")
    suspend fun get(): CollectionCheckpoint?

    @Upsert
    suspend fun upsert(checkpoint: CollectionCheckpoint)

    @Query("DELETE FROM collection_checkpoint")
    suspend fun clear()
}
