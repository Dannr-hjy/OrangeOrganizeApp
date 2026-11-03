package com.dannr.chengzikb.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.widget.WidgetUpdateJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                ClassNotifier(appContext, db).sync()
                if (action != ClassNotifier.ACTION_RENEW) WidgetUpdateJob(appContext).refreshAll()
            } catch (e: Exception) {
                Log.w("OrangeReminder", "系统事件后重排失败（$action）", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val HANDLED = setOf(
            ClassNotifier.ACTION_RENEW,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, // = android.intent.action.TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
