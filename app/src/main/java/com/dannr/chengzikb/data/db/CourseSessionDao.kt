package com.dannr.chengzikb.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dannr.chengzikb.data.model.CourseSession
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseSessionDao {

    @Query("SELECT * FROM course_sessions ORDER BY dayOfWeek ASC, startPeriodIdx ASC")
    fun observeAll(): Flow<List<CourseSession>>

    @Query("SELECT * FROM course_sessions ORDER BY dayOfWeek ASC, startPeriodIdx ASC")
    suspend fun getAll(): List<CourseSession>

    @Query(
        "SELECT * FROM course_sessions WHERE course_id IN " +
            "(SELECT id FROM courses WHERE timetable_id = :timetableId) " +
            "ORDER BY dayOfWeek ASC, startPeriodIdx ASC",
    )
    fun observeByTimetable(timetableId: Long): Flow<List<CourseSession>>

    @Query(
        "SELECT * FROM course_sessions WHERE course_id IN " +
            "(SELECT id FROM courses WHERE timetable_id = :timetableId) " +
            "ORDER BY dayOfWeek ASC, startPeriodIdx ASC",
    )
    suspend fun getAllByTimetable(timetableId: Long): List<CourseSession>

    @Query("SELECT * FROM course_sessions WHERE id = :id")
    suspend fun byId(id: Long): CourseSession?

    @Query("SELECT * FROM course_sessions WHERE course_id = :courseId ORDER BY dayOfWeek ASC, startPeriodIdx ASC")
    suspend fun byCourseId(courseId: Long): List<CourseSession>

    @Query("SELECT * FROM course_sessions WHERE course_id = :courseId ORDER BY dayOfWeek ASC, startPeriodIdx ASC")
    fun observeByCourse(courseId: Long): Flow<List<CourseSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CourseSession): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<CourseSession>)

    @Delete
    suspend fun delete(session: CourseSession)

    @Query("DELETE FROM course_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM course_sessions")
    suspend fun deleteAll()
}
