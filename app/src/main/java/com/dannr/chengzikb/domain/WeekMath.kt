package com.dannr.chengzikb.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 周相关的纯数学：全部以“学期第一周的周一”为基准（不含时区/系统时间，便于单测）。
 */
object WeekMath {

    /** 返回 date 所在 ISO 周的周一（含 date 本身若是周一） */
    fun mondayOfWeek(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * date 对应的学期周号（1 基）。termStartMonday 为第 1 周周一。
     * 若早于学期开始则返回 <=0 或 0；调用方按需 clamp 到 [1..totalWeeks]。
     */
    fun weekIndexOf(date: LocalDate, termStartMonday: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(termStartMonday, mondayOfWeek(date))
        return (days / 7).toInt() + 1
    }

    /** 第 weekIdx 周(1 基) 覆盖的 [周一, 周日] */
    fun dateRangeForWeek(termStartMonday: LocalDate, weekIdx: Int): Pair<LocalDate, LocalDate> {
        val start = termStartMonday.plusWeeks((weekIdx - 1).toLong())
        return start to start.plusDays(6)
    }

    /** 今天是否在学期范围（第 1..totalWeeks 周）内 */
    fun inTerm(date: LocalDate, termStartMonday: LocalDate, totalWeeks: Int): Boolean {
        val w = weekIndexOf(date, termStartMonday)
        return w in 1..totalWeeks
    }

    /** 中文整周名：1=周一 … 7=周日 */
    fun weekdayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"
        6 -> "周六"; 7 -> "周日"; else -> ""
    }

    /** 中文单字：1=一 … 7=日 */
    fun weekdayChar(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
        6 -> "六"; 7 -> "日"; else -> ""
    }

    /** 星期数字号（周一=1）：LocalDate -> 1..7 */
    fun dayIndexOf(date: LocalDate): Int =
        when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> 1; DayOfWeek.TUESDAY -> 2; DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4; DayOfWeek.FRIDAY -> 5; DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 7
        }

    /** "9月3日" 短日期 */
    fun shortDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /** 周区间显示文本，如 周一9月1日 的简化展示由 UI 自行拼接 */
    fun dateWithWeekday(date: LocalDate): String = "${weekdayName(dayIndexOf(date))} ${shortDate(date)}"
}
