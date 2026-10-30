package com.capstone.dataharvester.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One upload request: rows of the enrolled study plus, at most once per run, the identity. */
data class UploadBatch(
    val deviceId: String,
    val studyId: String,
    val appVersion: String,
    val usage: List<Map<String, Any>>,
    val app: List<Map<String, Any>>,
    val identity: Map<String, Any>?
) {
    val recordCount get() = usage.size + app.size
}

/** What the server confirmed it holds after the request; only these are marked synced. */
data class UploadAck(
    val usageUids: Set<String>,
    val appUids: Set<String>,
    val identityConfirmed: Boolean,
    /** Rows the server refused as invalid (record_uid → reason); resending them cannot help */
    val usageRejected: Map<String, String> = emptyMap(),
    val appRejected: Map<String, String> = emptyMap()
)

/** The gateway or endpoint answered, but with an error (possibly inside a 2xx response). */
class GatewayException(message: String) : IOException(message)

/**
 * Sends a batch and reports what the server confirmed. Throws on transport or server
 * errors; rows that are not confirmed stay pending and are retried, and every insert is
 * keyed on record_uid, so resending rows that did arrive is harmless.
 */
interface UploadTransport {
    fun upload(batch: UploadBatch): UploadAck
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json".toMediaType()

private fun Any.toJsonValue(): Any = when (this) {
    is Map<*, *> -> JSONObject().also { o -> forEach { (k, v) -> o.put(k as String, v?.toJsonValue()) } }
    is List<*> -> JSONArray().also { a -> forEach { a.put(it?.toJsonValue()) } }
    else -> this
}

private fun JSONArray?.rejections(): Map<String, String> =
    if (this == null) emptyMap() else (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { o -> o.optString("record_uid").takeIf(String::isNotEmpty)?.let { it to o.optString("reason") } }
    }.toMap()

private fun JSONArray?.strings(): Set<String> =
    if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }.toSet()

/**
 * Upload through the ingest endpoint (api/ingest.js). The APK holds no database key:
 * the endpoint validates every record, inserts with its own server-side key and returns
 * the record_uids it read back, so a client can neither read the dataset nor run SQL.
 */
class IngestTransport(private val url: String, private val appToken: String) : UploadTransport {

    override fun upload(batch: UploadBatch): UploadAck {
        val body = JSONObject()
            .put("device_id", batch.deviceId)
            .put("study_id", batch.studyId)
            .put("app_version", batch.appVersion)
            .put("usage", batch.usage.toJsonValue())
            .put("app", batch.app.toJsonValue())
            .put("identity", batch.identity?.toJsonValue() ?: JSONObject.NULL)

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .addHeader("Accept", "application/json")
            // Not a secret (it ships in the APK); only tells casual traffic apart
            .addHeader("X-App-Token", appToken)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GatewayException("HTTP ${response.code}: ${text.take(300)}")
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw GatewayException("Unreadable response: ${text.take(200)}")
            }
            if (json.has("error") && !json.isNull("error")) throw GatewayException("Ingest error: ${json.get("error")}")
            return UploadAck(
                usageUids = json.optJSONArray("usage_acked").strings(),
                appUids = json.optJSONArray("app_acked").strings(),
                identityConfirmed = json.optBoolean("identity_acked", false),
                usageRejected = json.optJSONArray("usage_rejected").rejections(),
                appRejected = json.optJSONArray("app_rejected").rejections()
            )
        }
    }
}

/**
 * Fallback for builds without INGEST_URL: raw SQL to the SQLite Cloud gateway with the
 * key compiled into the APK. Anyone can extract that key, so a release meant for
 * participants should use [IngestTransport]; this path exists for local testing.
 */
class GatewayTransport(
    private val gatewayUrl: String,
    private val apiKey: String,
    private val dbName: String
) : UploadTransport {

    companion object {
        private const val TAG = "GatewayTransport"

        fun sqlStr(value: String?): String = if (value == null) "NULL" else "'${value.replace("'", "''")}'"

        private fun literal(v: Any): String = when (v) {
            is String -> sqlStr(v)
            is Boolean -> if (v) "1" else "0"
            is Number -> v.toString()
            else -> sqlStr(v.toString())
        }

        fun insertOrIgnore(table: String, row: Map<String, Any>): String =
            "INSERT OR IGNORE INTO $table (${row.keys.joinToString()}) " +
                "VALUES (${row.values.joinToString { literal(it) }});"
    }

    override fun upload(batch: UploadBatch): UploadAck {
        val usageUids = batch.usage.map { it["record_uid"] as String }
        val appUids = batch.app.map { it["record_uid"] as String }

        // Rows the server already holds (a retry after a lost response): the history
        // then counts only rows this batch really added
        val alreadyThere = fetchExisting("usage_records", usageUids).size +
            fetchExisting("app_usage_records", appUids).size

        val sql = StringBuilder()
        batch.usage.forEach { sql.append(insertOrIgnore("usage_records", it)).append('\n') }
        batch.app.forEach { sql.append(insertOrIgnore("app_usage_records", it)).append('\n') }
        batch.identity?.let { id ->
            sql.append("INSERT INTO device_identity (${id.keys.joinToString()}) VALUES (${id.values.joinToString { literal(it) }}) ")
            sql.append("ON CONFLICT(device_id) DO UPDATE SET ")
            sql.append(id.keys.filter { it != "device_id" }.joinToString { "$it = excluded.$it" })
            sql.append(";\n")
        }
        val payloadSize = sql.toString().toByteArray(Charsets.UTF_8).size
        if (sql.isNotEmpty()) postSql(sql.toString())

        // Acknowledge only what the server confirms it holds
        val ackedUsage = fetchExisting("usage_records", usageUids)
        val ackedApp = fetchExisting("app_usage_records", appUids)
        val identityConfirmed = batch.identity?.let { id ->
            val check = "SELECT device_id FROM device_identity WHERE device_id = ${sqlStr(id["device_id"] as String)} " +
                "AND study_id = ${sqlStr(id["study_id"] as String)} AND enrolled_at = ${id["enrolled_at"]};"
            rows(postSql(check)).isNotEmpty()
        } ?: false

        if (batch.recordCount > 0) {
            val confirmed = ackedUsage.size + ackedApp.size
            val history = linkedMapOf<String, Any>(
                "batch_uid" to CloudSyncManager.batchUid(usageUids + appUids),
                "device_id" to batch.deviceId,
                "uploaded_timestamp" to System.currentTimeMillis(),
                "records_uploaded" to (confirmed - alreadyThere).coerceAtLeast(0),
                "records_attempted" to batch.recordCount,
                "records_confirmed" to confirmed,
                "payload_size_bytes" to payloadSize,
                "payload_size_kb" to payloadSize / 1024.0,
                "app_version" to batch.appVersion
            )
            try {
                postSql(insertOrIgnore("upload_history", history))
            } catch (e: Exception) {
                Log.w(TAG, "Upload history not recorded (records are already confirmed)", e)
            }
        }
        return UploadAck(ackedUsage, ackedApp, identityConfirmed)
    }

    private fun fetchExisting(table: String, uids: List<String>): Set<String> =
        uids.chunked(500).flatMap { chunk ->
            rows(postSql("SELECT record_uid FROM $table WHERE record_uid IN (${chunk.joinToString(",") { sqlStr(it) }});"))
        }.toSet()

    /**
     * First-column values of a SELECT response. Rows come as objects ({"record_uid": "…"},
     * the shape the Dataharvester_v2 gateway returns) or arrays; anything unrecognised
     * yields no acknowledgement, so rows stay pending rather than being marked synced.
     */
    private fun rows(json: JSONObject): List<String> {
        val data = json.opt("data") as? JSONArray ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            when (val row = data.opt(i)) {
                is JSONObject -> row.keys().asSequence().firstOrNull()?.let { row.optString(it) }?.takeIf { it.isNotEmpty() }
                is JSONArray -> row.optString(0).takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    private fun postSql(sql: String): JSONObject {
        val body = JSONObject().put("database", dbName).put("sql", sql)
        val request = Request.Builder()
            .url(gatewayUrl)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GatewayException("HTTP ${response.code}: $text")
            val json = if (text.isBlank()) JSONObject() else try {
                JSONObject(text)
            } catch (e: Exception) {
                throw GatewayException("Unreadable response: ${text.take(200)}")
            }
            // An error in a 2xx body still means the SQL did not (fully) run
            for (key in listOf("error", "errors")) {
                if (json.has(key) && !json.isNull(key)) throw GatewayException("Gateway error: ${json.get(key)}")
            }
            return json
        }
    }
}
