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
-- uploads carries record_uid, a random id generated on the phone: it is the
-- ONLY uniqueness key, so a retry after a lost response is ignored instead of
-- duplicated, and the app marks a row synced only after reading its record_uid
-- back. (device_id, local_id) and (device_id, timestamp) are plain indexes: local
-- ids can repeat after a backup restore and two ticks can share a millisecond, and
-- a unique key there silently dropped real rows. No uniqueness rule uses
-- package_name + timestamp: one snapshot has a MOBILE and a WIFI row for the same
-- app and time, and both are real measurements.
--
-- An existing v2 database is upgraded with cloud_schema_v2_upgrade_1.6.3.sql.
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
    received_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    record_uid TEXT,
    -- interval the bytes belong to; elapsed_ms is monotonic (-1 = unknown)
    interval_start INTEGER NOT NULL DEFAULT 0,
    interval_end INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT -1,
    -- OK / BASELINE / COUNTER_RESET / UNSUPPORTED / GAP / LEGACY
    measurement_status TEXT NOT NULL DEFAULT 'LEGACY',
    -- raw active network at the tick; network_type is the cleaning label
    active_network_at_sample TEXT NOT NULL DEFAULT '',
    -- app version that collected the row; 'legacy' = before 1.6.3
    collection_version TEXT NOT NULL DEFAULT 'legacy'
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_record_uid
    ON usage_records (record_uid);
CREATE INDEX IF NOT EXISTS idx_usage_records_device_local
    ON usage_records (device_id, local_id);
CREATE INDEX IF NOT EXISTS idx_usage_records_device_timestamp
    ON usage_records (device_id, timestamp);
-- Rows from 1.6.1 phones (record_uid NULL) keep their old retry protection:
-- a 1.6.1 retry after a lost response must still be ignored, not duplicated.
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_legacy_device_local
    ON usage_records (device_id, local_id) WHERE record_uid IS NULL;

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
    received_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    record_uid TEXT,
    -- OK / COUNTER_RESET / LEGACY
    measurement_status TEXT NOT NULL DEFAULT 'LEGACY',
    collection_version TEXT NOT NULL DEFAULT 'legacy'
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_record_uid
    ON app_usage_records (record_uid);
CREATE INDEX IF NOT EXISTS idx_app_usage_records_device_local
    ON app_usage_records (device_id, local_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_legacy_device_local
    ON app_usage_records (device_id, local_id) WHERE record_uid IS NULL;
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
