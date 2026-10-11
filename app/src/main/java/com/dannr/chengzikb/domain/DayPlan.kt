package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.DayOverride
import java.time.LocalDate

/**
 * 「某一天实际怎么上」的解析结果：
 * [Rest] = 当天无课；[Follow] = 按 [Follow.dayOfWeek] (1..7) 的课表上课。
 *
 * 全项目有 5 处需要「日期 → 星期几」的换算（课表网格 / 桌面小组件 / 课前提醒 / 节假日方案预览 /
 * 编辑弹窗），全部必须走 [DayPlanIndex]，否则调休后几处会互相打架。
 */
sealed interface DayPlan {
    /** 当天休息，没有任何课 */
    data object Rest : DayPlan

    /**
     * 按 [dayOfWeek] (1..7) 的课表上课。
     * @param sourceWeek 复制**哪一周**（1 基学期周号）的该星期课表；
     *   null = 跟随「当天所在的周」（普通日子、旧数据、该星期各周课表一致，
     *   或所选周段本就含当天所在的周时，都无需指定）。
     */
    data class Follow(val dayOfWeek: Int, val sourceWeek: Int? = null) : DayPlan
}

/** 把一条手动覆盖翻译成 [DayPlan] */
fun DayOverride.toPlan(): DayPlan =
    if (kind == DayOverride.KIND_REST) DayPlan.Rest
    else DayPlan.Follow(followDayOfWeek.coerceIn(1, 7), followWeek?.takeIf { it >= 1 })

/**
 * 日期 → 上法的解析器，优先级：
 * 1. 该日期的手动覆盖（数据库里的 [DayOverride]）
 * 2. 内置节假日方案（开启「自动套用节假日调休」时）——放假 → [DayPlan.Rest]，
 *    补课 → [DayPlan.Follow]，该星期各周课表不一致时自动挑一套（见 [makeupSourceWeek]）
 * 3. 兜底：当天真实星期 [WeekMath.dayIndexOf]
 *
 * [variantsByDay] / [termStartMonday] / [totalWeeks] 只在开启自动调休时才需要注入：自动补课日
 * 要靠它们判断「当天所在的周有没有课、该落到哪一段课表」。纯逻辑、无 Android 依赖，可直接单测。
 */
class DayPlanIndex private constructor(
    private val manual: Map<Long, DayOverride>, // key = epochDay
    val autoEnabled: Boolean,
    private val variantsByDay: Map<Int, List<DayVariant>>,
    private val termStartMonday: LocalDate? = null,
    private val totalWeeks: Int = 0,
) {

    /** 既没有手动覆盖、也没开自动方案——调用方可据此整体跳过换算 */
    val isEmpty: Boolean get() = manual.isEmpty() && !autoEnabled

    /** date 当天的手动覆盖行（没有则 null） */
    fun manualFor(date: LocalDate): DayOverride? = manual[date.toEpochDay()]

    /** 该星期几在学期内的课表差异分组（0/1 组 = 无歧义）；供选周 UI 取用 */
    fun variantsFor(dayOfWeek: Int): List<DayVariant> = variantsByDay[dayOfWeek].orEmpty()

    fun planFor(date: LocalDate): DayPlan {
        manualFor(date)?.let { return it.toPlan() }
        return autoOnly(date)
    }

    /** 忽略手动覆盖、只看自动方案时 date 的上法（用于「跟随默认」那一行的说明文案） */
    fun autoOnly(date: LocalDate): DayPlan {
        if (autoEnabled) {
            HolidaySchemes.forYear(date.year)?.let { scheme ->
                if (scheme.isHoliday(date)) return DayPlan.Rest
                scheme.makeupFor(date)?.let { dow ->
                    return DayPlan.Follow(dow, makeupSourceWeek(dow, date))
                }
            }
        }
        return DayPlan.Follow(WeekMath.dayIndexOf(date))
    }

    /**
     * 补课日「复制哪一周的课表」——返回 null 表示沿用「当天所在的周」：
     * - 补课日在学期内、且当天所在的周该星期本来就有课 → null（内容与该课段一致，无需指定）；
     * - 补课日在学期内、但当天所在的周该星期无课（课已结课等）→ 落到离它**最近的一段有课的周**
     *   （[DayVariants.nearestWeek]）；该星期整学期都没课时无从匹配，仍返回 null；
     * - 补课日在学期外（开学前 / 结课后）或未配置学期 → null，由调用方按「不在学期内」留空。
     */
    private fun makeupSourceWeek(dow: Int, date: LocalDate): Int? {
        val vs = variantsFor(dow)
        if (vs.isEmpty()) return null
        val start = termStartMonday ?: return null
        val anchor = WeekMath.weekIndexOf(date, start)
        if (anchor !in 1..totalWeeks) return null
        if (vs.any { anchor in it.weeks }) return null
        return DayVariants.nearestWeek(vs, anchor)
    }

    companion object {
        /** 无手动覆盖、自动关闭：planFor 恒等于当天真实星期 */
        val EMPTY = DayPlanIndex(emptyMap(), autoEnabled = false, variantsByDay = emptyMap())

        fun of(
            overrides: List<DayOverride>,
            autoEnabled: Boolean,
            variantsByDay: Map<Int, List<DayVariant>> = emptyMap(),
            termStartMonday: LocalDate? = null,
            totalWeeks: Int = 0,
        ): DayPlanIndex {
            if (overrides.isEmpty() && !autoEnabled) return EMPTY
            return DayPlanIndex(
                overrides.associateBy { it.dateEpochDay },
                autoEnabled,
                variantsByDay,
                termStartMonday,
                totalWeeks,
            )
        }

        /** 假设移除该日的手动覆盖，date 会怎么上（编辑弹窗里「跟随默认」的副标题用）。 */
        fun autoOnly(date: LocalDate, autoEnabled: Boolean, termStartMonday: LocalDate? = null): DayPlan =
            DayPlanIndex(emptyMap(), autoEnabled, emptyMap(), termStartMonday).autoOnly(date)
    }
}
