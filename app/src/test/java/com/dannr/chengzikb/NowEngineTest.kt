package com.dannr.chengzikb

import com.dannr.chengzikb.domain.NowEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowEngineTest {

    private val periods = listOf(
        NowEngine.Period(0, 8 * 60, 8 * 60 + 45),     // 第1节
        NowEngine.Period(1, 8 * 60 + 55, 9 * 60 + 40), // 第2节
        NowEngine.Period(2, 10 * 60, 10 * 60 + 45),    // 第3节
    )

    private val courses = listOf(
        NowEngine.CourseWindow(1L, "高数", "A101", startPeriod = 0, endPeriod = 1),
        NowEngine.CourseWindow(2L, "英语", null, startPeriod = 2, endPeriod = 2),
    )

    /** 显式 Long 比较，规避 JUnit 对 Int/Int? 的装箱歧义 */
    private fun assertIndex(expected: Int, actual: Int?) {
        assertEquals(expected.toLong(), (actual ?: Int.MIN_VALUE).toLong())
    }

    @Test
    fun `上课中能定位到当前节次`() {
        assertIndex(0, NowEngine.currentIndexOf(periods, 8 * 60 + 20))
        assertIndex(2, NowEngine.currentIndexOf(periods, 10 * 60 + 30))
        assertNull(NowEngine.currentIndexOf(periods, 8 * 60 + 45)) // 课间
        assertNull(NowEngine.currentIndexOf(periods, 23 * 60))
    }

    @Test
    fun `下一节课指向课后第一门`() {
        val next = NowEngine.nextWindow(courses, nowIndex = 1) // 第2节中 → 下门=第3节英语
        assertEquals(2L, next?.courseId)
        assertNull(NowEngine.nextWindow(courses, nowIndex = 2)) // 全天最后无下一节
        val first = NowEngine.nextWindow(courses, nowIndex = null)
        assertEquals(0L, first?.startPeriod?.toLong())
    }

    @Test
    fun `当前课窗口`() {
        val cur = NowEngine.currentWindow(courses, nowIndex = 1)
        assertEquals(1L, cur?.courseId) // 第2节落在高数 0..1
        val cur3 = NowEngine.currentWindow(courses, nowIndex = 2)
        assertEquals(2L, cur3?.courseId)
        assertNull(NowEngine.currentWindow(courses, nowIndex = null))
    }

    @Test
    fun `剩余分钟与时刻格式化`() {
        assertEquals(10L, NowEngine.minutesUntil(8 * 60 + 55, 8 * 60 + 45).toLong())
        assertEquals(0L, NowEngine.minutesUntil(8 * 60, 8 * 60 + 20).toLong()) // 已开始
        assertEquals("08:45", NowEngine.formatClock(8 * 60 + 45))
        assertEquals("23:59", NowEngine.formatClock(24 * 60 - 1))
    }
}
