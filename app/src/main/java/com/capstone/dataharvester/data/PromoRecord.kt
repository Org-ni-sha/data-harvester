package com.capstone.dataharvester.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "promo_records",
    indices = [Index(value = ["device_id"])]
)
data class PromoRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val device_id: String,
    val promo_name: String,
    val network_provider: String,
    val data_allowance_mb: Int,
    val validity_days: Int,
    val price: Double,
    val date_availed: Long,
    val is_active: Int = 1,
    val is_synced: Int = 0
)
