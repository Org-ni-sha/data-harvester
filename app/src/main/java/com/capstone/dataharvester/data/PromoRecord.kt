package com.capstone.dataharvester.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(
    tableName = "promo_records",
    indices = [Index(value = ["device_id"])]
)
data class PromoRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val device_id: String,
    val promo_name: String,
    val promo_price: Double,
    val promo_duration: Int,
    val promo_data_allowance: Double,
    val custom_data_allowance: Double,
    val has_unlimited_data: Int,
    val sms_allocation: String,
    val call_allocation: String,
    val provider: String,
    val start_date: String,
    val expiry_date: String,
    val created_at: Long,
    val status: String,
    @ColumnInfo(defaultValue = "0") val is_synced: Int = 0
)
