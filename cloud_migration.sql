-- Cloud Migration Script (SQLite Cloud) — schema to match app DB v9
-- Run manually on the SQLite Cloud dashboard, one section at a time.
--
-- STATUS: already applied to production. Verified against sqlite_master on
-- 2026-10-05: all columns and unique indexes below exist, and there are 0
-- duplicate (device_id, timestamp) rows. Kept here so the schema can be
-- rebuilt and so the next change follows the same order.
--
-- ORDER MATTERS: CloudSyncManager sends each batch as ONE SQL request. If the
-- app references a column or ON CONFLICT target the cloud lacks, the whole
-- batch fails and nothing uploads. Always run cloud changes BEFORE shipping
-- the APK that depends on them.

-- 1. Missing columns (v8 -> v9).
--    ALTER TABLE ADD COLUMN is not idempotent: skip any line whose column
--    already exists (check with: PRAGMA table_info(<table>);).
ALTER TABLE usage_records ADD COLUMN network_operator TEXT NOT NULL DEFAULT 'Unknown';
ALTER TABLE usage_records ADD COLUMN utc_offset_minutes INTEGER NOT NULL DEFAULT 480;

ALTER TABLE app_usage_records ADD COLUMN utc_offset_minutes INTEGER NOT NULL DEFAULT 480;
ALTER TABLE app_usage_records ADD COLUMN start_time TEXT NOT NULL DEFAULT '';
ALTER TABLE app_usage_records ADD COLUMN end_time TEXT NOT NULL DEFAULT '';
-- Room row id on the phone; the sync INSERT sends it. NULL for rows uploaded
-- before v9, which is fine (NULLs never collide in a UNIQUE index).
ALTER TABLE app_usage_records ADD COLUMN local_id INTEGER;

ALTER TABLE device_identity ADD COLUMN network_provider TEXT NOT NULL DEFAULT 'Unknown';

ALTER TABLE upload_history ADD COLUMN datetime_str TEXT NOT NULL DEFAULT '';
ALTER TABLE upload_history ADD COLUMN datetime_stamp TEXT NOT NULL DEFAULT '';

-- 2. Backfill start/end time for rows uploaded by older app versions.
UPDATE app_usage_records
SET start_time = query_start,
    end_time = datetime_str
WHERE start_time = '' OR start_time IS NULL;

-- 3. promo_records (v8). local_id + its unique index are what the app's
--    ON CONFLICT(device_id, local_id) upsert targets.
CREATE TABLE IF NOT EXISTS promo_records (
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
    local_id INTEGER
);
CREATE INDEX IF NOT EXISTS index_promo_records_device_id ON promo_records(device_id);

-- 4. Remove existing duplicates. MUST run before step 5: CREATE UNIQUE INDEX
--    fails outright while duplicates exist. Keeps the earliest copy of each row.
DELETE FROM usage_records
WHERE id NOT IN (SELECT MIN(id) FROM usage_records GROUP BY device_id, timestamp);

DELETE FROM app_usage_records
WHERE id NOT IN (SELECT MIN(id) FROM app_usage_records GROUP BY device_id, package_name, timestamp);

DELETE FROM app_usage_records
WHERE local_id IS NOT NULL
  AND id NOT IN (SELECT MIN(id) FROM app_usage_records WHERE local_id IS NOT NULL GROUP BY device_id, local_id);

DELETE FROM promo_records
WHERE id NOT IN (SELECT MIN(id) FROM promo_records GROUP BY device_id, local_id);

-- device_identity: keep the most recent link per hardware_id.
DELETE FROM device_identity
WHERE id NOT IN (SELECT MAX(id) FROM device_identity GROUP BY hardware_id);

-- 5. Unique indexes. These make the app's INSERT OR IGNORE / ON CONFLICT
--    clauses work, so re-uploads after a lost response are dropped, not duplicated.
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_unique
ON usage_records(device_id, timestamp);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_unique
ON app_usage_records(device_id, package_name, timestamp);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_device_local
ON app_usage_records(device_id, local_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_records_device_local
ON promo_records(device_id, local_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_identity_hardware_id
ON device_identity(hardware_id);

-- 6. Readable respondent IDs (R001...) for the manuscript; filled at export time.
CREATE TABLE IF NOT EXISTS respondent_map (
    device_id     TEXT PRIMARY KEY,
    respondent_id TEXT NOT NULL,
    enrolled_at   TEXT
);

-- 7. Optional cleanup: production has redundant indexes on
--    device_identity(hardware_id) — the unique one above covers all of them.
-- DROP INDEX IF EXISTS idx_device_identity_hardware;
-- DROP INDEX IF EXISTS index_device_identity_hardware_id;
