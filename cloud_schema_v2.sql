-- Cloud schema for the Dataharvester_v2 database (SQLite Cloud).
-- Fresh start: nothing is copied from the old DATAra_harvester.sqlite database,
-- which is left untouched. cloud_migration.sql / cloud_migration_v10.sql are for
-- the OLD database only; do not run them here.
--
-- Safe to re-run (IF NOT EXISTS everywhere). Must be applied BEFORE any APK that
-- points at Dataharvester_v2 uploads: CloudSyncManager sends each batch as one
-- SQL request, and a missing column or ON CONFLICT target fails the whole batch.
--
-- Columns mirror exactly what CloudSyncManager inserts. Every row a phone
-- uploads carries (device_id, local_id), the phone's Room row id, so retries
-- after a lost response are ignored instead of duplicated. No uniqueness rule
-- uses package_name + timestamp: one snapshot has a MOBILE and a WIFI row for
-- the same app and time, and both are real measurements.
--
-- received_at is filled by the server (epoch millis) and is never sent by the app.

-- 1. Device-wide usage, one row per 30-second collection tick.
CREATE TABLE IF NOT EXISTS usage_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    datetime_str TEXT NOT NULL,
    hour INTEGER NOT NULL,
    minute INTEGER NOT NULL,
    day_of_week INTEGER NOT NULL,
    is_weekend INTEGER NOT NULL,
    time_period TEXT NOT NULL,
    bytes_rx INTEGER NOT NULL,
    bytes_tx INTEGER NOT NULL,
    bytes_total INTEGER NOT NULL,
    mb_used REAL NOT NULL,
    cumulative_mb_today REAL NOT NULL,
    network_type TEXT NOT NULL,
    screen_on INTEGER NOT NULL,
    battery_level INTEGER NOT NULL,
    signal_strength INTEGER NOT NULL DEFAULT -999,
    is_charging INTEGER NOT NULL DEFAULT 0,
    device_model TEXT NOT NULL DEFAULT '',
    network_operator TEXT NOT NULL DEFAULT 'Unknown',
    utc_offset_minutes INTEGER NOT NULL DEFAULT 480,
    received_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_device_local
    ON usage_records (device_id, local_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_device_timestamp
    ON usage_records (device_id, timestamp);

-- 2. Per-app usage, one row per app per network type per collection window.
CREATE TABLE IF NOT EXISTS app_usage_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    datetime_str TEXT NOT NULL,
    package_name TEXT NOT NULL,
    app_name TEXT NOT NULL,
    uid INTEGER NOT NULL,
    bytes_rx INTEGER NOT NULL,
    bytes_tx INTEGER NOT NULL,
    bytes_total INTEGER NOT NULL,
    network_type TEXT NOT NULL,
    query_start TEXT NOT NULL DEFAULT '',
    start_time TEXT NOT NULL DEFAULT '',
    end_time TEXT NOT NULL DEFAULT '',
    is_system_app INTEGER NOT NULL,
    utc_offset_minutes INTEGER NOT NULL DEFAULT 480,
    received_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_device_local
    ON app_usage_records (device_id, local_id);
CREATE INDEX IF NOT EXISTS idx_app_usage_records_device_timestamp
    ON app_usage_records (device_id, timestamp);

-- 3. One row per app install. device_id is the random install UUID; no
--    hardware identifier (ANDROID_ID) is stored.
CREATE TABLE IF NOT EXISTS device_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    device_model TEXT NOT NULL,
    network_provider TEXT NOT NULL DEFAULT 'Unknown',
    app_version TEXT NOT NULL DEFAULT '',
    linked_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_identity_device_id
    ON device_identity (device_id);

-- 4. One row per successful upload batch.
CREATE TABLE IF NOT EXISTS upload_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    uploaded_timestamp INTEGER NOT NULL,
    datetime_str TEXT NOT NULL DEFAULT '',
    datetime_stamp TEXT NOT NULL DEFAULT '',
    records_uploaded INTEGER NOT NULL,
    payload_size_bytes INTEGER NOT NULL,
    payload_size_kb REAL NOT NULL,
    app_version TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_upload_history_device_id
    ON upload_history (device_id);

-- 5. Readable respondent IDs (R001...) for the manuscript; filled at export time.
CREATE TABLE IF NOT EXISTS respondent_map (
    device_id TEXT PRIMARY KEY,
    respondent_id TEXT NOT NULL,
    enrolled_at TEXT
);
