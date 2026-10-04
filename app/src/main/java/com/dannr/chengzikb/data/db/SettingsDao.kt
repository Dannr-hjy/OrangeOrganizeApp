package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.AppSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings WHERE timetable_id = :timetableId")
    fun observe(timetableId: Long): Flow<AppSettings?>

    @Query("SELECT * FROM settings WHERE timetable_id = :timetableId LIMIT 1")
    suspend fun getByTimetable(timetableId: Long): AppSettings?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<AppSettings>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettings)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(settings: List<AppSettings>)

    @Query("DELETE FROM settings WHERE timetable_id = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}
