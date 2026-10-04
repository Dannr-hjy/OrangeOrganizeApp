package com.dannr.chengzikb.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨桌面尺寸决策的边界单测（纯 JVM）。以 density=3.0、infoMinHeight=330px(=110dp)、
 * originFloor=504px(=168dp) 为锚点，对应真机 OriginOS 的实测场景。
 */
class WidgetSizePolicyTest {

    private val density = 3.0f
    private val infoMinHeightPx = 330
    private val infoMinWidthPx = 330

    private fun decide(
        minHdp: Int,
        maxHdp: Int,
        minWdp: Int = 110,
        maxWdp: Int = 110,
        isOrigin: Boolean = false,
    ): WidgetSizing = WidgetSizePolicy.decideSizing(
        minWdp = minWdp,
        maxWdp = maxWdp,
        minHdp = minHdp,
        maxHdp = maxHdp,
        infoMinWidthPx = infoMinWidthPx,
        infoMinHeightPx = infoMinHeightPx,
        density = density,
        isOrigin = isOrigin,
    )

    // ---- OriginOS 分支：参照不回归 ----

    @Test
    fun origin_keeps_168dp_floor_when_report_smaller() {
        // 旧实机：上报≈156dp，真实≈168dp → 必须取地板 504，保持现状行数
        val s = decide(minHdp = 156, maxHdp = 156, isOrigin = true)
        assertEquals(504, s.heightPx)
        assertEquals(HeightSource.ORIGIN_FLOOR, s.heightSource)
    }

    @Test
    fun origin_without_any_options_uses_floor() {
        val s = decide(minHdp = 0, maxHdp = 0, isOrigin = true)
        assertEquals(504, s.heightPx)
        assertEquals(HeightSource.ORIGIN_FLOOR, s.heightSource)
    }

    @Test
    fun origin_uses_reported_when_larger_than_floor() {
        val s = decide(minHdp = 200, maxHdp = 200, isOrigin = true)
        assertEquals(600, s.heightPx)
    }

    // ---- 其它桌面：信上报、MIN 优先、绝不引入 168dp 地板 ----

    @Test
    fun aosp_trusts_reported_min() {
        val s = decide(minHdp = 156, maxHdp = 156)
        assertEquals(468, s.heightPx)
        assertEquals(HeightSource.REPORTED_MIN, s.heightSource)
    }

    @Test
    fun host_only_writes_max_uses_max() {
        val s = decide(minHdp = 0, maxHdp = 156)
        assertEquals(468, s.heightPx)
        assertEquals(HeightSource.REPORTED_MAX, s.heightSource)
    }

    @Test
    fun suspicious_min_below_provider_min_falls_back_to_max() {
        // min 只有 60dp(<110dp 契约下限，说明宿主没按固定语义上报) → 回退可信的 MAX
        val s = decide(minHdp = 60, maxHdp = 156)
        assertEquals(468, s.heightPx)
        assertEquals(HeightSource.REPORTED_MAX, s.heightSource)
    }

    @Test
    fun no_options_falls_back_to_provider_min_not_origin_floor() {
        // 兜底用 provider.min(110dp=330px)，而不是 168dp(504px)：非 vivo 不许隐式放大
        val s = decide(minHdp = 0, maxHdp = 0)
        assertEquals(330, s.heightPx)
        assertEquals(HeightSource.PROVIDER_MIN, s.heightSource)
    }

    @Test
    fun fixed_at_provider_min_uses_min() {
        // min==max 且正好等于 provider.min → 取该值（330）
        val s = decide(minHdp = 110, maxHdp = 110)
        assertEquals(330, s.heightPx)
        assertEquals(HeightSource.REPORTED_MIN, s.heightSource)
    }

    @Test
    fun range_host_prefers_max_to_fill() {
        // 宿主按范围上报(min=110,max=200)时取较大者，避免按 min 把行数预算成 0
        val s = decide(minHdp = 110, maxHdp = 200)
        assertEquals(600, s.heightPx)
        assertEquals(HeightSource.REPORTED_MAX, s.heightSource)
    }

    @Test
    fun honor_range_report_uses_max() {
        // 真实 Honor(MagicOS) 上报 opt=(144x196)x(132x184)、density=3.5、provider.min=110dp(=385px)
        // minH=132dp=462 可信但 < maxH=184dp=644 → 应取 644 REPORTED_MAX（否则 budget=0、卡片下方无课程行）
        val s = WidgetSizePolicy.decideSizing(
            minWdp = 144, maxWdp = 196, minHdp = 132, maxHdp = 184,
            infoMinWidthPx = 385, infoMinHeightPx = 385,
            density = 3.5f, isOrigin = false,
        )
        assertEquals(644, s.heightPx)
        assertEquals(HeightSource.REPORTED_MAX, s.heightSource)
    }

    @Test
    fun wide_range_stock_android_report_uses_max() {
        // 真实原生 A12(Sony) 上报 opt=(146x331)x(122x288)、density=2.625、provider.min=110dp(≈289px)
        val s = WidgetSizePolicy.decideSizing(
            minWdp = 146, maxWdp = 331, minHdp = 122, maxHdp = 288,
            infoMinWidthPx = 289, infoMinHeightPx = 289,
            density = 2.625f, isOrigin = false,
        )
        assertEquals((288 * 2.625).toInt(), s.heightPx)
        assertEquals(HeightSource.REPORTED_MAX, s.heightSource)
    }

    @Test
    fun big_cell_oneui_style_scales_with_report() {
        val s = decide(minHdp = 190, maxHdp = 190)
        assertEquals(570, s.heightPx)
        assertEquals(HeightSource.REPORTED_MIN, s.heightSource)
    }

    @Test
    fun non_origin_respects_real_frame_smaller_than_origin_floor() {
        // 旧代码无条件 max(reported, 168dp) 会把所有桌面抬到 504px 而裁底；
        // 非 OriginOS 真实格只有 150dp 时必须照 150dp 用(450px)，不许放大。
        val s = decide(minHdp = 150, maxHdp = 150)
        assertEquals(450, s.heightPx)
        assertEquals(HeightSource.REPORTED_MIN, s.heightSource)
    }

    @Test
    fun non_origin_no_options_falls_to_provider_min_not_504() {
        // 顺带显式守卫：非 vivo 全缺失时兜底是 provider.min(330)，绝不可能落出 OriginOS 的 504
        val s = decide(minHdp = 0, maxHdp = 0)
        assertEquals(330, s.heightPx)
    }

    @Test
    fun width_follows_report_and_provider_min() {
        // width 与高度无关（只影响省略号/度量），取 max(上报, provider.minWidth)
        val s = WidgetSizePolicy.decideSizing(
            minWdp = 150, maxWdp = 150, minHdp = 156, maxHdp = 156,
            infoMinWidthPx = infoMinWidthPx, infoMinHeightPx = infoMinHeightPx,
            density = density, isOrigin = false,
        )
        assertEquals(450, s.widthPx)
    }

    @Test
    fun density_dependent_rounding() {
        // 低密度(2.75)：156dp→429px，floor 168dp→462px → OriginOS 仍走地板
        val s = WidgetSizePolicy.decideSizing(
            minWdp = 110, maxWdp = 110, minHdp = 156, maxHdp = 156,
            infoMinWidthPx = infoMinWidthPx, infoMinHeightPx = infoMinHeightPx,
            density = 2.75f, isOrigin = true,
        )
        assertEquals(462, s.heightPx)
    }
}
