package com.dannr.chengzikb.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课程模板：一门“课”的名称/老师/颜色只存一次。
 * 具体的一周安排在 [CourseSession] 里（同一门课可挂多个不同时段安排）；
 * 上课地点是**逐安排**的（同一门课不同时段可去不同教室），见 [CourseSession.location]。
 * 归属某个 [Timetable]（课表）。
 *
 * 说明：v1.1 起地点已下放到 [CourseSession.location]，此处的 `location` 是**遗留列**
 * （旧版数据迁移后在数据库中保留、不再读写）。新代码一律读 session.location，
 * 新写入统一置 null。保留该列是为了避免 v6→v7 迁移时重建 courses 表及其外键。
 */
@Entity(
    tableName = "courses",
    indices = [Index("timetable_id")],
)
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** 课程简称（可选）：课表格子/桌面小组件等空间紧张处按设置替代全称展示。 */
    val shortName: String? = null,
    val teacher: String? = null,
    @Deprecated("v1.1 起地点属于上课安排；此遗留字段恒为 null，请用 CourseSession.location")
    val location: String? = null,
    val colorIndex: Int = 0,
    val colorHex: String? = null, // 自定义颜色（#RRGGBB/#AARRGGBB），为空时用调色板 colorIndex
    @ColumnInfo(name = "timetable_id") val timetableId: Long = 0L,
) {
    /**
     * 展示用课名：[useShortName] 为 true 且本课填了简称时用简称，否则一律回退全称。
     * 课表格子、桌面小组件等“可选显示简称”的地方统一经此取名字。
     */
    fun displayName(useShortName: Boolean): String {
        val short = shortName
        return if (useShortName && !short.isNullOrBlank()) short else name
    }
}
