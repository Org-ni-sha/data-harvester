package com.capstone.dataharvester.update

import org.json.JSONObject

/**
 * The advertised update (update.json) and the checks a downloaded APK must pass before the
 * installer is opened. Kept free of Android types so the JVM unit tests can drive it.
 *
 * Android rejects an APK that is not newer than the installed app or is signed with a
 * different key, and only says "package appears to be invalid". Checking first lets the
 * app say what is actually wrong, and keeps it from offering the same broken file again.
 */
object UpdateVerifier {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        /** Optional in update.json: older manifests only have the three fields above */
        val sizeBytes: Long? = null,
        val sha256: String? = null,
        val signingCertSha256: String? = null
    ) {
        /** Identifies this exact file, so a failed one is not offered again */
        val fingerprint get() = "$versionCode|$apkUrl|${sha256 ?: ""}"
    }

    fun parse(json: JSONObject) = UpdateInfo(
        versionCode = json.getInt("latestVersionCode"),
        versionName = json.getString("latestVersionName"),
        apkUrl = json.getString("apkUrl"),
        sizeBytes = json.optLong("sizeBytes", -1L).takeIf { it > 0 },
        sha256 = json.optString("sha256").takeIf { it.isNotBlank() }?.lowercase(),
        signingCertSha256 = json.optString("signingCertSha256").takeIf { it.isNotBlank() }?.lowercase()
    )

    /** What the downloaded file turned out to be (null fields = could not be read). */
    data class Downloaded(
        val sizeBytes: Long,
        val sha256: String,
        val packageName: String?,
        val versionCode: Long?,
        /** SHA-256 of each signing certificate; null if the platform did not report them */
        val signerSha256: Set<String>?
    )

    data class Installed(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

    /**
     * Checks that need no download (call only for a newer versionCode): an update signed
     * with another key is never fetched.
     */
    fun checkManifest(info: UpdateInfo, installed: Installed): String? = when {
        info.signingCertSha256 != null && installed.signerSha256.isNotEmpty() &&
            info.signingCertSha256 !in installed.signerSha256 ->
            "The update is signed with a different key than the installed app, so Android cannot " +
                "install it over this version. Please contact the research team."
        else -> null
    }

    /** null = safe to hand to the installer; otherwise the reason to show the participant. */
    fun check(info: UpdateInfo, file: Downloaded, installed: Installed): String? = when {
        info.sizeBytes != null && file.sizeBytes != info.sizeBytes ->
            "The download is incomplete (${file.sizeBytes} of ${info.sizeBytes} bytes). Please try again."
        info.sha256 != null && file.sha256 != info.sha256 ->
            "The downloaded file is damaged (checksum mismatch). Please try again."
        file.packageName == null || file.versionCode == null ->
            "The downloaded file is not a valid app package. Please contact the research team."
        file.packageName != installed.packageName ->
            "The downloaded file is a different app. Please contact the research team."
        file.versionCode <= installed.versionCode ->
            "The hosted update is version code ${file.versionCode}, which is not newer than the " +
                "installed version (${installed.versionCode}). Please contact the research team."
        file.signerSha256 != null && installed.signerSha256.isNotEmpty() &&
            file.signerSha256.none { it in installed.signerSha256 } ->
            "The update is signed with a different key than the installed app, so Android cannot " +
                "install it over this version. Please contact the research team."
        else -> null
    }
}
