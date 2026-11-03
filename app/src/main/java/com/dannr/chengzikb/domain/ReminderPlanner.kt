package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.isActiveIn
import com.dannr.chengzikb.data.model.startMinuteIn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 一个时钟/时区快照计算全部绝对时间；不依赖 Android，可直接验证跨天、周次与调休。 */
object ReminderPlanner {
    data class Entry(
        val courseId: Long,
        val sessionId: Long,
        val date: LocalDate,
        val startMinute: Int,
        val startMillis: Long,
        val fireMillis: Long,
        val courseName: String,
        val location: String,
    )

    fun build(
        settings: AppSettings,
        periods: List<PeriodSetting>,
        courses: List<Course>,
        sessions: List<CourseSession>,
        overrides: List<DayOverride>,
        leadMinutes: Int,
        now: Instant,
        zone: ZoneId,
        scheduleDays: Long = 7L,
    ): List<Entry> {
        require(leadMinutes in 1..90)
        require(scheduleDays >= 0)
        val termStart = LocalDate.ofEpochDay(settings.termStartEpochDay)
        val dayPlans = DayPlanIndex.of(
            overrides, settings.autoHoliday,
            DayVariants.allFor(WeekView.occurrences(courses, sessions), settings.totalWeeks, periods),
            termStartMonday = termStart, totalWeeks = settings.totalWeeks,
        )
        val byId = courses.associateBy { it.id }
        val today = now.atZone(zone).toLocalDate()
        val nowMillis = now.toEpochMilli()
        return buildList {
            for (offset in 0..scheduleDays) {
                val date = today.plusDays(offset)
                val plan = dayPlans.planFor(date) as? DayPlan.Follow ?: continue
                val week = plan.sourceWeek ?: WeekMath.weekIndexOf(date, termStart)
                if (week !in 1..settings.totalWeeks) continue
                for (session in sessions) {
                    if (session.dayOfWeek != plan.dayOfWeek || !session.isActiveIn(week)) continue
                    val course = byId[session.courseId] ?: continue
                    val minute = session.startMinuteIn(periods)?.takeIf { it in 0..1439 } ?: continue
                    val start = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone)
                        .toInstant().toEpochMilli()
                    val fire = start - leadMinutes * 60_000L
                    // 尚未开课但提醒时刻已过：交给调度层补排；已送达记录负责去重。
                    if (start <= nowMillis) continue
                    add(Entry(course.id, session.id, date, minute, start, fire, course.name, session.location.orEmpty()))
                }
            }
        }.sortedBy { it.fireMillis }
    }
}
