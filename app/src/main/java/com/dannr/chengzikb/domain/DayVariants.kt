package com.dannr.chengzikb.domain

import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.data.model.describe
import com.dannr.chengzikb.data.model.endMinuteIn
import com.dannr.chengzikb.data.model.startMinuteIn
import com.dannr.chengzikb.data.model.weekSet

/**
 * 某个星期几在学期内的一种「课表快照」：这段时间里每周该星期的课**完全一致**。
 * 用于「按其它星期上课」时判断要不要让用户选复制哪一周。
 */
data class DayVariant(
    /** 共享该快照的周次（升序、非空；这些周该星期都是有课的） */
    val weeks: WeekSet,
    /** 写进 [com.dannr.chengzikb.data.model.DayOverride.followWeek] 的代表周 = weeks 的最小周 */
    val representativeWeek: Int,
    /** 代表周该星期的安排，供选周 UI 预览 */
    val sample: List<Occurrence>,
) {
    /** 中文周区间，如「第1-8周」「第1-8、11周」 */
    val label: String get() = weeks.describe()
}

/**
 * 把一个星期几在本学期 `1..totalWeeks` 各周的课表按「是否一致」分组。
 *
 * 一致性按**课程 + 节次 + 教室 + 老师 + 钟点**（最严格）判定：键内先排序再比较，与顺序无关。
 * 该星期在某周完全没有课时，该周被忽略（不参与判定、也不作为可选项），
 * 以免「课在第8周就结束了」这种正常情况在学期末反复弹出选周。
 *
 * 纯逻辑、无 Android 依赖，可直接单测。
 */
object DayVariants {

    /** 一条安排在同一周里的“指纹” */
    private data class SlotKey(
        val courseId: Long,
        val start: Int,
        val end: Int,
        val location: String?,
        val teacher: String?,
        val startMinute: Int?,
        val endMinute: Int?,
    )

    private val SLOT_ORDER: Comparator<SlotKey> = compareBy(
        { it.start }, { it.end }, { it.courseId },
        { it.location ?: "" }, { it.teacher ?: "" },
        { it.startMinute ?: -1 }, { it.endMinute ?: -1 },
    )

    private class Candidate(val occ: Occurrence, val key: SlotKey, val weeks: Set<Int>)

    fun forWeekday(
        occurrences: List<Occurrence>,
        dayOfWeek: Int,
        totalWeeks: Int,
        periods: List<PeriodSetting> = emptyList(),
    ): List<DayVariant> {
        if (totalWeeks < 1) return emptyList()
        val cands = occurrences.filter { it.session.dayOfWeek == dayOfWeek }.map { occ ->
            Candidate(occ, keyOf(occ, periods), occ.session.weekSet().weeks)
        }
        if (cands.isEmpty()) return emptyList()

        // 同一“指纹集合”的周归为一组（可跨不连续周，如单双周）
        val groups = linkedMapOf<List<SlotKey>, MutableList<Int>>()
        for (w in 1..totalWeeks) {
            val active = cands.filter { w in it.weeks }
            if (active.isEmpty()) continue
            val sig = active.map { it.key }.sortedWith(SLOT_ORDER)
            groups.getOrPut(sig) { mutableListOf() }.add(w)
        }

        return groups.values
            .sortedBy { it.first() }
            .map { weeks ->
                val weekSet = WeekSet.fromIterable(weeks)
                val rep = weekSet.minWeek ?: weeks.first()
                DayVariant(weekSet, rep, cands.filter { rep in it.weeks }.map { it.occ })
            }
    }

    /** 1..7 → 该星期几的差异分组（没有课的星期几不出现）。 */
    fun allFor(
        occurrences: List<Occurrence>,
        totalWeeks: Int,
        periods: List<PeriodSetting> = emptyList(),
    ): Map<Int, List<DayVariant>> =
        (1..7).associateWith { forWeekday(occurrences, it, totalWeeks, periods) }
            .filterValues { it.isNotEmpty() }

    private fun keyOf(occ: Occurrence, periods: List<PeriodSetting>): SlotKey = SlotKey(
        courseId = occ.course.id,
        start = occ.session.startPeriodIdx,
        end = occ.session.endPeriodIdx,
        location = occ.session.location,
        teacher = occ.course.teacher,
        // 用“解析后”的钟点比较：显式导入钟点与按作息推算出的同一时刻不应被误判为不同
        startMinute = occ.session.startMinuteIn(periods),
        endMinute = occ.session.endMinuteIn(periods),
    )
}
