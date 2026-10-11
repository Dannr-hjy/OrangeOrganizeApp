package com.dannr.chengzikb

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.domain.DayVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「某星期几在学期内各周的课表是否一致」的分组逻辑。 */
class DayVariantsTest {

    private fun occ(
        courseId: Long,
        day: Int,
        weeks: String,
        start: Int = 0,
        end: Int = 1,
        location: String? = null,
        teacher: String? = null,
        startMinute: Int? = null,
        endMinute: Int? = null,
    ) = Occurrence(
        Course(id = courseId, name = "C$courseId", teacher = teacher),
        CourseSession(
            id = courseId * 100 + day, courseId = courseId, dayOfWeek = day,
            startPeriodIdx = start, endPeriodIdx = end, activeWeeksText = weeks,
            location = location, startMinute = startMinute, endMinute = endMinute,
        ),
    )

    @Test
    fun `整学期一致时只有一组`() {
        val vs = DayVariants.forWeekday(listOf(occ(1, 1, "1-16")), dayOfWeek = 1, totalWeeks = 16)
        assertEquals(1, vs.size)
        assertEquals(1, vs.single().representativeWeek)
        assertEquals("第1-16周", vs.single().label)
    }

    @Test
    fun `同一时段不同课程时按周分成两组`() {
        val vs = DayVariants.forWeekday(
            listOf(occ(1, 1, "1-8"), occ(2, 1, "9-16")), dayOfWeek = 1, totalWeeks = 16,
        )
        assertEquals(2, vs.size)
        assertEquals(listOf("第1-8周", "第9-16周"), vs.map { it.label })
        assertEquals(listOf(1, 9), vs.map { it.representativeWeek })
    }

    @Test
    fun `该星期无课的周被忽略 不因此拆组`() {
        // 周二只有第1-8周有课，9-20 空 → 仍是一组，不因“课提前结束”而要求选周
        val vs = DayVariants.forWeekday(listOf(occ(1, 2, "1-8")), dayOfWeek = 2, totalWeeks = 20)
        assertEquals(1, vs.size)
        assertEquals("第1-8周", vs.single().label)
    }

    @Test
    fun `整学期该星期都没课时为空`() {
        assertTrue(DayVariants.forWeekday(listOf(occ(1, 3, "1-16")), dayOfWeek = 1, totalWeeks = 16).isEmpty())
    }

    @Test
    fun `任一展示属性不同即拆组`() {
        // 教室/老师/节次/钟点任一不同 → 不同组
        val a = occ(1, 1, "1-8", start = 0, end = 1, location = "A", teacher = "T")
        val variants = listOf(
            occ(1, 1, "9-10", start = 0, end = 1, location = "B", teacher = "T"), // 教室不同
            occ(1, 1, "11-12", start = 0, end = 1, location = "A", teacher = "U"), // 老师不同
            occ(1, 1, "13-14", start = 2, end = 3, location = "A", teacher = "T"), // 节次不同
            occ(1, 1, "15-16", start = 0, end = 1, location = "A", teacher = "T", startMinute = 500, endMinute = 600), // 钟点不同
        )
        val vs = DayVariants.forWeekday(listOf(a) + variants, dayOfWeek = 1, totalWeeks = 16)
        assertEquals(5, vs.size)
    }

    @Test
    fun `仅顺序不同视为同一组`() {
        val forward = listOf(occ(1, 1, "1-16"), occ(2, 1, "1-16"))
        val reversed = listOf(occ(2, 1, "1-16"), occ(1, 1, "1-16"))
        assertEquals(1, DayVariants.forWeekday(forward, 1, 16).size)
        assertEquals(1, DayVariants.forWeekday(reversed, 1, 16).size)
    }

    @Test
    fun `显式钟点与作息推算相等时视为同一组`() {
        val periods = Defaults.defaultPeriods() // 第1节 08:00-08:45 = 480-525
        val explicit = occ(1, 1, "1-8", start = 0, end = 0, startMinute = 480, endMinute = 525)
        val derived = occ(1, 1, "9-16", start = 0, end = 0) // 钟点由作息推算 → 480-525
        val vs = DayVariants.forWeekday(listOf(explicit, derived), dayOfWeek = 1, totalWeeks = 16, periods = periods)
        assertEquals(1, vs.size)
    }

    @Test
    fun `超出总周数的周不参与`() {
        // 活动周次到第 25 周，但学期只有 16 周 → 只看到 1-16
        val vs = DayVariants.forWeekday(listOf(occ(1, 1, "1-25")), dayOfWeek = 1, totalWeeks = 16)
        assertEquals("第1-16周", vs.single().label)
    }

    @Test
    fun `单双周形态的同一课表归为一组`() {
        // 第1-8 奇数周与 11-16 周的课表相同 → 归并到一组（跨不连续周）
        val vs = DayVariants.forWeekday(listOf(occ(1, 1, "1,3,5,7,11,13,15")), dayOfWeek = 1, totalWeeks = 16)
        assertEquals(1, vs.size)
        assertEquals("第1、3、5、7、11、13、15周", vs.single().label)
    }
}
