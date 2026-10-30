-- Upgrade of the EXISTING Dataharvester_v2 database (SQLite Cloud) for app 1.6.5
-- (1.6.4 was never deployed; this file replaces its upgrade).
-- Run once, after cloud_schema_v2_upgrade_1.6.3.sql and BEFORE any 1.6.5 APK uploads:
-- the app now inserts the columns below, and a missing column fails the whole batch.
--
-- Not for DATAra_harvester (the old database). A fresh v2 database needs only
-- cloud_schema_v2.sql, which already contains everything here.
--
-- What changes:
-- 1. study_id: every row collected by app 1.6.1 or later belongs to the official study,
--    'dataharvester_v2' - all rows already in this database, and all rows 1.6.3 phones
--    upload later (the column DEFAULT fills it in for them). 1.6.5 sends it explicitly.
-- 2. device_identity.study_id / enrolled_at: the study, and when the install first ran a
--    version that tags rows with it (-1 for identities sent by 1.6.3).
-- 3. upload_history: one row per logical batch (batch_uid, unique). records_uploaded
--    now counts only rows the batch newly added, so SUM(records_uploaded) no longer
--    overcounts retries; records_attempted / records_confirmed describe the attempt.
--    Older history rows keep batch_uid NULL and -1 in the new columns; their
--    records_uploaded is the number ATTEMPTED and may include retries.
-- 4. schema_version: version of the measurement rules that produced each row: 0 for
--    collection_version 'legacy' (1.6.1), 1 for 1.6.3 (also the DEFAULT, so later 1.6.3
--    uploads get it), 2 for 1.6.5 (sent by the app). Split or filter analyses on it.
-- 7. Hotspot (1.6.5, no schema change): app_usage_records rows with package_name =
--    'hotspot' (uid -5) are data other devices used through this phone's hotspot. They are
--    already included in usage_records totals.
-- 6. Repair: the two partial unique indexes from the 1.6.3 script were missing on the
--    deployed database (checked 2026-10-30); they are created again below.
-- 5. New measurement_status values (no schema change): INTERRUPTED on usage_records,
--    GAP and CLOCK_ERROR on app_usage_records.
--
-- ALTER TABLE ADD COLUMN fails if the column already exists: if this script stopped
-- half-way, run only the statements after the last one that succeeded.
--
-- SAFETY: step 0 first copies the four tables into backup_v165_* tables. The only
-- statements that remove data (the two DELETEs in step 6) delete only rows present in
-- those copies, so they fail instead of deleting if the copy was not made.
-- Steps 1-6 run as ONE transaction (BEGIN ... COMMIT): if any statement fails, the
-- gateway drops the unfinished transaction and none of steps 1-6 is applied. Run the
-- whole file in a single execution; a BEGIN sent alone is discarded at the end of
-- that request (tested on the gateway 2026-10-30), so it cannot be committed later.
-- To undo: the ROLLBACK section at the end of this file. When the result is checked,
-- drop the backup tables (its last part).

-- 0. Safety copy, before anything changes. CREATE TABLE fails if a copy already exists,
-- so a second run can never overwrite the original copy with already-changed data.
CREATE TABLE backup_v165_usage_records AS SELECT * FROM usage_records;
CREATE TABLE backup_v165_app_usage_records AS SELECT * FROM app_usage_records;
CREATE TABLE backup_v165_device_identity AS SELECT * FROM device_identity;
CREATE TABLE backup_v165_upload_history AS SELECT * FROM upload_history;
-- Check before going on: each pair of counts must be equal.
--   SELECT (SELECT COUNT(*) FROM usage_records), (SELECT COUNT(*) FROM backup_v165_usage_records),
--          (SELECT COUNT(*) FROM app_usage_records), (SELECT COUNT(*) FROM backup_v165_app_usage_records);

-- 1-6. All or nothing
BEGIN;

ALTER TABLE usage_records ADD COLUMN study_id TEXT NOT NULL DEFAULT 'dataharvester_v2';
ALTER TABLE usage_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;
UPDATE usage_records SET schema_version = 0 WHERE collection_version = 'legacy';
CREATE INDEX IF NOT EXISTS idx_usage_records_study
    ON usage_records (study_id);

ALTER TABLE app_usage_records ADD COLUMN study_id TEXT NOT NULL DEFAULT 'dataharvester_v2';
ALTER TABLE app_usage_records ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;
UPDATE app_usage_records SET schema_version = 0 WHERE collection_version = 'legacy';
CREATE INDEX IF NOT EXISTS idx_app_usage_records_study
    ON app_usage_records (study_id);

ALTER TABLE device_identity ADD COLUMN study_id TEXT NOT NULL DEFAULT 'dataharvester_v2';
ALTER TABLE device_identity ADD COLUMN enrolled_at INTEGER NOT NULL DEFAULT -1;

ALTER TABLE upload_history ADD COLUMN batch_uid TEXT;
ALTER TABLE upload_history ADD COLUMN records_attempted INTEGER NOT NULL DEFAULT -1;
ALTER TABLE upload_history ADD COLUMN records_confirmed INTEGER NOT NULL DEFAULT -1;
CREATE UNIQUE INDEX IF NOT EXISTS idx_upload_history_batch_uid
    ON upload_history (batch_uid);

-- Repair (item 6): retry protection for rows from 1.6.1 phones (record_uid NULL).
-- Live data (checked 2026-10-30) holds exact double uploads of such rows (2 usage pairs,
-- 47 per-app pairs, one device), which would make the unique indexes fail. Keep the
-- first copy (lowest id) of each (device_id, local_id) and delete the later ones.
DELETE FROM usage_records
WHERE record_uid IS NULL
  AND EXISTS (SELECT 1 FROM usage_records o
              WHERE o.record_uid IS NULL AND o.device_id = usage_records.device_id
                AND o.local_id = usage_records.local_id AND o.id < usage_records.id)
  AND id IN (SELECT id FROM backup_v165_usage_records);
DELETE FROM app_usage_records
WHERE record_uid IS NULL
  AND EXISTS (SELECT 1 FROM app_usage_records o
              WHERE o.record_uid IS NULL AND o.device_id = app_usage_records.device_id
                AND o.local_id = app_usage_records.local_id AND o.id < app_usage_records.id)
  AND id IN (SELECT id FROM backup_v165_app_usage_records);
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_legacy_device_local
    ON usage_records (device_id, local_id) WHERE record_uid IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_legacy_device_local
    ON app_usage_records (device_id, local_id) WHERE record_uid IS NULL;

COMMIT;

-- Check after the run (expected 2026-10-30: 2 and 47 rows removed, columns present):
--   SELECT (SELECT COUNT(*) FROM backup_v165_usage_records) - (SELECT COUNT(*) FROM usage_records) AS usage_removed,
--          (SELECT COUNT(*) FROM backup_v165_app_usage_records) - (SELECT COUNT(*) FROM app_usage_records) AS app_removed;
-- (Phones keep uploading, so run this right away; new uploads lower these numbers.)

-- ════════════════════════════════════════════════════════════════════════════════
-- ROLLBACK: only if something went wrong. Never part of the normal run: each part
-- below is inside /* ... */, so running this whole file skips it. To use a part,
-- delete its /* and */ lines (or copy just that part) and run it on its own.
-- Needs the backup_v165_* tables made by step 0.
-- ════════════════════════════════════════════════════════════════════════════════

-- ── Check first: what did the upgrade change? ──────────────────────────────────
-- Rows in the backup that are no longer in the live table (the deleted duplicates):
--   SELECT COUNT(*) FROM backup_v165_usage_records WHERE id NOT IN (SELECT id FROM usage_records);
--   SELECT COUNT(*) FROM backup_v165_app_usage_records WHERE id NOT IN (SELECT id FROM app_usage_records);
-- Expected on 2026-10-30: 2 and 47.

-- ── Part A: put the deleted rows back ──────────────────────────────────────────
-- Safe while phones are uploading: only rows missing from the live table are inserted,
-- with their original id. The unique indexes that required the deletes go first.
-- All or nothing: run Part A in one execution.
/*
BEGIN;
DROP INDEX IF EXISTS idx_usage_records_legacy_device_local;
DROP INDEX IF EXISTS idx_app_usage_records_legacy_device_local;

INSERT INTO usage_records (id, device_id, local_id, timestamp, datetime_str, hour, minute,
    day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used,
    cumulative_mb_today, network_type, screen_on, battery_level, signal_strength, is_charging,
    device_model, network_operator, utc_offset_minutes, received_at, record_uid, interval_start,
    interval_end, elapsed_ms, measurement_status, active_network_at_sample, collection_version)
SELECT id, device_id, local_id, timestamp, datetime_str, hour, minute,
    day_of_week, is_weekend, time_period, bytes_rx, bytes_tx, bytes_total, mb_used,
    cumulative_mb_today, network_type, screen_on, battery_level, signal_strength, is_charging,
    device_model, network_operator, utc_offset_minutes, received_at, record_uid, interval_start,
    interval_end, elapsed_ms, measurement_status, active_network_at_sample, collection_version
FROM backup_v165_usage_records
WHERE id NOT IN (SELECT id FROM usage_records);

INSERT INTO app_usage_records (id, device_id, local_id, timestamp, datetime_str, package_name,
    app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time,
    end_time, is_system_app, utc_offset_minutes, received_at, record_uid, measurement_status,
    collection_version)
SELECT id, device_id, local_id, timestamp, datetime_str, package_name,
    app_name, uid, bytes_rx, bytes_tx, bytes_total, network_type, query_start, start_time,
    end_time, is_system_app, utc_offset_minutes, received_at, record_uid, measurement_status,
    collection_version
FROM backup_v165_app_usage_records
WHERE id NOT IN (SELECT id FROM app_usage_records);
COMMIT;
*/
-- Check: both must now be 0.
--   SELECT COUNT(*) FROM backup_v165_usage_records WHERE id NOT IN (SELECT id FROM usage_records);
--   SELECT COUNT(*) FROM backup_v165_app_usage_records WHERE id NOT IN (SELECT id FROM app_usage_records);

-- ── Part B (optional): undo the schema changes too ─────────────────────────────
-- Only while NO phone runs 1.6.5: 1.6.5 uploads these columns and fails without them.
-- 1.6.3 phones never use them, so keeping them is harmless. Delete the /* and */ lines to run it.
/*
DROP INDEX IF EXISTS idx_usage_records_study;
DROP INDEX IF EXISTS idx_app_usage_records_study;
DROP INDEX IF EXISTS idx_upload_history_batch_uid;
ALTER TABLE usage_records DROP COLUMN study_id;
ALTER TABLE usage_records DROP COLUMN schema_version;
ALTER TABLE app_usage_records DROP COLUMN study_id;
ALTER TABLE app_usage_records DROP COLUMN schema_version;
ALTER TABLE device_identity DROP COLUMN study_id;
ALTER TABLE device_identity DROP COLUMN enrolled_at;
ALTER TABLE upload_history DROP COLUMN batch_uid;
ALTER TABLE upload_history DROP COLUMN records_attempted;
ALTER TABLE upload_history DROP COLUMN records_confirmed;
*/

-- ── Cleanup: when everything is checked ────────────────────────────────────────
-- The backup tables are the only copy of the deleted rows: drop them only when you are
-- sure (for example after the study, or after exporting them). Delete the /* and */ lines to run it.
/*
DROP TABLE backup_v165_usage_records;
DROP TABLE backup_v165_app_usage_records;
DROP TABLE backup_v165_device_identity;
DROP TABLE backup_v165_upload_history;
*/
