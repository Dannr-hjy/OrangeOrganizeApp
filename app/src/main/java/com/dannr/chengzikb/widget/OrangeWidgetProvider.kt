package com.dannr.chengzikb.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面课表小组件的 Provider：桌面添加/尺寸变化/系统触发更新时整组重绘。
 * 真正的“分钟级/跨天自动刷新”由 [WidgetClockReceiver] 的自链式闹钟驱动。
 */
class OrangeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAsync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 桌面尺寸变化（现已固定 2×2，仍会触发）→ 整组按最新尺寸重算
        refreshAsync(context)
    }

    override fun onEnabled(context: Context) {
        refreshAsync(context)
    }

    override fun onDisabled(context: Context) {
        // 最后一个组件被移除：停掉自刷新闹钟省电
        WidgetUpdateJob(context.applicationContext).cancelSchedule()
    }

    private fun refreshAsync(context: Context) {
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
