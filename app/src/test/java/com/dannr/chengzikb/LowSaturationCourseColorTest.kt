package com.dannr.chengzikb

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.ui.theme.blockTextColor
import com.dannr.chengzikb.ui.theme.courseColor
import com.dannr.chengzikb.ui.theme.courseFillColor
import com.dannr.chengzikb.ui.theme.mutedCourseTone
import com.dannr.chengzikb.ui.theme.mutedThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 低饱和课程配色：保色相、亮/暗两套配方，以及最要紧的一条——
 * 关掉低饱和开关后必须**逐字节还原**成课程原本的颜色，不会变成别的色。
 *
 * 走 Robolectric 是因为取色用了 android.graphics.Color 的 HSV 转换。
 * application 换成裸 Application：别把 OrangeApp 拉起来，它会在后台协程里开库，污染测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LowSaturationCourseColorTest {

    /** 调色板全部 10 个下标（编辑页只露前 7 个，自动轮替会用到后面几个） */
    private val allIndices = 0 until 10

    private fun hsv(c: Color): FloatArray =
        FloatArray(3).also { AndroidColor.colorToHSV(c.toArgb(), it) }

    /** 色相环上的最短距离（0..180） */
    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun offRestoresOriginalExactly() {
        for (i in allIndices) {
            val c = Course(id = 1L, name = "课", colorIndex = i)
            val original = courseFillColor(i)
            // 两种明暗下都必须原样返回（暗色不是"把颜色改暗"，只是关掉开关）
            assertEquals(original.toArgb(), courseColor(c, lowSaturation = false, dark = false).toArgb())
            assertEquals(original.toArgb(), courseColor(c, lowSaturation = false, dark = true).toArgb())
            assertEquals(original.toArgb(), courseFillColor(i, lowSaturation = false, dark = true).toArgb())
        }
        // 自定义色同理：关掉后就是用户当初选的那个 hex
        val custom = Course(id = 2L, name = "自定义", colorHex = "#3D7A2E")
        assertEquals(Color(0xFF3D7A2E).toArgb(), courseColor(custom, lowSaturation = false, dark = true).toArgb())
    }

    @Test
    fun lowSaturationActuallyChangesTheColor() {
        for (i in allIndices) {
            val c = Course(id = 1L, name = "课", colorIndex = i)
            val original = courseFillColor(i).toArgb()
            assertNotEquals(original, courseColor(c, lowSaturation = true, dark = false).toArgb())
            assertNotEquals(original, courseColor(c, lowSaturation = true, dark = true).toArgb())
        }
    }

    @Test
    fun keepsHueSoCoursesStayRecognizable() {
        for (i in allIndices) {
            val original = courseFillColor(i)
            for (dark in listOf(false, true)) {
                val muted = mutedCourseTone(original, dark)
                val drift = hueDistance(hsv(original)[0], hsv(muted)[0])
                assertTrue("下标 $i dark=$dark 色相漂移 $drift 过大", drift <= 10f)
            }
        }
    }

    @Test
    fun lightModeTurnsColorsPaleAndMuted() {
        for (i in allIndices) {
            val original = courseFillColor(i)
            val muted = mutedCourseTone(original, dark = false)
            val s0 = hsv(original)[1]
            val v0 = hsv(original)[2]
            val s1 = hsv(muted)[1]
            val v1 = hsv(muted)[2]
            assertTrue("下标 $i 亮色下没提亮: v $v0 -> $v1", v1 >= 0.93f)
            assertTrue("下标 $i 亮色下没压饱和: s $s0 -> $s1", s1 < s0)
            assertTrue("下标 $i 亮色下不该变暗: v $v0 -> $v1", v1 >= v0)
        }
    }

    @Test
    fun darkModeTurnsColorsDeepAndMuted() {
        for (i in allIndices) {
            val original = courseFillColor(i)
            val muted = mutedCourseTone(original, dark = true)
            val s0 = hsv(original)[1]
            val v0 = hsv(original)[2]
            val s1 = hsv(muted)[1]
            val v1 = hsv(muted)[2]
            assertTrue("下标 $i 暗色下没压暗: v $v0 -> $v1", v1 <= 0.43f && v1 < v0)
            assertTrue("下标 $i 暗色下没压饱和: s $s0 -> $s1", s1 < s0)
        }
    }

    @Test
    fun textStaysReadableOnMutedFills() {
        for (i in allIndices) {
            val c = Course(id = 1L, name = "课", colorIndex = i)
            // 浅色底 → 深字；暗调底 → 白字。blockTextColor 本来就是按亮度自适的
            val lightText = blockTextColor(courseColor(c, lowSaturation = true, dark = false))
            val darkText = blockTextColor(courseColor(c, lowSaturation = true, dark = true))
            assertTrue("下标 $i 浅色底没配深字", lightText.luminance() < 0.2f)
            assertTrue("下标 $i 暗调底没配白字", darkText.luminance() > 0.9f)
        }
    }

    @Test
    fun themePresetPreviewUsesThemeRecipeNotCourseRecipe() {
        // 设置页主题色预设显示的是"主题自己的低饱和结果"（只降饱和、不动亮度），
        // 与课程格子的浅色配方是两回事——别把两者搞混。
        val seed = Color(0xFFC62828)
        val shown = mutedThemeColor(seed)
        assertEquals(hsv(seed)[0], hsv(shown)[0], 10f)   // 色相不变
        assertEquals(hsv(seed)[2], hsv(shown)[2], 0.02f) // 亮度基本不变
        assertTrue(hsv(shown)[1] < hsv(seed)[1])         // 饱和度确实降了
    }
}
