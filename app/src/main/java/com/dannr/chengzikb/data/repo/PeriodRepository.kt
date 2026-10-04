package com.dannr.chengzikb.data.repo

import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.db.PeriodDao
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.PeriodSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

class PeriodRepository(
    private val db: AppDatabase,
    private val dao: PeriodDao,
    private val tables: TimetableManager,
) {
    /** 当前课表的作息表 */
    val all: Flow<List<PeriodSetting>> = tables.activeId.flatMapLatest { dao.observeByTimetable(it) }

    suspend fun getAllOnce(): List<PeriodSetting> = dao.getAllByTimetable(tables.active())

    /** 末尾追加一节（order = 当前 max+1），不动已有节次 */
    suspend fun append(name: String, startMinute: Int, endMinute: Int): PeriodSetting {
        val tid = tables.active()
        val order = dao.maxOrderByTimetable(tid) + 1
        val period = PeriodSetting(
            order = order,
            name = name,
            startMinute = startMinute,
            endMinute = endMinute,
            timetableId = tid,
        )
        dao.upsert(period)
        return period
    }

    /** 修改某节的名称/时间（不动 order → 不影响课程布局） */
    suspend fun update(period: PeriodSetting) {
        dao.upsert(period)
    }

    /**
     * 安全删除：仅当没有任何【安排】的节次区间触及该节及其以上位置时才允许；
     * 删除后把更高 pos 的节次下移一档（事务内）。
     * @return 是否删除成功（false 表示被安排占用，应提示用户）。
     */
    suspend fun deleteSafely(target: PeriodSetting, sessions: List<CourseSession>): Boolean =
        db.withTransaction {
            val tid = tables.active()
            val referenced = sessions.any {
                it.startPeriodIdx >= target.order || it.endPeriodIdx >= target.order
            }
            if (referenced) return@withTransaction false
            dao.delete(target)
            dao.getAllByTimetable(tid).filter { it.order > target.order }
                .forEach { dao.upsert(it.copy(order = it.order - 1)) }
            true
        }
}
