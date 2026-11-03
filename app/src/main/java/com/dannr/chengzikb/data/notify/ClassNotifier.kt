package com.dannr.chengzikb.data.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.ReminderPlanner
import com.dannr.chengzikb.data.repo.PrefsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 最近一次排程的结果，展示在设置页。
 *
 * 课前提醒的失效大多是"系统层面静默发生"的——精确闹钟权限被回收、
 * 应用被强停/覆盖安装、系统时间或时区被改动。把它显式暴露出来，
 * 用户才能看出问题卡在哪一步，而不是只知道"它又不响了"。
 */
data class ReminderStatus(
    val count: Int = 0,
    val nextText: String? = null,
    val exactAllowed: Boolean = true,
    val syncedAtMillis: Long = 0L,
    val notificationWarning: String? = null,
)

/**
 * 当前课表的系统闹钟。每日自动续排；数据变化、系统事件与返回前台时重新同步。
 * 所有实例共用排程锁，读库使用同一事务，通知接收器不需要打开数据库。
 */
class ClassNotifier(private val context: Context, private val db: AppDatabase) {

    companion object {
        const val CHANNEL_ID = "class_reminder"
        private const val TAG = "OrangeReminder"
        private const val PREFS = "orange_reminders"
        private const val KEY_CODES = "scheduled_codes"
        private const val KEY_COUNT = "status_count"
        private const val KEY_NEXT = "status_next"
        private const val KEY_EXACT = "status_exact"
        private const val KEY_SYNCED_AT = "status_synced_at"
        private const val SCHEDULE_DAYS = 7L
        private val syncLock = Mutex()
        internal val deliveryLock = Any()
        private const val TEST_CODE = 0x7E57

        const val ACTION_REMIND = "com.dannr.chengzikb.action.CLASS_REMIND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_CODE = "code"
        const val EXTRA_TEST = "test"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_FIRE_MILLIS = "fire_millis"
        const val ACTION_RENEW = "com.dannr.chengzikb.action.RENEW_REMINDERS"

        // 进程内共用：设置页订阅它，任何一处 sync（含开机/更新后的重排）都会即时反映到 UI
        private val _status = MutableStateFlow(ReminderStatus())

        /** 最近一次排程结果。 */
        val status: StateFlow<ReminderStatus> = _status.asStateFlow()
    }

    private val alarm: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    init {
        // 进程刚起时用上次落盘的结果打底，避免设置页先显示"0 条"再跳变
        if (_status.value.syncedAtMillis == 0L) _status.value = readStatus()
    }

    /** 只读取持久化偏好，UI 的加载占位值不能清除已有闹钟。 */
    suspend fun sync() = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val alarmMgr = alarm
            var enabled = false
            val plan = try {
                db.withTransaction {
                    enabled = db.metaDao().get(PrefsRepository.KEY_REMIND_ENABLED) == "1"
                    val lead = (db.metaDao().get(PrefsRepository.KEY_REMIND_MINUTES)?.toIntOrNull() ?: 10)
                        .coerceIn(1, 90)
                    if (enabled && alarmMgr != null) buildPlan(lead) else emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "重排课前提醒失败，保留原有闹钟", e)
                return@withContext
            }
            withContext(NonCancellable) {
                synchronized(deliveryLock) {
                    prefs().edit().putBoolean("enabled", enabled).commit()
                    applyPlan(alarmMgr, plan)
                }
                scheduleRenewal(alarmMgr, enabled)
            }
        }
    }

    private fun applyPlan(alarmMgr: AlarmManager?, plan: List<Reminder>) {
        val canExact = alarmMgr != null && canScheduleExact(alarmMgr)
        var exact = canExact
        val old = loadCodes()
        val scheduled = mutableSetOf<Int>()
        // 调用方持有 deliveryLock，投递与重排不会交错后重复弹出同一节课。
        for (r in plan) {
            if (wasDelivered(context, r.code, r.startMillis)) continue
            if (alarmMgr != null) {
                val result = setAlarm(alarmMgr, r, canExact)
                if (result != AlarmResult.EXACT) exact = false
                if (result != AlarmResult.FAILED || r.code in old) scheduled += r.code
            }
        }
        // 失败的替换保留原有闹钟；只有删除、关闭或已送达的提醒才取消。
        (old - scheduled).forEach { alarmMgr?.cancel(pending(it)) }
        storeCodes(scheduled)

        val next = plan.filter { it.code in scheduled }.minByOrNull { it.fireAt }
        publish(
            ReminderStatus(
                count = scheduled.size,
                nextText = next?.let { "${dayLabel(it.fireAt.toLocalDate())} ${clock(it.fireAt.hour * 60 + it.fireAt.minute)} 提醒 · ${clock(it.startMinute)} ${it.courseName}上课" },
                exactAllowed = exact,
                syncedAtMillis = System.currentTimeMillis(),
                notificationWarning = ReminderNotifications.warning(context),
            ),
        )
        Log.i(TAG, "已排入 ${scheduled.size} 条课前提醒（精确=$exact），最近一条：${next?.fireAt}")
    }

    /** 一条待排的提醒：触发时刻 + 已固化在 Intent 里的通知内容（到点无需再查库）。 */
    private class Reminder(
        val code: Int,
        val fireAt: LocalDateTime,
        val startMinute: Int,
        val courseName: String,
        val intent: Intent,
        val startMillis: Long,
        val triggerMillis: Long,
    )

    /** 算出未来 [SCHEDULE_DAYS] 天内全部需要提醒的时刻（不接触系统闹钟，纯计算）。 */
    private suspend fun buildPlan(leadMinutes: Int): List<Reminder> {
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
        if (active <= 0) return emptyList()
        val settings = db.settingsDao().getByTimetable(active) ?: return emptyList()
        val periods = db.periodDao().getAllByTimetable(active)
        val sessions = db.courseSessionDao().getAllByTimetable(active)
        val courses = db.courseDao().getAllByTimetable(active)
        val overrides = db.dayOverrideDao().getAllByTimetable(active)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        pruneDelivered(context, now.toEpochMilli())
        return ReminderPlanner.build(
            settings, periods, courses, sessions, overrides, leadMinutes, now, zone, SCHEDULE_DAYS,
        ).mapNotNull { r ->
            val code = ("t$active-c${r.courseId}-s${r.sessionId}-d${r.date}").hashCode()
            if (wasDelivered(context, code, r.startMillis)) return@mapNotNull null
            val intent = baseIntent(code)
                .putExtra(EXTRA_TITLE, r.courseName)
                .putExtra(EXTRA_TIME, clock(r.startMinute))
                .putExtra(EXTRA_LOCATION, r.location)
                .putExtra(EXTRA_START_MILLIS, r.startMillis)
                .putExtra(EXTRA_FIRE_MILLIS, r.fireMillis)
            Reminder(
                code, LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(r.fireMillis, now.toEpochMilli())), zone),
                r.startMinute, r.courseName, intent, r.startMillis, maxOf(r.fireMillis, now.toEpochMilli()),
            )
        }
    }

    /** 续排独立于 UI 与小组件；即使没有课、长期不开 App，也会延长排程窗口。 */
    private fun scheduleRenewal(alarmMgr: AlarmManager?, enabled: Boolean) {
        if (alarmMgr == null) return
        val operation = PendingIntent.getBroadcast(
            context, 0, Intent(context, BootRescheduler::class.java).setAction(ACTION_RENEW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!enabled) {
            alarmMgr.cancel(operation)
            return
        }
        val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 续排已有七天余量，不占用 allowWhileIdle 的配额，以免延迟真正的课前提醒。
        runCatching { alarmMgr.set(AlarmManager.RTC_WAKEUP, next, operation) }
            .onFailure { Log.w(TAG, "提醒续排闹钟设置失败", it) }
    }

    private enum class AlarmResult { EXACT, INEXACT, FAILED }

    /** 返回实际排程模式，避免精确闹钟调用失败后界面仍显示“准点”。 */
    private fun setAlarm(alarmMgr: AlarmManager, r: Reminder, canExact: Boolean): AlarmResult {
        val pi = pendingFor(r.code, r.intent)
        return try {
            if (canExact) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
                AlarmResult.EXACT
            } else {
                // 没有“精确闹钟”权限：退化为允许的近似闹钟（可能略有延迟，但可后台触发）
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
                AlarmResult.INEXACT
            }
        } catch (e: Exception) {
            Log.w(TAG, "精确排程失败，尝试近似闹钟 code=${r.code}", e)
            try {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
                AlarmResult.INEXACT
            } catch (e2: Exception) {
                Log.w(TAG, "排程失败 code=${r.code}", e2)
                AlarmResult.FAILED
            }
        }
    }

    private fun canScheduleExact(alarmMgr: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || runCatching { alarmMgr.canScheduleExactAlarms() }.getOrDefault(false)

    private fun baseIntent(code: Int): Intent =
        Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_REMIND).putExtra(EXTRA_CODE, code)

    private fun pending(code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, baseIntent(code), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun pendingFor(code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storeCodes(codes: Set<Int>) {
        prefs().edit().putStringSet(KEY_CODES, codes.map { it.toString() }.toSet()).apply()
    }

    private fun loadCodes(): Set<Int> =
        (prefs().getStringSet(KEY_CODES, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()

    private fun publish(status: ReminderStatus) {
        prefs().edit()
            .putInt(KEY_COUNT, status.count)
            .putString(KEY_NEXT, status.nextText)
            .putBoolean(KEY_EXACT, status.exactAllowed)
            .putLong(KEY_SYNCED_AT, status.syncedAtMillis)
            .apply()
        _status.value = status
    }

    private fun readStatus(): ReminderStatus {
        val p = prefs()
        return ReminderStatus(
            count = p.getInt(KEY_COUNT, 0),
            nextText = p.getString(KEY_NEXT, null),
            exactAllowed = p.getBoolean(KEY_EXACT, true),
            syncedAtMillis = p.getLong(KEY_SYNCED_AT, 0L),
            notificationWarning = ReminderNotifications.warning(context),
        )
    }

    /**
     * 几秒后弹一条测试提醒，供用户自查"通知到底能不能响"（不改动已排的课前提醒）。
     * @return 是否成功排上
     */
    fun sendTestReminder(delaySeconds: Long = 5L): Boolean {
        ReminderNotifications.ensureChannel(context)
        val alarmMgr = alarm ?: return false
        val at = LocalDateTime.now().plusSeconds(delaySeconds)
        val intent = baseIntent(TEST_CODE)
            .putExtra(EXTRA_TITLE, "测试课程")
            .putExtra(EXTRA_TIME, clock(at.hour * 60 + at.minute))
            .putExtra(EXTRA_LOCATION, "")
            .putExtra(EXTRA_CODE, TEST_CODE)
            .putExtra(EXTRA_TEST, true)
        val millis = System.currentTimeMillis() + delaySeconds * 1000
        return try {
            if (canScheduleExact(alarmMgr)) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingFor(TEST_CODE, intent))
            } else {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingFor(TEST_CODE, intent))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "测试提醒排程失败", e)
            false
        }
    }
}

/** 到点同步投递，不启动数据库；记录已送达的课程，避免重排/重复广播再次提醒。 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClassNotifier.ACTION_REMIND) return
        try {
            synchronized(ClassNotifier.deliveryLock) {
                val code = intent.getIntExtra(ClassNotifier.EXTRA_CODE, 0)
                val start = intent.getLongExtra(ClassNotifier.EXTRA_START_MILLIS, 0L)
                val fire = intent.getLongExtra(ClassNotifier.EXTRA_FIRE_MILLIS, 0L)
                val test = intent.getBooleanExtra(ClassNotifier.EXTRA_TEST, false)
                val now = System.currentTimeMillis()
                if (!test && !deliveryPrefs(context).getBoolean("enabled", true)) return
                if (!test && start > 0L) {
                    // 已经过了上课时间的旧通知不再弹出；尚未开课的系统延迟通知仍投递。
                    if (now >= start || now < fire) return
                    if (wasDelivered(context, code, start)) return
                }
                if (ReminderNotifications.deliver(context, intent) && !test && start > 0L) {
                    markDelivered(context, code, start)
                }
            }
        } catch (e: Exception) {
            Log.w("OrangeReminder", "课前提醒投递失败", e)
        }
    }
}

private fun deliveryPrefs(context: Context) = context.getSharedPreferences("orange_reminders", Context.MODE_PRIVATE)

internal fun wasDelivered(context: Context, code: Int, start: Long): Boolean =
    deliveryPrefs(context).getStringSet("delivered_occurrences", emptySet()).orEmpty().contains("$code:$start")

private fun markDelivered(context: Context, code: Int, start: Long) {
    val p = deliveryPrefs(context)
    val delivered = p.getStringSet("delivered_occurrences", emptySet()).orEmpty().toMutableSet()
    delivered += "$code:$start"
    p.edit().putStringSet("delivered_occurrences", delivered).commit()
}

private fun pruneDelivered(context: Context, now: Long) = synchronized(ClassNotifier.deliveryLock) {
    val p = deliveryPrefs(context)
    val delivered = p.getStringSet("delivered_occurrences", emptySet()).orEmpty()
        .filter { (it.substringAfter(':').toLongOrNull() ?: 0L) > now }.toSet()
    p.edit().putStringSet("delivered_occurrences", delivered).apply()
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** 相对日期前缀：今天/明天/后天/周X（设置页"最近一条"文案用） */
private fun dayLabel(date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when {
        days <= 0L -> "今天"
        days == 1L -> "明天"
        days == 2L -> "后天"
        else -> WeekMath.weekdayName(date.dayOfWeek.value)
    }
}
