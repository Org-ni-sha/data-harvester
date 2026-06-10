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
 */
@Database(
    entities = [UsageRecord::class, AppUsageRecord::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao
    abstract fun appUsageDao(): AppUsageDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration() // <-- Add this line
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
