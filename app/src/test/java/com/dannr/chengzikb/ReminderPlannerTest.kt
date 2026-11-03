package com.dannr.chengzikb

import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.domain.ReminderPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderPlannerTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val settings = AppSettings(timetableId = 1, termStartEpochDay = monday.toEpochDay())
    private val course = Course(id = 1, name = "高等数学", timetableId = 1)
    private val period = PeriodSetting(order = 0, name = "第一节", startMinute = 480, endMinute = 525)
    private val session = CourseSession(id = 1, courseId = 1, dayOfWeek = 1,
        startPeriodIdx = 0, endPeriodIdx = 0, activeWeeksText = "1-20", location = "A101")
    private fun instant(text: String, z: ZoneId = zone) = java.time.LocalDateTime.parse(text).atZone(z).toInstant()
    private fun plan(
        now: String = "2026-11-02T07:00:00",
        lead: Int = 10,
        sessions: List<CourseSession> = listOf(session),
        overrides: List<DayOverride> = emptyList(),
        z: ZoneId = zone,
        days: Long = 0,
        config: AppSettings = settings,
    ) = ReminderPlanner.build(config, listOf(period), listOf(course), sessions, overrides, lead, instant(now, z), z, days)

    @Test fun `reminder is exactly the configured number of minutes before class`() {
        val r = plan().single()
        assertEquals(instant("2026-11-02T07:50:00").toEpochMilli(), r.fireMillis)
        assertEquals(instant("2026-11-02T08:00:00").toEpochMilli(), r.startMillis)
        assertEquals(600_000L, r.startMillis - r.fireMillis)
        assertEquals("A101", r.location)
    }

    @Test fun `imported class clock takes precedence over period defaults`() {
        val r = plan(sessions = listOf(session.copy(startMinute = 500, endMinute = 545))).single()
        assertEquals(500, r.startMinute)
        assertEquals(instant("2026-11-02T08:10:00").toEpochMilli(), r.fireMillis)
    }

    @Test fun `trigger exactly now is retained rather than cancelled`() {
        assertEquals(1, plan(now = "2026-11-02T07:50:00").size)
    }

    @Test fun `an undelivered overdue reminder is retained until class starts`() {
        assertEquals(1, plan(now = "2026-11-02T07:59:00").size)
        assertTrue(plan(now = "2026-11-02T08:00:00").isEmpty())
        assertTrue(plan(now = "2026-11-02T09:00:00").isEmpty())
    }

    @Test fun `early morning class reminds on the previous date`() {
        val r = plan(now = "2026-11-01T23:00:00", lead = 90, days = 1,
            sessions = listOf(session.copy(startMinute = 30, endMinute = 75))).single()
        assertEquals(monday, r.date)
        assertEquals(instant("2026-11-01T23:00:00").toEpochMilli(), r.fireMillis)
    }

    @Test fun `same school clock is converted using the current device zone`() {
        val shanghai = plan().single()
        val utc = plan(z = ZoneId.of("UTC")).single()
        assertEquals(8 * 3_600_000L, utc.fireMillis - shanghai.fireMillis)
    }

    @Test fun `lead time is an elapsed duration across a daylight saving transition`() {
        val newYork = ZoneId.of("America/New_York")
        val sunday = session.copy(dayOfWeek = 7, startMinute = 210, endMinute = 255)
        val config = settings.copy(termStartEpochDay = LocalDate.of(2026, 3, 2).toEpochDay())
        val r = plan(now = "2026-03-08T00:00:00", z = newYork, lead = 90,
            sessions = listOf(sunday), config = config).single()
        assertEquals(90 * 60_000L, r.startMillis - r.fireMillis)
        assertEquals(Instant.parse("2026-03-08T06:00:00Z").toEpochMilli(), r.fireMillis)
    }

    @Test fun `rest day does not schedule reminders`() {
        val rest = DayOverride(1, monday.toEpochDay(), DayOverride.KIND_REST)
        assertTrue(plan(overrides = listOf(rest)).isEmpty())
    }

    @Test fun `makeup day follows the selected source week and weekday`() {
        val override = DayOverride(1, monday.plusDays(1).toEpochDay(), DayOverride.KIND_FOLLOW,
            followDayOfWeek = 1, followWeek = 2)
        val s = session.copy(activeWeeksText = "2")
        assertTrue(plan(sessions = listOf(s)).isEmpty())
        assertEquals(1, plan(now = "2026-11-03T07:00:00", sessions = listOf(s), overrides = listOf(override)).size)
    }

    @Test fun `inactive weeks and dates outside the term remain empty`() {
        assertTrue(plan(sessions = listOf(session.copy(activeWeeksText = "2,4"))).isEmpty())
        assertTrue(plan(now = "2026-10-26T07:00:00").isEmpty())
        assertTrue(plan(now = "2027-03-22T07:00:00").isEmpty())
    }

    @Test fun `same time courses are all retained and renewal can reach beyond the original window`() {
        val entries = plan(days = 7, sessions = listOf(session, session.copy(id = 2)))
        assertEquals(4, entries.size)
        assertEquals(entries[0].fireMillis, entries[1].fireMillis)
        assertEquals(1, plan(now = "2026-12-14T07:00:00").size)
    }
}
