package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.PeriodSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodDao {

    @Query("SELECT * FROM periods ORDER BY pos ASC")
    fun observeAll(): Flow<List<PeriodSetting>>

    @Query("SELECT * FROM periods ORDER BY pos ASC")
    suspend fun getAll(): List<PeriodSetting>

    @Query("SELECT * FROM periods WHERE timetable_id = :timetableId ORDER BY pos ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<PeriodSetting>>

    @Query("SELECT * FROM periods WHERE timetable_id = :timetableId ORDER BY pos ASC")
    suspend fun getAllByTimetable(timetableId: Long): List<PeriodSetting>

    /** 某课表内当前最大 pos，空表返回 -1 */
    @Query("SELECT COALESCE(MAX(pos), -1) FROM periods WHERE timetable_id = :timetableId")
    suspend fun maxOrderByTimetable(timetableId: Long): Int

    @Query("DELETE FROM periods WHERE timetable_id = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(period: PeriodSetting): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(periods: List<PeriodSetting>)

    @Delete
    suspend fun delete(period: PeriodSetting)

    @Query("DELETE FROM periods WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM periods")
    suspend fun deleteAll()
}
