package com.capstone.dataharvester.util

import androidx.room.withTransaction
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.CollectionCheckpoint
import com.capstone.dataharvester.data.CollectionLocks
import com.capstone.dataharvester.data.UsageRecord
import kotlinx.coroutines.sync.withLock

/**
 * One primary tick from checkpoint to committed row, outside the Service so the
 * instrumented tests can run overlapping ticks and failed saves against a real Room.
 *
 * Holds [CollectionLocks.db] from loading the checkpoint to committing the new one, and
 * reads the counters and clocks inside it: two overlapping triggers (timer + network
 * switch) therefore run one after the other and never count the same bytes twice.
 * The row and the checkpoint commit in ONE transaction, so after a crash either both
 * exist or neither does.
 */
class PrimaryCollector(
    private val db: AppDatabase,
    /** Checkpoint to start from when the table is empty (e.g. the pre-v11 prefs hand-over) */
    private val initialCheckpoint: suspend () -> CollectionCheckpoint = { CollectionCheckpoint() }
) {

    /** Clock readings for one tick: SystemClock.elapsedRealtime() and wall-clock millis */
    data class Now(val elapsed: Long, val wall: Long, val datePrefix: String)

    data class Committed(val tick: PrimaryTick.Result, val record: UsageRecord)

    suspend fun tick(
        now: () -> Now,
        readCounters: () -> PrimaryTick.Counters,
        buildRecord: (tick: PrimaryTick.Result, now: Now, cumulativeMbToday: Double) -> UsageRecord
    ): Committed = CollectionLocks.db.withLock {
        val cp = db.checkpointDao().get() ?: initialCheckpoint()
        val time = now()
        val tick = PrimaryTick.compute(cp, readCounters(), time.elapsed, time.wall)

        db.withTransaction {
            val cumulative = db.usageDao().getTodaySum(time.datePrefix) + tick.bytesTotal / 1048576.0
            val record = buildRecord(tick, time, cumulative)
            db.usageDao().insert(record)
            db.checkpointDao().upsert(tick.checkpoint)
            Committed(tick, record)
        }
    }
}
