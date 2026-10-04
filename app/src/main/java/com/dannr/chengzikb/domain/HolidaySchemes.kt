package com.dannr.chengzikb.domain

import java.time.LocalDate

/**
 * 一次节假日调休方案（某一年）。
 * [holidays] 为放假日区间（当天不上课），[makeupDays] 为补课/补班日及其「按周几上课」。
 */
data class HolidayScheme(
    val year: Int,
    val holidays: List<HolidayRange>,
    val makeupDays: List<MakeupDay>,
) {
    /** date 是否落在某个放假区间内 */
    fun isHoliday(date: LocalDate): Boolean =
        holidays.any { !date.isBefore(it.start) && !date.isAfter(it.endInclusive) }

    /** date 若是补课日，返回当天该按周几(1..7)的课表上课；否则 null */
    fun makeupFor(date: LocalDate): Int? =
        makeupDays.firstOrNull { it.date == date }?.followDayOfWeek

    fun holidayOn(date: LocalDate): HolidayRange? =
        holidays.firstOrNull { !date.isBefore(it.start) && !date.isAfter(it.endInclusive) }
}

/** 一个放假区间（含首尾） */
data class HolidayRange(val name: String, val start: LocalDate, val endInclusive: LocalDate) {
    val days: Int get() = (endInclusive.toEpochDay() - start.toEpochDay()).toInt() + 1
}

/**
 * 一个补课/补班日。[followDayOfWeek] 表示当天按周几(1..7)的课表上课。
 */
data class MakeupDay(val date: LocalDate, val followDayOfWeek: Int)

/**
 * 内置的节假日调休方案表。
 *
 * 数据来源：国务院办公厅《关于 2026 年部分节假日安排的通知》（国办发明电〔2025〕7 号，2025-11-04）。
 * 官方只规定「哪天放假、哪天上班」，并未规定补课日上星期几的课——**各校口径并不统一**
 * （例：2026-05-09，多数高校按周二课表，少数按周一；2026-09-20 / 10-10，多数按周二/周三，
 * 部分院校一律按周五）。这里采用**多数派口径**作为默认值，用户可在
 * 「设置 → 学期与作息 → 节假日调休」或直接在课表点日期头部逐天覆盖。
 *
 * 新增年份只需往 [ALL] 里加一条；[DayPlanIndex] 按日期年份自动选表，跨年学期无需特殊处理。
 */
object HolidaySchemes {

    /** 2026 年放假安排（国务院办公厅 国办发明电〔2025〕7 号） */
    val Y2026: HolidayScheme = HolidayScheme(
        year = 2026,
        holidays = listOf(
            HolidayRange("元旦", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)),
            HolidayRange("春节", LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23)),
            HolidayRange("清明节", LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6)),
            HolidayRange("劳动节", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5)),
            HolidayRange("端午节", LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21)),
            HolidayRange("中秋节", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27)),
            HolidayRange("国庆节", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)),
        ),
        makeupDays = listOf(
            MakeupDay(LocalDate.of(2026, 1, 4), followDayOfWeek = 5),   // 周日补 1/2(周五)
            MakeupDay(LocalDate.of(2026, 2, 14), followDayOfWeek = 5),  // 周六补 2/20(周五)
            MakeupDay(LocalDate.of(2026, 2, 28), followDayOfWeek = 1),  // 周六补 2/23(周一)
            MakeupDay(LocalDate.of(2026, 5, 9), followDayOfWeek = 2),   // 周六补 5/5(周二)
            MakeupDay(LocalDate.of(2026, 9, 20), followDayOfWeek = 2),  // 周日补 10/6(周二)
            MakeupDay(LocalDate.of(2026, 10, 10), followDayOfWeek = 3), // 周六补 10/7(周三)
        ),
    )

    val ALL: List<HolidayScheme> = listOf(Y2026)

    /** 按年份取方案；没有内置方案的年份返回 null（该年不做自动调休） */
    fun forYear(year: Int): HolidayScheme? = ALL.firstOrNull { it.year == year }
}
