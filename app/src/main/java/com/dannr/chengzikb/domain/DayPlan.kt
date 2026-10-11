package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.DayOverride
import java.time.LocalDate

/**
 * 「某一天实际怎么上」的解析结果：
 * [Rest] = 当天无课；[Follow] = 按 [Follow.dayOfWeek] (1..7) 的课表上课；
 * [PendingWeek] = 自动补课日，但该星期各周课表不同、还没选复制哪一周，需用户手动指定。
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
     *   null = 跟随「当天所在的周」（普通日子、旧数据或该星期各周课表一致时无需指定）。
     */
    data class Follow(val dayOfWeek: Int, val sourceWeek: Int? = null) : DayPlan

    /**
     * 自动补课日：目标星期在本学期各周课表不同，无法自动确定补哪一周。
     * 未经用户选定前不渲染任何猜测的课表（课表网格显示「待选周次」、小组件/提醒当天留空）。
     */
    data class PendingWeek(val dayOfWeek: Int) : DayPlan
}

/** 把一条手动覆盖翻译成 [DayPlan] */
fun DayOverride.toPlan(): DayPlan =
    if (kind == DayOverride.KIND_REST) DayPlan.Rest
    else DayPlan.Follow(followDayOfWeek.coerceIn(1, 7), followWeek?.takeIf { it >= 1 })

/**
 * 日期 → 上法的解析器，优先级：
 * 1. 该日期的手动覆盖（数据库里的 [DayOverride]）
 * 2. 内置节假日方案（开启「自动套用节假日调休」时）——放假 → [DayPlan.Rest]，
 *    补课 → 该星期无歧义时 [DayPlan.Follow]，各周不同则 [DayPlan.PendingWeek]
 * 3. 兜底：当天真实星期 [WeekMath.dayIndexOf]
 *
 * [variantsByDay] 只在开启自动调休时才需要注入：只有自动补课日才会因为「各周课表不同」产生
 * [DayPlan.PendingWeek]。纯逻辑、无 Android 依赖，可直接单测。
 */
class DayPlanIndex private constructor(
    private val manual: Map<Long, DayOverride>, // key = epochDay
    val autoEnabled: Boolean,
    private val variantsByDay: Map<Int, List<DayVariant>>,
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
                    return if (variantsFor(dow).size >= 2) DayPlan.PendingWeek(dow)
                    else DayPlan.Follow(dow) // 无歧义：沿用「当天所在的周」
                }
            }
        }
        return DayPlan.Follow(WeekMath.dayIndexOf(date))
    }

    companion object {
        /** 无手动覆盖、自动关闭：planFor 恒等于当天真实星期 */
        val EMPTY = DayPlanIndex(emptyMap(), autoEnabled = false, variantsByDay = emptyMap())

        fun of(
            overrides: List<DayOverride>,
            autoEnabled: Boolean,
            variantsByDay: Map<Int, List<DayVariant>> = emptyMap(),
        ): DayPlanIndex {
            if (overrides.isEmpty() && !autoEnabled) return EMPTY
            return DayPlanIndex(overrides.associateBy { it.dateEpochDay }, autoEnabled, variantsByDay)
        }

        /** 假设移除该日的手动覆盖，date 会怎么上（编辑弹窗里「跟随默认」的副标题用）。 */
        fun autoOnly(date: LocalDate, autoEnabled: Boolean): DayPlan =
            DayPlanIndex(emptyMap(), autoEnabled, emptyMap()).autoOnly(date)
    }
}
