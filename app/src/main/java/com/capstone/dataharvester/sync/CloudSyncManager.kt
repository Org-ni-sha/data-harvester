package com.capstone.dataharvester.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.capstone.dataharvester.BuildConfig
import com.capstone.dataharvester.data.AppDatabase
import com.capstone.dataharvester.util.DeviceIdManager
import com.capstone.dataharvester.util.StudyManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class SyncResult {
    data class Success(val usage: Int, val app: Int) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

class CloudSyncManager(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context),
    private val transport: UploadTransport = defaultTransport()
) {

    companion object {
        private const val TAG = "SyncManager"
        const val BATCH_SIZE = 2000

        /**
         * Held for a whole sync run. The manual upload, SyncWorker and Reset all take it,
         * so two uploads never send the same rows and Reset never deletes mid-upload.
         */
        val syncMutex = Mutex()

        /**
         * The ingest endpoint when the build has one (the APK then carries no database
         * key); otherwise the direct gateway, for local testing only.
         */
        fun defaultTransport(): UploadTransport =
            if (BuildConfig.INGEST_URL.isNotBlank()) {
                IngestTransport(BuildConfig.INGEST_URL, BuildConfig.INGEST_APP_TOKEN)
            } else {
                GatewayTransport(BuildConfig.GATEWAY_URL, BuildConfig.API_KEY, BuildConfig.DB_NAME)
            }

        /**
         * Stable id of a batch: a hash of its record_uids (order-independent), so
         * re-sending the same rows yields the same id and the same history row.
         */
        fun batchUid(recordUids: List<String>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(recordUids.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    /**
     * Uploads the enrolled study's unsynced records in batches.
     *
     * A row is marked synced only after the server confirms it holds it (the transport
     * reads its record_uid back); the device identity likewise only after its row reads
     * back with this enrollment. Unconfirmed rows stay pending and are retried; inserts
     * are keyed on record_uid, so a retry of rows that did arrive is harmless.
     */
    suspend fun syncPendingData(): SyncResult = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncResult {
        // Only the official study's rows go to Dataharvester_v2: every row collected by
        // app 1.6.1 or later (see StudyManager)
        val study = StudyManager(context)
        val studyId = study.studyId()
        val enrolledAt = study.enrolledAt()
        val deviceId = DeviceIdManager(context).getDeviceId()
        val appVersion = BuildConfig.VERSION_NAME

        val usageDao = db.usageDao()
        val appUsageDao = db.appUsageDao()
        val identityDao = db.deviceIdentityDao()

        var totalUsage = 0
        var totalApp = 0
        var identityPending = true

        repeat(500) {
            val usage = usageDao.getUnsyncedBatch(studyId, BATCH_SIZE)
            val app = appUsageDao.getUnsyncedBatch(studyId, BATCH_SIZE)
            // One cloud row per install: send the newest local identity row, once per run
            val identities = if (identityPending) identityDao.getAllUnsynced() else emptyList()
            identityPending = false
            if (usage.isEmpty() && app.isEmpty() && identities.isEmpty()) {
                return SyncResult.Success(totalUsage, totalApp)
            }

            val batch = UploadBatch(
                deviceId = deviceId,
                studyId = studyId,
                appVersion = appVersion,
                usage = usage.map(UploadPayload::usage),
                app = app.map(UploadPayload::app),
                identity = identities.maxByOrNull { it.linked_at }
                    ?.let { UploadPayload.identity(it, appVersion, studyId, enrolledAt) }
            )

            val ack = try {
                transport.upload(batch)
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                return SyncResult.Failure(e.message ?: "Unknown error")
            }

            // Only uids this batch sent may be marked, whatever the server echoes
            val ackedUsage = usage.map { it.recordUid }.filter { it in ack.usageUids }
            val ackedApp = app.map { it.recordUid }.filter { it in ack.appUids }
            val rejectedUsage = usage.map { it.recordUid }.filter { it in ack.usageRejected && it !in ack.usageUids }
            val rejectedApp = app.map { it.recordUid }.filter { it in ack.appRejected && it !in ack.appUids }
            (ack.usageRejected + ack.appRejected).entries.take(5).forEach { (uid, reason) ->
                Log.w(TAG, "Server rejected $uid: $reason")
            }
            db.withTransaction {
                ackedUsage.chunked(500).forEach { usageDao.markAsSyncedByRecordUid(it) }
                ackedApp.chunked(500).forEach { appUsageDao.markAsSyncedByRecordUid(it) }
                rejectedUsage.chunked(500).forEach { usageDao.markRejectedByRecordUid(it) }
                rejectedApp.chunked(500).forEach { appUsageDao.markRejectedByRecordUid(it) }
                if (ack.identityConfirmed && identities.isNotEmpty()) {
                    identityDao.markAsSynced(identities.map { it.id })
                }
            }
            totalUsage += ackedUsage.size
            totalApp += ackedApp.size

            val confirmed = ackedUsage.size + ackedApp.size
            val settled = confirmed + rejectedUsage.size + rejectedApp.size
            if (settled < batch.recordCount) {
                // The rest stay pending; stop here so the retry starts from them
                Log.w(TAG, "Server confirmed $confirmed of ${batch.recordCount} records — rest kept pending")
                return SyncResult.Failure("Server confirmed $confirmed of ${batch.recordCount} records")
            }
            if (batch.identity != null && !ack.identityConfirmed) {
                Log.w(TAG, "Device identity not confirmed — kept pending")
                return SyncResult.Failure("Device identity not confirmed by the server")
            }
            Log.i(TAG, "Batch synced and confirmed $confirmed records")
        }
        return SyncResult.Success(totalUsage, totalApp)
    }
}
