package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.DayOverride
import java.time.LocalDate

/**
 * 「某一天实际怎么上」的解析结果：
 * [Rest] = 当天无课；[Follow] = 按 [Follow.dayOfWeek] (1..7) 的课表上课。
 *
 * 全项目有 5 处需要「日期 → 星期几」的换算（课表网格 / 桌面小组件 / 课前提醒 / ICS 导入 /
 * 主界面今天高亮），全部必须走 [DayPlanIndex]，否则调休后几处会互相打架。
 */
sealed interface DayPlan {
    /** 当天休息，没有任何课 */
    data object Rest : DayPlan

    /** 按 dayOfWeek(1..7) 的课表上课（等于当天真实星期时即为「正常」） */
    data class Follow(val dayOfWeek: Int) : DayPlan
}

/** 把一条手动覆盖翻译成 [DayPlan] */
fun DayOverride.toPlan(): DayPlan =
    if (kind == DayOverride.KIND_REST) DayPlan.Rest
    else DayPlan.Follow(followDayOfWeek.coerceIn(1, 7))

/**
 * 日期 → 上法的解析器，优先级：
 * 1. 该日期的手动覆盖（数据库里的 [DayOverride]）
 * 2. 内置节假日方案（开启「自动套用节假日调休」时）——放假 → [DayPlan.Rest]，补课 → [DayPlan.Follow]
 * 3. 兜底：当天真实星期 [WeekMath.dayIndexOf]
 *
 * 纯逻辑、无 Android 依赖，可直接单测。跨年学期无需特殊处理：按被查日期的年份取方案。
 */
class DayPlanIndex private constructor(
    private val manual: Map<Long, DayOverride>, // key = epochDay
    val autoEnabled: Boolean,
) {

    /** 既没有手动覆盖、也没开自动方案——调用方可据此整体跳过换算 */
    val isEmpty: Boolean get() = manual.isEmpty() && !autoEnabled

    /** date 当天的手动覆盖行（没有则 null） */
    fun manualFor(date: LocalDate): DayOverride? = manual[date.toEpochDay()]

    fun planFor(date: LocalDate): DayPlan {
        manualFor(date)?.let { return it.toPlan() }
        return autoOnly(date)
    }

    /** 忽略手动覆盖、只看自动方案时 date 的上法（用于「跟随默认」那一行的说明文案） */
    private fun autoOnly(date: LocalDate): DayPlan {
        if (autoEnabled) {
            HolidaySchemes.forYear(date.year)?.let { scheme ->
                if (scheme.isHoliday(date)) return DayPlan.Rest
                scheme.makeupFor(date)?.let { return DayPlan.Follow(it) }
            }
        }
        return DayPlan.Follow(WeekMath.dayIndexOf(date))
    }

    companion object {
        /** 无手动覆盖、自动关闭：planFor 恒等于当天真实星期 */
        val EMPTY = DayPlanIndex(emptyMap(), autoEnabled = false)

        fun of(overrides: List<DayOverride>, autoEnabled: Boolean): DayPlanIndex {
            if (overrides.isEmpty() && !autoEnabled) return EMPTY
            return DayPlanIndex(overrides.associateBy { it.dateEpochDay }, autoEnabled)
        }

        /** 假设移除该日的手动覆盖，date 会怎么上（编辑弹窗里「跟随默认」的副标题用） */
        fun autoOnly(date: LocalDate, autoEnabled: Boolean): DayPlan =
            DayPlanIndex(emptyMap(), autoEnabled).autoOnly(date)
    }
}
