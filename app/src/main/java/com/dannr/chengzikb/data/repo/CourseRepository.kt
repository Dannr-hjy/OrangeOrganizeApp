package com.dannr.chengzikb.data.repo

import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.db.CourseDao
import com.dannr.chengzikb.data.db.CourseSessionDao
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.WeekSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

class CourseRepository(
    private val db: AppDatabase,
    private val courseDao: CourseDao,
    private val sessionDao: CourseSessionDao,
    private val tables: TimetableManager,
) {
    /** 当前课表的课程模板 */
    val allCourses: Flow<List<Course>> = tables.activeId.flatMapLatest { courseDao.observeByTimetable(it) }

    /** 当前课表的全部安排 */
    val allSessions: Flow<List<CourseSession>> = tables.activeId.flatMapLatest { sessionDao.observeByTimetable(it) }

    suspend fun courseById(id: Long): Course? = courseDao.byId(id)

    suspend fun coursesForTimetable(tid: Long): List<Course> = courseDao.getAllByTimetable(tid)

    /**
     * 保存课程模板及其全部安排（原子），归属当前课表。
     * 若与其他课（或本课内部安排）在“同一天 + 节次重叠 + 周次重叠”冲突则**不保存**并返回 false。
     */
    suspend fun trySaveCourseWithSessions(course: Course, sessions: List<CourseSession>): Boolean =
        db.withTransaction {
            val tid = tables.active()
            val stamped = course.copy(timetableId = tid)
            val others = sessionDao.getAllByTimetable(tid).filter { it.courseId != stamped.id }
            if (hasOverlap(sessions + others)) return@withTransaction false

            val courseId = courseDao.upsert(stamped)
            val keptIds = sessions.filter { it.id > 0 }.map { it.id }.toSet()
            val old = sessionDao.byCourseId(courseId)
            old.filter { it.id !in keptIds }.forEach { sessionDao.delete(it) }
            sessions.forEach { sessionDao.upsert(it.copy(courseId = courseId)) }
            true
        }

    /**
     * 拖拽换时间：移到 (newDay, newStart..newEnd)。
     * @param thisWeekOnly true = 仅把 [week] 这一周挪走，其余周仍在原位置。
     * @return 目标时段被别的课占用则返回 false（未移动）。
     */
    suspend fun moveSession(
        session: CourseSession,
        newDay: Int,
        newStart: Int,
        newEnd: Int,
        thisWeekOnly: Boolean,
        week: Int,
    ): Boolean = db.withTransaction {
        val tid = tables.active()
        val moveWeeks = if (thisWeekOnly) WeekSet.of(week)
        else runCatching { WeekSet.parse(session.activeWeeksText) }.getOrDefault(WeekSet.EMPTY)
        if (moveWeeks.isEmpty) return@withTransaction false
        val target = CourseSession(courseId = 0L, dayOfWeek = newDay, startPeriodIdx = newStart, endPeriodIdx = newEnd, activeWeeksText = moveWeeks.toText())
        val others = sessionDao.getAllByTimetable(tid).filter { it.id != session.id }
        if (others.any { overlaps(it, target) }) return@withTransaction false

        if (!thisWeekOnly) {
            val keepClock = newStart == session.startPeriodIdx && newEnd == session.endPeriodIdx
            sessionDao.upsert(session.copy(dayOfWeek = newDay, startPeriodIdx = newStart, endPeriodIdx = newEnd,
                startMinute = if (keepClock) session.startMinute else null,
                endMinute = if (keepClock) session.endMinute else null))
            return@withTransaction true
        }
        val remaining = WeekSet.fromIterable(
            runCatching { WeekSet.parse(session.activeWeeksText) }.getOrDefault(WeekSet.EMPTY).weeks.filter { it != week },
        )
        if (remaining.isEmpty) {
            sessionDao.delete(session)
        } else {
            sessionDao.upsert(session.copy(activeWeeksText = remaining.toText()))
        }
        sessionDao.upsert(
            CourseSession(
                courseId = session.courseId,
                dayOfWeek = newDay,
                startPeriodIdx = newStart,
                endPeriodIdx = newEnd,
                activeWeeksText = WeekSet.of(week).toText(),
                location = session.location, // 挪走的那一周沿用原教室
                startMinute = if (newStart == session.startPeriodIdx && newEnd == session.endPeriodIdx) session.startMinute else null,
                endMinute = if (newStart == session.startPeriodIdx && newEnd == session.endPeriodIdx) session.endMinute else null,
            ),
        )
        true
    }

    suspend fun deleteCourse(id: Long) {
        courseDao.deleteById(id)
    }

    suspend fun deleteSession(session: CourseSession) {
        sessionDao.delete(session)
    }

    suspend fun sessionsFor(courseId: Long): List<CourseSession> = sessionDao.byCourseId(courseId)

    /* ---------- 冲突检测：同一天 + 节次区间重叠 + 周次有交集 = 冲突 ---------- */

    private fun overlaps(a: CourseSession, b: CourseSession): Boolean {
        if (a.dayOfWeek != b.dayOfWeek) return false
        if (a.startPeriodIdx > b.endPeriodIdx || b.startPeriodIdx > a.endPeriodIdx) return false
        val aw = runCatching { WeekSet.parse(a.activeWeeksText) }.getOrDefault(WeekSet.EMPTY)
        val bw = runCatching { WeekSet.parse(b.activeWeeksText) }.getOrDefault(WeekSet.EMPTY)
        return aw.weeks.any { it in bw.weeks }
    }

    private fun hasOverlap(list: List<CourseSession>): Boolean {
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (overlaps(list[i], list[j])) return true
            }
        }
        return false
    }
}
