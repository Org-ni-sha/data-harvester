// Validation and SQL building for the ingest endpoint. No I/O here, so
// `node --test api/` checks it without a database.
//
// The endpoint is the ONLY way phones write to Dataharvester_v2: the APK holds no
// database key, cannot run SQL and cannot read other participants' rows. Every field
// is checked against the spec below before it is turned into a SQL literal.
'use strict';

const crypto = require('node:crypto');

const STUDY_ID = 'dataharvester_v2';
const MAX_ROWS_PER_TABLE = 2000;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// record_uid: a UUID from the app, or 32 hex chars for rows migrated in SQLite
const RECORD_UID = /^([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;

const MIN_TIME = Date.UTC(2025, 0, 1);
const maxTime = () => Date.now() + 24 * 3600 * 1000;
const MAX_BYTES = 50 * 1024 ** 3; // per row; far above any 30 s / 10 min interval

const USAGE_STATUS = ['OK', 'BASELINE', 'COUNTER_RESET', 'UNSUPPORTED', 'GAP', 'INTERRUPTED'];
const APP_STATUS = ['OK', 'COUNTER_RESET', 'GAP', 'CLOCK_ERROR'];
const USAGE_NETWORK = ['5G', 'LTE', '3G', '2G', 'CELLULAR', 'WIFI', 'NONE', 'UNKNOWN', 'ETHERNET', 'OTHER'];

const int = (min, max) => ({ kind: 'int', min, max });
const num = (min, max) => ({ kind: 'num', min, max });
const str = (max, opts = {}) => ({ kind: 'str', max, ...opts });
const time = () => ({ kind: 'int', min: MIN_TIME, max: null });

// Column specs mirror UploadPayload.kt; every key is required.
const USAGE_FIELDS = {
  record_uid: str(36, { pattern: RECORD_UID }),
  local_id: int(0, Number.MAX_SAFE_INTEGER),
  timestamp: time(),
  datetime_str: str(32),
  hour: int(0, 23),
  minute: int(0, 59),
  day_of_week: int(1, 7),
  is_weekend: int(0, 1),
  time_period: str(16, { oneOf: ['Morning', 'Afternoon', 'Evening', 'Night'] }),
  bytes_rx: int(0, MAX_BYTES),
  bytes_tx: int(0, MAX_BYTES),
  bytes_total: int(0, 2 * MAX_BYTES),
  mb_used: num(0, 2 * MAX_BYTES / 1048576),
  cumulative_mb_today: num(0, 1e7),
  network_type: str(16, { oneOf: USAGE_NETWORK }),
  screen_on: int(0, 1),
  battery_level: int(-1, 100),
  device_id: str(36, { pattern: UUID }),
  signal_strength: int(-999, 0),
  is_charging: int(0, 1),
  device_model: str(100),
  network_operator: str(100),
  utc_offset_minutes: int(-720, 840),
  interval_start: time(),
  interval_end: time(),
  elapsed_ms: int(-1, 366 * 24 * 3600 * 1000),
  measurement_status: str(16, { oneOf: USAGE_STATUS }),
  active_network_at_sample: str(16, { oneOf: [...USAGE_NETWORK, ''] }),
  collection_version: str(32),
  schema_version: int(0, 100),
  study_id: str(64, { oneOf: [STUDY_ID] }),
};

const APP_FIELDS = {
  record_uid: str(36, { pattern: RECORD_UID }),
  local_id: int(0, Number.MAX_SAFE_INTEGER),
  timestamp: time(),
  datetime_str: str(32),
  device_id: str(36, { pattern: UUID }),
  package_name: str(255),
  app_name: str(255),
  // NetworkStats uses small negative UIDs for removed apps (-4) and tethering (-5)
  uid: int(-10, 2 ** 31 - 1),
  bytes_rx: int(0, MAX_BYTES),
  bytes_tx: int(0, MAX_BYTES),
  bytes_total: int(0, 2 * MAX_BYTES),
  network_type: str(8, { oneOf: ['MOBILE', 'WIFI'] }),
  query_start: str(32),
  start_time: str(32),
  end_time: str(32),
  is_system_app: int(0, 1),
  utc_offset_minutes: int(-720, 840),
  measurement_status: str(16, { oneOf: APP_STATUS }),
  collection_version: str(32),
  schema_version: int(0, 100),
  study_id: str(64, { oneOf: [STUDY_ID] }),
};

const IDENTITY_FIELDS = {
  device_id: str(36, { pattern: UUID }),
  device_model: str(100),
  network_provider: str(100),
  app_version: str(32),
  linked_at: time(),
  study_id: str(64, { oneOf: [STUDY_ID] }),
  enrolled_at: time(),
};

class ValidationError extends Error {}

function checkValue(spec, value, where) {
  const fail = (why) => { throw new ValidationError(`${where}: ${why}`); };
  if (spec.kind === 'int' || spec.kind === 'num') {
    if (typeof value !== 'number' || !Number.isFinite(value)) fail('not a number');
    if (spec.kind === 'int' && !Number.isSafeInteger(value)) fail('not an integer');
    const max = spec.max === null ? maxTime() : spec.max;
    if (value < spec.min || value > max) fail(`out of range (${value})`);
    return value;
  }
  if (typeof value !== 'string') fail('not a string');
  if (value.length > spec.max) fail('too long');
  if (value.includes('\u0000')) fail('contains NUL');
  if (spec.pattern && !spec.pattern.test(value)) fail('bad format');
  if (spec.oneOf && !spec.oneOf.includes(value)) fail(`not allowed (${value})`);
  return value;
}

function checkRow(fields, row, where) {
  if (row === null || typeof row !== 'object' || Array.isArray(row)) {
    throw new ValidationError(`${where}: not an object`);
  }
  const out = {};
  for (const [name, spec] of Object.entries(fields)) {
    if (!(name in row)) throw new ValidationError(`${where}.${name}: missing`);
    out[name] = checkValue(spec, row[name], `${where}.${name}`);
  }
  return out; // unknown keys are dropped, never forwarded
}

/**
 * Validate a request body. Problems with the request itself (sender, study, sizes) throw
 * ValidationError. A bad ROW only rejects that row: it is reported back so the phone can
 * set it aside, and one odd value can never block a phone's uploads for good.
 */
function validateBatch(body) {
  if (body === null || typeof body !== 'object') throw new ValidationError('body: not an object');
  const deviceId = checkValue(str(36, { pattern: UUID }), body.device_id, 'device_id');
  checkValue(str(64, { oneOf: [STUDY_ID] }), body.study_id, 'study_id');
  const appVersion = checkValue(str(32), body.app_version, 'app_version');

  const rejected = { usage: [], app: [] };
  const rows = (list, fields, name) => {
    if (!Array.isArray(list)) throw new ValidationError(`${name}: not an array`);
    if (list.length > MAX_ROWS_PER_TABLE) throw new ValidationError(`${name}: more than ${MAX_ROWS_PER_TABLE} rows`);
    const ok = [];
    list.forEach((r, i) => {
      try {
        const row = checkRow(fields, r, `${name}[${i}]`);
        // A phone may only write its own rows
        if (row.device_id !== deviceId) throw new ValidationError(`${name}[${i}].device_id: not the sender`);
        ok.push(row);
      } catch (e) {
        if (!(e instanceof ValidationError)) throw e;
        const uid = r && typeof r.record_uid === 'string' && RECORD_UID.test(r.record_uid) ? r.record_uid : null;
        // A row without a readable record_uid cannot be reported, so it is just dropped
        if (uid) rejected[name].push({ record_uid: uid, reason: e.message });
      }
    });
    return ok;
  };
  const usage = rows(body.usage, USAGE_FIELDS, 'usage');
  const app = rows(body.app, APP_FIELDS, 'app');

  let identity = null;
  if (body.identity !== null && body.identity !== undefined) {
    identity = checkRow(IDENTITY_FIELDS, body.identity, 'identity');
    if (identity.device_id !== deviceId) throw new ValidationError('identity.device_id: not the sender');
  }
  return { deviceId, appVersion, usage, app, identity, rejected };
}

function sqlLiteral(v) {
  if (typeof v === 'number') return String(v);
  return `'${String(v).replace(/'/g, "''")}'`;
}

function insertOrIgnore(table, row) {
  const cols = Object.keys(row);
  return `INSERT OR IGNORE INTO ${table} (${cols.join(', ')}) VALUES (${cols.map((c) => sqlLiteral(row[c])).join(', ')});`;
}

function upsertIdentity(row) {
  const cols = Object.keys(row);
  const updates = cols.filter((c) => c !== 'device_id').map((c) => `${c} = excluded.${c}`).join(', ');
  return `INSERT INTO device_identity (${cols.join(', ')}) VALUES (${cols.map((c) => sqlLiteral(row[c])).join(', ')}) ` +
    `ON CONFLICT(device_id) DO UPDATE SET ${updates};`;
}

function selectUids(table, uids) {
  return `SELECT record_uid FROM ${table} WHERE record_uid IN (${uids.map(sqlLiteral).join(', ')});`;
}

/** Same as CloudSyncManager.batchUid in the app: SHA-256 of the sorted uids, 32 hex chars. */
function batchUid(uids) {
  return crypto.createHash('sha256').update([...uids].sort().join('\n'), 'utf8').digest('hex').slice(0, 32);
}

module.exports = {
  STUDY_ID, MAX_ROWS_PER_TABLE, USAGE_FIELDS, APP_FIELDS, IDENTITY_FIELDS,
  ValidationError, validateBatch, sqlLiteral, insertOrIgnore, upsertIdentity, selectUids, batchUid,
};
