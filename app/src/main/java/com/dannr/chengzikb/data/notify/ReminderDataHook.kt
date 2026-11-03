package com.dannr.chengzikb.data.notify

import android.util.Log
import androidx.room.InvalidationTracker
import com.dannr.chengzikb.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 排程属于进程而非 Compose 页面，离开编辑/导入页面或切到后台也不会取消同步。 */
class ReminderDataHook(private val db: AppDatabase, private val notifier: ClassNotifier) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private val lock = Any()
    private val observer = object : InvalidationTracker.Observer(
        "courses", "course_sessions", "periods", "settings", "timetables", "meta", "day_overrides",
    ) {
        override fun onInvalidated(tables: Set<String>) = schedule()
    }

    fun start() {
        db.invalidationTracker.addObserver(observer)
        schedule()
    }

    private fun schedule() = synchronized(lock) {
        pending?.cancel()
        pending = scope.launch {
            delay(300)
            try {
                notifier.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("OrangeReminder", "课表变化后重排失败", e)
            }
        }
    }
}
