package com.dannr.chengzikb.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个作息时间段（“第X节”）。
 * [order] 是从 0 起的稳定序号，是课程节次索引的业务锚点（改名字/时间不动 order 就不会影响课程）。
 * [startMinute]/[endMinute]：当日 00:00 起分钟数（墙钟显示用）。
 * 作息属于某个 [Timetable]，不同课表可各自定制。
 */
@Entity(
    tableName = "periods",
    indices = [Index("timetable_id")],
)
data class PeriodSetting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "pos") val order: Int, // 0 基，单调，唯一（同课表内）
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    @ColumnInfo(name = "timetable_id") val timetableId: Long = 0L,
)
