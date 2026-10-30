package com.capstone.dataharvester.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.capstone.dataharvester.util.StudyManager

/**
 * Room database for the Data Harvester app.
 * Contains two tables: usage_records and app_usage_records.
 *
 * Uses the singleton pattern to ensure only one database instance exists app-wide.
 *
 * Version history:
 *  - v1: Initial schema with usage_records table
 *  - v2: Added device_id, signal_strength, is_charging, device_model columns
 *         to usage_records + new app_usage_records table
 *  - v3: Added query_start column to app_usage_records for network-switch snapshots
 *  - v4: Added upload_history table for tracking cloud sync operations
 *  - v5: APK versioning for app updates
 *  - v6: Added start_time and end_time columns to app_usage_records for tracking network-switch snapshots
 *  - v7: Internal sync architecture upgrades (empty placeholder)
 *  - v8: Added device_identity and promo_record tables for new features
 *  - v9: Added network_operator, utc_offset_minutes, and synced promo_records
 *  - v10: Replaced the stored ANDROID_ID in device_identity.hardware_id with the install UUID;
 *         repairs is_synced on installs upgraded from v1-v8; dropped promo_records (unused)
 *  - v11: collection_checkpoint + app_usage_baseline tables (checkpoints committed with
 *         their rows); interval/quality columns, record_uid and collection_version
 *  - v12: study_id and schema_version on both record tables (only rows of the enrolled
 *         study are uploaded);
 *         counter_elapsed / counter_wall on collection_checkpoint
 *  - v13: rows already on the phone join the official study (every row collected by
 *         app 1.6.1 or later belongs to it); no schema change
 */
@Database(
    entities = [
        UsageRecord::class,
        AppUsageRecord::class,
        DeviceIdentity::class,
        CollectionCheckpoint::class,
        AppUsageBaseline::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun deviceIdentityDao(): DeviceIdentityDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun appUsageBaselineDao(): AppUsageBaselineDao

    companion object {
        /**
         * Schema version. Raising it needs a MIGRATION_<old>_<new> in [ALL_MIGRATIONS]:
         * MigrationCoverageTest (run by publishUpdate) fails otherwise.
         */
        const val VERSION = 13

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 to v2:
         * - Adds 4 new columns to usage_records
         * - Creates new app_usage_records table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns to existing usage_records table
                db.execSQL("ALTER TABLE usage_records ADD COLUMN device_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN signal_strength INTEGER NOT NULL DEFAULT -999")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN is_charging INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN device_model TEXT NOT NULL DEFAULT ''")

                // Create new app_usage_records table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_usage_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        datetime_str TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        package_name TEXT NOT NULL,
                        app_name TEXT NOT NULL,
                        uid INTEGER NOT NULL,
                        bytes_rx INTEGER NOT NULL,
                        bytes_tx INTEGER NOT NULL,
                        bytes_total INTEGER NOT NULL,
                        network_type TEXT NOT NULL,
                        is_system_app INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * Migration from v2 to v3:
         * - Adds query_start column to app_usage_records
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_usage_records ADD COLUMN query_start TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Migration from v3 to v4:
         * - No local schema changes (upload_history is a remote cloud-only table)
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No local changes required
            }
        }

        /**
         * Migration from v4 to v5:
         * - No local schema changes (APK versioning update)
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No local changes required
            }
        }

        /**
         * Migration from v5 to v6:
         * - Adds start_time and end_time columns to app_usage_records
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_usage_records ADD COLUMN start_time TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_usage_records ADD COLUMN end_time TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Migration from v6 to v7:
         * - Prep for device_identity and promo_record tables
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Future tables for promos and device ID matching
            }
        }
        
        /**
         * Migration from v7 to v8:
         * - Creates device_identity and promo_records tables
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS device_identity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        current_device_id TEXT NOT NULL,
                        previous_device_id TEXT,
                        hardware_id TEXT NOT NULL,
                        device_model TEXT NOT NULL,
                        network_provider TEXT NOT NULL DEFAULT 'Unknown',
                        linked_at INTEGER NOT NULL,
                        is_synced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_identity_hardware_id ON device_identity(hardware_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_identity_current_device_id ON device_identity(current_device_id)")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS promo_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        device_id TEXT NOT NULL,
                        promo_name TEXT NOT NULL,
                        network_provider TEXT NOT NULL,
                        data_allowance_mb INTEGER NOT NULL,
                        validity_days INTEGER NOT NULL,
                        price REAL NOT NULL,
                        date_availed INTEGER NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        is_synced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                db.execSQL("CREATE INDEX IF NOT EXISTS index_promo_records_device_id ON promo_records(device_id)")
            }
        }
        
        /**
         * Migration from v8 to v9:
         * - Adds network_operator and utc_offset_minutes to usage_records
         * - Adds utc_offset_minutes to app_usage_records
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_records ADD COLUMN network_operator TEXT NOT NULL DEFAULT 'Unknown'")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN utc_offset_minutes INTEGER NOT NULL DEFAULT 480")
                db.execSQL("ALTER TABLE app_usage_records ADD COLUMN utc_offset_minutes INTEGER NOT NULL DEFAULT 480")
                
                // Migrate promo_records from v8 schema to v9 schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS promo_records_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        device_id TEXT NOT NULL,
                        promo_name TEXT NOT NULL,
                        promo_price REAL NOT NULL,
                        promo_duration INTEGER NOT NULL,
                        promo_data_allowance REAL NOT NULL,
                        custom_data_allowance REAL NOT NULL,
                        has_unlimited_data INTEGER NOT NULL,
                        sms_allocation TEXT NOT NULL,
                        call_allocation TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        start_date TEXT NOT NULL,
                        expiry_date TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        is_synced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                db.execSQL("""
                    INSERT INTO promo_records_new (
                        id, device_id, promo_name, promo_price, promo_duration, promo_data_allowance, 
                        custom_data_allowance, has_unlimited_data, sms_allocation, call_allocation, 
                        provider, start_date, expiry_date, created_at, status, is_synced
                    )
                    SELECT 
                        id, device_id, promo_name, price, validity_days, data_allowance_mb, 
                        0.0, 0, 'None', 'None', 
                        network_provider, '', '', date_availed, 
                        CASE WHEN is_active = 1 THEN 'Active' ELSE 'Expired' END, is_synced
                    FROM promo_records
                """.trimIndent())
                
                db.execSQL("DROP TABLE promo_records")
                db.execSQL("ALTER TABLE promo_records_new RENAME TO promo_records")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_promo_records_device_id ON promo_records(device_id)")
            }
        }

        /** ALTER TABLE ADD COLUMN fails if the column exists, so check first. */
        private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return
                }
            }
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }

        /**
         * Migration from v9 to v10:
         * - Stops storing the hardware ANDROID_ID: hardware_id now holds the random install
         *   UUID (current_device_id). Rows are marked unsynced so the cloud copy is replaced.
         * - Adds is_synced to usage_records / app_usage_records when missing. No earlier
         *   migration added it, so installs upgraded from v1-v8 failed Room validation.
         * - Drops promo_records: the study no longer collects promos.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE device_identity SET hardware_id = current_device_id, is_synced = 0")
                addColumnIfMissing(db, "usage_records", "is_synced", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "app_usage_records", "is_synced", "INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP TABLE IF EXISTS promo_records")
            }
        }

        /**
         * Migration from v10 to v11:
         * - collection_checkpoint / app_usage_baseline: collection checkpoints move from
         *   SharedPreferences into Room so they commit together with their rows
         *   (DataCollectionService copies the old prefs values over once)
         * - usage_records: interval start/end, elapsed_ms, measurement_status and the raw
         *   active network, so delayed, baseline and reset samples are distinguishable
         * - both record tables: collection_version and a random record_uid per row
         *
         * is_synced is deliberately NOT touched (Option A of the cloud transition):
         * synced rows are already in DATAra_harvester and stay out of Dataharvester_v2;
         * pending rows are in neither database and upload to v2 tagged 'legacy'.
         * (Superseded by MIGRATION_11_12: pending pre-study rows are no longer uploaded.)
         */
        // internal: exercised by MigrationTest in androidTest
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_checkpoint` (`id` INTEGER NOT NULL, " +
                        "`mobile_rx` INTEGER NOT NULL, `mobile_tx` INTEGER NOT NULL, " +
                        "`last_tick_elapsed` INTEGER NOT NULL, `last_tick_wall` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_usage_baseline` (`uid` INTEGER NOT NULL, " +
                        "`transport` TEXT NOT NULL, `anchor` INTEGER NOT NULL, `rx` INTEGER NOT NULL, " +
                        "`tx` INTEGER NOT NULL, `taken_at` INTEGER NOT NULL, PRIMARY KEY(`uid`, `transport`))"
                )

                addColumnIfMissing(db, "usage_records", "interval_start", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "usage_records", "interval_end", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "usage_records", "elapsed_ms", "INTEGER NOT NULL DEFAULT -1")
                addColumnIfMissing(db, "usage_records", "measurement_status", "TEXT NOT NULL DEFAULT 'LEGACY'")
                addColumnIfMissing(db, "usage_records", "active_network_at_sample", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "app_usage_records", "measurement_status", "TEXT NOT NULL DEFAULT 'LEGACY'")

                for (table in listOf("usage_records", "app_usage_records")) {
                    addColumnIfMissing(db, table, "collection_version", "TEXT NOT NULL DEFAULT 'legacy'")
                    addColumnIfMissing(db, table, "record_uid", "TEXT NOT NULL DEFAULT ''")
                    // SQLite has no uuid(); 16 random bytes as hex is just as unique
                    db.execSQL("UPDATE $table SET record_uid = lower(hex(randomblob(16))) WHERE record_uid = ''")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_record_uid` ON `$table` (`record_uid`)")
                }
            }
        }

        /**
         * Migration from v11 to v12:
         * - schema_version on both record tables: 1 for rows tagged with an app version
         *   (1.6.3 rules), 0 for older rows; new rows get MeasurementRules.SCHEMA_VERSION.
         * - study_id on both record tables. Every existing row gets '' (collected before
         *   enrollment in the official study), so pending rows from older versions are
         *   kept on the phone and in CSV exports but are never uploaded to Dataharvester_v2.
         *   This replaces the v11 plan of uploading them tagged 'legacy'.
         * - collection_checkpoint.counter_elapsed / counter_wall: when the byte baseline was
         *   read, kept apart from the last tick so an UNSUPPORTED tick cannot shorten the
         *   next row's duration. Existing checkpoints were last set by a tick, so copy it.
         */
        // internal: exercised by MigrationTest in androidTest
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "collection_checkpoint", "counter_elapsed", "INTEGER NOT NULL DEFAULT -1")
                addColumnIfMissing(db, "collection_checkpoint", "counter_wall", "INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    "UPDATE collection_checkpoint SET counter_elapsed = last_tick_elapsed, " +
                        "counter_wall = last_tick_wall WHERE mobile_rx >= 0"
                )

                for (table in listOf("usage_records", "app_usage_records")) {
                    addColumnIfMissing(db, table, "study_id", "TEXT NOT NULL DEFAULT ''")
                    // Rows tagged with an app version came from 1.6.3's rules (schema 1)
                    addColumnIfMissing(db, table, "schema_version", "INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET schema_version = 1 WHERE collection_version != 'legacy'")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_${table}_study_id_is_synced` " +
                            "ON `$table` (`study_id`, `is_synced`)"
                    )
                }
            }
        }

        /**
         * v12 → v13 (app 1.6.5): there is no separate enrollment step any more; every row
         * collected by app 1.6.1 or later belongs to the official study. Rows saved before
         * this version (study_id '') join it. is_synced is untouched: rows already uploaded
         * are not sent again. The identity row is sent again so the cloud copy carries the
         * study and its start time.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("usage_records", "app_usage_records")) {
                    db.execSQL(
                        "UPDATE $table SET study_id = ? WHERE study_id = ''",
                        arrayOf(StudyManager.OFFICIAL_STUDY_ID)
                    )
                }
                db.execSQL("UPDATE device_identity SET is_synced = 0")
            }
        }

        /** Every migration, oldest first. A new schema version adds its migration here. */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13
            )

        /**
         * Get the singleton database instance.
         * Thread-safe via double-checked locking.
         *
         * Deliberately no fallbackToDestructiveMigration: a missing or broken migration
         * must never wipe a participant's records. Open it through [DatabaseGuard], which
         * turns that failure into a "waiting for a fixed update" state instead of a crash.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "data_harvester_db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Closes and drops the instance, so the next [getInstance] opens the file again. */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
