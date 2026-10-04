package com.dannr.chengzikb

import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.HolidaySchemes
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlanTest {

    private fun rest(date: LocalDate, tid: Long = 1L) =
        DayOverride(tid, date.toEpochDay(), DayOverride.KIND_REST)

    private fun follow(date: LocalDate, dow: Int, tid: Long = 1L) =
        DayOverride(tid, date.toEpochDay(), DayOverride.KIND_FOLLOW, dow)

    private fun followDay(plan: DayPlan): Int = (plan as DayPlan.Follow).dayOfWeek

    /* ---------- 内置方案自检 ---------- */

    @Test
    fun `2026 方案日期均落在 2026 年`() {
        val s = HolidaySchemes.Y2026
        s.holidays.forEach {
            assertEquals(2026, it.start.year)
            assertEquals(2026, it.endInclusive.year)
            assertTrue(it.start <= it.endInclusive)
        }
        s.makeupDays.forEach { assertEquals(2026, it.date.year) }
    }

    @Test
    fun `2026 放假区间互不重叠`() {
        val sorted = HolidaySchemes.Y2026.holidays.sortedBy { it.start }
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.name} 与 ${b.name} 重叠", a.endInclusive < b.start)
        }
    }

    @Test
    fun `2026 补课日均不在放假区间内`() {
        val s = HolidaySchemes.Y2026
        s.makeupDays.forEach { assertFalse("${it.date} 既是补课日又是放假日", s.isHoliday(it.date)) }
    }

    @Test
    fun `2026 补课映射均与当天真实星期不同`() {
        val s = HolidaySchemes.Y2026
        s.makeupDays.forEach { m ->
            assertFalse(
                "${m.date} 的映射等于当天真实星期，属冗余数据",
                m.followDayOfWeek == WeekMath.dayIndexOf(m.date),
            )
        }
    }

    @Test
    fun `2026 各假期天数与国务院通知一致`() {
        val byName = HolidaySchemes.Y2026.holidays.associateBy { it.name }
        assertEquals(3, byName.getValue("元旦").days)
        assertEquals(9, byName.getValue("春节").days)
        assertEquals(3, byName.getValue("清明节").days)
        assertEquals(5, byName.getValue("劳动节").days)
        assertEquals(3, byName.getValue("端午节").days)
        assertEquals(3, byName.getValue("中秋节").days)
        assertEquals(7, byName.getValue("国庆节").days)
    }

    @Test
    fun `2026 补课日为官方通知的六个上班日`() {
        val dates = HolidaySchemes.Y2026.makeupDays.map { it.date }.toSet()
        assertEquals(
            setOf(
                LocalDate.of(2026, 1, 4), LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 5, 9), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 10),
            ),
            dates,
        )
    }

    /* ---------- 解析优先级 ---------- */

    @Test
    fun `无覆盖且自动关闭 时等同当天真实星期`() {
        val idx = DayPlanIndex.EMPTY
        assertTrue(idx.isEmpty)
        // 2026-02-28 是周六(6)，2-23 是周一(1)
        assertEquals(6, followDay(idx.planFor(LocalDate.of(2026, 2, 28))))
        assertEquals(1, followDay(idx.planFor(LocalDate.of(2026, 2, 23))))
    }

    @Test
    fun `自动开启时 补课日按映射 放假日休息`() {
        val idx = DayPlanIndex.of(emptyList(), autoEnabled = true)
        assertEquals(1, followDay(idx.planFor(LocalDate.of(2026, 2, 28)))) // 周六补周一
        assertEquals(5, followDay(idx.planFor(LocalDate.of(2026, 2, 14)))) // 周六补周五
        assertEquals(2, followDay(idx.planFor(LocalDate.of(2026, 5, 9))))  // 周六补周二
        assertEquals(2, followDay(idx.planFor(LocalDate.of(2026, 9, 20)))) // 周日补周二
        assertEquals(3, followDay(idx.planFor(LocalDate.of(2026, 10, 10)))) // 周六补周三
        assertEquals(DayPlan.Rest, idx.planFor(LocalDate.of(2026, 2, 17)))  // 春节假期内
        assertEquals(DayPlan.Rest, idx.planFor(LocalDate.of(2026, 10, 1)))  // 国庆假期内
    }

    @Test
    fun `自动开启不影响普通日子`() {
        val idx = DayPlanIndex.of(emptyList(), autoEnabled = true)
        assertEquals(1, followDay(idx.planFor(LocalDate.of(2026, 3, 2)))) // 周一
        assertEquals(6, followDay(idx.planFor(LocalDate.of(2026, 3, 7)))) // 周六
    }

    @Test
    fun `手动休息 压过 自动补课`() {
        val d = LocalDate.of(2026, 2, 28)
        val idx = DayPlanIndex.of(listOf(rest(d)), autoEnabled = true)
        assertEquals(DayPlan.Rest, idx.planFor(d))
    }

    @Test
    fun `手动指定 压过 自动放假`() {
        val d = LocalDate.of(2026, 2, 17) // 春节假期内
        val idx = DayPlanIndex.of(listOf(follow(d, 3)), autoEnabled = true)
        assertEquals(3, followDay(idx.planFor(d)))
    }

    @Test
    fun `手动覆盖 在自动关闭时依然生效`() {
        val d = LocalDate.of(2026, 2, 28)
        val idx = DayPlanIndex.of(listOf(rest(d)), autoEnabled = false)
        assertEquals(DayPlan.Rest, idx.planFor(d))
        // 同一天的手动覆盖不会外溢到别的日期
        assertEquals(7, followDay(idx.planFor(LocalDate.of(2026, 3, 1))))
    }

    @Test
    fun `手动覆盖的取值会被规整到 1 至 7`() {
        val d = LocalDate.of(2026, 3, 2)
        assertEquals(7, followDay(DayPlanIndex.of(listOf(follow(d, 99)), true).planFor(d)))
        assertEquals(1, followDay(DayPlanIndex.of(listOf(follow(d, 0)), true).planFor(d)))
    }

    @Test
    fun `没有内置方案的年份不做自动调休`() {
        assertNull(HolidaySchemes.forYear(2027))
        val idx = DayPlanIndex.of(emptyList(), autoEnabled = true)
        // 2027-05-01 是周六，不应被当成劳动节放假
        assertEquals(6, followDay(idx.planFor(LocalDate.of(2027, 5, 1))))
    }

    /* ---------- 辅助查询 ---------- */

    @Test
    fun `manualFor 取回该日覆盖行`() {
        val d = LocalDate.of(2026, 2, 28)
        val row = rest(d, tid = 7L)
        val idx = DayPlanIndex.of(listOf(row), autoEnabled = false)
        assertNotNull(idx.manualFor(d))
        assertEquals(7L, idx.manualFor(d)!!.timetableId)
        assertNull(idx.manualFor(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `autoOnly 忽略手动覆盖`() {
        val d = LocalDate.of(2026, 2, 28)
        // 即便该日已有手动「休息」，autoOnly 仍返回自动方案里的「周一」
        assertEquals(1, followDay(DayPlanIndex.autoOnly(d, autoEnabled = true)))
        assertEquals(6, followDay(DayPlanIndex.autoOnly(d, autoEnabled = false)))
    }

    @Test
    fun `isEmpty 语义`() {
        assertTrue(DayPlanIndex.EMPTY.isEmpty)
        assertTrue(DayPlanIndex.of(emptyList(), autoEnabled = false).isEmpty)
        assertFalse(DayPlanIndex.of(emptyList(), autoEnabled = true).isEmpty)
        assertFalse(DayPlanIndex.of(listOf(rest(LocalDate.of(2026, 3, 2))), false).isEmpty)
    }
}
