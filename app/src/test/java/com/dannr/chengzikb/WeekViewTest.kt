package com.dannr.chengzikb

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.import.IcsImporter
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.domain.WeekView
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同一时段在不同周排不同课时，卡片必须各占一列、不相互遮挡。
 * 导入的 .ics 通过真实解析器生成安排，覆盖 WakeUp 把“同课同槽位不同周次/教室”拆成多个 VEVENT 的情形。
 */
class WeekViewTest {
    private val termStart = LocalDate.of(2026, 9, 7) // 周一

    private fun event(uid: String, name: String, date: String, count: Int, room: String, teacher: String) = """
BEGIN:VEVENT
UID:$uid
SUMMARY:$name
DTSTART;TZID=Asia/Shanghai:${date}T080000
DTEND;TZID=Asia/Shanghai:${date}T094000
RRULE:FREQ=WEEKLY;COUNT=$count
LOCATION:$room $teacher
DESCRIPTION:第1 - 2节\n$room\n$teacher
END:VEVENT
""".trimIndent()

    private fun parse(vararg events: String): IcsImporter.IcsResult = IcsImporter.buildCourses(
        termStart, 19, Defaults.defaultPeriods(),
        """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//YZune//WakeUpSchedule//EN
${events.joinToString("\n")}
END:VCALENDAR
""".trimIndent(),
    )

    /** 把解析结果落成 app 里会持久化的 Course/CourseSession（每个课程一个自增 id）。 */
    private fun occurrences(result: IcsImporter.IcsResult): List<Occurrence> {
        val courses = result.courses.mapIndexed { i, c -> Course(id = (i + 1).toLong(), name = c.name, teacher = c.teacher) }
        var sid = 100L
        val sessions = result.courses.flatMapIndexed { i, c ->
            c.sessions.map { s ->
                CourseSession(
                    id = sid++, courseId = (i + 1).toLong(), dayOfWeek = s.day,
                    startPeriodIdx = s.startPeriodIdx, endPeriodIdx = s.endPeriodIdx,
                    activeWeeksText = WeekSet.fromIterable(s.weeks).toText(), location = s.location,
                )
            }
        }
        return WeekView.occurrences(courses, sessions)
    }

    private fun lanesOneWeek(all: List<Occurrence>, week: Int, day: Int = 2) =
        WeekView.placementsForWeek(all, week, day, includeOtherWeeks = false)

    @Test
    fun `不同课程排在同一时段的不同周次时按周分别显示`() {
        val all = occurrences(parse(
            event("a", "CourseA", "20260908", 8, "RoomA", "T1"),   // 第1-8周
            event("b", "CourseB", "20261103", 8, "RoomB", "T2"),   // 第9-16周
        ))
        assertEquals(listOf("CourseA"), lanesOneWeek(all, 3).map { it.course.name })
        assertEquals(listOf("CourseB"), lanesOneWeek(all, 12).map { it.course.name })
    }

    @Test
    fun `同一门课的周次互不重叠时按周只显示当前周的安排`() {
        val all = occurrences(parse(
            event("a", "CourseA", "20260908", 8, "RoomA", "T1"),
            event("b", "CourseA", "20261103", 8, "RoomB", "T1"),
        ))
        assertEquals(listOf("RoomA"), lanesOneWeek(all, 3).map { it.session.location })
        assertEquals(listOf("RoomB"), lanesOneWeek(all, 12).map { it.session.location })
    }

    @Test
    fun `同一门课的周次重叠时两条安排各占一列而不是叠在一起`() {
        // 中途换教室被导出成 1-16 周 RoomA 与 1-16 周 RoomB 两条安排
        val all = occurrences(parse(
            event("a", "CourseA", "20260908", 16, "RoomA", "T1"),
            event("b", "CourseA", "20260908", 16, "RoomB", "T1"),
        ))
        val placed = lanesOneWeek(all, 3)
        assertEquals(2, placed.size)
        assertEquals(setOf("RoomA", "RoomB"), placed.map { it.session.location }.toSet())
        assertEquals(setOf(0, 1), placed.map { it.lane }.toSet())
        assertEquals(2, placed.first().lanes)
    }
}
