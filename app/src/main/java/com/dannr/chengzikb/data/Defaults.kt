package com.dannr.chengzikb.data

import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate

/**
 * 首启默认值（大学作息预设）。termStart 默认取“本周一”，用户可在设置里改。
 */
object Defaults {

    /** 大学常见作息：上午 4 节 / 下午 4 节 / 晚上 4 节 */
    fun defaultPeriods(): List<PeriodSetting> {
        fun p(order: Int, name: String, start: String, end: String) = PeriodSetting(
            order = order,
            name = name,
            startMinute = hhmm(start),
            endMinute = hhmm(end),
        )
        return listOf(
            p(0, "第1节", "08:00", "08:45"),
            p(1, "第2节", "08:55", "09:40"),
            p(2, "第3节", "10:00", "10:45"),
            p(3, "第4节", "10:55", "11:40"),
            p(4, "第5节", "14:00", "14:45"),
            p(5, "第6节", "14:55", "15:40"),
            p(6, "第7节", "16:00", "16:45"),
            p(7, "第8节", "16:55", "17:40"),
            p(8, "第9节", "19:00", "19:45"),
            p(9, "第10节", "19:55", "20:40"),
            p(10, "第11节", "20:50", "21:35"),
            p(11, "第12节", "21:45", "22:30"),
        )
    }

    fun defaultTermStartMonday(today: LocalDate): LocalDate = WeekMath.mondayOfWeek(today)

    /** 某课表 [timetableId] 的默认设置。 */
    fun defaultSettings(today: LocalDate, timetableId: Long = 0L): AppSettings = AppSettings(
        timetableId = timetableId,
        termStartEpochDay = defaultTermStartMonday(today).toEpochDay(),
        totalWeeks = 20,
        shownDays = 5,
        themeMode = 0,
        showLocation = true,
        showTeacher = true,
        showOtherWeeks = false,
    )

    private fun hhmm(hhmm: String): Int {
        val (h, m) = hhmm.split(":")
        return h.toInt() * 60 + m.toInt()
    }
}
