package com.dannr.chengzikb

import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.data.model.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekSetTest {

    @Test
    fun `parse 多段不连续与区间`() {
        val ws = WeekSet.parse("7-9,10,15-18")
        assertTrue(7 in ws)
        assertTrue(9 in ws)
        assertTrue(10 in ws)
        assertTrue(15 in ws)
        assertTrue(18 in ws)
        assertFalse(6 in ws)
        assertFalse(11 in ws)
        assertFalse(19 in ws)
    }

    @Test
    fun `相邻周自动合并为区间并规范化`() {
        val ws = WeekSet.parse("7-9,10,15-18")
        // 9、10 相邻 → 合并为 7-10
        assertEquals("7-10,15-18", ws.toText())
        assertEquals("第7-10、15-18周", ws.describe())
    }

    @Test
    fun `roundtrip 保持集合一致`() {
        val a = WeekSet.parse("1-3,5,7-20")
        val b = WeekSet.parse(a.toText())
        assertEquals(a.weeks, b.weeks)
        assertEquals("1-3,5,7-20", a.toText())
    }

    @Test
    fun `容忍空格 与 空串`() {
        assertEquals(setOf(1, 2, 3, 5), WeekSet.parse(" 1 - 3 ,5").weeks)
        assertTrue(WeekSet.parse("   ").isEmpty)
        assertTrue(WeekSet.parse("").isEmpty)
    }

    @Test
    fun `非法输入抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { WeekSet.parse("abc") }
        assertThrows(IllegalArgumentException::class.java) { WeekSet.parse("5-3") }
        assertThrows(IllegalArgumentException::class.java) { WeekSet.parse("0-3") }
        assertThrows(IllegalArgumentException::class.java) { WeekSet.parse("1--3") }
    }

    @Test
    fun `预设工厂`() {
        assertEquals(20, WeekSet.weekly(20).size)
        val odd = WeekSet.oddWeeks(20)
        val even = WeekSet.evenWeeks(20)
        assertTrue(1 in odd)
        assertTrue(19 in odd)
        assertFalse(2 in odd)
        assertTrue(2 in even)
        assertEquals(10, odd.size)
        assertEquals(10, even.size)
        assertTrue(WeekSet.EMPTY.isEmpty)
    }

    @Test
    fun `区间构造`() {
        val ws = WeekSet.fromRange(3, 6)
        assertEquals(setOf(3, 4, 5, 6), ws.weeks)
        assertEquals("3-6", ws.toText())
        assertEquals(listOf(3, 4, 5, 6), ws.sortedWeeks)
    }
}
