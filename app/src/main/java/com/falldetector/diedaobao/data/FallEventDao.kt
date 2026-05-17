package com.falldetector.diedaobao.data

import androidx.room.*

@Dao
interface FallEventDao {
    @Query("SELECT * FROM fall_events ORDER BY timestamp DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<FallEvent>>

    @Insert
    suspend fun insert(event: FallEvent): Long

    @Update
    suspend fun update(event: FallEvent)

    @Delete
    suspend fun delete(event: FallEvent)

    @Query("DELETE FROM fall_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM fall_events WHERE id = :id")
    suspend fun getById(id: Long): FallEvent?
}
