package com.dannr.chengzikb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 极简键值存储：目前只存“当前激活的课表 id”。 */
@Entity(tableName = "meta")
data class MetaEntry(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        const val KEY_ACTIVE_TIMETABLE = "active_timetable"
    }
}
