package com.dannr.chengzikb

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import com.dannr.chengzikb.di.AppContainer
import com.dannr.chengzikb.widget.WidgetDataHook
import com.dannr.chengzikb.widget.WidgetUpdateJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：持有手动 DI 容器，进程内唯一。
 *
 * 进程存活期间监听两类“状态变化”，及时整组刷新桌面小组件：
 *  - ACTION_TIME_TICK（每分钟整点）：上课/下课/“即将上课”等切点到分钟即刷新，不依赖精确闹钟授权；
 *  - ACTION_CONFIGURATION_CHANGED（仅当夜间标志真的翻转）：跟随系统时黑夜/白天切换立即生效。
 * 进程被系统回收后，仍有 AlarmManager 精确/兜底链与组件自身周期负责在关键边界唤醒重算。
 */
class OrangeApp : Application() {

    lateinit var container: AppContainer
        private set

    private var widgetDataHook: WidgetDataHook? = null
    private var refreshReceiver: BroadcastReceiver? = null
    private var lastNightMode: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 进程内中央钩子：课表数据一旦变化即自动刷新桌面小组件
        widgetDataHook = WidgetDataHook(this).also { it.start() }
        // 进程存活期间：到分钟/系统日夜切换时即时刷新组件
        registerRefreshTriggers()
    }

    private fun registerRefreshTriggers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> refreshWidget(context)
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        // 只把“夜间标志真的翻转”当作状态切换；旋转/分辨率等普通配置变化不打扰组件
                        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                            Configuration.UI_MODE_NIGHT_YES
                        if (lastNightMode != night) {
                            lastNightMode = night
                            refreshWidget(context)
                        }
                    }
                }
            }
        }
        refreshReceiver = receiver
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }
    }

    private fun refreshWidget(context: Context) {
        tickScope.launch {
            runCatching { WidgetUpdateJob(context).refreshAll() }
        }
    }

    companion object {
        private val tickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
