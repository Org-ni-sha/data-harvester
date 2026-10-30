package com.capstone.dataharvester.update

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.capstone.dataharvester.BuildConfig
import com.capstone.dataharvester.ui.Dialogs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** Fingerprint of an update that failed verification; not offered again */
        private const val PREF_FAILED_UPDATE = "failed_update"
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("UpdatePrefs", Context.MODE_PRIVATE)

    /**
     * Checks if the app was recently updated. If it was, shows a notification toast
     * to the user indicating the update was successful.
     */
    fun checkAndNotifyIfUpdated() {
        try {
            val sharedPreferences = context.getSharedPreferences("UpdatePrefs", Context.MODE_PRIVATE)
            val lastVersionCode = sharedPreferences.getInt("last_version_code", -1)

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            val currentVersionName = packageInfo.versionName ?: "unknown"

            if (lastVersionCode != -1 && currentVersionCode > lastVersionCode) {
                mainHandler.post {
                    Toast.makeText(
                        context,
                        "🎉 DATAra Harvester successfully updated to v$currentVersionName!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.i(TAG, "App successfully updated from version code $lastVersionCode to $currentVersionCode.")
            }

            // Save the new current version code
            sharedPreferences.edit().putInt("last_version_code", currentVersionCode).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking version for post-update notification", e)
        }
    }

    /**
     * Helper to compute the candidate branches for update checking in priority order.
     */
    private fun getCandidateBranches(currentBranch: String): List<String> {
        val branches = mutableListOf<String>()
        
        // Always try the current branch first
        branches.add(currentBranch)
        
        // If we are on a feat/, mod/, or fix/ branch, try the corresponding testing/ branch next
        if (currentBranch.startsWith("feat/") || currentBranch.startsWith("mod/") || currentBranch.startsWith("fix/")) {
            val suffix = currentBranch.substringAfter("/")
            val testingBranch = "testing/$suffix"
            if (testingBranch != currentBranch) {
                branches.add(testingBranch)
            }
            // Also try the general "testing" branch
            branches.add("testing")
        }
        // If we are on a testing/ branch, try possible source branches next
        else if (currentBranch.startsWith("testing/")) {
            val suffix = currentBranch.substringAfter("/")
            branches.add("feat/$suffix")
            branches.add("mod/$suffix")
            branches.add("fix/$suffix")
            // Also try the general "testing" branch
            branches.add("testing")
        }
        
        // For other development branches, ensure "testing" is checked before main
        if (currentBranch != "testing" && !branches.contains("testing")) {
            branches.add("testing")
        }
        
        // Always fallback to main
        if (!branches.contains("main")) {
            branches.add("main")
        }
        
        return branches.distinct()
    }

    /**
     * Checks the remote update.json to see if a newer version is available.
     * Evaluates a chain of candidate branches dynamically starting from the active build branch.
     */
    fun checkForUpdates() {
        val currentBranch = BuildConfig.GIT_BRANCH
        val branches = getCandidateBranches(currentBranch)
        Log.i(TAG, "Checking for updates. Active branch: $currentBranch. Candidates: $branches")
        tryNextBranch(branches, 0)
    }

    private fun tryNextBranch(branches: List<String>, index: Int) {
        if (index >= branches.size) {
            Log.w(TAG, "All update checks failed (none of the branches are available or contain updates).")
            return
        }

        val branch = branches[index]
        val url = "https://raw.githubusercontent.com/saybbbb/Data-Harvester/$branch/update.json"
        
        Log.d(TAG, "Attempting update check for branch '$branch' at URL: $url")
        fetchUpdateJson(url) {
            // Failure callback: fallback to the next candidate branch
            Log.i(TAG, "Update check failed or no branch config at: $url. Trying next fallback...")
            tryNextBranch(branches, index + 1)
        }
    }

    private fun fetchUpdateJson(url: String, onFailure: () -> Unit) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Network failure checking update at: $url", e)
                onFailure()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.w(TAG, "Server returned error code ${response.code} for: $url")
                    onFailure()
                    return
                }

                try {
                    val body = response.body?.string() ?: return
                    val info = UpdateVerifier.parse(JSONObject(body))
                    val installed = installedApp()

                    if (info.versionCode > installed.versionCode) {
                        // A file that already failed verification is not offered again
                        // until update.json changes
                        if (prefs.getString(PREF_FAILED_UPDATE, null) == info.fingerprint) {
                            Log.w(TAG, "Update ${info.versionName} failed verification before — not offering it")
                            return
                        }
                        val problem = UpdateVerifier.checkManifest(info, installed)
                        mainHandler.post {
                            if (problem != null) {
                                rememberFailure(info)
                                showError("Update Unavailable", problem)
                            } else {
                                showUpdateDialog(info)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing update JSON from: $url", e)
                    onFailure()
                }
            }
        })
    }

    private fun showUpdateDialog(info: UpdateVerifier.UpdateInfo) {
        Dialogs.builder(context)
            .setTitle("Update Available")
            .setMessage("A new version (${info.versionName}) is available. Would you like to update?")
            .setPositiveButton("Update") { _, _ ->
                if (canInstallPackages()) downloadAndInstallApk(info) else askInstallPermission()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    /** Android 8+: installing needs "Install unknown apps" allowed for this app. */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun askInstallPermission() {
        Dialogs.builder(context)
            .setTitle("Allow Updates")
            .setMessage(
                "To install updates, allow Data Harvester to install apps on the next screen, " +
                    "then come back and tap Update again."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallApk(info: UpdateVerifier.UpdateInfo) {
        val progressDialog = Dialogs.progress(
            context,
            "Downloading Update",
            "Downloading the new version. Please keep the app open."
        )

        val request = Request.Builder().url(info.apkUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to download APK", e)
                mainHandler.post {
                    progressDialog.dismiss()
                    showError("Download Failed", "Could not download the update. Please check your internet connection.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "APK download returned HTTP ${response.code}")
                        mainHandler.post {
                            progressDialog.dismiss()
                            showError("Download Failed", "The update file could not be downloaded (HTTP ${response.code}).")
                        }
                        return
                    }

                    try {
                        // A fresh name per download: the installer never reads a stale or half-written file
                        context.externalCacheDir?.listFiles { f -> f.name.startsWith("update") }?.forEach { it.delete() }
                        val apkFile = File(context.externalCacheDir, "update-${info.versionCode}.apk")
                        val body = response.body ?: throw IOException("Empty response")
                        val totalBytes = body.contentLength()
                        val digest = MessageDigest.getInstance("SHA-256")

                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var totalRead = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    output.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    totalRead += n
                                    if (totalBytes > 0) {
                                        val progress = ((totalRead * 100) / totalBytes).toInt()
                                        mainHandler.post { progressDialog.setProgress(progress) }
                                    }
                                }
                                output.fd.sync()
                            }
                        }

                        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                        val problem = UpdateVerifier.check(info, inspect(apkFile, sha256), installedApp())
                        mainHandler.post {
                            progressDialog.dismiss()
                            if (problem != null) {
                                Log.e(TAG, "Downloaded update rejected: $problem")
                                apkFile.delete()
                                rememberFailure(info)
                                showError("Update Not Installed", problem)
                            } else {
                                installApk(apkFile)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing APK to storage", e)
                        mainHandler.post {
                            progressDialog.dismiss()
                            showError("Download Failed", "The update could not be saved: ${e.message}")
                        }
                    }
                }
            }
        })
    }

    private fun rememberFailure(info: UpdateVerifier.UpdateInfo) {
        prefs.edit().putString(PREF_FAILED_UPDATE, info.fingerprint).apply()
    }

    private fun showError(title: String, message: String) {
        Dialogs.builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── Package inspection ────────────────────────────────────────────────

    private fun installedApp(): UpdateVerifier.Installed {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        return UpdateVerifier.Installed(context.packageName, info.versionCodeCompat(), signerDigests(info) ?: emptySet())
    }

    private fun inspect(apk: File, sha256: String): UpdateVerifier.Downloaded {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        }
        return UpdateVerifier.Downloaded(
            sizeBytes = apk.length(),
            sha256 = sha256,
            packageName = info?.packageName,
            versionCode = info?.versionCodeCompat(),
            signerSha256 = info?.let { signerDigests(it) }
        )
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    /** SHA-256 of each signing certificate, or null when the platform does not report them. */
    private fun signerDigests(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        if (signatures.isNullOrEmpty()) return null
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Hands the verified APK to Android's PackageInstaller directly. The older way, an
     * ACTION_VIEW intent with the APK's MIME type, depends on some app on the phone
     * claiming that type; on several vendor ROMs the file manager claims it instead and
     * answers "unsupported file type". A session needs no such app. The result arrives
     * at [UpdateInstallReceiver], which shows Android's confirmation screen.
     */
    private fun installApk(apkFile: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Mutable: the installer adds the status and the confirmation intent to it
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    flags
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install session failed — falling back to the system installer screen", e)
            installWithViewIntent(apkFile)
        }
    }

    private fun installWithViewIntent(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            showError("Update Not Installed", "This phone has no app that can install the update. Please contact the research team.")
        }
    }
}
