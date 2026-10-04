package com.dannr.chengzikb

import com.dannr.chengzikb.domain.WidgetEngine
import com.dannr.chengzikb.domain.WidgetEngine.FocusKind
import com.dannr.chengzikb.domain.WidgetEngine.WCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetEngineTest {

    private val mon = LocalDate.of(2026, 9, 7) // 周一
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)

    private fun course(id: Long, name: String, start: String, end: String, loc: String? = "综合楼203") =
        WCourse(id, name, loc, 0xFFE8590C.toInt(), hhmm(start), hhmm(end))

    private fun hhmm(t: String): Int {
        val (h, m) = t.split(":")
        return h.toInt() * 60 + m.toInt()
    }

    private fun engine(
        today: LocalDate,
        nowMinute: Int,
        byDate: Map<LocalDate, List<WCourse>>,
    ) = WidgetEngine.content(today, nowMinute, { byDate[it] ?: emptyList() })

    // 周一：高数 08:00-09:40、英语 10:00-10:45、物理 14:00-15:40
    private val monCourses = listOf(
        course(1, "高数", "08:00", "09:40"),
        course(2, "英语", "10:00", "10:45", "外语楼201"),
        course(3, "物理", "14:00", "15:40"),
    )

    @Test
    fun `上课中门课成为高亮卡且无灰行`() {
        val c = engine(mon, hhmm("09:00"), mapOf(mon to monCourses))
        assertEquals(mon, c.date)
        assertEquals(1L, c.focus?.course?.courseId)
        assertEquals(FocusKind.ONGOING, c.focus?.kind)
        assertEquals("上课中", c.focus?.statusText)
        assertEquals(emptyList<WCourse>(), c.past)
        assertEquals(listOf(2L, 3L), c.future.map { it.courseId })
        assertNull(c.message)
    }

    @Test
    fun `已上完课置灰置顶且下一节超一小时显示下节`() {
        val c = engine(mon, hhmm("11:00"), mapOf(mon to monCourses))
        assertEquals(listOf(1L, 2L), c.past.map { it.courseId }) // 时间升序
        assertEquals(3L, c.focus?.course?.courseId)
        assertEquals(FocusKind.NEXT, c.focus?.kind)
        assertEquals("下节", c.focus?.statusText)
        assertEquals(emptyList<WCourse>(), c.future)
    }

    @Test
    fun `开课前超过20分钟仅显示下节不显示剩余分钟`() {
        // 13:30，物理 14:00(还 30 分钟) → 不再逐分钟倒计时，仅“下节”
        val c = engine(mon, hhmm("13:30"), mapOf(mon to monCourses))
        assertEquals("下节", c.focus?.statusText)
    }

    @Test
    fun `开课前20分钟内显示即将上课`() {
        // 13:45，物理 14:00(还 15 分钟) → “即将上课”
        val c = engine(mon, hhmm("13:45"), mapOf(mon to monCourses))
        assertEquals("即将上课", c.focus?.statusText)
    }

    @Test
    fun `今日全部上完20点后前滚到明日首节并标明天`() {
        val c = engine(mon, hhmm("20:00"), mapOf(mon to monCourses, tue to listOf(course(5, "化学", "08:00", "09:40"))))
        assertEquals(tue, c.date)
        assertEquals(5L, c.focus?.course?.courseId)
        assertEquals("明天", c.focus?.statusText)
        assertEquals(emptyList<WCourse>(), c.past)
        assertEquals(emptyList<WCourse>(), c.future)
        assertEquals("明天", c.timePrefix)
        // 头部是真实今天（周一 9/7），不是所显示的周二
        assertEquals("9月7日", c.headerDateText)
        assertEquals("周一", c.headerWeekText)
    }

    @Test
    fun `20点前今日已收工则整列不高亮地预览次日`() {
        // 18:00（今天最后一节 15:40 早已结束）→ 前滚次日，但未到 20:00：整列不高亮、行时间带“明天”前缀
        val c = engine(mon, hhmm("18:00"), mapOf(mon to monCourses, tue to listOf(course(5, "化学", "08:00", "09:40"))))
        assertEquals(tue, c.date)
        assertNull(c.focus)
        assertTrue(c.past.isEmpty())
        assertEquals(listOf(5L), c.future.map { it.courseId })
        assertNull(c.message)
        assertEquals("明天", c.timePrefix)
    }

    @Test
    fun `20点后前滚则高亮次日首节`() {
        val c = engine(mon, hhmm("20:01"), mapOf(mon to monCourses, tue to listOf(course(5, "化学", "08:00", "09:40"))))
        assertEquals(5L, c.focus?.course?.courseId)
        assertEquals("明天", c.focus?.statusText)
        assertEquals("明天", c.timePrefix)
    }

    @Test
    fun `前滚预览头部日期恒为真实今天`() {
        val c = engine(mon, hhmm("18:00"), mapOf(mon to monCourses, tue to listOf(course(5, "化学", "08:00", "09:40"))))
        assertEquals("9月7日", c.headerDateText)
        assertEquals("周一", c.headerWeekText)
    }

    @Test
    fun `明天无课时不再往后找后天而是提示今天已上完`() {
        // 周一课上完，周二无课，周三有课 → 不再前滚到周三（这是本版刻意收紧的口径）
        val c = engine(mon, hhmm("20:00"), mapOf(mon to monCourses, wed to listOf(course(6, "体育", "10:00", "11:40"))))
        assertEquals(mon, c.date)
        assertNull(c.focus)
        assertTrue(c.future.isEmpty())
        assertTrue(c.isEmpty)
        assertEquals(WidgetEngine.MESSAGE_DAY_DONE, c.message)
        // 头部仍是真实今天
        assertEquals("9月7日", c.headerDateText)
        assertEquals("周一", c.headerWeekText)
    }

    @Test
    fun `明天无课时20点前同样提示今天已上完`() {
        val c = engine(mon, hhmm("18:00"), mapOf(mon to monCourses, wed to listOf(course(6, "体育", "10:00", "11:40"))))
        assertEquals(mon, c.date)
        assertEquals(WidgetEngine.MESSAGE_DAY_DONE, c.message)
    }

    @Test
    fun `今日无课未到20点次日有课则整列不高亮预览`() {
        val c = engine(mon, hhmm("09:00"), mapOf(tue to listOf(course(7, "英语", "08:00", "09:40"))))
        assertEquals(tue, c.date)
        assertNull(c.focus)
        assertNull(c.message)
        assertEquals(listOf(7L), c.future.map { it.courseId })
        assertEquals("明天", c.timePrefix)
    }

    @Test
    fun `今天有课但都上完且明天无课显示今天的课已上完`() {
        val c = engine(mon, hhmm("20:00"), mapOf(mon to monCourses))
        assertNull(c.focus)
        assertEquals(WidgetEngine.MESSAGE_DAY_DONE, c.message)
        assertEquals(mon, c.date)
    }

    @Test
    fun `今天本来就没课且明天也无课显示今天没课`() {
        val c = engine(mon, hhmm("09:00"), emptyMap())
        assertNull(c.focus)
        assertEquals(WidgetEngine.MESSAGE_NO_CLASS, c.message)
        assertEquals(mon, c.date)
    }

    @Test
    fun `今天没课但明天有课显示明天而不是今天没课`() {
        val c = engine(mon, hhmm("09:00"), mapOf(tue to listOf(course(7, "英语", "08:00", "09:40"))))
        assertNull(c.message)
        assertEquals(tue, c.date)
    }

    @Test
    fun `今日无课但明天有课仍显示明天`() {
        val c = engine(mon, hhmm("09:00"), mapOf(tue to listOf(course(7, "英语", "08:00", "09:40"))))
        assertEquals(tue, c.date)
        assertEquals("明天", c.timePrefix)
    }

    @Test
    fun `只看明天一天更远的有课日不再被读到`() {
        // today 之后只有周四有课（周二、周三都没有）→ 断言引擎根本没去查后天
        val queried = mutableListOf<LocalDate>()
        val c = WidgetEngine.content(mon, hhmm("09:00")) { d ->
            queried += d
            if (d == mon.plusDays(3)) listOf(course(9, "地理", "10:00", "11:40")) else emptyList()
        }
        assertEquals(WidgetEngine.MESSAGE_NO_CLASS, c.message) // 今天本来就没课
        assertEquals(listOf(mon, tue), queried) // 只问了今天和明天
    }

    // —— 非高亮行展示规则：nonHighlightRows ——

    @Test
    fun `高亮为当日第一节时下方显示两节未上`() {
        assertEquals(0 to 2, WidgetEngine.nonHighlightRows(pastSize = 0, futureSize = 3, rowBudget = 5))
        assertEquals(0 to 1, WidgetEngine.nonHighlightRows(pastSize = 0, futureSize = 1, rowBudget = 5))
    }

    @Test
    fun `高亮为当日最后一节时上方显示两节已上`() {
        assertEquals(2 to 0, WidgetEngine.nonHighlightRows(pastSize = 3, futureSize = 0, rowBudget = 5))
        assertEquals(1 to 0, WidgetEngine.nonHighlightRows(pastSize = 1, futureSize = 0, rowBudget = 5))
    }

    @Test
    fun `高亮为当日中间节时上方一节已上、下方一节未上`() {
        assertEquals(1 to 1, WidgetEngine.nonHighlightRows(pastSize = 2, futureSize = 2, rowBudget = 5))
        assertEquals(1 to 1, WidgetEngine.nonHighlightRows(pastSize = 4, futureSize = 1, rowBudget = 5))
    }

    @Test
    fun `行数预算不足时不超预算且优先保留下方未上`() {
        assertEquals(0 to 1, WidgetEngine.nonHighlightRows(pastSize = 0, futureSize = 3, rowBudget = 1))
        assertEquals(0 to 1, WidgetEngine.nonHighlightRows(pastSize = 2, futureSize = 2, rowBudget = 1)) // 中间节放不下→先保下方
        assertEquals(2 to 0, WidgetEngine.nonHighlightRows(pastSize = 3, futureSize = 0, rowBudget = 2))
        assertEquals(0 to 0, WidgetEngine.nonHighlightRows(pastSize = 0, futureSize = 0, rowBudget = 2)) // 全天仅一节
    }

    // —— 高亮提前切换：上课中且下一节 ≤30 分钟开课 ——

    @Test
    fun `上课中但下一节30分钟内开课则高亮切到下一节本节转即将下课灰行`() {
        // 高数 08:00-09:40 进行中(09:35)，英语 09:55 开课(20分钟后)
        val c = engine(
            mon, hhmm("09:35"),
            mapOf(mon to listOf(course(1, "高数", "08:00", "09:40"), course(2, "英语", "09:55", "10:25", "外语楼201"))),
        )
        assertEquals(2L, c.focus?.course?.courseId) // 高亮切到英语
        assertEquals(FocusKind.NEXT, c.focus?.kind)
        assertEquals("即将上课", c.focus?.statusText) // 距英语开课 20 分钟整 → “即将上课”
        assertEquals(listOf(1L), c.past.map { it.courseId }) // 高数成为上完形式的行
        assertTrue(c.past.single().endingSoon) // 但标记为即将下课
        assertTrue(c.future.isEmpty()) // 英语是当日最后一节
    }

    @Test
    fun `上课中但下一节超过30分钟仍维持上课中高亮`() {
        // 高数 08:00-09:40 进行中(09:35)，物理 10:30 开课(55分钟后) → 不提前切
        val c = engine(
            mon, hhmm("09:35"),
            mapOf(mon to listOf(course(1, "高数", "08:00", "09:40"), course(3, "物理", "10:30", "12:00"))),
        )
        assertEquals(1L, c.focus?.course?.courseId)
        assertEquals(FocusKind.ONGOING, c.focus?.kind)
        assertEquals("上课中", c.focus?.statusText)
        assertTrue(c.past.isEmpty())
        assertEquals(listOf(3L), c.future.map { it.courseId })
    }

    @Test
    fun `上课中下一节恰好30分钟开课也在边界提前让位`() {
        // 高数 08:00-09:40 进行中(09:10)，英语 09:40 开课(恰好 30 分钟后) → ≤SWITCH_AHEAD_MINUTE，高亮切英语
        val c = engine(
            mon, hhmm("09:10"),
            mapOf(mon to listOf(course(1, "高数", "08:00", "09:40"), course(2, "英语", "09:40", "10:20", "外语楼201"))),
        )
        assertEquals(2L, c.focus?.course?.courseId)
        assertEquals(FocusKind.NEXT, c.focus?.kind)
        assertEquals("下节", c.focus?.statusText) // 距开课 30 分钟：还没进“即将上课”(20 分钟)窗口
        assertEquals(listOf(1L), c.past.map { it.courseId })
        assertTrue(c.past.single().endingSoon) // 已让位 → 本节按“即将下课”灰行显示
    }

    @Test
    fun `提前切换后后续未上课仍列入下方未上`() {
        // 高数进行中(09:35)，英语 09:55(20分钟后)，物理 10:30 → 高亮英语，物理仍为下方未上
        val c = engine(
            mon, hhmm("09:35"),
            mapOf(
                mon to listOf(
                    course(1, "高数", "08:00", "09:40"),
                    course(2, "英语", "09:55", "10:25", "外语楼201"),
                    course(3, "物理", "10:30", "12:00"),
                ),
            ),
        )
        assertEquals(2L, c.focus?.course?.courseId)
        assertEquals(listOf(3L), c.future.map { it.courseId })
        assertEquals(listOf(1L), c.past.map { it.courseId })
        assertTrue(c.past.single().endingSoon)
    }
}
