package com.dannr.chengzikb

import com.dannr.chengzikb.domain.LaneLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class LaneLayoutTest {

    private fun place(list: List<Pair<Long, IntRange>>) =
        LaneLayout.layout(list.map { (id, r) -> LaneLayout.Interval(id, r.first, r.last) })

    private fun laneOf(pl: List<LaneLayout.Placement>, id: Long) =
        pl.first { it.courseId == id }

    @Test
    fun `完全互斥的课程各占一列单lane`() {
        val out = place(listOf(1L to 1..3, 2L to 5..6, 3L to 8..8))
        assertEquals(1, out[0].lanes)
    }

    @Test
    fun `互不重叠相邻排入同lane`() {
        val out = place(listOf(1L to 1..2, 2L to 3..4))
        assertEquals(1, out[0].lanes)
        assertEquals(laneOf(out, 1L).lane, laneOf(out, 2L).lane)
    }

    @Test
    fun `3门相互重叠分3lane`() {
        val out = place(listOf(1L to 1..3, 2L to 2..4, 3L to 3..5))
        assertEquals(3, out[0].lanes)
        val lanes = out.map { it.lane }.toSet()
        assertEquals(setOf(0, 1, 2), lanes)
    }

    @Test
    fun `经典混排 外层2 + 内侧2 共2lane`() {
        // a:1-2, b:1-2(冲突) ; c:3-3, d:3-3(冲突)；与上方时段无重叠 → 各自时段内 max depth 2
        val out = place(listOf(1L to 1..2, 2L to 1..2, 3L to 3..3, 4L to 3..3))
        assertEquals(2, out[0].lanes)
        assertEquals(laneOf(out, 1L).lane, laneOf(out, 3L).lane) // 错峰同lane
    }

    @Test
    fun `互斥课可复用已释放列 达到最小列数`() {
        // 重叠只发生在同一时间层：a(1-1), b(1-1), c(2-2) → c 复用 lane
        val out = place(listOf(1L to 1..1, 2L to 1..1, 3L to 2..2))
        assertEquals(2, out[0].lanes)
        assertEquals(laneOf(out, 3L).lane, laneOf(out, 1L).lane)
    }

    @Test
    fun `同一门课的两条同槽位安排也分列不互相遮挡`() {
        // 中途换教室：同一门课在同一时段的两条独立安排（courseId 相同）必须各占一列
        val out = place(listOf(1L to 1..2, 1L to 1..2))
        assertEquals(2, out[0].lanes)
        assertEquals(setOf(0, 1), out.map { it.lane }.toSet())
    }

    @Test
    fun `空输入`() {
        assertEquals(emptyList<LaneLayout.Placement>(), LaneLayout.layout(emptyList()))
    }
}
