package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.DayOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface DayOverrideDao {

    @Query("SELECT * FROM day_overrides WHERE timetable_id = :timetableId ORDER BY date_epoch_day")
    fun observeByTimetable(timetableId: Long): Flow<List<DayOverride>>

    @Query("SELECT * FROM day_overrides WHERE timetable_id = :timetableId ORDER BY date_epoch_day")
    suspend fun getAllByTimetable(timetableId: Long): List<DayOverride>

    @Query("DELETE FROM day_overrides WHERE timetable_id = :timetableId AND date_epoch_day = :epochDay")
    suspend fun deleteDate(timetableId: Long, epochDay: Long)

    @Query("DELETE FROM day_overrides WHERE timetable_id = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    @Query("DELETE FROM day_overrides")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DayOverride)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DayOverride>)
}
