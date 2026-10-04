package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.Course
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY id ASC")
    fun observeAll(): Flow<List<Course>>

    @Query("SELECT * FROM courses ORDER BY id ASC")
    suspend fun getAll(): List<Course>

    @Query("SELECT * FROM courses WHERE timetable_id = :timetableId ORDER BY id ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE timetable_id = :timetableId ORDER BY id ASC")
    suspend fun getAllByTimetable(timetableId: Long): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun byId(id: Long): Course?

    @Query("DELETE FROM courses WHERE timetable_id = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: Course): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<Course>)

    @Delete
    suspend fun delete(course: Course)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM courses")
    suspend fun deleteAll()
}
