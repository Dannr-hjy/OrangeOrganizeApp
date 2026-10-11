package com.dannr.chengzikb

import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.DayVariant
import com.dannr.chengzikb.domain.HolidaySchemes
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.toPlan
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

    /* ---------- 「补哪一周」 ---------- */

    /** 学期第 1 周周一。以下用例里 2026-05-09(补周二) 落在第 10 周，2026-02-14(补周五) 在开学前。 */
    private val TERM_START = LocalDate.of(2026, 3, 2)

    /** 周二在学期内的课表分段；[ranges] 的第 i 段代表周 = 该段首周 */
    private fun tuesdayVariants(vararg ranges: IntRange): Map<Int, List<DayVariant>> = mapOf(
        2 to ranges.map { DayVariant(WeekSet.fromIterable(it), it.first, emptyList()) },
    )

    @Test
    fun `补课日当天所在的周本来就有课时 沿用本周`() {
        // 5-9 补周二 → 第 10 周，落在周二的「第9-16周」那一段 → 沿用当天所在的周即可
        val idx = DayPlanIndex.of(
            emptyList(), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..8, 9..16),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(2, null), idx.planFor(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `补课日当天所在的周该星期无课时 落到最近的一段有课周`() {
        // 周二的课第 4 周就结束了，补课日在第 10 周 → 落到第 1-4 周那一段（不显示空白）
        val idx = DayPlanIndex.of(
            emptyList(), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..4),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(2, 1), idx.planFor(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `多段都无课时 取周次离补课日最近的一段`() {
        // 第 1-4 周与第 5-8 周各一套课表；补课日在第 10 周 → 第 5-8 周更近
        val idx = DayPlanIndex.of(
            emptyList(), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..4, 5..8),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(2, 5), idx.planFor(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `目标星期各周一致时不指定复制周次`() {
        val idx = DayPlanIndex.of(
            emptyList(), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..16),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(2, null), idx.planFor(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `补课日在学期外时不指定复制周次`() {
        // 2-14 补周五，但学期 3-02 才开始（第 -2 周）→ 维持空白，不硬塞某一周的课表
        val idx = DayPlanIndex.of(
            emptyList(), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..8, 9..16),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(5, null), idx.planFor(LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `手动覆盖带 followWeek 时 手动优先`() {
        val d = LocalDate.of(2026, 5, 9)
        val manual = DayOverride(1L, d.toEpochDay(), DayOverride.KIND_FOLLOW, 2, followWeek = 5)
        val idx = DayPlanIndex.of(
            listOf(manual), autoEnabled = true,
            variantsByDay = tuesdayVariants(1..8, 9..16),
            termStartMonday = TERM_START, totalWeeks = 16,
        )
        assertEquals(DayPlan.Follow(2, 5), idx.planFor(d))
    }

    @Test
    fun `旧数据 followWeek 为空时 按当天所在的周`() {
        val d = LocalDate.of(2026, 2, 28)
        val legacy = DayOverride(1L, d.toEpochDay(), DayOverride.KIND_FOLLOW, 1)
        assertNull(legacy.followWeek)
        assertEquals(DayPlan.Follow(1, null), legacy.toPlan())
    }
}
