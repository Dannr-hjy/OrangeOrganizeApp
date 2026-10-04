package com.dannr.chengzikb.domain

/**
 * “现在/下一节”引擎：纯函数，不含系统时钟（由调用方传入 nowMinute），便于单测。
 */
object NowEngine {

    /** 某节次（0 基索引 + 起止分钟，分钟为当日 00:00 起） */
    data class Period(val index: Int, val startMinute: Int, val endMinute: Int)

    /** 今日正在/接下来的一节课（已按 day+activeWeek 过滤好） */
    data class CourseWindow(
        val courseId: Long,
        val name: String,
        val location: String?,
        val startPeriod: Int,
        val endPeriod: Int,
    )

    /** 当前正在上的课 */
    fun currentIndexOf(periods: List<Period>, nowMinute: Int): Int? =
        periods.firstOrNull { it.startMinute <= nowMinute && nowMinute < it.endMinute }?.index

    /** 今日下一节课（startPeriod 严格晚于 nowIndex；期间休息时也会指向下一节） */
    fun nextWindow(courses: List<CourseWindow>, nowIndex: Int?): CourseWindow? {
        val threshold = (nowIndex ?: -1)
        return courses
            .filter { it.startPeriod > threshold }
            .minByOrNull { it.startPeriod }
    }

    /** 正在上的课（若 nowIndex 落在某课节次区间内） */
    fun currentWindow(courses: List<CourseWindow>, nowIndex: Int?): CourseWindow? {
        if (nowIndex == null) return null
        return courses.firstOrNull { it.startPeriod <= nowIndex && nowIndex <= it.endPeriod }
    }

    /** 距某课开始还差多少分钟（nowMinute 需为当日分钟）；已开始则 0 */
    fun minutesUntil(startMinute: Int, nowMinute: Int): Int = (startMinute - nowMinute).coerceAtLeast(0)

    fun formatClock(minute: Int): String {
        val m = minute.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
