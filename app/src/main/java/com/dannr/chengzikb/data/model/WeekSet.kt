package com.dannr.chengzikb.data.model

/**
 * 不可变周次集合：显式保存“该课在哪些周开”，支持任意多段、不连续（如 7~9、10、15~18）。
 * 以规范化文本持久化，例如 "1-3,5,7-20"（相邻合并、升序）。
 */
class WeekSet private constructor(val weeks: Set<Int>) {

    init {
        require(weeks.all { it >= 1 }) { "周次必须为正整数" }
    }

    override fun equals(other: Any?): Boolean = other is WeekSet && weeks == other.weeks

    override fun hashCode(): Int = weeks.hashCode()

    override fun toString(): String = "WeekSet(${toText()})"

    operator fun contains(week: Int): Boolean = weeks.contains(week)

    val isEmpty: Boolean get() = weeks.isEmpty()
    val size: Int get() = weeks.size
    val minWeek: Int? get() = weeks.minOrNull()
    val maxWeek: Int? get() = weeks.maxOrNull()

    /** 升序周列表（含间隙） */
    val sortedWeeks: List<Int> get() = weeks.sorted()

    /** 规范化文本：相邻(差1)合并为区间，如 {1,2,3,5,7} → "1-3,5,7"；空集 → "" */
    fun toText(): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var segStart = sorted.first()
        var prev = sorted.first()
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (segStart == prev) "$segStart" else "$segStart-$prev"
                segStart = w
                prev = w
            }
        }
        parts += if (segStart == prev) "$segStart" else "$segStart-$prev"
        return parts.joinToString(",")
    }

    companion object {
        val EMPTY = WeekSet(emptySet())

        fun of(vararg weeks: Int): WeekSet = fromIterable(weeks.asList())

        fun fromIterable(weeks: Iterable<Int>): WeekSet = WeekSet(weeks.filter { it >= 1 }.toSet())

        /** 覆盖 a..b 的连续周 */
        fun fromRange(a: Int, b: Int): WeekSet {
            require(a >= 1 && b >= a) { "非法周次区间 $a..$b" }
            return fromIterable(a..b)
        }

        /** 1..maxWeek 全部周 */
        fun weekly(maxWeek: Int): WeekSet = fromRange(1, maxWeek)

        /** maxWeek 内的单数周 */
        fun oddWeeks(maxWeek: Int): WeekSet = fromIterable((1..maxWeek).filter { it % 2 == 1 })

        /** maxWeek 内的双数周 */
        fun evenWeeks(maxWeek: Int): WeekSet = fromIterable((1..maxWeek).filter { it % 2 == 0 })

        /** 解析 "1-3,5,7-20"（容忍空格、空串=空集）。非法输入抛 [IllegalArgumentException]。 */
        fun parse(text: String): WeekSet {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return EMPTY
            val result = sortedSetOf<Int>()
            for (rawToken in trimmed.split(",")) {
                val token = rawToken.trim()
                if (token.isEmpty()) continue
                val dash = token.indexOf('-')
                if (dash < 0) {
                    val w = token.toIntOrNull() ?: throw IllegalArgumentException("无法解析周次“$token”")
                    if (w < 1) throw IllegalArgumentException("周次必须为正整数")
                    result += w
                } else {
                    val a = token.substring(0, dash).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("无法解析周次“$token”")
                    val b = token.substring(dash + 1).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("无法解析周次“$token”")
                    if (a < 1 || b < a) throw IllegalArgumentException("非法周次区间“$token”")
                    for (w in a..b) result += w
                }
            }
            return WeekSet(result.toSet())
        }
    }
}

/** “第X周”中文可读描述：如 {7..10,15..18} → “第7-10、15-18周” */
fun WeekSet.describe(): String {
    if (isEmpty) return "（无）"
    return "第${toText().replace(",", "、")}周"
}
