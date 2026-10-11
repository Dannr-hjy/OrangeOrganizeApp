package com.dannr.chengzikb.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 某个课表 [Timetable] 的设置（每表一行，主键即课表 id）。
 * [termStartEpochDay]：学期第一周周一的 epochDay（java.time.LocalDate.toEpochDay）。
 * [themeMode]：0=跟随系统 1=浅色 2=深色（暂固定浅色）。
 * [showShortNameInGrid]/[showShortNameInWidget]：是否在课表格子/桌面小组件里用课程简称
 * （未设置简称的课仍显示全称，见 [Course.displayName]）。
 * [autoHoliday]：是否自动套用内置的节假日调休方案（放假当天无课、补课日按对应星期上课）。
 * 手动改过的日期（[DayOverride]）优先级高于它，见 [com.dannr.chengzikb.domain.DayPlanIndex]。
 */
@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey @ColumnInfo(name = "timetable_id") val timetableId: Long = 0L,
    val termStartEpochDay: Long,
    val totalWeeks: Int = 20,
    val shownDays: Int = 5, // 5/6/7
    val themeMode: Int = 0,
    val showLocation: Boolean = true,
    val showTeacher: Boolean = true,
    val showOtherWeeks: Boolean = false,
    val themeSeedHex: String? = null, // 主题主色（#RRGGBB）；null = 默认橙色
    // 低饱和：整块色板按比例压饱和度（与所选的种子色叠加生效）
    @ColumnInfo(defaultValue = "0") val lowSaturation: Boolean = false,
    // 炫彩（隐藏主题）：色相持续轮转。是否"已解锁"由全局切换计数决定，见 PrefsRepository
    @ColumnInfo(defaultValue = "0") val rainbowTheme: Boolean = false,
    @ColumnInfo(defaultValue = "0") val showShortNameInGrid: Boolean = false,
    @ColumnInfo(defaultValue = "0") val showShortNameInWidget: Boolean = false,
    @ColumnInfo(defaultValue = "0") val autoHoliday: Boolean = false,
)
