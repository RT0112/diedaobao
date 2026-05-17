package com.falldetector.diedaobao.data

import androidx.room.*

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY priority ASC, id ASC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<Contact>>

    @Insert
    suspend fun insert(contact: Contact): Long

    @Update
    suspend fun update(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("SELECT * FROM contacts ORDER BY priority ASC LIMIT 1")
    suspend fun getFirstContact(): Contact?

    @Query("SELECT * FROM contacts ORDER BY priority ASC")
    suspend fun getAllFirst(): List<Contact>

    // 同步查询（紧急通知用）
    @Query("SELECT * FROM contacts ORDER BY priority ASC, id ASC")
    fun getAllContactsSync(): List<Contact>
}
