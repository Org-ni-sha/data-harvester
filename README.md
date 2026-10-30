# DATAra Harvester

An Android app that measures how much mobile data a phone uses, every 30 seconds and per app, for a consented research study. Records are stored on the phone and uploaded to a cloud database for analysis.

---

## Introduction

DATAra Harvester is the data collection tool of the DATAra capstone project. Participants install the app, read and accept the consent form, grant the permissions it needs, and tap **Start Official Collection**. From then on the app runs in the background and records:

- **Total mobile data usage** every 30 seconds (bytes received and sent), with the context of each interval: network type (Wi-Fi / LTE / 5G …), signal strength, battery level, charging and screen state, time of day.
- **Per-app data usage** every 10 minutes (which apps used the data, on mobile and on Wi-Fi).

Each row also says how trustworthy it is (`measurement_status`: OK, GAP, COUNTER_RESET, …), so analysis can tell real zero usage from time the phone was off or the app was stopped.

Records are uploaded automatically to the study database (`Dataharvester_v2` on SQLite Cloud) through a small server endpoint. No hardware identifiers are collected: each install gets a random device ID.

## APK version releases

| Version | versionCode | Main changes |
|---|---|---|
| **1.6.4** | 10 | Study enrollment ("Start Official Collection"); checkpoint and counter fixes; per-app GAP flags; uploads through the ingest endpoint (no database key in the APK); per-row server validation; watchdog that restarts a killed collector; signed releases with verified in-app updates; update installs through Android's PackageInstaller; database guard (a missing migration pauses the app instead of crashing or erasing records) |
| 1.6.3 | 9 | Collection, sync and cloud-transition fixes (record_uid, measurement_status, collection_version) |
| 1.6.1 | 8 | Consent form; move to `Dataharvester_v2`; battery-optimization guidance |
| 1.5.0 | 7 | Device identity, network provider, sync improvements, migration safety and schema export |
| 1.4.1 | 6 | SQLite Cloud sync, manual upload, remote update check, start/end time on per-app rows |
| 1.2 | — | Per-app usage via NetworkStatsManager, onboarding, dark theme, CSV export |

The latest APK is attached to the newest [GitHub Release](https://github.com/saybbbb/Data-Harvester/releases). Installed apps update themselves from inside the app (see [Releasing an update](#releasing-an-update)).

## Getting started

### For participants

1. Open the latest release link **in Chrome** and download `DataHarvester-<version>.apk`.
2. Tap the downloaded file and allow "Install unknown apps" for Chrome when asked.
3. Open DATAra Harvester, read the consent form and accept it.
4. Grant the requested permissions: notifications, phone state, **Usage Access**, and **battery optimization: Unrestricted**.
5. Tap **Start Official Collection**. Keep the app installed; it collects and uploads on its own.

When an update is available the app offers it on the dashboard. Tap **Update**, then **Install**. Records are kept.

### For developers

Requirements: Android Studio (or JDK 17 + Android SDK 36), Node.js 22+ for the server tests.

```bash
git clone https://github.com/saybbbb/Data-Harvester.git
```

```bash
cp .env.example .env
```

Fill in `.env` (it is gitignored):

| Key | Purpose |
|---|---|
| `INGEST_URL`, `INGEST_APP_TOKEN` | The deployed ingest endpoint (`api/ingest.js`). When set, the APK carries no database key. |
| `GATEWAY_URL`, `API_KEY`, `DB_NAME` | Direct SQLite Cloud gateway, **local testing only** (used when `INGEST_URL` is empty). |
| `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | The team release keystore. Only needed to build a release locally; normal releases are built by GitHub Actions. |
| `GITHUB_REPO`, `UPDATE_BASE_URL` | Where update APKs are hosted (GitHub Releases by default). |

Build and install a debug build on a connected phone or emulator:

```bash
./gradlew installDebug
```

Deploy the ingest endpoint (the repository root is a Vercel project). Set these environment variables in Vercel: `SQLITECLOUD_GATEWAY_URL`, `SQLITECLOUD_API_KEY`, `SQLITECLOUD_DB_NAME`, `INGEST_APP_TOKEN`.

## Features

- **Consent and onboarding**: a permission gate that explains every permission; no collection without consent.
- **Study enrollment**: rows are tagged with `study_id`. Only rows collected after "Start Official Collection" are uploaded; earlier test rows stay on the phone.
- **30-second primary collection** from TrafficStats, with a checkpoint saved in the same transaction as each row, so a crash or overlapping trigger never counts bytes twice or loses them.
- **10-minute per-app collection** from NetworkStatsManager, for mobile and Wi-Fi separately.
- **Data quality flags**: `measurement_status`, `interval_start` / `interval_end`, `elapsed_ms`, `schema_version`, and `collection_version` on every row.
- **Reliable background running**: a foreground service, restart after reboot and app update, and a 15-minute watchdog that restarts a killed collector or asks the participant to reopen the app.
- **Confirmed uploads**: rows are marked uploaded only after the server reads them back. Rejected rows are set aside with a reason instead of blocking later uploads.
- **Verified over-the-air updates**: the app checks the size, SHA-256, package, version and signing key of a download before installing it.
- **Safe database upgrades**: a missing or broken migration never crashes the app or erases records (see [Important notes](#important-notes)).
- **CSV export and Reset**: export both tables to Downloads; reset offers to upload first.

## Tech stack

| Layer | Technology |
|---|---|
| App | Kotlin 2.2, Android SDK 36 (min 23), AGP 9.1, Java 17 |
| Local storage | Room 2.7 (SQLite) with exported schemas and migrations |
| Background work | Foreground service (`specialUse`), WorkManager 2.9, Kotlin coroutines 1.10 |
| Measurement | TrafficStats, NetworkStatsManager, TelephonyManager, ConnectivityManager |
| Networking | OkHttp 4.12 |
| UI | AppCompat, Material Components, CardView (dark theme) |
| Server | Node.js serverless function on Vercel (`api/ingest.js`) |
| Cloud database | SQLite Cloud (`Dataharvester_v2`) through its Weblite SQL gateway |
| Update hosting | GitHub Releases (APK) + `update.json` in the repository, built by GitHub Actions |
| Tests | JUnit 4, AndroidX Test, Room MigrationTestHelper, `node:test` with `node:sqlite` |

## System architecture

```
 Phone (Android app)                                Server                    Cloud
┌──────────────────────────────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│ DataCollectionService (foreground)       │   │ Vercel           │   │ SQLite Cloud     │
│  ├─ every 30 s: PrimaryCollector ──┐     │   │ api/ingest.js    │   │ Dataharvester_v2 │
│  └─ every 10 min: per-app usage ───┤     │   │  validates every │   │  usage_records   │
│                                    ▼     │   │  field, inserts, │──▶│  app_usage_...   │
│              Room database (on phone)    │   │  reads uids back │   │  device_identity │
│                                    │     │   └────────▲─────────┘   │  upload_history  │
│ SyncWorker / Upload button         ▼     │            │             └──────────────────┘
│  CloudSyncManager ── JSON batch ─────────┼────────────┘
│                    ◀── acked / rejected uids
│                                          │   ┌────────────────────────────────────┐
│ UpdateManager ── update.json, APK ───────┼──▶│ GitHub: update.json + Releases APK │
│ CollectionWatchdogWorker (15 min)        │   └────────────────────────────────────┘
└──────────────────────────────────────────┘
```

### Project structure

```
app/src/main/java/com/capstone/dataharvester/
├── OnboardingActivity.kt        consent + permission gate (launcher)
├── MainActivity.kt              dashboard: status, stats, start/stop, upload, export, reset
├── DataCollectionService.kt     foreground service: 30 s and 10 min collection loops
├── CollectionWatchdogWorker.kt  restarts a killed collector
├── BootReceiver.kt              resumes collection after reboot / app update
├── data/                        Room entities, DAOs, AppDatabase (+ migrations), DatabaseGuard
├── sync/                        CloudSyncManager, SyncWorker, UploadPayload, UploadTransport
├── update/                      UpdateManager, UpdateVerifier, UpdateInstallReceiver
├── util/                        PrimaryTick / PrimaryCollector, MeasurementRules, StudyManager,
│                                ConsentManager, DeviceIdManager, NetworkStatsHelper, CsvExporter …
└── ui/Dialogs.kt                shared dialog styling
app/schemas/                     exported Room schemas (one JSON per database version)
app/src/test/                    JVM unit tests
app/src/androidTest/             device/emulator tests (migrations, collection, sync)
api/                             Vercel ingest endpoint + its tests
cloud_schema_v2.sql              full cloud schema
cloud_schema_v2_upgrade_*.sql    upgrades to apply to the live cloud database per release
update.json                      the update manifest phones read (written by publishUpdate)
.github/workflows/release.yml    builds, signs and publishes a release on GitHub
```

## Verification

Run these before every release:

```bash
./gradlew testDebugUnitTest
```

Unit tests: measurement rules, the 30-second tick (reboots, failed readings, crash recovery), update verification, and that every database version has a migration.

```bash
./gradlew connectedDebugAndroidTest
```

Device tests (needs a phone or emulator): Room migrations, a missing migration leaving records intact, overlapping ticks counting each byte once, pre-study rows never uploaded, partial acknowledgements retried, rejected rows set aside.

```bash
node --no-warnings --test api/_lib/records.test.js
```

Server tests: validation and the endpoint's SQL, run against the real cloud schema.

Manual checks on a real phone: an overnight run with a reboot and a network switch, and installing an update from the previous version.

## Releasing an update

Releases are built by **GitHub Actions** (`.github/workflows/release.yml`), so they do not depend on anyone's laptop. Nobody needs the keystore on their computer; it is stored in the repository's encrypted secrets.

### One-time setup: repository secrets

Done once by a repository admin. GitHub > the repository > **Settings > Secrets and variables > Actions > New repository secret**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The team keystore file encoded as base64 (command below) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |
| `INGEST_URL`, `INGEST_APP_TOKEN` | The deployed ingest endpoint (production) |
| `GATEWAY_URL`, `API_KEY`, `DB_NAME` | Only while `INGEST_URL` is not set (testing) |

Enter the values without quotes. To copy the keystore as base64 to the clipboard (Windows PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
```

Secrets cannot be read back from GitHub, so keep a private backup of the keystore and its passwords as well.

### Each release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`. If a table changed, raise `AppDatabase.VERSION` and add the migration (see Important notes). Commit and push.
2. If the release has a `cloud_schema_v2_upgrade_<version>.sql`, apply it to the live database.
3. GitHub > **Actions > Release APK > Run workflow**, choose the branch, keep **Pre-release** ticked for a test build. The workflow runs the unit tests, builds and signs the APK, creates the Release `v<version>` with the APK attached, checks the download, and commits `update.json` to that branch.
4. Test: phones running a build **from that branch** are now offered the update.
5. Roll out: put that `update.json` on the branch the participants' installed version reads (for 1.6.3 phones: `mod/cloud-issues`), for example by merging. Participants are offered the update from this moment.

### Building a release locally (fallback)

With the signing values in your own `.env`:

```bash
./gradlew publishUpdate
```

Then attach `releases/DataHarvester-<version>.apk` to a new GitHub Release `v<version>` and, **after** that, commit and push `update.json`.

## Important notes

- **Always sign with the same keystore.** Android refuses an update signed with a different key, and the only way out is to uninstall, which **deletes the participant's records**. Keep the keystore and its passwords backed up privately; never commit them.
- **versionCode must always go up.** Android never installs a lower version over a higher one. Installing an older version requires uninstalling, which deletes the records.
- **Never add `fallbackToDestructiveMigration()`.** It "fixes" a migration crash by deleting the whole database. Instead, every database change raises `AppDatabase.VERSION`, adds a `MIGRATION_<old>_<new>` to `ALL_MIGRATIONS`, and gets a test in `MigrationTest.kt`. `publishUpdate` refuses to build when a migration is missing. If one still reaches a phone, `DatabaseGuard` pauses collection, shows "Update Required", and leaves the file untouched for the fixed update.
- **The APK is public.** The repository and its releases can be downloaded by anyone, so nothing secret may be in the app. Uploads go through the ingest endpoint; the old database key in 1.6.x APKs should be revoked once all phones run 1.6.4 or later.
- **Host the APK as a direct file link.** In-app updates need a URL that returns the file itself (GitHub Releases does). A Google Drive share link returns a web page; it is fine for sending the first install by hand, not for `update.json`.
- **Battery settings matter.** Some brands (Vivo, Oppo, Xiaomi, Samsung) stop background apps aggressively. Participants should set battery usage to **Unrestricted**; gaps still show up as `GAP` rows instead of silent missing data.
- **Upload new columns too.** A new record column must also be added to `sync/UploadPayload.kt`, `api/_lib/records.js`, and the cloud schema, or it stays on the phone.
