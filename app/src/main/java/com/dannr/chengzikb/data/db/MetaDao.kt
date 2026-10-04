package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.MetaEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface MetaDao {

    @Query("SELECT value FROM meta WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM meta WHERE key = :key LIMIT 1")
    fun observe(key: String): Flow<MetaEntry?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntry)

    @Query("DELETE FROM meta WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM meta")
    suspend fun deleteAll()
}
