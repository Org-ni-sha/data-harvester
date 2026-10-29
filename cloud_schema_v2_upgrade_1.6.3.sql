-- Upgrade of the EXISTING Dataharvester_v2 database (SQLite Cloud) for app 1.6.3.
-- Run once, BEFORE any 1.6.3 APK uploads: the app now inserts the columns below,
-- and a missing column fails the whole batch.
--
-- Not for DATAra_harvester (the old database). A fresh v2 database needs only
-- cloud_schema_v2.sql, which already contains everything here.
--
-- What changes:
-- 1. record_uid: a random id per row, generated on the phone. It becomes the ONLY
--    unique key and the upload acknowledgement key. (device_id, local_id) and
--    (device_id, timestamp) silently dropped real rows (local ids reused after a backup
--    restore, two ticks in one millisecond), so they become plain indexes.
--    Rows uploaded by 1.6.1 keep record_uid NULL; NULLs never conflict in a unique index.
--    For those rows only, (device_id, local_id) stays unique through a partial index,
--    so phones still on 1.6.1 keep their retry protection until they update.
-- 2. Interval/quality columns (interval_start, interval_end, elapsed_ms,
--    measurement_status, active_network_at_sample) and collection_version.
--    Defaults match the app's own migration: pre-1.6.3 rows read as 'LEGACY' / 'legacy'.
--
-- ALTER TABLE ADD COLUMN fails if the column already exists: if this script stopped
-- half-way, run only the statements after the last one that succeeded.

-- usage_records ----------------------------------------------------------------
ALTER TABLE usage_records ADD COLUMN record_uid TEXT;
ALTER TABLE usage_records ADD COLUMN interval_start INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usage_records ADD COLUMN interval_end INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usage_records ADD COLUMN elapsed_ms INTEGER NOT NULL DEFAULT -1;
ALTER TABLE usage_records ADD COLUMN measurement_status TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE usage_records ADD COLUMN active_network_at_sample TEXT NOT NULL DEFAULT '';
ALTER TABLE usage_records ADD COLUMN collection_version TEXT NOT NULL DEFAULT 'legacy';

DROP INDEX IF EXISTS idx_usage_records_device_local;
DROP INDEX IF EXISTS idx_usage_records_device_timestamp;
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

-- app_usage_records --------------------------------------------------------------
ALTER TABLE app_usage_records ADD COLUMN record_uid TEXT;
ALTER TABLE app_usage_records ADD COLUMN measurement_status TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE app_usage_records ADD COLUMN collection_version TEXT NOT NULL DEFAULT 'legacy';

DROP INDEX IF EXISTS idx_app_usage_records_device_local;
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_record_uid
    ON app_usage_records (record_uid);
CREATE INDEX IF NOT EXISTS idx_app_usage_records_device_local
    ON app_usage_records (device_id, local_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_legacy_device_local
    ON app_usage_records (device_id, local_id) WHERE record_uid IS NULL;
