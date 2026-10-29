# mod/cloud-issues — remaining findings: current code vs. proposed fix

Source: teammate review "todo(fixing) v2" of `mod/cloud-issues` @ `628e0a7` (APK 1.6.1).
Every "Current" snippet below was checked against `628e0a7`; line numbers refer to that commit.
## Implementation status — 1.6.3 (versionCode 9), not committed yet

All 10 fixes and the Option A transition are implemented. Room schema v11 (`MIGRATION_10_11`) holds every local schema change.
**Verified:** `compileDebugKotlin`, `compileDebugAndroidTestKotlin` and `testDebugUnitTest` pass without a `.env`; `assembleDebug` is still blocked without one; the generated `schemas/11.json` matches the migration SQL.
**Not verified:** nothing has run on a phone, and `MigrationTest` (androidTest) needs a device or emulator: `./gradlew connectedDebugAndroidTest`.

Where the code differs from the plan below:
- **#2:** no `BEGIN`/`COMMIT` around the upload script. A pooled gateway connection could be left inside an open transaction after a failing statement. The read-back by `record_uid` already makes a partial failure safe: rows that arrived are confirmed, and the rest stay pending and are retried.
- **#3:** besides `specialUse`, `onTimeout` and the guarded boot start, the dashboard now restarts collection itself when it should be running (`PREF_IS_COLLECTING`) but Android refused the background restart. The receiver's "tap to resume" notification opens the dashboard.
- **#9:** the first per-app reading after the upgrade (or after Reset) only stores baselines, so up to 10 minutes of per-app usage is not split per app at that point.
- **#10:** the 1.6.1 rows already in v2 have `record_uid = NULL`. If a local row was uploaded by 1.6.1 but the phone never saw the response (`is_synced = 0`), 1.6.3 uploads it again with a `record_uid`. Dedupe on `(device_id, local_id, timestamp)` during the final merge (the merge rule below already dedupes on `(device_id, timestamp)`).
- **Test guard (correction):** the review was right that `testDebugUnitTest` stopped. The prefix match `startsWith("bundle")` also hit internal tasks such as `bundleDebugClassesToCompileJar`. The guard now matches only `assemble`/`bundle`/`install` with an optional `Debug`/`Release`.

**Cloud step before shipping:** run [`cloud_schema_v2_upgrade_1.6.3.sql`](../cloud_schema_v2_upgrade_1.6.3.sql) on the existing `Dataharvester_v2`. A fresh database needs only `cloud_schema_v2.sql`, which now includes the same changes. Bump `update.json` only after the 1.6.3 APK is uploaded.

The sections below are the original plan, kept for review.

## Summary

| # | Sev | Finding | Status at 628e0a7 | Proposed fix (short) | Touches |
|---|-----|---------|-------------------|----------------------|---------|
| 1 | High | Row and checkpoint not atomic (crash → bytes double-counted) | **Confirmed**: Room insert, then SharedPreferences `apply()` | Room `collection_checkpoint` table, written in the same `withTransaction` as the rows | `DataCollectionService`, `TrafficStatsHelper`, new entity/DAO, Room v11 |
| 2 | High | HTTP 2xx marks every selected row synced | **Confirmed**: `response.isSuccessful` → mark all IDs | Wrap upload in `BEGIN/COMMIT`, check body for errors, then `SELECT local_id` back and mark only the IDs found | `CloudSyncManager` |
| 3 | High | `dataSync` FGS: Android 15 6 h timeout + boot-start ban | **Confirmed**: `foregroundServiceType="dataSync"`, no `onTimeout`, `BootReceiver` starts it | Switch to `specialUse`, add `onTimeout`, guard boot start, restart after OTA update | Manifest, `DataCollectionService`, `BootReceiver` |
| 4 | High | No interval duration or quality status | **Confirmed**: `UsageRecord` has only `timestamp` | Add `interval_start/end`, `elapsed_ms`, `measurement_status` (OK/BASELINE/COUNTER_RESET/UNSUPPORTED/GAP) | `UsageRecord`, `TrafficStatsHelper`, collector, cloud schema |
| 5 | High | Mobile bytes labelled `WIFI` | **Confirmed**: bytes from `getMobileRxBytes()`, label = active network at tick time | Tick on every network switch; `WIFI` only if Wi-Fi the whole interval **and** mobile bytes ≤ noise; keep raw `active_network_at_sample` | collector, `DeviceInfoHelper`, `UsageRecord` |
| 6 | Med | Shared `SimpleDateFormat` across two coroutines | **Confirmed**: one `isoFormat` used by both pipelines on `Dispatchers.IO` | Create formatters per call (or one per pipeline) | `DataCollectionService` |
| 7 | Med | Wi-Fi failure blocks mobile per-app rows | **Confirmed**: `failed = mobile == null \|\| wifi == null` drops both | Separate window and checkpoint per transport | `NetworkStatsHelper`, collector, checkpoint table |
| 8 | Med | Reset deletes exported CSV backups | **Confirmed**: `deleteExportedFiles()` at `MainActivity.kt:299` | "Upload first?" modal (closable) → reset confirmation; keep exports by default; lock against collector and sync | `MainActivity`, DAOs, `CloudSyncManager` |
| 9 | High | Per-app windows (10 min) shorter than NetworkStats buckets (~2 h) | **New finding**; exact OEM behaviour to verify on a device | Cumulative query from a UTC-midnight anchor to the future, subtract the previous snapshot | `NetworkStatsHelper`, collector, new `app_usage_baseline` table |
| 10 | High | Cloud unique keys silently drop real rows (timestamp collision, `local_id` reused after backup restore) | **New finding** | Per-row `record_uid` as the only unique key; exclude IDs/DB from Android backup | entities, `CloudSyncManager`, backup XML, cloud schema |
| — | **Decided: A** | Old rows' `is_synced` carried into `Dataharvester_v2` | **Confirmed** | `collection_version` column; legacy `is_synced` left as is, so each row lands in exactly one DB for the final sum | Room v11 migration, schema |

Fixes 1, 4, 5, 7, 9, 10 and the `collection_version` column all need a new Room schema (v11). **Make them in one migration** (`MIGRATION_10_11`) so testers only go through one upgrade.

---

## 1. [High] Measurement and checkpoint are not committed atomically

### Current — `DataCollectionService.kt:379-388`
```kotlin
dao.insert(record)                       // ① committed to Room (SQLite)

trafficHelper.commit(delta)              // ② in-memory baseline moved
prefs.edit()                             // ③ checkpoint written to a DIFFERENT store,
    .putLong(PREF_LAST_CUMULATIVE_RX, …) //    asynchronously (apply())
    .putLong(PREF_LAST_CUMULATIVE_TX, …)
    .apply()
```
If the process dies between ① and ③ (including while `apply()` is still pending), the row is saved but the restart reads the **old** RX/TX baseline. The next tick counts the same bytes again.
The per-app path (`:492-495`) has the same gap: `insertAll(records)` and then `prefs…PREF_LAST_APP_COLLECTION_TIME`.

### Proposed
Move all checkpoints into Room so the rows and the checkpoint commit in one SQLite transaction.

```kotlin
// data/CollectionCheckpoint.kt  (single-row table)
@Entity(tableName = "collection_checkpoint")
data class CollectionCheckpoint(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "mobile_rx") val mobileRx: Long = -1L,
    @ColumnInfo(name = "mobile_tx") val mobileTx: Long = -1L,
    /** SystemClock.elapsedRealtime() of the last committed tick (see #4) */
    @ColumnInfo(name = "last_tick_elapsed") val lastTickElapsed: Long = -1L,
    @ColumnInfo(name = "last_tick_wall") val lastTickWall: Long = -1L,
    /** Per-transport per-app windows (see #7) */
    @ColumnInfo(name = "app_window_mobile") val appWindowMobile: Long = -1L,
    @ColumnInfo(name = "app_window_wifi") val appWindowWifi: Long = -1L
)

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM collection_checkpoint WHERE id = 0")
    suspend fun get(): CollectionCheckpoint?

    @Upsert   // Room 2.7.1 is already in libs.versions.toml
    suspend fun upsert(checkpoint: CollectionCheckpoint)

    @Query("DELETE FROM collection_checkpoint")
    suspend fun clear()
}
```

```kotlin
// DataCollectionService.collectData()
val db = AppDatabase.getInstance(this@DataCollectionService)
val cp = db.checkpointDao().get() ?: CollectionCheckpoint()
// … build record …
db.withTransaction {
    db.usageDao().insert(record)
    db.checkpointDao().upsert(
        cp.copy(
            mobileRx = delta.currentRx, mobileTx = delta.currentTx,
            lastTickElapsed = nowElapsed, lastTickWall = timestamp
        )
    )
}
trafficHelper.commit(delta)   // move the in-memory baseline only after the commit
```

- `TrafficStatsHelper.initializeFromSavedState()` reads from `checkpointDao().get()` in `onCreate` (run it inside `serviceScope` before the first tick, or `runBlocking` once in `onCreate`).
- **One-time hand-over:** if the checkpoint row is missing but the old prefs keys exist, copy them into Room and then remove the prefs keys. A Room `Migration` has no `Context`, so do this in the service, not in the migration.
- `MIGRATION_10_11`: `CREATE TABLE collection_checkpoint (...)` together with the columns from #4, #5 and the decision item.
- **Test:** an instrumented test that inserts a row, kills the process (or throws) right after the insert inside the transaction, and asserts that either both the row and the checkpoint exist or neither does.

---

## 2. [High] Upload acknowledgement trusts HTTP 2xx only

### Current — `CloudSyncManager.kt:121-141`
```kotlin
if (response.isSuccessful) {
    db.withTransaction {
        usageDao.markAsSynced(<every id in the batch>)
        appUsageDao.markAsSynced(<every id in the batch>)
        …
    }
}
```
The body is never read. Many SQL gateways (SQLite Cloud's included) can return 200 with an error in the body. The script is also many separate statements without a transaction, so statement 1,500 can fail after 1,499 have committed. Both cases mark all 2,000 rows synced and lose them.

### Proposed
1. **Atomic on the server:** send `BEGIN;` … inserts … `upload_history` … `COMMIT;`. A failing statement then rolls the whole batch back.
2. **Check the body:** parse the JSON and treat any `error` / `errors` field (or a missing `data`) as a failure, even on 2xx.
3. **Acknowledge by ID:** with a separate small request, read back which `(device_id, local_id)` pairs now exist, and mark only those. A dropped connection after the server accepted the batch is then safe: the retry's `INSERT OR IGNORE` does nothing and the read-back still confirms the rows.

```kotlin
// after the insert request returned 2xx and the body had no error:
val ackedUsage = fetchExistingLocalIds("usage_records", unsyncedUsage.map { it.deviceId to it.id })
val ackedApp   = fetchExistingLocalIds("app_usage_records", unsyncedAppUsage.map { it.deviceId to it.id })

db.withTransaction {
    ackedUsage.chunked(500).forEach { usageDao.markAsSynced(it) }
    ackedApp.chunked(500).forEach { appUsageDao.markAsSynced(it) }
    if (isFirstBatch && unsyncedIdentity.isNotEmpty()) deviceIdentityDao.markAsSynced(unsyncedIdentity.map { it.id })
}
if (ackedUsage.size < unsyncedUsage.size || ackedApp.size < unsyncedAppUsage.size) {
    return SyncResult.Failure("Server confirmed ${ackedUsage.size + ackedApp.size}/$totalSynced rows")  // the rest stay pending → retry
}

private fun fetchExistingLocalIds(table: String, keys: List<Pair<String, Int>>): List<Int> =
    keys.groupBy({ it.first }, { it.second }).flatMap { (deviceId, ids) ->
        ids.chunked(500).flatMap { chunk ->
            val sql = "SELECT local_id FROM $table WHERE device_id = ${sqlStr(deviceId)} " +
                      "AND local_id IN (${chunk.joinToString(",")});"
            runQuery(sql).map { it.getInt("local_id") }   // runQuery = POST + body error check + parse "data"
        }
    }
```

- Confirm the gateway's exact success and error JSON with one real call before writing the parser. Do not guess the shape.
- **Superseded by #10:** acknowledge by `record_uid` instead of `(device_id, local_id)`. Same flow, but the read-back becomes `SELECT record_uid FROM … WHERE record_uid IN (…)`, and it's immune to `local_id` reuse after a backup restore.
- **Tests (MockWebServer):** (a) 200 with an error body → nothing marked; (b) 200, read-back returns half the IDs → only that half marked; (c) insert request times out after the server committed → the retry marks everything with no duplicates.

---

## 3. [High] Background-service restrictions (Android 14/15+)

### Current
- `AndroidManifest.xml:84`: `android:foregroundServiceType="dataSync"`, with `targetSdk = 36`.
- `DataCollectionService` has no `onTimeout()`.
- `BootReceiver.kt:39`: `context.startForegroundService(...)` on `BOOT_COMPLETED`.

When targeting Android 15:
- A `dataSync` FGS may run only **6 h per 24 h**. The system then calls `Service.onTimeout(int, int)`, and if the service has not stopped within seconds the app crashes. Continuous 30-second collection is therefore impossible with this type.
- A `BOOT_COMPLETED` receiver **may not start a `dataSync` FGS**. It throws `ForegroundServiceStartNotAllowedException`. The boot restart fails on Android 15 phones.
- After an OTA self-update (`UpdateManager`) the service is killed and nothing restarts it.

The battery-optimization exemption does not lift any of these restrictions.

### Proposed
Continuous measurement is not "data sync", and uploads already run separately in `SyncWorker` (WorkManager). Declare the collector as **`specialUse`**. It has no runtime cap and may be started from `BOOT_COMPLETED`. Fine for sideloaded APKs; Play Store review would require a justification.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".DataCollectionService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Continuous on-device measurement of network data usage for a consented research study" />
</service>

<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />  <!-- restart after OTA update -->
    </intent-filter>
</receiver>
```

```kotlin
// DataCollectionService.onStartCommand
ServiceCompat.startForeground(
    this, NOTIFICATION_ID, notification,
    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
)

// Defensive: if any time-limited type is ever used again
override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "FGS timeout ($fgsType) — stopping cleanly")
    stopSelf()   // keep PREF_IS_COLLECTING = true so the next start resumes; #4 records the gap
}
```

```kotlin
// BootReceiver
if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
    intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
…
try {
    ContextCompat.startForegroundService(context, serviceIntent)
} catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on 31+
    Log.e(TAG, "Not allowed to restart collection", e)
    showResumeCollectionNotification(context)   // "Tap to resume data collection"
}
```

Optional: a 15-minute WorkManager watchdog that restarts the service if `PREF_IS_COLLECTING` is true but the service is not running. Apps exempt from battery optimization may start an FGS from the background.

**Verify** on an Android 15 or 16 phone: reboot, OTA update, and an overnight run past 6 h.

---

## 4. [High] Primary rows lack interval duration and quality status

### Current — `DataCollectionService.kt:355-377`, `TrafficStatsHelper.kt:75-110`
`UsageRecord` has only `timestamp`. A row after a 3-hour Doze freeze looks like a normal 30 s sample. `TrafficStatsHelper` returns `UsageDelta(0,0,0,false)` for **UNSUPPORTED** and for the **first-run baseline**. A counter reset gives `wasReboot = true`, but that flag is only logged and never stored. All three look like real zero usage in the dataset.

Reboot detection also relies only on `current < last`. A reboot after which the counters climb past the old value goes undetected.

### Proposed
```kotlin
// UsageRecord — new columns (Room v11 + cloud schema + CloudSyncManager INSERT)
@ColumnInfo(name = "interval_start") val intervalStart: Long,          // wall-clock ms of previous committed tick
@ColumnInfo(name = "interval_end") val intervalEnd: Long,              // = timestamp
@ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,                  // monotonic, from elapsedRealtime; -1 if unknown
@ColumnInfo(name = "measurement_status") val measurementStatus: String // OK | BASELINE | COUNTER_RESET | UNSUPPORTED | GAP
```

```kotlin
// TrafficStatsHelper
enum class Status { OK, BASELINE, COUNTER_RESET, UNSUPPORTED }
data class UsageDelta(…, val status: Status)

// DataCollectionService.collectData()
const val GAP_THRESHOLD_MS = 2 * COLLECTION_INTERVAL_MS + 15_000L

val nowElapsed = SystemClock.elapsedRealtime()
val rebootedSinceLastTick = cp.lastTickElapsed >= 0 && nowElapsed < cp.lastTickElapsed
val delta = trafficHelper.getUsageDelta(forceReset = rebootedSinceLastTick)
val elapsedMs = if (cp.lastTickElapsed >= 0 && !rebootedSinceLastTick) nowElapsed - cp.lastTickElapsed else -1L

val status = when {
    delta.status != Status.OK -> delta.status.name
    elapsedMs > GAP_THRESHOLD_MS -> "GAP"   // bytes are real, but spread over a long, unknown window
    else -> "OK"
}
```
- `GAP` rows keep their bytes, which are real usage, and now carry the true `elapsed_ms`. Preprocessing can spread them over time or drop them on purpose.
- `elapsedRealtime()` going backwards is a reliable reboot signal, replacing the `current < last` guess.
- Legacy rows get `measurement_status = 'LEGACY'` and `elapsed_ms = -1` in `MIGRATION_10_11`.

---

## 5. [High] Mobile bytes can be labelled as Wi-Fi

**Goal (team decision):** rows taken while the user really is on Wi-Fi stay labelled `WIFI`; data cleaning will drop them. The fix must only ensure that **no row holding mobile bytes is labelled `WIFI`**, so cleaning never removes real mobile usage.

### Current
`TrafficStatsHelper.kt:71-72` reads **mobile-only** counters (`getMobileRxBytes/TxBytes`). `DataCollectionService.kt:320` labels the row with `deviceHelper.getNetworkType()`, which is the network active **at the moment the tick fires**, not during the 30 s the bytes came from. Two ways mobile bytes end up in a `WIFI` row:

1. **Switch inside an interval.** Mobile from 0–20 s, Wi-Fi joins at 20 s, tick at 30 s → 20 s of mobile bytes labelled `WIFI`. This is the common case (arriving home, joining campus Wi-Fi).
2. **Cellular while Wi-Fi is the default.** Weak-Wi-Fi fallback ("Adaptive Wi-Fi", "Wi-Fi+"), MMS, or carrier apps pinned to cellular. The mobile counter rises while the active network says Wi-Fi.

### Proposed
Three layers, cheapest first:

**a) Close the interval at every switch.** The service already has a network callback (`DataCollectionService.kt:222-259`) that triggers a per-app snapshot on switch. Have it also run a primary tick, so the bytes before the switch are saved under the old label.
```kotlin
// in onCapabilitiesChanged / onLost, next to collectAppData():
collectData(trigger = "NETWORK_SWITCH")   // same collectMutex → never overlaps the 30 s tick
```

**b) Label by the interval, not the instant.** Track which transports were active during the interval (the callback sets a flag), and label `WIFI` only if Wi-Fi was active **for the whole interval**.
```kotlin
@Volatile private var cellularSeenThisInterval = false   // set by the callback; reset after each committed tick

val label = when {
    activeNow == "WIFI" && !cellularSeenThisInterval -> "WIFI"
    activeNow == "WIFI" -> deviceHelper.getCellularRadioType()   // mixed interval → mobile label
    else -> deviceHelper.getNetworkType()                        // LTE / 5G / 3G / …
}
```

**c) Safety net: bytes override the label.** The counter only measures mobile traffic, so any row with a mobile delta above noise is mobile usage, whatever the active network says.
```kotlin
const val MOBILE_NOISE_BYTES = 4_096L   // keep-alives/signalling; tune after a test run

val networkType = if (label == "WIFI" && delta.bytesTotal > MOBILE_NOISE_BYTES)
    deviceHelper.getCellularRadioType()       // e.g. "LTE": cleaning keeps this row
else label

// keep the raw context for auditing / the paper
@ColumnInfo(name = "active_network_at_sample") val activeNetworkAtSample: String,  // what getNetworkType() said
```
- `DeviceInfoHelper`: make `getCellularType()` public as `getCellularRadioType()`. `TelephonyManager.dataNetworkType` still reports the radio while Wi-Fi is active.
- Result for cleaning: `network_type = 'WIFI'` now means "on Wi-Fi the whole interval **and** ≤ 4 KB of mobile traffic". Dropping those rows loses at most noise.
- **Test:** turn mobile data on, start a download, join Wi-Fi mid-interval → the pre-switch bytes must be in a row labelled `LTE`/`5G`, and the `WIFI` rows must be ≈ 0 bytes.

---

## 6. [Medium] Shared, thread-unsafe `SimpleDateFormat`

### Current — `DataCollectionService.kt:110-111`
```kotlin
private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
```
`collectData()` (`:329`) and `collectAppData()` (`:465-466`) run on `Dispatchers.IO` under **different** mutexes, so they can call `isoFormat.format` at the same moment. `SimpleDateFormat` is not thread-safe and can produce corrupted strings.

### Proposed
`minSdk = 23` rules out `java.time` without desugaring. The simplest safe change is to create the formatter where it is used. That is cheap at one tick per 30 s, and `CloudSyncManager` already does it.
```kotlin
private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
private fun dateOnlyFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
// usage: isoFormat().format(now.time)
```
(Alternative: enable `coreLibraryDesugaring` and use `DateTimeFormatter`, which is immutable.)

---

## 7. [Medium] One failed transport discards both per-app results

### Current — `NetworkStatsHelper.kt:108-115`, `DataCollectionService.kt:452-455`
```kotlin
return AppUsageQueryResult(results, failed = mobile == null || wifi == null)
…
if (result.failed) { …; return@withLock }   // valid MOBILE rows thrown away too
```
A Wi-Fi query that fails every time (seen on some OEM builds) blocks mobile per-app data indefinitely, and the shared window keeps growing.

### Proposed
One window and one checkpoint per transport, each committed with its own rows (this builds on #1):
```kotlin
// NetworkStatsHelper — make the per-transport query public
fun queryTransport(transport: Transport, start: Long, end: Long): List<AppUsageSnapshot>?  // null = failed

// DataCollectionService.collectAppData()
for (t in Transport.values()) {   // MOBILE, WIFI
    val start = cp.windowFor(t).takeIf { it > 0 } ?: (now - APP_COLLECTION_INTERVAL_MS)
    val snaps = networkStatsHelper.queryTransport(t, start, now)
        ?: run { Log.w(TAG, "$t query failed — window kept"); continue }
    db.withTransaction {
        if (snaps.isNotEmpty()) db.appUsageDao().insertAll(toRecords(snaps, start, now))
        db.checkpointDao().upsert(db.checkpointDao().get()!!.withWindow(t, now))
    }
}
```
`query_start` / `start_time` then come from each transport's own window. Optionally, log a warning (or store a status row) once a transport has failed for more than 24 h.

---

## 8. [Medium] Reset deletes exported backups

### Current — `MainActivity.kt:284-300`
```kotlin
stopService(Intent(this, DataCollectionService::class.java))   // asynchronous: a tick may still be running
withContext(Dispatchers.IO) {
    db.usageDao().deleteAll()
    db.appUsageDao().deleteAll()
    CsvExporter(this@MainActivity).deleteExportedFiles()         // recovery copy gone
}
```
Two further problems: `stopService` returns before an in-flight tick finishes, so a row can be inserted right after `deleteAll()`. And `SyncWorker` ("CloudSQLiteSync") can be uploading or marking rows during the reset.

### Proposed flow (team decision: offer an upload first)

```
[Reset Data] tapped
   │
   ▼
Modal 1 — "Upload before resetting?"            (cancelable: ✕ / back / tap outside = abort, nothing happens)
   "N records have not been uploaded yet."
   [Upload first]            [Skip upload]
   │                          │
   ▼                          │
progress dialog "Uploading…"  │
   │ success / failure        │
   ▼                          ▼
Modal 2 — "Reset all data?"                     (always shown, whichever button was chosen; also cancelable)
   • X usage records, Y per-app records
   • ⚠ Z records are NOT uploaded and will be lost      (Z = 0 after a successful upload)
   • (if the upload failed) "Upload failed: <reason>"
   □ Also delete exported CSV files                       (unchecked by default)
   [Cancel]                  [Reset]
```
If Modal 1 shows 0 pending records (everything is uploaded), skip it and go straight to Modal 2.

```kotlin
private fun confirmReset() = mainScope.launch {
    val pending = withContext(Dispatchers.IO) { db.usageDao().countUnsynced() + db.appUsageDao().countUnsynced() }
    if (pending == 0) { showResetConfirmation(uploadError = null); return@launch }

    AlertDialog.Builder(this@MainActivity)
        .setTitle("Upload before resetting?")
        .setMessage("$pending records have not been uploaded to the cloud yet.")
        .setPositiveButton("Upload first") { _, _ -> uploadThenConfirm() }
        .setNegativeButton("Skip upload") { _, _ -> showResetConfirmation(uploadError = null) }
        .setCancelable(true)                     // closing = abort the reset
        .show()
}

private fun uploadThenConfirm() = mainScope.launch {
    val progress = showProgressDialog("Uploading…")
    val result = withContext(Dispatchers.IO) {
        if (!ConsentManager(this@MainActivity).isAccepted()) SyncResult.Failure("Consent not given")
        else CloudSyncManager(this@MainActivity).syncPendingData()     // takes CloudSyncManager.syncMutex
    }
    progress.dismiss()
    showResetConfirmation(uploadError = (result as? SyncResult.Failure)?.reason)
}

private fun showResetConfirmation(uploadError: String?) = mainScope.launch {
    val (main, app, pending) = withContext(Dispatchers.IO) { Triple(…getCount(), …getCount(), …countUnsynced()) }
    val deleteExports = CheckBox(this@MainActivity).apply { text = "Also delete exported CSV files" }
    AlertDialog.Builder(this@MainActivity)
        .setTitle("Reset all data?")
        .setMessage(buildString {
            append("• $main usage records\n• $app per-app records\n")
            if (pending > 0) append("\n⚠ $pending records are NOT uploaded and will be lost.\n")
            uploadError?.let { append("\nUpload failed: $it\n") }
        })
        .setView(deleteExports)
        .setPositiveButton("Reset") { _, _ -> performReset(deleteExports.isChecked) }
        .setNegativeButton("Cancel", null)
        .setCancelable(true)
        .show()
}

private fun performReset(deleteExports: Boolean) = mainScope.launch {
    stopService(Intent(this@MainActivity, DataCollectionService::class.java))
    WorkManager.getInstance(this@MainActivity).cancelUniqueWork("CloudSQLiteSync").await()

    withContext(Dispatchers.IO) {
        CloudSyncManager.syncMutex.withLock {          // no upload can be mid-flight
            CollectionLocks.db.withLock {              // no collector tick can be mid-flight
                db.withTransaction {
                    db.usageDao().deleteAll()
                    db.appUsageDao().deleteAll()
                    db.checkpointDao().clear()         // replaces the prefs .remove(...) calls
                }
            }
        }
        if (deleteExports) CsvExporter(this@MainActivity).deleteExportedFiles()
    }
    scheduleSync()   // re-enqueue the periodic upload
    …
}
```
- New DAO queries: `SELECT COUNT(*) FROM usage_records WHERE is_synced = 0` (and the same for `app_usage_records`).
- `CloudSyncManager.syncMutex` is a `companion object` `Mutex` that `syncPendingData()` takes for its whole run. The manual upload and `SyncWorker` then never upload the same rows twice, and the reset waits for an upload in progress.
- `CollectionLocks` is a small `object` holding a `Mutex`. The service's two tick bodies take it around their `withTransaction`.
- The "Upload first" path uses the fixed acknowledgement from #2, so "0 records not uploaded" in Modal 2 is true.

---

## Decided — cloud transition to `Dataharvester_v2` (Option A)

**Team decision:** Option A, a clean v2. At the end of data gathering, `Dataharvester_v2` and the old `DATAra_harvester` will be combined and summed, so **every measurement must end up in exactly one of the two databases**: never in both (double count) and never in neither (lost).

### Current
`cloud_schema_v2.sql` describes a fresh database, but `MIGRATION_9_10` keeps each local row's `is_synced`:
- rows already uploaded to the old DB (`is_synced = 1`) **never** reach v2 ✔ (correct for a sum: they are counted once, in the old DB);
- old pending rows (`is_synced = 0`) **do** upload to v2. They are not in the old DB, so this is also correct, but nothing marks them as pre-fix data.

### ⚠ Correction to Option A as first written
The first draft of Option A said `SET is_synced = 1` on **all** legacy rows. That would also flag the legacy rows that were **never uploaded anywhere**. The app now only talks to v2, so those rows would reach neither database and be **missing from the final sum**. Option A has to keep them pending:

```kotlin
// MIGRATION_10_11 (part)
db.execSQL("ALTER TABLE usage_records ADD COLUMN collection_version TEXT NOT NULL DEFAULT 'legacy'")
db.execSQL("ALTER TABLE app_usage_records ADD COLUMN collection_version TEXT NOT NULL DEFAULT 'legacy'")
// is_synced is deliberately NOT touched:
//   is_synced = 1 → already in DATAra_harvester → stays out of v2 (no double count)
//   is_synced = 0 → in neither DB → uploads to v2 tagged 'legacy' (not lost)
```
New rows get `collection_version = BuildConfig.VERSION_NAME` (for example `"1.6.3"`), set at insert and uploaded. The cloud tables gain the same column (`ALTER TABLE … ADD COLUMN collection_version TEXT NOT NULL DEFAULT 'legacy'`).

**What "clean v2" means after this:** analysis of the fixed pipeline uses `WHERE collection_version <> 'legacy'`. The final summed dataset uses everything from both databases.

### Merge rule for the final sum
- Dedupe across the two databases on **`(device_id, timestamp)`** for `usage_records`, and **`(device_id, timestamp, uid, network_type)`** for `app_usage_records`. This catches a row the old gateway accepted while the phone never saw the response (bug #2 in the old version), which would otherwise be in both.
- Don't dedupe on `local_id` across databases. The old DB doesn't guarantee it, and the duplicate-key section below explains why `local_id` is unsafe.
- Rows tagged `legacy` lack the #4/#5 quality columns. Treat their `network_type` with the old (instant) meaning when cleaning.

---

## 9. [High, not in the review] Per-app numbers may be wrong: windows shorter than NetworkStats buckets

### Current — `NetworkStatsHelper.kt:134-139`, `DataCollectionService.kt:441-448`
Every 10 minutes (and on each network switch) the collector asks `querySummary(type, null, lastCollectionTime, now)` for **only that short window**. Android doesn't store per-app traffic at that resolution. It keeps per-UID history in **buckets about 2 hours long** (the exact length depends on the OEM). A query for a 10-minute slice of a 2-hour bucket can't be answered exactly. Depending on the Android version, the system either:
- **interpolates:** returns `overlap ÷ bucket length × bytes so far` in that bucket. For the bucket still in progress, the bytes counted later in the bucket are never fully assigned to earlier windows, so the windows **don't add up to the real total** (systematic under-count) and usage is smeared evenly across the 2 h; or
- **counts whole buckets only:** most 10-minute windows return 0, and an occasional one returns a whole bucket.

Both cases distort per-app timing. The first also distorts the totals. The first-draft note above assumed only the second case. Which one a given phone does must be **checked on a device**, but the fix below is correct either way.

### Proposed — cumulative snapshots, then subtract (the same idea as TrafficStats)
Never ask for a short window. Instead, ask "how much has each app used **since a fixed anchor**, up to *now*", and subtract the previous answer. Two details make the cumulative number exact:
1. **The anchor sits on a bucket boundary:** UTC midnight, which is a multiple of 1, 2, 4, … hours. No partial bucket at the start.
2. **The end time is in the future** (`now + 1 day`): the in-progress bucket then counts as fully inside the range, so all bytes recorded so far are included instead of a fraction of them.

```kotlin
// data/AppUsageBaseline.kt — last cumulative value per (uid, transport, anchor)
@Entity(tableName = "app_usage_baseline", primaryKeys = ["uid", "transport"])
data class AppUsageBaseline(
    val uid: Int,
    val transport: String,                 // MOBILE / WIFI
    @ColumnInfo(name = "anchor") val anchor: Long,      // UTC midnight this cumulative is counted from
    @ColumnInfo(name = "rx") val rx: Long,
    @ColumnInfo(name = "tx") val tx: Long,
    @ColumnInfo(name = "taken_at") val takenAt: Long    // wall-clock ms of the snapshot
)
```

```kotlin
// NetworkStatsHelper — cumulative usage per UID since the anchor (null = query failed)
fun queryCumulative(transport: Transport, anchor: Long, now: Long): Map<Int, LongArray>? =
    queryForNetwork(statsManager, transport.legacyType, anchor, now + DAY_MS, transport.name)
        ?.associate { it.uid to longArrayOf(it.bytesRx, it.bytesTx) }

// DataCollectionService.collectAppData() — per transport (builds on #7)
val anchor = utcMidnight(now)
for (t in Transport.values()) {
    val current = networkStatsHelper.queryCumulative(t, anchor, now) ?: continue   // failed → retry next tick
    val baselines = baselineDao.getAll(t).associateBy { it.uid }

    val records = current.mapNotNull { (uid, cur) ->
        val prev = baselines[uid]?.takeIf { it.anchor == anchor }
        val dRx = cur[0] - (prev?.rx ?: 0L)
        val dTx = cur[1] - (prev?.tx ?: 0L)
        when {
            prev == null && baselines.isNotEmpty() && baselines.values.first().anchor == anchor ->
                toRecord(uid, t, cur[0], cur[1], since = baselines.values.first().takenAt, now)   // new UID today: whole cumulative is new
            prev == null -> null                                        // very first run / new day: baseline only (see below)
            dRx < 0 || dTx < 0 -> toRecord(uid, t, 0, 0, prev.takenAt, now, status = "COUNTER_RESET")  // stats wiped / UID reused
            dRx + dTx == 0L -> null
            else -> toRecord(uid, t, dRx, dTx, since = prev.takenAt, until = now)
        }
    }
    db.withTransaction {
        appUsageDao.insertAll(records)
        baselineDao.replaceAll(t, current.map { (uid, c) -> AppUsageBaseline(uid, t.name, anchor, c[0], c[1], now) })
    }
}
```
- **Day rollover:** when `anchor` changes, first close the old day: query `[oldAnchor, oldAnchor + DAY_MS]` (the whole day lies in the past and is exact), subtract the old baselines, and insert those rows. Then store new baselines from the new anchor, with the first new-day rows starting from 0. No bytes are lost at midnight.
- **First run:** store baselines only, without rows. The same applies to `BASELINE` in #4.
- `query_start`/`start_time`/`end_time` = `prev.takenAt` … `now`, the real span the bytes accumulated in, per transport. It also fits the per-transport checkpoints from #7: `app_usage_baseline` **replaces** `app_window_mobile/wifi` in the checkpoint table.
- The cost is unchanged: `querySummary` already aggregates over time, so a day-long range returns one row per UID, as now.
- **Remaining limit (document it):** Android updates per-UID stats when it polls (on network change, on query, or periodically). The deltas are exact in total but may fall a little later than when the traffic happened. Over 10-minute rows that is small and doesn't bias totals.
- **Verification on a phone:**
  1. Leave it collecting for a day.
  2. For one app, sum `bytes_total` over the day's `MOBILE` rows.
  3. Compare that sum with Settings → Network → Data usage for the same app. They should match within a few %. Run the same check on the **current** build to measure how large the existing error is (worth a sentence in the methods section).

---

## 10. [High, not in the review] Duplicate keys in the cloud: timestamp index and `local_id` reuse

### Current
`cloud_schema_v2.sql` gives `usage_records` **two** unique keys, and `CloudSyncManager` inserts with `INSERT OR IGNORE`, which **silently drops** any row that hits either one:
```sql
CREATE UNIQUE INDEX idx_usage_records_device_local     ON usage_records (device_id, local_id);
CREATE UNIQUE INDEX idx_usage_records_device_timestamp ON usage_records (device_id, timestamp);
```
Two ways real rows get dropped:

1. **Same `(device_id, timestamp)`:** two ticks in the same millisecond. Rare now, more likely once #5a adds switch-triggered ticks next to the 30 s tick (the mutex serialises them, but `Calendar.getInstance()` can repeat a millisecond, and the wall clock can jump backwards after an NTP or manual time change). With #2's read-back by `local_id`, such a row would also stay pending forever.
2. **Same `(device_id, local_id)`. This one is serious.** `android:allowBackup="true"` with the default (empty) `backup_rules.xml` / `data_extraction_rules.xml` means Android backs up **both** `device_id_prefs` (the install UUID) and the Room database. When a participant reinstalls the app or moves to a new phone, Android restores the **same `device_id`** with an **older copy of the database**. New rows then reuse `local_id`s the cloud already has from before → `INSERT OR IGNORE` drops them, **and** #2's read-back finds those `local_id`s and marks the new rows synced. Silent, permanent loss. A device-to-device transfer can even leave two phones uploading under one `device_id`.

### Proposed
**a) A per-row UUID as the only idempotency key**
```kotlin
// UsageRecord / AppUsageRecord
@ColumnInfo(name = "record_uid") val recordUid: String = UUID.randomUUID().toString()
```
```kotlin
// MIGRATION_10_11 — give existing rows a random id (SQLite has no uuid(), randomblob is fine)
addColumnIfMissing(db, "usage_records", "record_uid", "TEXT NOT NULL DEFAULT ''")
db.execSQL("UPDATE usage_records SET record_uid = lower(hex(randomblob(16))) WHERE record_uid = ''")
db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_usage_records_record_uid ON usage_records(record_uid)")
// same three lines for app_usage_records
```
```sql
-- Dataharvester_v2 (run before the new APK ships)
ALTER TABLE usage_records     ADD COLUMN record_uid TEXT;
ALTER TABLE app_usage_records ADD COLUMN record_uid TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_records_record_uid     ON usage_records (record_uid);
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_usage_records_record_uid ON app_usage_records (record_uid);

-- the keys that can collide on real, distinct rows become plain indexes
DROP INDEX IF EXISTS idx_usage_records_device_timestamp;
DROP INDEX IF EXISTS idx_usage_records_device_local;
DROP INDEX IF EXISTS idx_app_usage_records_device_local;
CREATE INDEX IF NOT EXISTS idx_usage_records_device_timestamp ON usage_records (device_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_usage_records_device_local     ON usage_records (device_id, local_id);
```
- `CloudSyncManager` sends `record_uid`, keeps `INSERT OR IGNORE` (now safe: only a true retry of the same row matches), and #2's read-back becomes `SELECT record_uid FROM … WHERE record_uid IN (…)`. That is simpler than grouping by `device_id`.
- Rows already in v2 from 1.6.1 have `record_uid = NULL`. SQLite allows many NULLs in a unique index, so they don't conflict. Those rows' local copies are `is_synced = 1` and are never re-sent.
- `local_id` stays as information only.

**b) Stop restoring identity and collection state from backups**
```xml
<!-- res/xml/data_extraction_rules.xml (Android 12+) -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="device_id_prefs.xml" />
        <exclude domain="sharedpref" path="data_harvester_prefs.xml" />
        <exclude domain="database" path="data_harvester_db" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="device_id_prefs.xml" />
        <exclude domain="sharedpref" path="data_harvester_prefs.xml" />
        <exclude domain="database" path="data_harvester_db" />
    </device-transfer>
</data-extraction-rules>
<!-- res/xml/backup_rules.xml (Android ≤ 11): the same three <exclude> lines inside <full-backup-content> -->
```
(Or set `android:allowBackup="false"`. The CSV export is the participant's recovery copy, and #8 now keeps it.) A reinstall or new phone then gets a **new `device_id`** and fresh counters. That is correct: restored TrafficStats baselines and per-app checkpoints from another boot or phone would produce wrong deltas anyway.

**c) Timestamps stay non-unique but readable.** No code change is needed beyond (a). Analysis should order by `(device_id, timestamp, local_id)`, and #4's `elapsed_ms` (monotonic) is the reliable interval length if the wall clock jumps.

- **Tests:** (1) two records with the same timestamp both reach the cloud; (2) reset local `sqlite_sequence` (simulating a restore) → new rows with reused `local_id` still upload, because `record_uid` differs; (3) retrying the same batch inserts nothing new.

## Test setup note
The review says `testDebugUnitTest` stopped because `.env` lacked keys. As written, the Gradle guard (`build.gradle.kts:27-36`) only matches tasks named `assemble*`, `bundle*` or `install*`, so plain `testDebugUnitTest` should pass. Ask the teammate for the exact command they ran. Either way, the new tests (#1 transaction, #2 MockWebServer, migration 9→10→11 with `MigrationTestHelper`) must run with no production credentials.

## Suggested order
1. Room v11: `collection_checkpoint` + `app_usage_baseline` tables, plus columns for #4, #5, #10 (`record_uid`) and `collection_version`, with migration tests 9→10→11.
2. #10b backup exclusions. Small, and it stops the `local_id` reuse risk immediately.
3. #1, #7, #9: collector writes inside transactions; per-app cumulative snapshots.
4. #4, #5: interval/status columns, switch-triggered ticks, cleaning-safe label.
5. #2 + #10a: sync acknowledgement by `record_uid` (needs the gateway JSON shape first).
6. #3: FGS type, boot and update restart.
7. #6, #8: formatter, two-step reset flow.
8. Cloud: run the `ALTER TABLE` / index changes on `Dataharvester_v2` **before** shipping the APK.
9. Device run: Android 15+, reboot, OTA update, airplane mode mid-upload, Wi-Fi ↔ mobile switching, overnight; per-app daily totals vs. Settings → Data usage.
