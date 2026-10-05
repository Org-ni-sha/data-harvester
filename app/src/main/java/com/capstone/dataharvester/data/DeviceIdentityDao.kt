package com.capstone.dataharvester.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deviceIdentity: DeviceIdentity)

    @Query("SELECT * FROM device_identity WHERE hardware_id = :hardwareId ORDER BY linked_at DESC LIMIT 1")
    suspend fun getLatestByHardwareId(hardwareId: String): DeviceIdentity?
    
    @Query("SELECT * FROM device_identity WHERE is_synced = 0")
    suspend fun getAllUnsynced(): List<DeviceIdentity> // For cloud sync
    
    @Query("UPDATE device_identity SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Int>)
    
    @Query("DELETE FROM device_identity")
    suspend fun clearAll()
}
