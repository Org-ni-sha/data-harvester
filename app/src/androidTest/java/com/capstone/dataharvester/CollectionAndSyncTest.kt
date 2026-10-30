package com.capstone.dataharvester

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.DeviceIdentity
import com.capstone.dataharvester.data.UsageRecord
import com.capstone.dataharvester.sync.CloudSyncManager
import com.capstone.dataharvester.sync.SyncResult
import com.capstone.dataharvester.sync.UploadAck
import com.capstone.dataharvester.sync.UploadBatch
import com.capstone.dataharvester.sync.UploadTransport
import com.capstone.dataharvester.util.PrimaryCollector
import com.capstone.dataharvester.util.PrimaryTick
import com.capstone.dataharvester.util.StudyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Collection and upload against a real (in-memory) Room database:
 * interrupted saves, overlapping triggers, study filtering, retry acknowledgements and
 * the hotspot pseudo-app.
 * Run on a device or emulator: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CollectionAndSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val study = StudyManager.OFFICIAL_STUDY_ID

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        context.getSharedPreferences("study_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("study_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── collection ─────────────────────────────────────────────────────────

    private fun record(tick: PrimaryTick.Result, now: PrimaryCollector.Now, uid: String? = null) = UsageRecord(
        timestamp = now.wall, datetimeStr = "x", hour = 0, minute = 0, dayOfWeek = 1, isWeekend = 0,
        timePeriod = "Night", bytesRx = tick.bytesRx, bytesTx = tick.bytesTx, bytesTotal = tick.bytesTotal,
        mbUsed = 0.0, cumulativeMbToday = 0.0, networkType = "LTE", screenOn = 1, batteryLevel = 50,
        measurementStatus = tick.status, studyId = study
    ).let { if (uid != null) it.copy(recordUid = uid) else it }

    @Test
    fun overlappingTicks_countEveryByteExactlyOnce() = runBlocking {
        val collector = PrimaryCollector(db)
        val counter = AtomicLong(1_000)
        val clock = AtomicLong(1_000_000)
        // 20 triggers at once (timer + network switches): each read moves the counter by 100
        (1..20).map {
            async(Dispatchers.Default) {
                collector.tick(
                    now = { clock.addAndGet(1_000).let { PrimaryCollector.Now(it, 1_793_000_000_000 + it, "x") } },
                    readCounters = { PrimaryTick.Counters(counter.addAndGet(100), 0) },
                    buildRecord = { tick, now, _ -> record(tick, now) }
                )
            }
        }.awaitAll()

        val rows = db.usageDao().getAllAscending()
        assertEquals(20, rows.size)
        assertEquals(1, rows.count { it.measurementStatus == "BASELINE" })
        // From the baseline reading (1,100) to the last (3,000): every byte once
        assertEquals(1_900L, rows.sumOf { it.bytesTotal })
        assertEquals(counter.get(), db.checkpointDao().get()!!.mobileRx)
    }

    @Test
    fun failedSave_leavesNoRowAndNoCheckpoint_soTheBytesCarryOver() = runBlocking {
        val collector = PrimaryCollector(db)
        val first = collector.tick(
            now = { PrimaryCollector.Now(100_000, 1_793_000_100_000, "x") },
            readCounters = { PrimaryTick.Counters(1_000, 0) },
            buildRecord = { tick, now, _ -> record(tick, now, uid = "dup") }
        )
        assertEquals("BASELINE", first.tick.status)

        // The next save fails inside the transaction (record_uid collides → insert throws)
        val failed = runCatching {
            collector.tick(
                now = { PrimaryCollector.Now(130_000, 1_793_000_130_000, "x") },
                readCounters = { PrimaryTick.Counters(1_500, 0) },
                buildRecord = { tick, now, _ -> record(tick, now, uid = "dup") }
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(1, db.usageDao().getCount())
        assertEquals(1_000L, db.checkpointDao().get()!!.mobileRx) // checkpoint did not move

        // The following tick counts the 500 bytes of the failed one, once
        val next = collector.tick(
            now = { PrimaryCollector.Now(160_000, 1_793_000_160_000, "x") },
            readCounters = { PrimaryTick.Counters(1_800, 0) },
            buildRecord = { tick, now, _ -> record(tick, now) }
        )
        assertEquals(800L, next.tick.bytesTotal)
        assertEquals(60_000L, next.tick.elapsedMs)
    }

    @Test
    fun tetheringUid_isRecordedAsTheHotspotRow() {
        val app = com.capstone.dataharvester.util.NetworkStatsHelper(context)
            .resolveApp(android.app.usage.NetworkStats.Bucket.UID_TETHERING)
        assertEquals(com.capstone.dataharvester.util.NetworkStatsHelper.HOTSPOT_PACKAGE, app.packageName)
        assertEquals("Hotspot (tethering)", app.appName)
    }

    // ── upload ─────────────────────────────────────────────────────────────

    /** Records every batch; answers with [respond] (default: confirm everything). */
    private class FakeTransport(var respond: (UploadBatch) -> UploadAck = { b ->
        UploadAck(
            b.usage.map { it["record_uid"] as String }.toSet(),
            b.app.map { it["record_uid"] as String }.toSet(),
            identityConfirmed = b.identity != null
        )
    }) : UploadTransport {
        val batches = mutableListOf<UploadBatch>()
        override fun upload(batch: UploadBatch): UploadAck {
            batches += batch
            return respond(batch)
        }
    }

    private suspend fun insertRows(studyId: String, n: Int) = (1..n).map {
        UsageRecord(
            timestamp = it.toLong(), datetimeStr = "x", hour = 0, minute = 0, dayOfWeek = 1, isWeekend = 0,
            timePeriod = "Night", bytesRx = 1, bytesTx = 1, bytesTotal = 2, mbUsed = 0.0, cumulativeMbToday = 0.0,
            networkType = "LTE", screenOn = 1, batteryLevel = 50, studyId = studyId
        ).also { db.usageDao().insert(it) }.recordUid
    }

    private fun unsyncedUids() = runBlocking { db.usageDao().getAll().filter { !it.isSynced }.map { it.recordUid }.toSet() }

    @Test
    fun rowsOutsideTheStudy_areNeverUploaded() = runBlocking {
        // Only possible for rows written outside MIGRATION_12_13 / the collector; still filtered
        val old = insertRows("", 5)
        val official = insertRows(study, 3)
        val transport = FakeTransport()

        val result = CloudSyncManager(context, db, transport).syncPendingData()

        assertEquals(SyncResult.Success(3, 0), result)
        val sent = transport.batches.flatMap { b -> b.usage.map { it["record_uid"] as String } }
        assertEquals(official.toSet(), sent.toSet())
        assertEquals(old.toSet(), unsyncedUids()) // kept on the phone, still unsynced
    }

    @Test
    fun partialAcknowledgement_marksOnlyConfirmedRows_andTheRetrySendsTheRest() = runBlocking {
        val uids = insertRows(study, 4)
        val transport = FakeTransport()
        transport.respond = { b ->
            // The server holds only the first two rows after this request
            UploadAck(b.usage.map { it["record_uid"] as String }.take(2).toSet(), emptySet(), b.identity != null)
        }

        val first = CloudSyncManager(context, db, transport).syncPendingData()
        assertTrue(first is SyncResult.Failure)
        assertEquals(uids.drop(2).toSet(), unsyncedUids())

        transport.respond = FakeTransport().respond
        val retry = CloudSyncManager(context, db, transport).syncPendingData()
        assertEquals(SyncResult.Success(2, 0), retry)
        assertEquals(uids.drop(2), transport.batches.last().usage.map { it["record_uid"] })
        assertTrue(unsyncedUids().isEmpty())
    }

    @Test
    fun transportError_marksNothing() = runBlocking {
        val uids = insertRows(study, 2)
        val transport = FakeTransport { throw java.io.IOException("connection reset") }
        assertTrue(CloudSyncManager(context, db, transport).syncPendingData() is SyncResult.Failure)
        assertEquals(uids.toSet(), unsyncedUids())
    }

    @Test
    fun serverRejectedRows_areSetAsideAndNotResent() = runBlocking {
        val uids = insertRows(study, 3)
        val transport = FakeTransport { b ->
            val sent = b.usage.map { it["record_uid"] as String }
            UploadAck(sent.drop(1).toSet(), emptySet(), b.identity != null, usageRejected = mapOf(sent[0] to "bad value"))
        }

        assertEquals(SyncResult.Success(2, 0), CloudSyncManager(context, db, transport).syncPendingData())
        assertEquals(0, db.usageDao().countUnsynced(study))
        transport.batches.clear()
        CloudSyncManager(context, db, transport).syncPendingData()
        assertTrue(transport.batches.none { b -> b.usage.any { it["record_uid"] == uids[0] } })
    }

    @Test
    fun deviceIdentity_staysPendingUntilTheServerConfirmsIt() = runBlocking {
        db.deviceIdentityDao().insert(
            DeviceIdentity(
                current_device_id = "d", previous_device_id = null, hardware_id = "d",
                device_model = "m", linked_at = 1
            )
        )
        val transport = FakeTransport { b -> UploadAck(emptySet(), emptySet(), identityConfirmed = false) }
        assertTrue(CloudSyncManager(context, db, transport).syncPendingData() is SyncResult.Failure)
        assertEquals(1, db.deviceIdentityDao().getAllUnsynced().size)

        transport.respond = { b -> UploadAck(emptySet(), emptySet(), identityConfirmed = b.identity != null) }
        assertEquals(SyncResult.Success(0, 0), CloudSyncManager(context, db, transport).syncPendingData())
        assertTrue(db.deviceIdentityDao().getAllUnsynced().isEmpty())
    }
}
