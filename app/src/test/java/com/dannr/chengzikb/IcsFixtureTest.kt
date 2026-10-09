package com.dannr.chengzikb

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.import.IcsImporter
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Anonymized reproductions of single-event ICS, WakeUp exports and full-timetable imports. */
class IcsFixtureTest {
    private fun verify(label: String, expectedCount: Int, expectedCourses: Int): IcsImporter.IcsResult {
        fun resource(extension: String) = checkNotNull(javaClass.getResourceAsStream("/ics/$label.$extension"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = IcsImporter.buildCourses(LocalDate.of(2026, 9, 7), 19, Defaults.defaultPeriods(), resource("ics"))
        assertEquals(emptyList<String>(), result.warnings)
        val actual = result.courses.flatMap { course -> course.sessions.flatMap { session -> session.weeks.map { week ->
            listOf(course.name, course.teacher.orEmpty(), session.location.orEmpty(), session.day,
                session.startPeriodIdx, session.endPeriodIdx, week, session.startMinute, session.endMinute).joinToString("\t")
        } } }.sorted()
        val expected = resource("tsv").lineSequence().filter { it.isNotBlank() }.sorted().toList()
        assertEquals(expectedCount, actual.size)
        assertEquals(expectedCourses, result.courses.size)
        assertEquals(expected, actual)
        return result
    }

    @Test(timeout = 5000) fun `single events retain every clock classroom teacher and week`() { verify("single-events", 247, 9) }
    @Test(timeout = 5000) fun `WakeUp recurring export expands to exactly 247 classes and updates standard periods`() {
        val result = verify("weekly-events", 247, 9)
        assertTrue(result.periodsAligned)
        assertEquals(listOf(500, 555, 620, 675, 840, 895, 960, 1015), result.periods.take(8).map { it.startMinute })
        assertEquals(listOf(545, 600, 665, 720, 885, 940, 1005, 1060), result.periods.take(8).map { it.endMinute })
    }
    /**
     * 真实 WakeUp 导出的上午块 100 分钟、下午块 90 分钟；作息表默认全按 100 分钟，
     * 所以下午两块必须交用户拍板，接受后按"保留 10 分钟课间、差值平分进单节"落到 40 分钟一节。
     */
    @Test(timeout = 5000) fun `real WakeUp reference offers its afternoon blocks for the timetable`() {
        val result = verify("wakeup-reference", 148, 7)
        assertTrue(result.periods.isEmpty())
        assertFalse(result.periodsAligned)
        assertEquals(listOf("第5–6节", "第7–8节"), result.periodConflicts.map { it.label })
        val accepted = IcsImporter.assemblePeriods(result, result.periodConflicts.map { it.startPeriodIdx }.toSet())!!
        assertEquals(listOf(480, 535, 600, 655, 870, 920, 970, 1020), accepted.take(8).map { it.startMinute })
        assertEquals(listOf(525, 580, 645, 700, 910, 960, 1010, 1060), accepted.take(8).map { it.endMinute })
    }
    @Test(timeout = 5000) fun `complete school timetable is imported together with all classes`() {
        val result = verify("complete-periods", 247, 9)
        assertEquals(listOf(500, 555, 620, 675, 840, 895, 960, 1015), result.periods.map { it.startMinute })
        assertEquals(listOf(545, 600, 665, 720, 885, 940, 1005, 1060), result.periods.map { it.endMinute })
    }
}
