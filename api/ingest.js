// POST /api/ingest — the only write path from phones into Dataharvester_v2.
//
// Server environment (Vercel → Project → Settings → Environment Variables):
//   SQLITECLOUD_GATEWAY_URL  https://<project>.<region>.gateway.sqlite.cloud/v2/weblite/sql
//   SQLITECLOUD_API_KEY      key with INSERT/SELECT on the record tables and
//                            INSERT/UPDATE/SELECT on device_identity (never shipped in an APK)
//   SQLITECLOUD_DB_NAME      Dataharvester_v2
//   INGEST_APP_TOKEN         same value as INGEST_APP_TOKEN in the app's .env
//
// The phone sends JSON rows; this function validates every field (api/_lib/records.js),
// inserts them with its own key and answers with the record_uids it read back.
'use strict';

const { ingest, gatewaySql } = require('./_lib/ingest-core');
const { ValidationError } = require('./_lib/records');

const MAX_BODY_BYTES = 6 * 1024 * 1024;

module.exports = async (req, res) => {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ error: 'POST only' });
  }
  const token = process.env.INGEST_APP_TOKEN;
  if (token && req.headers['x-app-token'] !== token) {
    return res.status(401).json({ error: 'unknown client' });
  }

  let body = req.body;
  if (typeof body === 'string') {
    if (Buffer.byteLength(body) > MAX_BODY_BYTES) return res.status(413).json({ error: 'body too large' });
    try { body = JSON.parse(body); } catch { return res.status(400).json({ error: 'invalid JSON' }); }
  } else if (Buffer.byteLength(JSON.stringify(body || {})) > MAX_BODY_BYTES) {
    return res.status(413).json({ error: 'body too large' });
  }

  const sql = gatewaySql({
    url: process.env.SQLITECLOUD_GATEWAY_URL,
    apiKey: process.env.SQLITECLOUD_API_KEY,
    database: process.env.SQLITECLOUD_DB_NAME,
  });

  try {
    return res.status(200).json(await ingest(body, sql));
  } catch (e) {
    if (e instanceof ValidationError) return res.status(400).json({ error: e.message });
    console.error('ingest failed', e);
    // Nothing is acknowledged, so the phone keeps the rows and retries
    return res.status(502).json({ error: 'database unavailable' });
  }
};
