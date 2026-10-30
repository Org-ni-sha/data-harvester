-- Upgrade of the EXISTING Dataharvester_v2 database (SQLite Cloud) for app 1.6.4.
-- Run once, after cloud_schema_v2_upgrade_1.6.3.sql and BEFORE any 1.6.4 APK uploads:
-- the app now inserts the columns below, and a missing column fails the whole batch.
--
-- Not for DATAra_harvester (the old database). A fresh v2 database needs only
-- cloud_schema_v2.sql, which already contains everything here.
--
-- What changes:
-- 1. study_id: the app uploads only rows collected after the phone's "Start Official
--    Collection" step, each tagged study_id = 'dataharvester_v2'. Rows already in this
--    database were sent before enrollment existed and get study_id '' here, so the
--    official dataset is exactly: WHERE study_id = 'dataharvester_v2'.
-- 2. device_identity.study_id / enrolled_at: when each install joined the study.
-- 3. upload_history: one row per logical batch (batch_uid, unique). records_uploaded
--    now counts only rows the batch newly added, so SUM(records_uploaded) no longer
--    overcounts retries; records_attempted / records_confirmed describe the attempt.
--    Older history rows keep batch_uid NULL and -1 in the new columns; their
--    records_uploaded is the number ATTEMPTED and may include retries.
-- 4. schema_version: version of the measurement rules that produced each row. Rows
--    already here get 1 when tagged with an app version (1.6.3 rules), else 0.
--    Split or filter analyses on it.
-- 6. Repair: the two partial unique indexes from the 1.6.3 script were missing on the
--    deployed database (checked 2026-10-30); they are created again below.
-- 5. New measurement_status values (no schema change): INTERRUPTED on usage_records,
--    GAP and CLOCK_ERROR on app_usage_records.
--
-- ALTER TABLE ADD COLUMN fails if the column already exists: if this script stopped
-- half-way, run only the statements after the last one that succeeded.

ALTER TABLE usage_records ADD COLUMN study_id TEXT NOT NULL DEFAULT '';
ALTER TABLE usage_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 0;
UPDATE usage_records SET schema_version = 1 WHERE collection_version != 'legacy';
CREATE INDEX IF NOT EXISTS idx_usage_records_study
    ON usage_records (study_id);

ALTER TABLE app_usage_records ADD COLUMN study_id TEXT NOT NULL DEFAULT '';
ALTER TABLE app_usage_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 0;
UPDATE app_usage_records SET schema_version = 1 WHERE collection_version != 'legacy';
CREATE INDEX IF NOT EXISTS idx_app_usage_records_study
    ON app_usage_records (study_id);

ALTER TABLE device_identity ADD COLUMN study_id TEXT NOT NULL DEFAULT '';
ALTER TABLE device_identity ADD COLUMN enrolled_at INTEGER NOT NULL DEFAULT -1;

ALTER TABLE upload_history ADD COLUMN batch_uid TEXT;
ALTER TABLE upload_history ADD COLUMN records_attempted INTEGER NOT NULL DEFAULT -1;
ALTER TABLE upload_history ADD COLUMN records_confirmed INTEGER NOT NULL DEFAULT -1;
CREATE UNIQUE INDEX IF NOT EXISTS idx_upload_history_batch_uid
    ON upload_history (batch_uid);

-- Repair (item 6): retry protection for rows from 1.6.1 phones (record_uid NULL)
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_legacy_device_local
    ON usage_records (device_id, local_id) WHERE record_uid IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_legacy_device_local
    ON app_usage_records (device_id, local_id) WHERE record_uid IS NULL;

-- Optional, only if the pre-study rows should not stay in the official database at all.
-- Check the counts first:
--   SELECT COUNT(*) FROM usage_records WHERE study_id = '';
--   SELECT COUNT(*) FROM app_usage_records WHERE study_id = '';
-- DELETE FROM usage_records WHERE study_id = '';
-- DELETE FROM app_usage_records WHERE study_id = '';
