package com.dannr.chengzikb.data.repo

import androidx.room.withTransaction
import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.db.MetaDao
import com.dannr.chengzikb.data.db.TimetableDao
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.Timetable
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * 课表（校历）集合 + 当前激活课表。
 * 数据仓库（course/period/settings）都订阅 [activeId]，切表后全局响应。
 */
class TimetableManager(
    private val db: AppDatabase,
    private val timetableDao: TimetableDao,
    private val metaDao: MetaDao,
) {
    private val _activeId = MutableStateFlow(readActiveIdSync())
    val activeId: StateFlow<Long> = _activeId
    val tables: Flow<List<Timetable>> = timetableDao.observeAll()

    /** 当前激活课表 id（同步读内存态） */
    fun active(): Long = _activeId.value

    suspend fun activate(id: Long) {
        val exists = timetableDao.getAll().any { it.id == id }
        if (!exists || id == _activeId.value) return
        metaDao.put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, id.toString()))
        _activeId.value = id
    }

    /** 新建课表：名称不能与现有课表重复；继承当前课表的作息与学期设置（课程另排），随后自动切到新课表。
     * @return 新课表 id；名称重复返回 -1（不创建不切换）。 */
    suspend fun create(name: String): Long {
        val clean = name.trim().ifBlank { "未命名课表" }
        if (timetableDao.getAll().any { it.name.trim() == clean }) return -1L
        return db.withTransaction {
            val tid = timetableDao.insert(Timetable(name = clean))
            val src = _activeId.value
            if (src > 0) {
                val periods = db.periodDao().getAllByTimetable(src)
                if (periods.isNotEmpty()) db.periodDao().insertAll(periods.map { it.copy(id = 0L, timetableId = tid) })
                val s = db.settingsDao().getByTimetable(src)
                db.settingsDao().upsert((s ?: Defaults.defaultSettings(LocalDate.now())).copy(timetableId = tid))
            } else {
                db.periodDao().insertAll(Defaults.defaultPeriods().map { it.copy(timetableId = tid) })
                db.settingsDao().upsert(Defaults.defaultSettings(LocalDate.now(), tid))
            }
            metaDao.put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, tid.toString()))
            _activeId.value = tid
            tid
        }
    }

    /** @return 重命名是否成功；名称与他人重复或为空返回 false。 */
    suspend fun rename(id: Long, name: String): Boolean {
        val t = timetableDao.byId(id) ?: return false
        val clean = name.trim()
        if (clean.isBlank()) return false
        if (timetableDao.getAll().any { it.id != id && it.name.trim() == clean }) return false
        timetableDao.update(t.copy(name = clean))
        return true
    }

    /** 删除课表及其全部课程/作息/设置/调休；若删除的是当前表则切到剩余第一个。 */
    suspend fun delete(id: Long): Boolean = db.withTransaction {
        if (timetableDao.getAll().size <= 1) return@withTransaction false
        db.courseDao().deleteByTimetable(id) // 级联删除该课表安排
        db.periodDao().deleteByTimetable(id)
        db.settingsDao().deleteByTimetable(id)
        db.dayOverrideDao().deleteByTimetable(id)
        timetableDao.deleteById(id)
        if (_activeId.value == id) {
            val next = timetableDao.getAll().firstOrNull()?.id ?: 0L
            if (next > 0) metaDao.put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, next.toString()))
            _activeId.value = next
        }
        true
    }

    private fun readActiveIdSync(): Long = runBlocking {
        val fromMeta = metaDao.get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
        if (fromMeta != null && fromMeta > 0) return@runBlocking fromMeta
        timetableDao.getAll().firstOrNull()?.id ?: 0L
    }
}
