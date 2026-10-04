package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dannr.chengzikb.data.model.Timetable
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {

    @Query("SELECT * FROM timetables ORDER BY id ASC")
    fun observeAll(): Flow<List<Timetable>>

    @Query("SELECT * FROM timetables ORDER BY id ASC")
    suspend fun getAll(): List<Timetable>

    @Query("SELECT * FROM timetables WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Timetable?

    @Insert
    suspend fun insert(timetable: Timetable): Long

    @Update
    suspend fun update(timetable: Timetable)

    @Delete
    suspend fun delete(timetable: Timetable)

    @Query("DELETE FROM timetables WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM timetables")
    suspend fun deleteAll()
}
