package com.capstone.dataharvester.util

import android.os.Build

/**
 * Whether the app runs on an Android emulator. Emulator data is never study data, so
 * CloudSyncManager does not upload from one (test runs once put fake device identities
 * into Dataharvester_v2).
 */
object Emulator {

    fun isEmulator(): Boolean =
        isEmulator(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT, Build.MODEL)

    /** Pure check on the Build values, so it can be unit-tested. */
    fun isEmulator(fingerprint: String?, hardware: String?, product: String?, model: String?): Boolean {
        val f = fingerprint.orEmpty().lowercase()
        val h = hardware.orEmpty().lowercase()
        val p = product.orEmpty().lowercase()
        val m = model.orEmpty().lowercase()
        return h == "ranchu" || h == "goldfish" ||             // Android Emulator kernels
            f.startsWith("generic") || f.contains("/sdk_gphone") ||
            f.contains("emulator") || f.contains("emu64") ||
            p.startsWith("sdk_") || p.contains("_sdk_") || p == "sdk" ||
            m.startsWith("sdk_gphone") || m.contains("android sdk built for")
    }
}
