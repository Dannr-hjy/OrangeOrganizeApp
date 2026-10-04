package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.isActiveIn

/**
 * 把“课程模板 + 安排 → 一周某页 UI 布局”聚合的纯逻辑。
 */
object WeekView {

    /** 已排好 lane 的一条安排（ghost=true 表示“本周不开、但同格点在别周有课”的半透明展示） */
    data class PlacedCourse(
        val course: Course,
        val session: CourseSession,
        val lane: Int,
        val lanes: Int,
        val ghost: Boolean = false,
    )

    fun occurrences(courses: List<Course>, sessions: List<CourseSession>): List<Occurrence> =
        sessions.mapNotNull { s ->
            courses.firstOrNull { it.id == s.courseId }?.let { Occurrence(it, s) }
        }

    /** 该周该天(1..7)开课的所有安排 */
    fun activeOnDay(all: List<Occurrence>, weekIdx: Int, day: Int): List<Occurrence> =
        all.filter { it.session.dayOfWeek == day && it.session.isActiveIn(weekIdx) }

    /** 某天开课安排 → lane 排布 */
    fun layoutDay(occurrencesOnDay: List<Occurrence>): List<PlacedCourse> {
        if (occurrencesOnDay.isEmpty()) return emptyList()
        val intervals = occurrencesOnDay.map {
            LaneLayout.Interval(it.course.id, it.session.startPeriodIdx, it.session.endPeriodIdx)
        }
        val placements = LaneLayout.layout(intervals)
        return occurrencesOnDay.mapNotNull { o ->
            placements.firstOrNull { it.courseId == o.course.id }
                ?.let { PlacedCourse(o.course, o.session, it.lane, it.lanes) }
        }
    }

    private fun overlaps(a: CourseSession, b: CourseSession): Boolean =
        a.startPeriodIdx <= b.endPeriodIdx && b.startPeriodIdx <= a.endPeriodIdx

    /**
     * 一周某天的完整排布：本周实课 + （可选）非本周课程的幽灵半透明卡。
     * 幽灵课若与本周实课撞时段则忽略（避免遮挡）。
     */
    fun placementsForWeek(
        all: List<Occurrence>,
        weekIdx: Int,
        day: Int,
        includeOtherWeeks: Boolean,
    ): List<PlacedCourse> {
        val active = activeOnDay(all, weekIdx, day)
        val placedActive = layoutDay(active)
        if (!includeOtherWeeks) return placedActive

        val ghosts = all.filter { occ ->
            occ.session.dayOfWeek == day &&
                !occ.session.isActiveIn(weekIdx) &&
                active.none { overlaps(it.session, occ.session) }
        }
        if (ghosts.isEmpty()) return placedActive
        val ghostPlaced = layoutDay(ghosts).map { it.copy(ghost = true) }
        return placedActive + ghostPlaced
    }
}
