package com.dannr.chengzikb.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一门课程在“一周内”的一条具体安排：周几 + 节次区间 + 哪些周开 + 该时段的教室。
 * 同一 [Course] 可对应多条不同时段的安排（这正是“定义一次、多处排”的实现）；
 * 上课地点挂在安排上，故同一门课不同时段可去不同地点。
 * 删除课程时级联删除其全部安排。
 */
@Entity(
    tableName = "course_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("course_id"), Index("dayOfWeek"), Index("startPeriodIdx")],
)
data class CourseSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "course_id") val courseId: Long,
    val dayOfWeek: Int, // 1..7
    val startPeriodIdx: Int, // 0 基
    val endPeriodIdx: Int, // 0 基，含端点（>= startPeriodIdx）
    @ColumnInfo(name = "active_weeks_text") val activeWeeksText: String = "",
    val location: String? = null, // 该时段的上课地点（可为空）
)

/** 把持久化周次文本解析为周集（异常按空集，编辑流程保证规范） */
fun CourseSession.weekSet(): WeekSet = runCatching { WeekSet.parse(activeWeeksText) }.getOrDefault(WeekSet.EMPTY)

/** 该安排是否在学期第 weekIndex 周开 */
fun CourseSession.isActiveIn(weekIndex: Int): Boolean = weekSet().contains(weekIndex)

/** 该安排占用的节次区间（0 基含端点） */
fun CourseSession.periodSpan(): IntRange = startPeriodIdx..endPeriodIdx

/**
 * 课程 + 其一条安排 的联合视图（渲染用）。
 */
data class Occurrence(
    val course: Course,
    val session: CourseSession,
)
