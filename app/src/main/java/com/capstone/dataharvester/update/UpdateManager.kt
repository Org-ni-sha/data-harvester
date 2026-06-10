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

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        
        // 1. Try testing branch first (for your testing environments)
        private const val TESTING_UPDATE_JSON_URL = "https://raw.githubusercontent.com/saybbbb/Data-Harvester/testing/sqlite-cloud-sync/update.json"
        
        // 2. Fallback to main branch (production default)
        private const val MAIN_UPDATE_JSON_URL = "https://raw.githubusercontent.com/saybbbb/Data-Harvester/main/update.json"
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks the remote update.json to see if a newer version is available.
     * First attempts to query the testing branch, and falls back to the main branch if it fails.
     */
    fun checkForUpdates() {
        fetchUpdateJson(TESTING_UPDATE_JSON_URL) {
            Log.i(TAG, "Testing branch update check failed. Falling back to main branch...")
            fetchUpdateJson(MAIN_UPDATE_JSON_URL) {
                Log.w(TAG, "All update checks failed (testing and main branches are unavailable).")
            }
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
