package com.dannr.chengzikb.data.import

import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import java.time.LocalDateTime

/** Parse ICS for preview, then persist courses, exact clocks and optional period metadata. */
object IcsImporter {
    data class SessionSpec(
        val day: Int,
        val startPeriodIdx: Int,
        val endPeriodIdx: Int,
        val weeks: Set<Int>,
        val location: String? = null,
        val startMinute: Int? = null,
        val endMinute: Int? = null,
    )

    data class MergedCourse(
        val name: String,
        val teacher: String?,
        val colorIndex: Int,
        val sessions: List<SessionSpec>,
    )

    data class IcsResult(
        val courses: List<MergedCourse>,
        val sessionCount: Int,
        val periods: List<PeriodSetting> = emptyList(),
        val warnings: List<String> = emptyList(),
        val periodsAligned: Boolean = false,
        val periodNotes: List<String> = emptyList(),
    )

    fun parsePeriodSpan(description: String?): Pair<Int, Int>? = IcsParser.parsePeriodSpan(description)

    fun mapByTime(periods: List<PeriodSetting>, startLdt: LocalDateTime, endLdt: LocalDateTime): Pair<Int, Int> =
        IcsParser.mapByTime(periods, startLdt, endLdt)

    fun parseEvents(termStartMonday: LocalDate, totalWeeks: Int, ics: String): List<SessionSpec> =
        buildCourses(termStartMonday, totalWeeks, emptyList(), ics).courses.flatMap { it.sessions }

    fun buildCourses(termStartMonday: LocalDate, totalWeeks: Int, periods: List<PeriodSetting>, ics: String): IcsResult =
        IcsParser.buildCourses(termStartMonday, totalWeeks, periods, ics)

    suspend fun buildForActive(db: AppDatabase, ics: String): IcsResult {
        val active = activeId(db)
        val settings = db.settingsDao().getByTimetable(active)
        val termStart = settings?.let { LocalDate.ofEpochDay(it.termStartEpochDay) }
            ?: WeekMath.mondayOfWeek(LocalDate.now())
        return buildCourses(termStart, settings?.totalWeeks ?: 20, db.periodDao().getAllByTimetable(active), ics)
    }

    /** Deletion and insertion share a transaction: failed imports preserve the old courses. */
    suspend fun overwriteActive(db: AppDatabase, result: IcsResult) = persist(db, result, overwrite = true)

    suspend fun write(db: AppDatabase, result: IcsResult) = persist(db, result, overwrite = false)

    private suspend fun activeId(db: AppDatabase): Long =
        db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)

    private suspend fun persist(db: AppDatabase, result: IcsResult, overwrite: Boolean) {
        if (result.courses.isEmpty()) return
        db.withTransaction {
            val active = activeId(db)
            val tid = if (active > 0) active else db.timetableDao().insert(Timetable(name = "导入课表"))
            val periods = result.periods.ifEmpty { db.periodDao().getAllByTimetable(tid) }
            val orders = periods.map { it.order }.toSet()
            require(result.courses.flatMap { it.sessions }.all { s ->
                (s.startPeriodIdx..s.endPeriodIdx).all { it in orders }
            }) { "作息表未包含文件中的全部节次，请补齐作息后重新导入" }
            if (overwrite) db.courseDao().deleteByTimetable(tid)
            if (result.periods.isNotEmpty()) {
                require(overwrite || db.courseDao().getAllByTimetable(tid).isEmpty()) { "导入文件带有作息时间，请选择覆盖或新建课表" }
                db.periodDao().deleteByTimetable(tid)
                db.periodDao().insertAll(result.periods.map { it.copy(id = 0L, timetableId = tid) })
            }
            result.courses.forEach { c ->
                val courseId = db.courseDao().upsert(Course(
                    name = c.name, teacher = c.teacher, colorIndex = c.colorIndex, timetableId = tid,
                ))
                c.sessions.forEach { s ->
                    db.courseSessionDao().upsert(CourseSession(
                        courseId = courseId, dayOfWeek = s.day,
                        startPeriodIdx = s.startPeriodIdx, endPeriodIdx = s.endPeriodIdx,
                        activeWeeksText = WeekSet.fromIterable(s.weeks).toText(), location = s.location,
                        startMinute = s.startMinute, endMinute = s.endMinute,
                    ))
                }
            }
        }
    }
}
