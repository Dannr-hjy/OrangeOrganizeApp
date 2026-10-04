package com.dannr.chengzikb

import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekMathTest {

    private val termStart = LocalDate.of(2026, 2, 23) // 周一

    @Test
    fun `mondayOfWeek 正确归一到周一`() {
        assertEquals(termStart, WeekMath.mondayOfWeek(LocalDate.of(2026, 2, 25)))
        assertEquals(termStart, WeekMath.mondayOfWeek(termStart))
        assertEquals(LocalDate.of(2026, 3, 9), WeekMath.mondayOfWeek(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun `weekIndexOf 基准周与边界`() {
        assertEquals(1, WeekMath.weekIndexOf(termStart, termStart))
        assertEquals(1, WeekMath.weekIndexOf(LocalDate.of(2026, 3, 1), termStart)) // 周日仍属第1周
        assertEquals(2, WeekMath.weekIndexOf(LocalDate.of(2026, 3, 2), termStart))
        assertEquals(3, WeekMath.weekIndexOf(LocalDate.of(2026, 3, 9), termStart))
    }

    @Test
    fun `学期前日期周号非正`() {
        assertTrue(WeekMath.weekIndexOf(LocalDate.of(2026, 2, 16), termStart) <= 0)
    }

    @Test
    fun `dateRangeForWeek 覆盖整周`() {
        val (s, e) = WeekMath.dateRangeForWeek(termStart, 2)
        assertEquals(LocalDate.of(2026, 3, 2), s)
        assertEquals(LocalDate.of(2026, 3, 8), e)
    }

    @Test
    fun `inTerm 边界`() {
        assertTrue(WeekMath.inTerm(termStart, termStart, totalWeeks = 20))
        // 第 20 周（最后一周）= 2026-07-06(周一) .. 2026-07-12(周日)
        assertTrue(WeekMath.inTerm(LocalDate.of(2026, 7, 12), termStart, 20))
        assertFalse(WeekMath.inTerm(LocalDate.of(2026, 7, 13), termStart, 20)) // 第21周
        assertTrue(WeekMath.inTerm(LocalDate.of(2026, 7, 6), termStart, 20)) // 第20周周一
    }

    @Test
    fun `星期映射`() {
        assertEquals("周一", WeekMath.weekdayName(1))
        assertEquals("周日", WeekMath.weekdayName(7))
        assertEquals("三", WeekMath.weekdayChar(3))
        assertEquals(1, WeekMath.dayIndexOf(LocalDate.of(2026, 2, 23))) // 周一
        assertEquals(7, WeekMath.dayIndexOf(LocalDate.of(2026, 3, 1))) // 周日
    }

    @Test
    fun `短日期文案`() {
        assertEquals("3月1日", WeekMath.shortDate(LocalDate.of(2026, 3, 1)))
    }
}
