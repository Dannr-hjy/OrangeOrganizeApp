package com.dannr.chengzikb.widget

import android.content.Context
import androidx.room.InvalidationTracker
import com.dannr.chengzikb.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 进程级中央钩子：监听六张业务表的任何写入（增删改课/作息/学期/切课表/导入/备份恢复），
 * 节流后整组刷新桌面小组件，保证桌面永远与软件内数据一致。
 */
class WidgetDataHook(context: Context) {

    private val app = context.applicationContext
    private val db = AppDatabase.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var pending: Job? = null

    private val observer = object : InvalidationTracker.Observer(
        "courses", "course_sessions", "periods", "settings", "timetables", "meta", "day_overrides",
    ) {
        override fun onInvalidated(tables: Set<String>) {
            schedule()
        }
    }

    fun start() {
        db.invalidationTracker.addObserver(observer)
    }

    private fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MS)
                runCatching { WidgetUpdateJob(app).refreshAll() }
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
