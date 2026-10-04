package com.dannr.chengzikb.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * 某一天的手动调休覆盖（每个课表各自一份，主键为 课表id + 日期）。
 * 只存**用户手动改过**的日期；内置的节假日方案在 [com.dannr.chengzikb.domain.HolidaySchemes] 里，
 * 不落库——这样开/关「自动套用节假日调休」不产生任何数据迁移，且「手动 vs 自动」来源天然分离，
 * 手动永远优先（见 [com.dannr.chengzikb.domain.DayPlanIndex]）。
 *
 * [dateEpochDay]：java.time.LocalDate.toEpochDay()。
 * [kind]：0 = 休息（当天无课）；1 = 按 [followDayOfWeek] 的课表上课。
 */
@Entity(
    tableName = "day_overrides",
    primaryKeys = ["timetable_id", "date_epoch_day"],
    indices = [Index("timetable_id")],
)
data class DayOverride(
    @ColumnInfo(name = "timetable_id") val timetableId: Long,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
    @ColumnInfo(name = "kind") val kind: Int,
    /** kind=1 时有效：按周几(1..7)的课表上课 */
    @ColumnInfo(name = "follow_day_of_week", defaultValue = "1") val followDayOfWeek: Int = 1,
) {
    companion object {
        const val KIND_REST = 0
        const val KIND_FOLLOW = 1
    }
}
