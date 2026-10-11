package com.dannr.chengzikb.domain

/**
 * 某(星期, 周)内的课程横向冲突排布。
 * 输入为该格点所有有效的课程区间（按节次索引，0 基含端点），
 * 输出为每个区间应占据的 lane（并排列）与总 lane 数，按宽度 1/lanes、x=lane/lanes 渲染。
 *
 * 采用区间图贪心染色（按开始时间升序、跨度降序，依次放入第一个不冲突列）：
 * 区间图是完美图，该贪心可得到最少的列数（= 最大重叠深度）。
 *
 * 输出与输入**一一对应、顺序相同**（第 i 项即输入第 i 个区间的排布）。同一门课在同一
 * 时段的不同周可能是两条独立安排（如中途换教室），它们会在同一周内同时出现——因此
 * lane 只能按区间（而非课程）分配，否则同一门课的两条安排会挤进同一列而相互遮挡。
 */
object LaneLayout {

    /** 待排课程区间 */
    data class Interval(val courseId: Long, val start: Int, val end: Int)

    /** 单个区间的排布结果；[courseId] 仅作标识，排布本身按区间独立计算。 */
    data class Placement(val courseId: Long, val lane: Int, val lanes: Int)

    private fun overlaps(a: Interval, b: Interval): Boolean =
        a.start <= b.end && b.start <= a.end

    fun layout(intervals: List<Interval>): List<Placement> {
        if (intervals.isEmpty()) return emptyList()
        val order = intervals.indices.sortedWith(
            compareBy<Int> { intervals[it].start }.thenByDescending { intervals[it].end - intervals[it].start }
        )
        // columns[i] 保存该列已放置区间（任意时刻互不重叠）
        val columns = mutableListOf<MutableList<Interval>>()
        val laneByIndex = IntArray(intervals.size)
        for (i in order) {
            val iv = intervals[i]
            val idx = columns.indexOfFirst { col -> col.none { overlaps(it, iv) } }
            val lane = if (idx < 0) {
                columns.add(mutableListOf(iv))
                columns.lastIndex
            } else {
                columns[idx].add(iv)
                idx
            }
            laneByIndex[i] = lane
        }
        val lanes = columns.size
        return intervals.indices.map { Placement(intervals[it].courseId, laneByIndex[it], lanes) }
    }
}
