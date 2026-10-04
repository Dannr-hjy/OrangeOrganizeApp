package com.dannr.chengzikb.widget

import kotlin.math.roundToInt

/**
 * 桌面小组件尺寸决策（纯函数，无 android 依赖，便于 JVM 单测）。
 *
 * 背景：各桌面通过 AppWidgetManager OPTION 上报组件尺寸(dp)，但语义不一——
 *  - OriginOS(vivo/iQOO)：上报值明显小于真实 inflate 尺寸（实测 2×2≈504px=168dp，上报≈468px）；
 *  - 其它桌面（原生 12+ / OneUI / ColorOS / HyperOS / MagicOS）：OPTION ≈ 真实可用尺寸。
 * 只有「预算高度 > 真实框架」才会把底部内容裁掉，故决策目标是：预算 ≤ 真实。
 *
 * 规则：
 *  - vivo/iQOO（[isOrigin]）：维持 OriginOS 参照，高度 = max(上报, 168dp 地板, provider.minHeight)，
 *    保证现状不回归（168dp 只是「这台 vivo」的实测校准值，经 [originFloorDp] 注入，可换比值方案调参）。
 *  - 其它厂商：绝不使用 168dp 地板。宿主按**范围**上报(min<max，荣耀/原生大桌面常见)时取较大者
 *    （否则按 min 预算会把可用行算成 0，出现“卡片下方缺课程行”的假裁切）；固定尺寸(min==max)
 *    则信该值；两者都没有就用 provider.minHeight（桌面契约必满足 provider 的 min，永不裁底，
 *    最多底部留白，下次 OPTION 到位自动填满）。
 *  - width 两者一致：按 max(MIN,MAX) 折算，与 provider.minWidth 取大；宽度只影响省略号/度量，非裁切向量。
 */
enum class HeightSource { REPORTED_MIN, REPORTED_MAX, PROVIDER_MIN, ORIGIN_FLOOR }

data class WidgetSizing(
    val widthPx: Int,
    val heightPx: Int,
    val heightSource: HeightSource,
)

object WidgetSizePolicy {

    /** 2×2 组件的真实可用高度(dp)，在 vivo OriginOS 上实机测得约 504px(=168dp)；仅 OriginOS 分支使用。 */
    const val ORIGIN_FLOOR_DP = 168

    fun decideSizing(
        minWdp: Int,
        maxWdp: Int,
        minHdp: Int,
        maxHdp: Int,
        infoMinWidthPx: Int,
        infoMinHeightPx: Int,
        density: Float,
        isOrigin: Boolean,
        originFloorDp: Int = ORIGIN_FLOOR_DP,
    ): WidgetSizing {
        fun dpToPx(v: Int): Int = if (v > 0) (v * density).roundToInt() else 0
        val infoMinWidthPx = infoMinWidthPx.coerceAtLeast(0)
        val infoMinHPx = infoMinHeightPx.coerceAtLeast(1)

        val widthPx = maxOf(dpToPx(maxOf(minWdp, maxWdp)), infoMinWidthPx).coerceAtLeast(1)

        if (isOrigin) {
            // 与旧实现逐字一致：max(上报, 168dp 地板, provider.minHeight)
            val reportedPx = dpToPx(maxOf(minHdp, maxHdp))
            val floorPx = (originFloorDp * density).roundToInt()
            return WidgetSizing(widthPx, maxOf(reportedPx, floorPx, infoMinHPx), HeightSource.ORIGIN_FLOOR)
        }

        val minPx = dpToPx(minHdp)
        val maxPx = dpToPx(maxHdp)
        val (estPx, source) = when {
            // 范围上报（min<max，宿主让组件占更大面积）→ 取较大者填满实际占据区，避免把行数预算成 0
            maxPx > minPx && maxPx > 0 -> maxPx to HeightSource.REPORTED_MAX
            // 固定尺寸（min==max）且可信（≥ provider 下限）→ 它就是实际占用
            minPx >= infoMinHPx -> minPx to HeightSource.REPORTED_MIN
            // MIN 缺失/可疑（宿主没按固定语义上报）→ 回退 MAX
            maxPx > 0 -> maxPx to HeightSource.REPORTED_MAX
            // 两者都没有（首装/宿主未推）→ 桌面契约必保的最小值兜底
            else -> 0 to HeightSource.PROVIDER_MIN
        }
        return WidgetSizing(widthPx, maxOf(estPx, infoMinHPx), source)
    }
}
