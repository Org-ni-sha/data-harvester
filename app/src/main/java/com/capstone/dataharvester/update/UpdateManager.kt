package com.capstone.dataharvester.update

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.widget.Toast
import com.capstone.dataharvester.BuildConfig

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        }
        // If we are on a testing/ branch, try possible source branches next
        else if (currentBranch.startsWith("testing/")) {
            val suffix = currentBranch.substringAfter("/")
            branches.add("feat/$suffix")
            branches.add("mod/$suffix")
            branches.add("fix/$suffix")
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
                    val json = JSONObject(body)

                    val latestVersionCode = json.getInt("latestVersionCode")
                    val latestVersionName = json.getString("latestVersionName")
                    val apkUrl = json.getString("apkUrl")

                    // Get current version code on this device
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                    if (latestVersionCode > currentVersionCode) {
                        mainHandler.post {
                            showUpdateDialog(latestVersionName, apkUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing update JSON from: $url", e)
                    onFailure()
                }
            }
        })
    }

    private fun showUpdateDialog(newVersionName: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("A new version ($newVersionName) is available. Would you like to update?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstallApk(apkUrl)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("Downloading Update")
            setMessage("Please wait...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val request = Request.Builder().url(apkUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to download APK", e)
                mainHandler.post {
                    progressDialog.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("Download Failed")
                        .setMessage("Could not download the update. Please check your internet connection.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post { progressDialog.dismiss() }
                    return
                }

                try {
                    val apkFile = File(context.externalCacheDir, "update.apk")
                    val inputStream = response.body?.byteStream() ?: return
                    val outputStream = FileOutputStream(apkFile)
                    val totalBytes = response.body?.contentLength() ?: -1L

                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0) {
                            val progress = ((totalRead * 100) / totalBytes).toInt()
                            mainHandler.post {
                                progressDialog.progress = progress
                            }
                        }
                    }

                    outputStream.close()
                    inputStream.close()

                    mainHandler.post {
                        progressDialog.dismiss()
                        installApk(apkFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing APK to storage", e)
                    mainHandler.post {
                        progressDialog.dismiss()
                    }
                }
            }
        })
    }

    private fun installApk(apkFile: File) {
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
        }
    }
}
