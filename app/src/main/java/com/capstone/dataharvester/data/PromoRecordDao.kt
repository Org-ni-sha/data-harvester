package com.capstone.dataharvester.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PromoRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(promoRecord: PromoRecord)

    @Query("SELECT * FROM promo_records WHERE device_id = :deviceId ORDER BY date_availed DESC")
    suspend fun getByDeviceId(deviceId: String): List<PromoRecord>
    
    @Query("SELECT * FROM promo_records WHERE is_synced = 0")
    suspend fun getAllUnsynced(): List<PromoRecord> // For cloud sync
    
    @Query("UPDATE promo_records SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)
    
    @Query("DELETE FROM promo_records")
    suspend fun clearAll()
}
