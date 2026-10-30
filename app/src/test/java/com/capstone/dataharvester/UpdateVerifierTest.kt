package com.capstone.dataharvester

import com.capstone.dataharvester.update.UpdateVerifier
import com.capstone.dataharvester.update.UpdateVerifier.Downloaded
import com.capstone.dataharvester.update.UpdateVerifier.Installed
import com.capstone.dataharvester.update.UpdateVerifier.UpdateInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVerifierTest {

    private val key = "ab1f26de5fc8dc7fa17a43bab477d060e09e62a042f5b3473395e6de2c22f481"
    private val installed = Installed("com.capstone.dataharvester", 8, setOf(key))
    private val info = UpdateInfo(9, "1.6.3", "https://example/DataHarvester-1.6.3.apk", 9_000_000, "aa", key)
    private val good = Downloaded(9_000_000, "aa", "com.capstone.dataharvester", 9, setOf(key))

    @Test
    fun matchingNewerApk_passes() {
        assertNull(UpdateVerifier.check(info, good, installed))
        assertNull(UpdateVerifier.checkManifest(info, installed))
    }

    @Test
    fun olderApkBehindNewerManifest_isRejectedWithTheReason() {
        // What happened on 2026-10-30: update.json said 1.6.3 (9), the file was 1.4.0 (5)
        val stale = good.copy(versionCode = 5)
        val reason = UpdateVerifier.check(info.copy(sizeBytes = null, sha256 = null), stale, installed)
        assertNotNull(reason)
        assertTrue(reason!!.contains("version code 5"))
    }

    @Test
    fun differentSigningKey_isRejectedBeforeAndAfterDownload() {
        val other = "0".repeat(64)
        assertNotNull(UpdateVerifier.checkManifest(info.copy(signingCertSha256 = other), installed))
        assertNotNull(UpdateVerifier.check(info.copy(signingCertSha256 = null), good.copy(signerSha256 = setOf(other)), installed))
    }

    @Test
    fun incompleteOrDamagedDownload_isRejected() {
        assertNotNull(UpdateVerifier.check(info, good.copy(sizeBytes = 4_000_000), installed))
        assertNotNull(UpdateVerifier.check(info, good.copy(sha256 = "bb"), installed))
    }

    @Test
    fun unreadableOrForeignPackage_isRejected() {
        assertNotNull(UpdateVerifier.check(info, good.copy(packageName = null, versionCode = null), installed))
        assertNotNull(UpdateVerifier.check(info, good.copy(packageName = "com.other"), installed))
    }

    @Test
    fun oldManifestWithoutChecksums_stillChecksVersionAndPackage() {
        val bare = UpdateInfo(9, "1.6.3", "https://example/app-debug.apk")
        assertNull(UpdateVerifier.check(bare, good, installed))
        assertNotNull(UpdateVerifier.check(bare, good.copy(versionCode = 8), installed))
    }

    @Test
    fun platformNotReportingSigners_doesNotBlockTheInstall() {
        // Android itself still enforces the key at install time
        assertNull(UpdateVerifier.check(info, good.copy(signerSha256 = null), installed))
    }
}
