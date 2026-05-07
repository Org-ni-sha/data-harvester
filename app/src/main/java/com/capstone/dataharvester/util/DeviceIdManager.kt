package com.capstone.dataharvester.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

/**
 * Manages a persistent device identifier for multi-device data collection.
 *
 * Generates a UUID on first launch and stores it in SharedPreferences.
 * Also supports a user-set friendly label (defaults to manufacturer + model).
 *
 * Usage:
 *   val manager = DeviceIdManager(context)
 *   val id = manager.getDeviceId()       // "a1b2c3d4-..."
 *   val label = manager.getDeviceLabel()  // "Samsung SM-A546E"
 */
class DeviceIdManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "device_id_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_LABEL = "device_label"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the persistent device UUID.
     * Generated once on first call, then persisted across app restarts.
     */
    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /**
     * Get the human-readable device label.
     * Defaults to "Manufacturer Model" (e.g., "Samsung SM-A546E").
     */
    fun getDeviceLabel(): String {
        return prefs.getString(KEY_DEVICE_LABEL, null)
            ?: getDefaultLabel()
    }

    /**
     * Set a custom friendly label for this device.
     * @param label e.g., "Juan_Phone", "Test_Device_1"
     */
    fun setDeviceLabel(label: String) {
        prefs.edit().putString(KEY_DEVICE_LABEL, label).apply()
    }

    /**
     * Get the default label derived from device hardware info.
     */
    private fun getDefaultLabel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        // Avoid "Samsung Samsung SM-..." duplication
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Get the device model string (for the device_model column).
     */
    fun getDeviceModel(): String {
        return getDefaultLabel()
    }
}
