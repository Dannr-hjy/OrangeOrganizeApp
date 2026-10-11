package com.dannr.chengzikb.ui.grid

/**
 * 课表某一列（某一天）实际怎么上——由 [com.dannr.chengzikb.domain.DayPlan] 解析后、带上
 * 供渲染用的信息。列的位置始终是真实日历列，只有**内容**跟着它走。
 */
sealed interface EffectiveDay {

    /** 当天无课（休息/放假） */
    data object Rest : EffectiveDay

    /**
     * 按 [dayOfWeek] 的课表上课，内容取 [contentWeek] 那一周。
     * 普通日子与「只改星期、不改周次」的调休满足 contentWeek == 列所在周；
     * 指定了复制周次（[com.dannr.chengzikb.data.model.DayOverride.followWeek]）时二者不同。
     */
    data class Follow(val dayOfWeek: Int, val contentWeek: Int) : EffectiveDay

    /** 自动补课日但还没选复制哪一周：「待选周次」，不渲染猜测的课表 */
    data class Pending(val dayOfWeek: Int) : EffectiveDay
}
