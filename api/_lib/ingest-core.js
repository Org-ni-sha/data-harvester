// The ingest flow, independent of Vercel's request object so tests can drive it with a
// fake gateway. One call = one batch from one phone.
'use strict';

const { validateBatch, insertOrIgnore, upsertIdentity, selectUids, batchUid, sqlLiteral } = require('./records');

/** First-column values of a gateway SELECT response ({"data":[{"record_uid":"…"}]}). */
function firstColumn(json) {
  const data = Array.isArray(json && json.data) ? json.data : [];
  return data
    .map((row) => (Array.isArray(row) ? row[0] : row && typeof row === 'object' ? Object.values(row)[0] : null))
    .filter((v) => typeof v === 'string' && v.length > 0);
}

async function existing(sql, table, uids) {
  const found = new Set();
  for (let i = 0; i < uids.length; i += 500) {
    const chunk = uids.slice(i, i + 500);
    if (chunk.length) firstColumn(await sql(selectUids(table, chunk))).forEach((u) => found.add(u));
  }
  return found;
}

/**
 * Validate, insert, read back. `sql(text)` runs SQL on Dataharvester_v2 and resolves to
 * the gateway's JSON (throwing on any error). Returns the acknowledgement for the phone:
 * only record_uids that were sent AND read back, so the phone never marks a row synced
 * that the database does not hold.
 */
async function ingest(body, sql) {
  const batch = validateBatch(body);
  const usageUids = batch.usage.map((r) => r.record_uid);
  const appUids = batch.app.map((r) => r.record_uid);

  // Rows already present (a retry after a lost response) do not count as newly uploaded
  const before = (await existing(sql, 'usage_records', usageUids)).size +
    (await existing(sql, 'app_usage_records', appUids)).size;

  const statements = [
    ...batch.usage.map((r) => insertOrIgnore('usage_records', r)),
    ...batch.app.map((r) => insertOrIgnore('app_usage_records', r)),
  ];
  if (batch.identity) statements.push(upsertIdentity(batch.identity));
  const payload = statements.join('\n');
  if (statements.length) await sql(payload);

  const usageAcked = [...(await existing(sql, 'usage_records', usageUids))];
  const appAcked = [...(await existing(sql, 'app_usage_records', appUids))];

  let identityAcked = false;
  if (batch.identity) {
    const id = batch.identity;
    const rows = firstColumn(await sql(
      `SELECT device_id FROM device_identity WHERE device_id = ${sqlLiteral(id.device_id)} ` +
      `AND study_id = ${sqlLiteral(id.study_id)} AND enrolled_at = ${sqlLiteral(id.enrolled_at)};`
    ));
    identityAcked = rows.includes(id.device_id);
  }

  const attempted = usageUids.length + appUids.length;
  const confirmed = usageAcked.length + appAcked.length;
  if (attempted > 0) {
    const size = Buffer.byteLength(payload, 'utf8');
    try {
      await sql(insertOrIgnore('upload_history', {
        batch_uid: batchUid([...usageUids, ...appUids]),
        device_id: batch.deviceId,
        uploaded_timestamp: Date.now(),
        records_uploaded: Math.max(0, confirmed - before),
        records_attempted: attempted,
        records_confirmed: confirmed,
        payload_size_bytes: size,
        payload_size_kb: size / 1024,
        app_version: batch.appVersion,
      }));
    } catch (e) {
      // Bookkeeping only: the records themselves are confirmed above
      console.warn('upload_history not recorded', e.message);
    }
  }

  return {
    usage_acked: usageAcked,
    app_acked: appAcked,
    // Rows that failed validation: never inserted, and never worth resending unchanged
    usage_rejected: batch.rejected.usage,
    app_rejected: batch.rejected.app,
    identity_acked: identityAcked,
    inserted: Math.max(0, confirmed - before),
  };
}

/** A `sql` function backed by the SQLite Cloud Weblite gateway. */
function gatewaySql({ url, apiKey, database, fetchImpl = fetch }) {
  return async (text) => {
    const r = await fetchImpl(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({ database, sql: text }),
    });
    const raw = await r.text();
    if (!r.ok) throw new Error(`gateway HTTP ${r.status}: ${raw.slice(0, 300)}`);
    const json = raw ? JSON.parse(raw) : {};
    // An error inside a 2xx body still means the SQL did not (fully) run
    for (const key of ['error', 'errors']) {
      if (json[key] !== undefined && json[key] !== null) throw new Error(`gateway error: ${JSON.stringify(json[key]).slice(0, 300)}`);
    }
    return json;
  };
}

module.exports = { ingest, gatewaySql, firstColumn };
