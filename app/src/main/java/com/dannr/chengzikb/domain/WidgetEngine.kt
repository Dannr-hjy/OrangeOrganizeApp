package com.dannr.chengzikb.domain

import java.time.LocalDate

/**
 * 桌面小组件的内容引擎：纯函数，不含 android 依赖、不含系统时钟与数据库，
 * 由调用方传入“某日开课的课程列表”，便于单测。
 *
 * 显示模型：
 *  - 头部日期**恒为真实今天**（[WidgetDayContent.headerDateText] 用 [WidgetDayContent.today]），
 *    它是一枚“当日历”，不随展示哪天的课而变。
 *  - “显示今天”：今日仍有未结束(进行中/未开始)的课 → 展示今日，正常高亮。
 *  - 今日的课已上完 / 今日无课 → **只看明天**：
 *      · 明天有课：展示明天的课。当天 **20:00 前**整列不高亮（这是全应用唯一“有课却全不高亮”的情形），
 *        **20:00 后**把明天视作“下一个事件”、高亮其第一节。每行时间前带“明天”前缀。
 *      · 明天也没有课：不再往后找（后天/下周都不显示），直接出提示——
 *        今天本来就没课 → “今天没课”；今天有课但已上完 → “今天的课已上完”。
 *
 * 前缀/状态文案见 [dayLabel]：+1 天=“明天”。（更远的“后天/周X”分支仅为通用工具函数保留，
 * [content] 自本版起只可能在今天与明天之间选择，不会再产生它们。）
 */
object WidgetEngine {

    /** “当日收工”的钟点：此刻起次日被视作下一个事件并高亮首节。 */
    const val DAY_CLOSE_MINUTE = 20 * 60 // 20:00

    /** “即将上课”窗口：距开课 ≤ 该分钟时状态显示“即将上课”，其余时间只显示“下节”。 */
    const val UPCOMING_SOON_MINUTE = 20

    /** “高亮提前让位”窗口：上课中且下一节距开课 ≤ 该分钟时，高亮切给下一节、本节按“即将下课”灰行显示。 */
    const val SWITCH_AHEAD_MINUTE = 30

    /** 小组件里一条当日的课（时间段已按作息换算成 00:00 起分钟） */
    data class WCourse(
        val courseId: Long,
        val name: String,
        val location: String?,
        val colorArgb: Int,
        val startMinute: Int,
        val endMinute: Int,
        /** 临近下课仍在上课、被高亮让给下一节的那门课：按“已上完”灰行展示，时间栏显示“即将下课”。 */
        val endingSoon: Boolean = false,
    )

    /** 高亮卡里那门课处于什么位置 */
    enum class FocusKind { ONGOING, NEXT }

    /** 高亮卡 */
    data class Focus(
        val course: WCourse,
        val kind: FocusKind,
        val statusText: String,
    )

    /** 某显示日渲染所需的全部内容 */
    data class WidgetDayContent(
        val date: LocalDate, // 展示的是哪天的课（≠today 时即“前滚预览/次日高亮”）
        val today: LocalDate, // 真实当天：头部日期恒定显示它
        val past: List<WCourse>, // 上完的课(灰)，时间升序；可含 endingSoon=“即将下课”的那节
        val focus: Focus?, // null → 用 [message]，或“20:00 前次日预览”（此时 [future] 非空且整列不高亮）
        val future: List<WCourse>, // 高亮卡之后未开始的课（黑）
        val message: String?, // 无 focus 时的提示：今日无课 / 今日课程已结束
    ) {
        val headerDateText: String get() = WeekMath.shortDate(today)
        val headerWeekText: String get() = WeekMath.weekdayName(WeekMath.dayIndexOf(today))
        /** 非今天时给行时间加的相对前缀：明天 / 后天 / 周X；今天则空串。 */
        val timePrefix: String get() = dayLabel(date, today)
        val isEmpty: Boolean get() = focus == null && past.isEmpty() && future.isEmpty()
    }

    /** 今天**有课且已全部上完**、明天也没课时的提示文案 */
    const val MESSAGE_DAY_DONE = "今天的课已上完"

    /** 今天**本来就没有课**、明天也没课时的提示文案 */
    const val MESSAGE_NO_CLASS = "今天没课"

    fun content(
        today: LocalDate,
        nowMinute: Int,
        forDate: (LocalDate) -> List<WCourse>,
    ): WidgetDayContent {
        // 今日仍有未结束(进行中/未开始)的课 → 就显示今日
        val todayCourses = forDate(today)
        if (todayCourses.any { it.endMinute > nowMinute }) return dayContent(today, today, nowMinute, todayCourses)

        // 今天已无课/课已上完：**只看明天**。明天有课才显示明天，后天及更远一律不显示。
        val tomorrow = today.plusDays(1)
        val tomorrowCourses = forDate(tomorrow)
        if (tomorrowCourses.isEmpty()) {
            return WidgetDayContent(
                date = today,
                today = today,
                past = emptyList(),
                focus = null,
                future = emptyList(),
                // 今天压根没课 → “今天没课”；今天有课但都上完了 → “今天的课已上完”
                message = if (todayCourses.isEmpty()) MESSAGE_NO_CLASS else MESSAGE_DAY_DONE,
            )
        }
        return dayContent(tomorrow, today, nowMinute, tomorrowCourses)
    }

    /** 对某个显示日 [d] 分段；[today] 用于算头部与相对前缀。 */
    private fun dayContent(d: LocalDate, today: LocalDate, nowMinute: Int, rawCourses: List<WCourse>): WidgetDayContent {
        val courses = rawCourses.sortedBy { it.startMinute }
        if (d == today) {
            val ongoing = courses.firstOrNull { it.startMinute <= nowMinute && nowMinute < it.endMinute }
            val past = courses.filter { it.endMinute <= nowMinute } // 已结束，时间升序
            val upcoming = courses.filter { it.startMinute > nowMinute }
            if (ongoing != null) {
                // 下一节 ≤SWITCH_AHEAD_MINUTE(30) 分钟后就开课：把高亮提前让给下一节（方便提前看下一节信息），
                // 本节改为“即将下课”的灰行。否则维持“上课中”高亮。用 ≤ 与调度边界(nextStart-30)对齐，切点不差一分钟。
                val next = upcoming.firstOrNull()
                val switchAhead = next != null && next.startMinute - nowMinute <= SWITCH_AHEAD_MINUTE
                if (switchAhead) {
                    val ending = ongoing.copy(endingSoon = true)
                    val pastAll = (past + ending).sortedBy { it.startMinute }
                    val future = upcoming.drop(1)
                    val status = statusText(d, today, next.startMinute, nowMinute)
                    return WidgetDayContent(d, today, pastAll, Focus(next, FocusKind.NEXT, status), future, null)
                }
                val future = upcoming // 全部未开始
                return WidgetDayContent(d, today, past, Focus(ongoing, FocusKind.ONGOING, "上课中"), future, null)
            }
            val next = upcoming.minByOrNull { it.startMinute }
                ?: return WidgetDayContent(d, today, past, null, emptyList(), "今日课程已结束")
            val future = upcoming.filter { it.startMinute > next.startMinute }
            val status = statusText(d, today, next.startMinute, nowMinute)
            return WidgetDayContent(d, today, past, Focus(next, FocusKind.NEXT, status), future, null)
        }
        // 未来日：全天课都还没上 → 无灰行。
        return if (nowMinute < DAY_CLOSE_MINUTE) {
            // 20:00 前：整列不高亮地预览次日/下个有课日（唯一“有课却全不高亮”的情形）
            WidgetDayContent(d, today, emptyList(), null, courses, null)
        } else {
            // 20:00 后：次日被当作“下一个事件”，高亮其第一节
            val first = courses.first()
            WidgetDayContent(d, today, emptyList(), Focus(first, FocusKind.NEXT, dayLabel(d, today)), courses.drop(1), null)
        }
    }

    /** 某天相对“今天”的显示前缀：今天=""、明天、后天、更远=周X。 */
    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> ""
        day == today.plusDays(1) -> "明天"
        day == today.plusDays(2) -> "后天"
        else -> WeekMath.weekdayName(WeekMath.dayIndexOf(day))
    }

    /**
     * 高亮卡右侧状态文案（仅同日路径使用）：
     *  正在上课→“上课中”由调用方定；此函数只管“下一节”类：
     *  距开课 ≤ [UPCOMING_SOON_MINUTE] 分钟 → “即将上课”，否则 “下节”（不逐分钟显示剩余分钟，省去高频刷新）。
     */
    fun statusText(day: LocalDate, today: LocalDate, startMinute: Int, nowMinute: Int): String {
        if (day == today) {
            val diff = startMinute - nowMinute // >0
            return if (diff <= UPCOMING_SOON_MINUTE) "即将上课" else "下节"
        }
        return dayLabel(day, today)
    }

    /**
     * 非高亮普通行的展示规则（[past]/[future] 是相对高亮课的“已上完/未上”集合，天然互斥）：
     *  - 高亮=当日第一节（past 空）→ 下方(未上)显示 2 节；
     *  - 高亮=当日最后一节（future 空）→ 上方(已上)显示 2 节；
     *  - 其余（中间节）→ 上方 1 节 + 下方 1 节。
     * 高度预算 [rowBudget] 不足时优先保留下方(未上)，绝不超预算，保证不出格子。
     * @return (上方=已上完/灰 行数, 下方=未上/黑 行数)
     */
    fun nonHighlightRows(pastSize: Int, futureSize: Int, rowBudget: Int): Pair<Int, Int> {
        val wantAbove: Int
        val wantBelow: Int
        if (futureSize == 0) {
            wantAbove = minOf(2, pastSize) // 当日最后一节：卡片上方列已上完的
            wantBelow = 0
        } else if (pastSize == 0) {
            wantAbove = 0
            wantBelow = minOf(2, futureSize) // 当日第一节：卡片下方列未上的
        } else {
            wantAbove = 1 // 中间节：前后各一节
            wantBelow = 1
        }
        val below = minOf(wantBelow, rowBudget)
        val above = minOf(wantAbove, (rowBudget - below).coerceAtLeast(0))
        return above to below
    }
}
