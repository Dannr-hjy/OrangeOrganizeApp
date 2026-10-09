package com.dannr.chengzikb

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.import.IcsImporter
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.startMinuteIn
import com.dannr.chengzikb.data.model.endMinuteIn
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class IcsCompatibilityTest {
    @Test fun `different block duration is reported instead of inventing its internal break`() {
        val result = parse(event(end = "20260907T095000"))
        assertTrue(result.periods.isEmpty())
        assertTrue(result.periodNotes.single().contains("时长"))
        assertEquals(590, result.courses.single().sessions.single().endMinute)
    }

    @Test fun `inconsistent clocks for one span do not rewrite its shared timetable`() {
        val result = parse(event() + "\n" + event(start = "20260914T083000", end = "20260914T101000"))
        assertTrue(result.periods.isEmpty())
        assertTrue(result.periodNotes.single().contains("多个不同"))
    }

    @Test fun `aligned spans cannot overlap neighboring configured periods`() {
        val result = parse(event(start = "20260907T090000", end = "20260907T104000"))
        assertTrue(result.periods.isEmpty())
        assertTrue(result.periodNotes.any { it.contains("重叠") })
    }
    private val monday = LocalDate.of(2026, 9, 7)
    private fun parse(text: String, weeks: Int = 19) = IcsImporter.buildCourses(monday, weeks, Defaults.defaultPeriods(), text.trimIndent())
    private fun event(extra: String = "", start: String = "20260907T082000", end: String = "20260907T100000") = listOf(
        "BEGIN:VEVENT", "SUMMARY:测试课", "DTSTART;TZID=Asia/Shanghai:$start", "DTEND;TZID=Asia/Shanghai:$end",
        "DESCRIPTION:第1 - 2节\\n教室A\\n教师A", extra, "END:VEVENT",
    ).joinToString("\n")

    @Test(timeout = 2000) fun `no recurrence means one occurrence and never loops`() {
        val result = parse(event())
        assertEquals(setOf(1), result.courses.single().sessions.single().weeks)
        assertEquals(500, result.courses.single().sessions.single().startMinute)
        assertEquals(600, result.courses.single().sessions.single().endMinute)
    }

    @Test(timeout = 2000) fun `weekly without until stops at semester end`() {
        assertEquals((1..19).toSet(), parse(event("RRULE:FREQ=WEEKLY")).courses.single().sessions.single().weeks)
    }

    @Test fun `alarm description cannot replace lesson span room or teacher`() {
        val result = parse(event("""
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT20M
            SUMMARY:提醒
            DESCRIPTION:错误备注
            END:VALARM
        """.trimIndent()))
        assertEquals("测试课", result.courses.single().name)
        assertEquals("教师A", result.courses.single().teacher)
        assertEquals("教室A", result.courses.single().sessions.single().location)
        assertEquals(1, result.courses.single().sessions.single().endPeriodIdx)
    }

    @Test fun `interval two preserves odd and even weeks`() {
        val odd = parse(event("RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20270118T160000Z"))
        assertEquals((1..19 step 2).toSet(), odd.courses.single().sessions.single().weeks)
        val even = parse(event("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=9", "20260914T082000", "20260914T100000"))
        assertEquals((2..18 step 2).toSet(), even.courses.single().sessions.single().weeks)
    }

    @Test fun `UTC dates convert to Shanghai without changing local day`() {
        val result = parse(event(start = "20260906T162000Z", end = "20260906T180000Z"))
        val session = result.courses.single().sessions.single()
        assertEquals(1, session.day)
        assertEquals(20, session.startMinute)
        assertEquals(120, session.endMinute)
    }

    @Test fun `TZID of another zone converts correctly`() {
        val result = parse(event(start = "20260907T002000", end = "20260907T020000").replace("Asia/Shanghai", "UTC"))
        assertEquals(500, result.courses.single().sessions.single().startMinute)
    }

    @Test fun `count exclusions and extra dates preserve the exact week set`() {
        val result = parse(event("""
            RRULE:FREQ=WEEKLY;COUNT=3
            EXDATE;TZID=Asia/Shanghai:20260914T082000
            RDATE;TZID=Asia/Shanghai:20260928T082000
        """.trimIndent()))
        assertEquals(setOf(1, 3, 4), result.courses.single().sessions.single().weeks)
    }

    @Test fun `BYDAY count applies to occurrences not weeks`() {
        val result = parse(event("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=3"))
        val sessions = result.courses.single().sessions
        assertEquals(setOf(1, 2), sessions.first { it.day == 1 }.weeks)
        assertEquals(setOf(1), sessions.first { it.day == 3 }.weeks)
    }

    @Test fun `non UTC until is interpreted in the event zone`() {
        assertEquals(setOf(1), parse(event("RRULE:FREQ=WEEKLY;UNTIL=20260914T080000")).courses.single().sessions.single().weeks)
    }

    @Test fun `unfolding removes exactly one whitespace and text escaping is decoded`() {
        val result = parse(event().replace("SUMMARY:测试课", "SUMMARY:Hello \r\n World\\, A\\;B")
            .replace("DESCRIPTION:第1 - 2节\\n教室A\\n教师A", "DESCRIPTION:第1 - 2节\\n教室A\\n教师A"))
        assertEquals("Hello World, A;B", result.courses.single().name)
    }

    @Test fun `unicode lesson spans in SUMMARY import ordinary calendar files`() {
        val result = parse(event().replace("SUMMARY:测试课", "SUMMARY:测试课（第1–2节）")
            .replace("DESCRIPTION:第1 - 2节\\n教室A\\n教师A", "DESCRIPTION:教师：教师B\nLOCATION:教室B"))
        assertEquals("测试课", result.courses.single().name)
        assertEquals("教师B", result.courses.single().teacher)
        assertEquals(1, result.courses.single().sessions.single().endPeriodIdx)
    }

    @Test fun `times map to configured periods without a lesson description`() {
        val result = parse(event().replace("DESCRIPTION:第1 - 2节\\n教室A\\n教师A", "LOCATION:教室A"))
        assertEquals(0, result.courses.single().sessions.single().startPeriodIdx)
        assertEquals(1, result.courses.single().sessions.single().endPeriodIdx)
    }

    @Test fun `same course with varying time or room keeps separate arrangements`() {
        val text = event() + "\n" + event(start = "20260914T083000", end = "20260914T101000").replace("教室A", "教室B")
        val result = parse(text)
        assertEquals(1, result.courses.size)
        assertEquals(2, result.sessionCount)
        assertEquals(setOf(500, 510), result.courses.single().sessions.map { it.startMinute }.toSet())
    }

    @Test fun `complete period metadata is imported without interpolating breaks`() {
        val result = parse("BEGIN:VCALENDAR\nVERSION:2.0\nX-ORANGE-PERIODS:1=08:20-09:05,2=09:15-10:00\n${event()}\nEND:VCALENDAR")
        assertEquals(listOf(500, 555), result.periods.map { it.startMinute })
        assertEquals(listOf(545, 600), result.periods.map { it.endMinute })
    }

    @Test fun `invalid timetable metadata cannot replace valid existing periods`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("BEGIN:VCALENDAR\nX-ORANGE-PERIODS:1=08:20-09:05,2=09:00-10:00\n${event()}\nEND:VCALENDAR")
        }
    }

    @Test fun `unsupported recurrence yields actionable warnings instead of fabricated classes`() {
        val result = parse(event("RRULE:FREQ=MONTHLY"))
        assertTrue(result.courses.isEmpty())
        assertTrue(result.warnings.single().contains("按周"))
    }

    @Test fun `events outside semester are excluded`() {
        assertTrue(parse(event(start = "20260906T082000", end = "20260906T100000")).courses.isEmpty())
        assertTrue(parse(event(start = "20270118T082000", end = "20270118T100000")).courses.isEmpty())
    }

    @Test fun `imported clocks override defaults while manual courses still follow periods`() {
        val imported = CourseSession(courseId = 1, dayOfWeek = 1, startPeriodIdx = 0, endPeriodIdx = 1,
            startMinute = 500, endMinute = 600)
        assertEquals(500, imported.startMinuteIn(Defaults.defaultPeriods()))
        assertEquals(600, imported.endMinuteIn(Defaults.defaultPeriods()))
        val manual = imported.copy(startMinute = null, endMinute = null)
        assertEquals(480, manual.startMinuteIn(Defaults.defaultPeriods()))
        assertEquals(580, manual.endMinuteIn(Defaults.defaultPeriods()))
    }
}
