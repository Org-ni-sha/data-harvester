-- Dataharvester_v2 as deployed on 2026-10-30 (read from sqlite_master): cloud_schema_v2
-- plus cloud_schema_v2_upgrade_1.6.3.sql, but WITHOUT its two partial unique indexes.
-- Used by records.test.js to check that the 1.6.4 upgrade script lands on exactly the
-- columns of cloud_schema_v2.sql.
CREATE TABLE app_usage_records (
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
, record_uid TEXT, measurement_status TEXT NOT NULL DEFAULT 'LEGACY', collection_version TEXT NOT NULL DEFAULT 'legacy');
CREATE TABLE device_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    device_model TEXT NOT NULL,
    network_provider TEXT NOT NULL DEFAULT 'Unknown',
    app_version TEXT NOT NULL DEFAULT '',
    linked_at INTEGER NOT NULL
);
CREATE INDEX idx_app_usage_records_device_local ON app_usage_records (device_id, local_id);
CREATE INDEX idx_app_usage_records_device_timestamp ON app_usage_records (device_id, timestamp);
CREATE UNIQUE INDEX idx_app_usage_records_record_uid ON app_usage_records (record_uid);
CREATE UNIQUE INDEX idx_device_identity_device_id ON device_identity (device_id);
CREATE TABLE respondent_map (
    device_id TEXT PRIMARY KEY,
    respondent_id TEXT NOT NULL,
    enrolled_at TEXT
);
CREATE TABLE upload_history (
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
CREATE INDEX idx_upload_history_device_id ON upload_history (device_id);
CREATE TABLE usage_records (
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
, record_uid TEXT, interval_start INTEGER NOT NULL DEFAULT 0, interval_end INTEGER NOT NULL DEFAULT 0, elapsed_ms INTEGER NOT NULL DEFAULT -1, measurement_status TEXT NOT NULL DEFAULT 'LEGACY', active_network_at_sample TEXT NOT NULL DEFAULT '', collection_version TEXT NOT NULL DEFAULT 'legacy');
CREATE INDEX idx_usage_records_device_local ON usage_records (device_id, local_id);
CREATE INDEX idx_usage_records_device_timestamp ON usage_records (device_id, timestamp);
CREATE UNIQUE INDEX idx_usage_records_record_uid ON usage_records (record_uid);
