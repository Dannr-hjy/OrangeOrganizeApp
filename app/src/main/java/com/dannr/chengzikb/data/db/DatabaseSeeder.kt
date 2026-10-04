package com.dannr.chengzikb.data.db

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.Timetable
import java.time.LocalDate

/**
 * 首启播种（幂等）：无课表则建默认课表“我的课表”并配默认作息/学期设置；
 * 保证 meta 中“当前课表”始终指向某个存在课表。
 */
object DatabaseSeeder {

    suspend fun ensureSeeded(db: AppDatabase) {
        if (db.timetableDao().getAll().isEmpty()) {
            val id = db.timetableDao().insert(Timetable(name = "我的课表"))
            db.periodDao().insertAll(Defaults.defaultPeriods().map { it.copy(timetableId = id) })
            db.settingsDao().upsert(Defaults.defaultSettings(LocalDate.now(), id))
        }
        val activeText = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)
        val tables = db.timetableDao().getAll()
        val active = activeText?.toLongOrNull()
        if (tables.isNotEmpty() && (active == null || tables.none { it.id == active })) {
            db.metaDao().put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, tables.first().id.toString()))
        }
    }
}
