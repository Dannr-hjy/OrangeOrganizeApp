package com.dannr.chengzikb.data.repo

import com.dannr.chengzikb.data.db.DayOverrideDao
import com.dannr.chengzikb.data.model.DayOverride
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 某一天的手动调休覆盖（休息 / 按周几上课）。数据只对**当前课表**生效，
 * 与 [SettingsRepository] 一样订阅 [TimetableManager.activeId]，切表后自动跟随。
 */
class DayOverrideRepository(
    private val dao: DayOverrideDao,
    private val tables: TimetableManager,
) {

    val overrides: Flow<List<DayOverride>> = tables.activeId.flatMapLatest { tid ->
        dao.observeByTimetable(tid)
    }

    /**
     * 写入或清除某一天的手动设置。
     * @param row null = 清除该日设置（恢复为「跟随自动方案/当天真实星期」）
     */
    suspend fun set(date: LocalDate, row: DayOverride?) {
        val tid = tables.active()
        if (tid <= 0) return
        if (row == null) {
            dao.deleteDate(tid, date.toEpochDay())
        } else {
            dao.upsert(
                DayOverride(
                    timetableId = tid,
                    dateEpochDay = date.toEpochDay(),
                    kind = row.kind,
                    followDayOfWeek = row.followDayOfWeek.coerceIn(1, 7),
                    // 只在「按其它星期上课」时有意义；上界留给渲染端按当前 totalWeeks 兜底
                    followWeek = row.followWeek?.takeIf { it >= 1 && row.kind == DayOverride.KIND_FOLLOW },
                ),
            )
        }
    }

    suspend fun deleteAllRows() = dao.deleteAll()
}
