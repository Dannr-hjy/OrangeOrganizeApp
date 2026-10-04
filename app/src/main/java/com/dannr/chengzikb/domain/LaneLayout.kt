package com.dannr.chengzikb.domain

/**
 * 某(星期, 周)内的课程横向冲突排布。
 * 输入为该格点所有有效的课程区间（按节次索引，0 基含端点），
 * 输出为每个课程应占据的 lane（并排列）与总 lane 数，按宽度 1/lanes、x=lane/lanes 渲染。
 *
 * 采用区间图贪心染色（按开始时间升序、跨度降序，依次放入第一个不冲突列）：
 * 区间图是完美图，该贪心可得到最少的列数（= 最大重叠深度）。
 */
object LaneLayout {

    /** 待排课程区间 */
    data class Interval(val courseId: Long, val start: Int, val end: Int)

    /** 排布结果 */
    data class Placement(val courseId: Long, val lane: Int, val lanes: Int)

    private fun overlaps(a: Interval, b: Interval): Boolean =
        a.start <= b.end && b.start <= a.end

    fun layout(intervals: List<Interval>): List<Placement> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedWith(
            compareBy<Interval> { it.start }.thenByDescending { it.end - it.start }
        )
        // columns[i] 保存该列已放置课程（任意时刻互不重叠）
        val columns = mutableListOf<MutableList<Interval>>()
        val laneByCourse = mutableMapOf<Long, Int>()
        for (iv in sorted) {
            val idx = columns.indexOfFirst { col -> col.none { overlaps(it, iv) } }
            if (idx < 0) {
                columns.add(mutableListOf(iv))
                laneByCourse[iv.courseId] = columns.lastIndex
            } else {
                columns[idx].add(iv)
                laneByCourse[iv.courseId] = idx
            }
        }
        val lanes = columns.size
        return sorted.map { Placement(it.courseId, laneByCourse.getValue(it.courseId), lanes) }
    }
}
