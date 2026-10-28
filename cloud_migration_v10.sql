-- Cloud Migration Script (SQLite Cloud) — app DB v10: stop storing ANDROID_ID
-- Run manually on the SQLite Cloud dashboard. Safe to re-run.
--
-- From v10 the app no longer reads Settings.Secure.ANDROID_ID. The
-- device_identity.hardware_id column is kept (old APKs still in the field send
-- it, and their whole upload batch would fail if it disappeared), but v10 phones
-- fill it with the random install UUID, i.e. hardware_id = current_device_id.
--
-- This script removes the ANDROID_ID values already stored in the cloud.
-- Phones still on v1.6.0 or older will keep re-sending their ANDROID_ID until
-- they update, so run it again once everyone is on the new version.

-- 1. Drop old rows whose install already has a new-style row, so step 2 cannot
--    collide with the UNIQUE index on hardware_id.
DELETE FROM device_identity
WHERE hardware_id != current_device_id
  AND current_device_id IN (SELECT hardware_id FROM device_identity);

-- 2. Overwrite remaining ANDROID_ID values with the install UUID.
UPDATE device_identity
SET hardware_id = current_device_id
WHERE hardware_id != current_device_id;

-- 3. Check: should return 0.
SELECT COUNT(*) AS rows_still_holding_android_id
FROM device_identity
WHERE hardware_id != current_device_id;

-- 4. Redundant indexes on device_identity(hardware_id); the unique
--    idx_device_identity_hardware_id covers them.
DROP INDEX IF EXISTS idx_device_identity_hardware;
DROP INDEX IF EXISTS index_device_identity_hardware_id;
