package com.dannr.chengzikb.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自刷新闹钟到点接收器：重算并整组更新小组件，随后 [WidgetUpdateJob] 会排下一次闹钟。
 */
class WidgetClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WidgetUpdateJob.ACTION_TICK) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                WidgetUpdateJob(context).refreshAll()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
