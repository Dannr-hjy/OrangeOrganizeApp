package com.dannr.chengzikb.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.repo.PrefsRepository
import com.dannr.chengzikb.widget.WidgetUpdateJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 系统事件后重排课前提醒闹钟 + 桌面小组件自刷新。
 *
 * 会"清空/错位"已排闹钟的事件有四类，缺一类就会出现一种静默失效：
 * - 重启（含 QUICKBOOT_POWERON）：AlarmManager 清空；
 * - **覆盖安装/应用更新**：同样清空，且不会重新走一遍 UI —— 这是"更新一次之后提醒就再也不响"的元凶；
 * - 系统时间被改动、时区被改动：RTC 闹钟存的是绝对时刻，改完全部错位；
 * - 用户事后才授予"精确闹钟"权限：之前排的都是近似闹钟，要重排成精确的。
 */
class BootRescheduler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED) return
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val prefs = PrefsRepository(db.metaDao())
            runBlocking(Dispatchers.IO) {
                val enabled = prefs.remindersEnabled.first()
                val minutes = prefs.remindMinutes.first()
                ClassNotifier(appContext, db).sync(enabled, minutes)
                WidgetUpdateJob(appContext).refreshAll()
            }
        } catch (e: Exception) {
            // 广播里抛异常会拖垮进程；重排失败下次事件/开应用时还有机会补上
            Log.w("OrangeReminder", "系统事件后重排失败（$action）", e)
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, // = android.intent.action.TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
