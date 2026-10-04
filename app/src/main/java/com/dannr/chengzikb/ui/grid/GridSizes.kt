package com.dannr.chengzikb.ui.grid

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.data.model.PeriodSetting

/**
 * 时间轴网格统一 token。
 * 每一节的行高【固定】为 45 分钟对应的高度（不随某节实际时长变化）——
 * 即 1 节、2 节连堂都按“每节同高”累加，视觉整齐。
 */
object GridSizes {
    /** 每 1 分钟对应高度(dip)：45 分钟 ≈ 60.75dp */
    const val MINUTE_HEIGHT = 1.35f

    /** 一节（无论 45/50/90 分钟）统一占用的行高，等同 45 分钟的高度 */
    const val PERIOD_DP = 45f * MINUTE_HEIGHT // 60.75

    val GutterWidth = 52.dp
    val DateHeaderHeight = 54.dp

    /** 保留旧调用点：任何“节时长”都渲染成固定行高 */
    @Deprecated("每节已等高，请用 PERIOD_DP")
    fun dpForMinutes(m: Int): Dp = PERIOD_DP.dp

    /**
     * 各节次顶部纵坐标（相对容器顶）与总高。
     * 相邻节次首尾相接、每节固定同高（不再按分钟缩放）。
     */
    fun cumulativeOffsets(periods: List<PeriodSetting>): Pair<List<Dp>, Dp> {
        val offsets = periods.indices.map { (it * PERIOD_DP).dp }
        val total = (periods.size * PERIOD_DP).dp
        return offsets to total
    }

    /** from..to 连续节次的总高度（按节数 × 固定行高，含端点） */
    fun spanHeight(periods: List<PeriodSetting>, from: Int, to: Int): Dp =
        (((to - from + 1).coerceAtLeast(0)) * PERIOD_DP).dp
}
