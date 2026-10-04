package com.dannr.chengzikb.data.repo

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.db.SettingsDao
import com.dannr.chengzikb.data.model.AppSettings
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val db: AppDatabase,
    private val dao: SettingsDao,
    private val tables: TimetableManager,
) {
    /** 当前课表设置流。若该表尚无行则退化为默认（播种后一般已有真实行）。 */
    val settings: Flow<AppSettings> = tables.activeId.flatMapLatest { tid ->
        dao.observe(tid).map { it ?: Defaults.defaultSettings(LocalDate.now(), tid) }
    }

    suspend fun save(settings: AppSettings) {
        dao.upsert(settings)
    }

    suspend fun currentOrInit(): AppSettings {
        val tid = tables.active()
        return dao.getByTimetable(tid) ?: Defaults.defaultSettings(LocalDate.now(), tid).also { dao.upsert(it) }
    }

    suspend fun settingsFor(tid: Long): AppSettings? = dao.getByTimetable(tid)

    suspend fun deleteAllRows() = dao.deleteAll()
}
