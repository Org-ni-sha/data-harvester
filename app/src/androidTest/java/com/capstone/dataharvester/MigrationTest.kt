package com.capstone.dataharvester

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.data.DatabaseGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migrations, validated against the exported schemas/<version>.json, and what
 * happens when an update lacks one.
 * Run on a device or emulator: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate10To11_keepsSyncFlagsAndTagsLegacyRows() {
        helper.createDatabase(dbName, 10).use { db ->
            // one uploaded and one pending legacy row in each table
            for ((id, synced) in listOf(1 to 1, 2 to 0)) {
                db.execSQL(
                    "INSERT INTO usage_records (id, timestamp, datetime_str, hour, minute, day_of_week, " +
                        "is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, " +
                        "network_type, screen_on, battery_level, device_id, signal_strength, is_charging, " +
                        "device_model, network_operator, utc_offset_minutes, is_synced) VALUES " +
                        "($id, $id, '2026-10-29T10:00:0$id', 10, 0, 4, 0, 'Morning', 1, 1, 2, 0.0, 0.0, " +
                        "'LTE', 1, 80, 'dev', -90, 0, 'Model', 'Globe', 480, $synced)"
                )
                db.execSQL(
                    "INSERT INTO app_usage_records (id, timestamp, datetime_str, device_id, package_name, app_name, " +
                        "uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time, end_time, " +
                        "is_system_app, utc_offset_minutes, is_synced) VALUES " +
                        "($id, $id, 'x', 'dev', 'pkg', 'App', 10100, 1, 1, 2, 'MOBILE', '', '', '', 0, 480, $synced)"
                )
            }
        }

        // validateDroppedTables = true: fails if the migrated schema differs from 11.json
        val db = helper.runMigrationsAndValidate(dbName, 11, true, AppDatabase.MIGRATION_10_11)

        for (table in listOf("usage_records", "app_usage_records")) {
            db.query("SELECT id, is_synced, collection_version, record_uid, measurement_status FROM $table ORDER BY id")
                .use { c ->
                    val uids = mutableSetOf<String>()
                    while (c.moveToNext()) {
                        val id = c.getInt(0)
                        // Option A: is_synced untouched → each row ends up in exactly one cloud DB
                        assertEquals("$table #$id is_synced", if (id == 1) 1 else 0, c.getInt(1))
                        assertEquals("legacy", c.getString(2))
                        assertTrue("$table #$id record_uid", c.getString(3).length == 32)
                        uids += c.getString(3)
                        assertEquals("LEGACY", c.getString(4))
                    }
                    assertEquals("$table record_uids unique", 2, uids.size)
                }
        }
        db.query("SELECT COUNT(*) FROM collection_checkpoint").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun migrate11To12_leavesPendingRowsOutsideTheStudy() {
        helper.createDatabase(dbName, 11).use { db ->
            db.execSQL(
                "INSERT INTO usage_records (id, timestamp, datetime_str, hour, minute, day_of_week, " +
                    "is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, " +
                    "network_type, screen_on, battery_level, device_id, signal_strength, is_charging, " +
                    "device_model, network_operator, utc_offset_minutes, is_synced, interval_start, " +
                    "interval_end, elapsed_ms, measurement_status, active_network_at_sample, " +
                    "collection_version, record_uid) VALUES " +
                    "(1, 1, 'x', 10, 0, 4, 0, 'Morning', 1, 1, 2, 0.0, 0.0, 'LTE', 1, 80, 'dev', -90, 0, " +
                    "'Model', 'Globe', 480, 0, 0, 1, 30000, 'OK', 'LTE', '1.6.3', 'uid-usage')"
            )
            db.execSQL(
                "INSERT INTO app_usage_records (id, timestamp, datetime_str, device_id, package_name, app_name, " +
                    "uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time, end_time, " +
                    "is_system_app, utc_offset_minutes, is_synced, measurement_status, collection_version, " +
                    "record_uid) VALUES " +
                    "(1, 1, 'x', 'dev', 'pkg', 'App', 10100, 1, 1, 2, 'MOBILE', '', '', '', 0, 480, 0, " +
                    "'OK', '1.6.3', 'uid-app')"
            )
            db.execSQL(
                "INSERT INTO collection_checkpoint (id, mobile_rx, mobile_tx, last_tick_elapsed, last_tick_wall) " +
                    "VALUES (0, 1000, 2000, 5000, 1700000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, AppDatabase.MIGRATION_11_12)

        for (table in listOf("usage_records", "app_usage_records")) {
            db.query("SELECT is_synced, study_id, schema_version FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                // Still pending, but in no study: the upload query (study_id = enrolled study) skips it
                assertEquals("$table is_synced", 0, c.getInt(0))
                assertEquals("$table study_id", "", c.getString(1))
                // Tagged with an app version (1.6.3) → produced by schema-1 rules
                assertEquals("$table schema_version", 1, c.getInt(2))
            }
        }
        // The v11 checkpoint was set by a successful tick: its counter time is that tick
        db.query("SELECT counter_elapsed, counter_wall FROM collection_checkpoint").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5000L, c.getLong(0))
            assertEquals(1700000000000L, c.getLong(1))
        }
    }

    @Test
    fun missingMigration_isReportedAndLeavesTheRecordsForTheFixedUpdate() {
        val name = "guard-test"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 11).use { db ->
            db.execSQL(
                "INSERT INTO usage_records (id, timestamp, datetime_str, hour, minute, day_of_week, " +
                    "is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used, cumulative_mb_today, " +
                    "network_type, screen_on, battery_level, device_id, signal_strength, is_charging, " +
                    "device_model, network_operator, utc_offset_minutes, is_synced, interval_start, " +
                    "interval_end, elapsed_ms, measurement_status, active_network_at_sample, " +
                    "collection_version, record_uid) VALUES " +
                    "(1, 1, 'x', 10, 0, 4, 0, 'Morning', 1, 1, 2, 0.0, 0.0, 'LTE', 1, 80, 'dev', -90, 0, " +
                    "'Model', 'Globe', 480, 0, 0, 1, 30000, 'OK', 'LTE', '1.6.3', 'uid-usage')"
            )
        }

        // A build that forgot MIGRATION_11_12: no crash, no wipe — just a reported problem
        var broken: AppDatabase? = null
        val problem = DatabaseGuard.tryOpen(
            open = {
                Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(AppDatabase.MIGRATION_10_11)
                    .build()
                    .also { broken = it }
            },
            close = { broken?.close() }
        )
        assertNotNull(problem)

        // The fixed update opens the same file and finds the record
        val fixed = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            assertEquals(1, runBlocking { fixed.usageDao().getCount() })
        } finally {
            fixed.close()
            context.deleteDatabase(name)
        }
    }
}
