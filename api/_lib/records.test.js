// Run: node --no-warnings --test api/_lib/records.test.js
// Uses Node's built-in SQLite to execute the endpoint's real SQL against the real
// cloud schema files, so schema, upgrade script and inserts are checked together.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const { validateBatch, batchUid, ValidationError, STUDY_ID } = require('./records');
const { ingest } = require('./ingest-core');

const root = path.join(__dirname, '..', '..');
const read = (f) => fs.readFileSync(path.join(root, f), 'utf8');

const DEVICE = '82a9b4d5-0d6b-4b5e-bbfe-506675628a1a';
const NOW = Date.UTC(2026, 9, 30, 2, 0, 0);
let seq = 0;
const uid = () => `00000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`;

function usageRow(over = {}) {
  return {
    record_uid: uid(), local_id: seq, timestamp: NOW, datetime_str: '2026-10-30T10:00:00',
    hour: 10, minute: 0, day_of_week: 5, is_weekend: 0, time_period: 'Morning',
    bytes_rx: 1000, bytes_tx: 200, bytes_total: 1200, mb_used: 1200 / 1048576, cumulative_mb_today: 1.5,
    network_type: 'LTE', screen_on: 1, battery_level: 80, device_id: DEVICE, signal_strength: -90,
    is_charging: 0, device_model: 'Vivo V2322', network_operator: "TM's", utc_offset_minutes: 480,
    interval_start: NOW - 30000, interval_end: NOW, elapsed_ms: 30000, measurement_status: 'OK',
    active_network_at_sample: 'WIFI', collection_version: '1.6.4', schema_version: 2, study_id: STUDY_ID,
    ...over,
  };
}

function appRow(over = {}) {
  return {
    record_uid: uid(), local_id: seq, timestamp: NOW, datetime_str: '2026-10-30T10:00:00', device_id: DEVICE,
    package_name: 'com.example', app_name: 'Example', uid: 10100, bytes_rx: 5, bytes_tx: 5, bytes_total: 10,
    network_type: 'MOBILE', query_start: 'a', start_time: 'a', end_time: 'b', is_system_app: 0,
    utc_offset_minutes: 480, measurement_status: 'OK', collection_version: '1.6.4', schema_version: 2,
    study_id: STUDY_ID, ...over,
  };
}

const identity = () => ({
  device_id: DEVICE, device_model: 'Vivo V2322', network_provider: 'TM', app_version: '1.6.4',
  linked_at: NOW - 1000, study_id: STUDY_ID, enrolled_at: NOW - 500,
});

const body = (over = {}) => ({
  device_id: DEVICE, study_id: STUDY_ID, app_version: '1.6.4', usage: [usageRow()], app: [appRow()],
  identity: identity(), ...over,
});

/** A `sql` function on an in-memory SQLite with the given schema files applied. */
function fakeGateway(...schemaFiles) {
  const db = new DatabaseSync(':memory:');
  schemaFiles.forEach((f) => db.exec(read(f)));
  const sql = async (text) => {
    if (/^\s*SELECT/i.test(text)) return { data: db.prepare(text).all() };
    db.exec(text);
    return { data: [] };
  };
  return { db, sql };
}

// name, type, NOT NULL and DEFAULT: an upgraded database must behave like a fresh one for old apps too
const columns = (db, table) => db.prepare(`PRAGMA table_info(${table})`).all()
  .map((c) => `${c.name} ${c.type} ${c.notnull ? 'NOT NULL' : ''} ${c.dflt_value ?? ''}`).sort();

// ── validation ──────────────────────────────────────────────────────────────

test('a well-formed batch validates', () => {
  const b = validateBatch(body());
  assert.equal(b.usage.length, 1);
  assert.equal(b.app.length, 1);
  assert.ok(b.identity);
});

/** Validate one bad usage row next to a good one; returns the rejection list. */
const rejectOne = (over) => {
  const good = usageRow();
  const bad = typeof over === 'function' ? over(usageRow()) : usageRow(over);
  const b = validateBatch(body({ usage: [good, bad] }));
  assert.deepEqual(b.usage.map((r) => r.record_uid), [good.record_uid], 'the good row still goes through');
  return b.rejected.usage;
};

test('rows from before the study are rejected; a request for another study is refused', () => {
  assert.equal(rejectOne({ study_id: '' }).length, 1);
  assert.throws(() => validateBatch(body({ study_id: 'other' })), ValidationError);
});

test('a phone cannot write rows for another device', () => {
  const other = 'ef97f9f3-b0cc-4195-b87c-fefe65db4668';
  assert.match(rejectOne({ device_id: other })[0].reason, /not the sender/);
  assert.throws(() => validateBatch(body({ identity: { ...identity(), device_id: other } })), /not the sender/);
});

test('a bad value rejects only its own row, with the reason', () => {
  const bad = [
    { bytes_rx: -1 }, { bytes_rx: 1.5 }, { bytes_rx: '10' }, { hour: 24 },
    { measurement_status: 'MADE_UP' }, { network_type: 'SATELLITE' }, { timestamp: Date.now() + 7 * 864e5 },
    { device_model: 'x'.repeat(101) },
  ];
  for (const over of bad) {
    const rejected = rejectOne(over);
    assert.equal(rejected.length, 1, JSON.stringify(over));
    assert.ok(rejected[0].reason.length > 0);
  }
  assert.match(rejectOne((r) => { delete r.elapsed_ms; return r; })[0].reason, /missing/);
});

test('a row with an unreadable record_uid is dropped, never inserted', () => {
  const b = validateBatch(body({ usage: [usageRow({ record_uid: "x'; DROP TABLE usage_records; --" })] }));
  assert.equal(b.usage.length, 0);
  assert.equal(b.rejected.usage.length, 0);
});

test('oversized batches are refused', () => {
  assert.throws(() => validateBatch(body({ usage: Array.from({ length: 2001 }, () => usageRow()) })), /more than/);
});

test('unknown keys are dropped, never forwarded as columns', () => {
  const b = validateBatch(body({ usage: [{ ...usageRow(), 'x) VALUES (1); --': 1 }] }));
  assert.ok(!Object.keys(b.usage[0]).some((k) => k.includes('VALUES')));
});

test('batchUid matches the app (CloudSyncManager.batchUid)', () => {
  // Same vector as MeasurementRulesTest.batchUid_matchesServer in the app
  assert.equal(batchUid(['u3', 'u1', 'u2']), 'f050416e571aa7132e4b29d9885d6806');
  assert.equal(batchUid(['u1', 'u2', 'u3']), batchUid(['u2', 'u3', 'u1']));
});

// ── schema files ────────────────────────────────────────────────────────────

test('the 1.6.5 upgrade on the deployed database matches a fresh cloud_schema_v2', () => {
  const upgraded = fakeGateway('api/_lib/fixtures/deployed_2026-10-30.sql', 'cloud_schema/cloud_schema_v2_upgrade_1.6.5.sql').db;
  const fresh = fakeGateway('cloud_schema/cloud_schema_v2.sql').db;
  for (const t of ['usage_records', 'app_usage_records', 'device_identity', 'upload_history']) {
    assert.deepEqual(columns(upgraded, t), columns(fresh, t), t);
  }
});

// ── ingest flow ─────────────────────────────────────────────────────────────

test('ingest inserts, reads back and acknowledges every row', async () => {
  const { db, sql } = fakeGateway('cloud_schema/cloud_schema_v2.sql');
  const b = body({ usage: [usageRow(), usageRow()], app: [appRow()] });
  const ack = await ingest(b, sql);
  assert.deepEqual(new Set(ack.usage_acked), new Set(b.usage.map((r) => r.record_uid)));
  assert.deepEqual(ack.app_acked, [b.app[0].record_uid]);
  assert.equal(ack.identity_acked, true);
  assert.equal(ack.inserted, 3);
  const row = db.prepare('SELECT network_operator, study_id, schema_version FROM usage_records LIMIT 1').get();
  assert.deepEqual({ ...row }, { network_operator: "TM's", study_id: STUDY_ID, schema_version: 2 });
  const h = db.prepare('SELECT records_uploaded, records_attempted, records_confirmed FROM upload_history').all();
  assert.deepEqual(h.map((r) => ({ ...r })), [{ records_uploaded: 3, records_attempted: 3, records_confirmed: 3 }]);
});

test('a retry after a lost response duplicates nothing and adds no history', async () => {
  const { db, sql } = fakeGateway('cloud_schema/cloud_schema_v2.sql');
  const b = body();
  await ingest(b, sql);            // the phone never saw this answer…
  const again = await ingest(b, sql); // …so it sends the same batch again
  assert.equal(again.inserted, 0);
  assert.equal(again.usage_acked.length, 1);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM usage_records').get().n, 1);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM upload_history').get().n, 1);
  // one usage + one per-app row, counted once
  assert.equal(db.prepare('SELECT SUM(records_uploaded) s FROM upload_history').get().s, 2);
});

test('rows the database did not keep are not acknowledged', async () => {
  const { sql } = fakeGateway('cloud_schema/cloud_schema_v2.sql');
  const b = body({ usage: [usageRow(), usageRow()] });
  // The insert request "succeeds" but the database dropped the second row
  const lossy = async (text) => sql(/^\s*SELECT/i.test(text) ? text : text.split('\n').filter((l) => !l.includes(b.usage[1].record_uid)).join('\n'));
  const ack = await ingest(b, lossy);
  assert.deepEqual(ack.usage_acked, [b.usage[0].record_uid]);
});

test('ingest reports rejected rows and inserts the rest', async () => {
  const { db, sql } = fakeGateway('cloud_schema/cloud_schema_v2.sql');
  const good = usageRow();
  const bad = usageRow({ battery_level: 900 });
  const ack = await ingest(body({ usage: [good, bad], app: [] }), sql);
  assert.deepEqual(ack.usage_acked, [good.record_uid]);
  assert.deepEqual(ack.usage_rejected.map((r) => r.record_uid), [bad.record_uid]);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM usage_records').get().n, 1);
});

test('a database error acknowledges nothing', async () => {
  const failing = async () => { throw new Error('gateway HTTP 500'); };
  await assert.rejects(ingest(body(), failing), /500/);
});
