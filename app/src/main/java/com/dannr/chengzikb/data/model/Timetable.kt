package com.dannr.chengzikb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个课表（校历），如“大一上”“大一下”。
 * 每个课表拥有独立的课程、作息、学期设置；id 从小到大即创建顺序。
 */
@Entity(tableName = "timetables")
data class Timetable(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
)
