package com.capstone.dataharvester.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 *  - v7: Added Fix Limit problem and added network provider
 *  - v8: Added device_identity and promo_record tables for new features
 */
@Database(
    entities = [UsageRecord::class, AppUsageRecord::class, DeviceIdentity::class, PromoRecord::class],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun deviceIdentityDao(): DeviceIdentityDao
    abstract fun promoRecordDao(): PromoRecordDao

    companion object {
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
         * Get the singleton database instance.
         * Thread-safe via double-checked locking.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "data_harvester_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
