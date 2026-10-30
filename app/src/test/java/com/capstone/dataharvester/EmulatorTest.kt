package com.capstone.dataharvester

import com.capstone.dataharvester.util.Emulator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emulators never upload; real phones always may. */
class EmulatorTest {

    @Test
    fun androidStudioEmulator_isDetected() {
        // The emulator that uploaded fake identities on 2026-10-30 (model "sdk_gphone64_x86_64")
        assertTrue(Emulator.isEmulator(
            "google/sdk_gphone64_x86_64/emu64xa:16/BP22.250221.010/13193326:user/release-keys",
            "ranchu", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64"
        ))
        // Older emulator images
        assertTrue(Emulator.isEmulator("generic/sdk/generic:9/PSR1/1:userdebug/test-keys", "goldfish", "sdk", "Android SDK built for x86"))
    }

    @Test
    fun realPhones_areNotEmulators() {
        assertFalse(Emulator.isEmulator("google/husky/husky:14/AP1A.240305.019/11365237:user/release-keys", "husky", "husky", "Pixel 8 Pro"))
        assertFalse(Emulator.isEmulator("vivo/PD2250F/PD2250:13/TP1A.220624.014/compiler0426:user/release-keys", "mt6833", "PD2250F", "V2250"))
        assertFalse(Emulator.isEmulator("samsung/a54xnsxx/a54x:14/UP1A.231005.007/A546EXXU5BXA4:user/release-keys", "s5e8835", "a54xnsxx", "SM-A546E"))
        assertFalse(Emulator.isEmulator("TECNO/KI5q-GL/TECNO-KI5q:13/TP1A.220624.014/240315V1234:user/release-keys", "mt6768", "KI5q-GL", "TECNO KI5q"))
        assertFalse(Emulator.isEmulator(null, null, null, null))
    }
}
