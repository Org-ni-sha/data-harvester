package com.capstone.dataharvester.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "device_identity",
    indices = [
        Index(value = ["hardware_id"]),
        Index(value = ["current_device_id"])
    ]
)
data class DeviceIdentity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val current_device_id: String,
    val previous_device_id: String?,
    val hardware_id: String,
    val device_model: String,
    val linked_at: Long,
    val is_synced: Int = 0
)
