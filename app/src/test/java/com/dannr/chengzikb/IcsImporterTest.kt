package com.dannr.chengzikb

import com.dannr.chengzikb.data.import.IcsImporter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcsImporterTest {

    // 2026-09-07 是周一
    private val termStart: LocalDate = LocalDate.of(2026, 9, 7)

    private fun vevent(uid: String, date: String, until: String, room: String = "研C102", teacher: String = "周波") = """
BEGIN:VEVENT
DTSTAMP:20260904T131253Z
UID:$uid
SUMMARY:程序设计
DTSTART;TZID=Asia/Shanghai:${date}T080000
DTEND;TZID=Asia/Shanghai:${date}T094000
RRULE:FREQ=WEEKLY;UNTIL=${until}T160000Z;INTERVAL=1
LOCATION:$room $teacher
DESCRIPTION:第1 - 2节\n$room\n$teacher
END:VEVENT
""".trimIndent()

    @Test
    fun `同课同天同时段的多个VEVENT合并为一门课一条安排，周次取并集`() {
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//YZune//WakeUpSchedule//EN
${vevent("a", "20261027", "20261102")}
${vevent("b", "20261124", "20261130")}
END:VCALENDAR
""".trimIndent()
        val res = IcsImporter.buildCourses(termStart, 30, emptyList(), ics)
        assertEquals(1, res.courses.size)
        val c = res.courses[0]
        assertEquals("程序设计", c.name)
        assertEquals("周波", c.teacher)
        assertEquals(1, c.sessions.size)
        val s = c.sessions[0]
        assertEquals(2, s.day) // 2026-10-27 / 2026-11-24 都是周二
        assertEquals(0, s.startPeriodIdx)
        assertEquals(1, s.endPeriodIdx)
        assertEquals(2, s.weeks.size)
        assertEquals("研C102", s.location)
    }

    @Test
    fun `不同天的同类课拆成两条安排`() {
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//YZune//WakeUpSchedule//EN
${vevent("a", "20261027", "20261102")}
${vevent("b", "20261029", "20261104")}
END:VCALENDAR
""".trimIndent()
        // 10-27 周二 / 10-29 周四
        val res = IcsImporter.buildCourses(termStart, 30, emptyList(), ics)
        assertEquals(1, res.courses.size)
        assertEquals(2, res.courses[0].sessions.size)
        assertEquals(2, res.courses[0].sessions[0].day)
        assertEquals(4, res.courses[0].sessions[1].day)
    }

    @Test
    fun `同课同老师不同地点仍合并为一门课，各时段保留各自地点`() {
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//YZune//WakeUpSchedule//EN
${vevent("a", "20261027", "20261102", room = "研C102")}
${vevent("b", "20261029", "20261104", room = "研C208")}
END:VCALENDAR
""".trimIndent()
        val res = IcsImporter.buildCourses(termStart, 30, emptyList(), ics)
        assertEquals(1, res.courses.size) // 合并键只有 名称+老师，地点不同不拆课
        assertEquals(2, res.courses[0].sessions.size)
        assertEquals("研C102", res.courses[0].sessions[0].location) // 周二去 102
        assertEquals("研C208", res.courses[0].sessions[1].location) // 周四去 208
    }

    @Test
    fun `同一门课无地点时安排地点为空`() {
        val noRoom = vevent("a", "20261027", "20261102")
            .replace("LOCATION:研C102 周波\n", "")
            .replace("DESCRIPTION:第1 - 2节\\n研C102\\n周波", "DESCRIPTION:第1 - 2节")
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//YZune//WakeUpSchedule//EN
$noRoom
END:VCALENDAR
""".trimIndent()
        val res = IcsImporter.buildCourses(termStart, 30, emptyList(), ics)
        assertEquals(1, res.courses.size)
        assertNull(res.courses[0].sessions[0].location)
    }
}
