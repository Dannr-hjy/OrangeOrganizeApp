package com.dannr.chengzikb

import com.dannr.chengzikb.data.model.Course
import org.junit.Assert.assertEquals
import org.junit.Test

/** 课程简称的取用规则：开了开关且填了简称才用简称，否则一律回退全称。 */
class CourseDisplayNameTest {

    private val withShort = Course(name = "高等数学", shortName = "高数")
    private val noShort = Course(name = "大学英语")
    private val blankShort = Course(name = "线性代数", shortName = "   ")

    @Test
    fun `开关关闭时一律全称`() {
        assertEquals("高等数学", withShort.displayName(useShortName = false))
        assertEquals("大学英语", noShort.displayName(useShortName = false))
    }

    @Test
    fun `开关开启且有简称时用简称`() {
        assertEquals("高数", withShort.displayName(useShortName = true))
    }

    @Test
    fun `开关开启但未设简称回退全称`() {
        assertEquals("大学英语", noShort.displayName(useShortName = true))
        assertEquals("线性代数", blankShort.displayName(useShortName = true))
    }
}
