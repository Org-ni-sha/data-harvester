package com.capstone.dataharvester

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capstone.dataharvester.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration 10 → 11, validated against the exported schemas/11.json.
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
}
