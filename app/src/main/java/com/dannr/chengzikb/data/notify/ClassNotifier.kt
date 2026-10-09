package com.dannr.chengzikb.data.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dannr.chengzikb.MainActivity
import com.dannr.chengzikb.R
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.isActiveIn
import com.dannr.chengzikb.data.model.startMinuteIn
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
)

/**
 * 课前通知：把【当前课表】未来 ~35 天的上课提醒排入系统 AlarmManager；
 * 每门课在"上课时间 − 提前分钟"触发系统通知（开关与提前分钟来自全局偏好）。
 * 应用每次在前台且数据/设置变化时调用 [sync] 重排。
 *
 * 可靠性上做了三件事（对应真机上"提醒经常不响/不及时"的三类原因）：
 * 1) 精确闹钟：Android 14 起 SCHEDULE_EXACT_ALARM 默认不授予，退化成近似闹钟后
 *    在 Doze 里可能被推迟十几分钟——清单里改声明 USE_EXACT_ALARM（见 AndroidManifest）。
 * 2) 重排触发点：除开机外，覆盖安装、改时间/时区也都会让已排的 RTC 闹钟整体失效，
 *    统一由 [BootRescheduler] 兜住。
 * 3) 先算后写：见 [sync]。
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
        private const val SCHEDULE_DAYS = 35L
        private const val TEST_CODE = 0x7E57

        const val ACTION_REMIND = "com.dannr.chengzikb.action.CLASS_REMIND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_CODE = "code"
        const val EXTRA_TEST = "test"

        // 进程内共用：设置页订阅它，任何一处 sync（含开机/更新后的重排）都会即时反映到 UI
        private val _status = MutableStateFlow(ReminderStatus())

        /** 最近一次排程结果。 */
        val status: StateFlow<ReminderStatus> = _status.asStateFlow()
    }

    private val alarm: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 串行化重排：开着应用时前后台切换/设置变更可能同时触发多次 sync，交错执行会互相踩。 */
    private val syncLock = Mutex()

    init {
        // 进程刚起时用上次落盘的结果打底，避免设置页先显示"0 条"再跳变
        if (_status.value.syncedAtMillis == 0L) _status.value = readStatus()
    }

    /**
     * 按当前开关与提前分钟重排全部课前提醒。
     *
     * 关键点：**先把要排的算完，再动系统闹钟**。旧实现是"先 cancelAll 再算"，
     * 中途任何一步抛异常（或提前 return）都会把已经排好的提醒一起清空，
     * 用户侧表现为"提醒忽然整体失效、怎么都不响"。
     */
    suspend fun sync(enabled: Boolean, leadMinutes: Int) = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val alarmMgr = alarm
            val plan = try {
                if (enabled && leadMinutes > 0 && alarmMgr != null) buildPlan(leadMinutes) else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "重排课前提醒失败，保留原有闹钟", e)
                return@withContext
            }
            // 改动系统闹钟这一步不允许被取消：上面算完好几分钟的课表，这里只差"写进去"，
            // 半途中断会留下"排了一半、编码也没记全"的残局。整段是幂等且毫秒级的。
            withContext(NonCancellable) { applyPlan(alarmMgr, plan) }
        }
    }

    private fun applyPlan(alarmMgr: AlarmManager?, plan: List<Reminder>) {
        val canExact = alarmMgr != null && canScheduleExact(alarmMgr)
        val scheduled = mutableSetOf<Int>()
        for (r in plan) {
            if (alarmMgr != null && setAlarm(alarmMgr, r, canExact)) scheduled += r.code
        }
        // 只取消这次没排上的旧闹钟。这次排上的走 FLAG_UPDATE_CURRENT 原地替换，
        // 若先 cancel 再 set，同一个 PendingIntent 会被自己取消掉。
        (loadCodes() - scheduled).forEach { alarmMgr?.cancel(pending(it)) }
        storeCodes(scheduled)

        val next = plan.filter { it.code in scheduled }.minByOrNull { it.fireAt }
        publish(
            ReminderStatus(
                count = scheduled.size,
                nextText = next?.let { "${dayLabel(it.fireAt.toLocalDate())} ${clock(it.startMinute)} ${it.courseName}" },
                exactAllowed = canExact,
                syncedAtMillis = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "已排入 ${scheduled.size} 条课前提醒（精确=$canExact），最近一条：${next?.fireAt}")
    }

    /** 一条待排的提醒：触发时刻 + 已固化在 Intent 里的通知内容（到点无需再查库）。 */
    private class Reminder(
        val code: Int,
        val fireAt: LocalDateTime,
        val startMinute: Int,
        val courseName: String,
        val intent: Intent,
    ) {
        val triggerMillis: Long =
            fireAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 算出未来 [SCHEDULE_DAYS] 天内全部需要提醒的时刻（不接触系统闹钟，纯计算）。 */
    private suspend fun buildPlan(leadMinutes: Int): List<Reminder> {
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
        if (active <= 0) return emptyList()
        val settings = db.settingsDao().getByTimetable(active) ?: return emptyList()
        val termStart = LocalDate.ofEpochDay(settings.termStartEpochDay)
        val totalWeeks = settings.totalWeeks
        val periods = db.periodDao().getAllByTimetable(active)
        if (periods.isEmpty()) return emptyList()
        val sessions = db.courseSessionDao().getAllByTimetable(active)
        if (sessions.isEmpty()) return emptyList()
        val courses = db.courseDao().getAllByTimetable(active).associateBy { it.id }

        // 调休：休息日不排提醒、补课日按映射后的星期排——与课表/小组件同一套解析
        val dayPlans = DayPlanIndex.of(db.dayOverrideDao().getAllByTimetable(active), settings.autoHoliday)

        val now = LocalDateTime.now()
        val baseDate = now.toLocalDate()
        val out = ArrayList<Reminder>()

        for (offset in 0..SCHEDULE_DAYS) {
            val date = baseDate.plusDays(offset)
            val week = WeekMath.weekIndexOf(date, termStart)
            if (week !in 1..totalWeeks) continue
            val dayOfWeek = when (val plan = dayPlans.planFor(date)) {
                is DayPlan.Rest -> continue
                is DayPlan.Follow -> plan.dayOfWeek
            }
            for (s in sessions) {
                if (s.dayOfWeek != dayOfWeek) continue
                if (!s.isActiveIn(week)) continue
                val course = courses[s.courseId] ?: continue
                val startMinute = s.startMinuteIn(periods) ?: continue
                val classStart = LocalDateTime.of(date, LocalTime.of(startMinute / 60, startMinute % 60))
                if (!classStart.isAfter(now)) continue
                val fire = classStart.minusMinutes(leadMinutes.toLong())
                // 触发时刻已过的不再补排——避免“划掉又弹回来”（已提醒过一次就保持已提醒）
                if (!fire.isAfter(now)) continue
                // 编码里带 date：同一门课在不同日期是不同的闹钟；同一格在重排后编码不变，
                // 于是能原地替换而不是"取消+重排"，少一个"恰好卡在两者之间"的空窗
                val code = ("t$active-c${course.id}-s${s.id}-d${date}").hashCode()
                val intent = baseIntent(code)
                    .putExtra(EXTRA_TITLE, course.name)
                    .putExtra(EXTRA_TIME, clock(startMinute))
                    .putExtra(EXTRA_LOCATION, s.location ?: "") // 地点随安排（同一门课不同时段可不同）
                    .putExtra(EXTRA_CODE, code)
                out += Reminder(code, fire, startMinute, course.name, intent)
            }
        }
        return out
    }

    /** @return 是否真的排上了（精确闹钟被拒时会退化成近似闹钟再试一次） */
    private fun setAlarm(alarmMgr: AlarmManager, r: Reminder, canExact: Boolean): Boolean {
        val pi = pendingFor(r.code, r.intent)
        return try {
            if (canExact) {
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
            } else {
                // 没有“精确闹钟”权限：退化为允许的近似闹钟（可能略有延迟，但可后台触发）
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
            }
            true
        } catch (_: Exception) {
            try {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerMillis, pi)
                true
            } catch (e2: Exception) {
                Log.w(TAG, "排程失败 code=${r.code}", e2)
                false
            }
        }
    }

    private fun canScheduleExact(alarmMgr: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || runCatching { alarmMgr.canScheduleExactAlarms() }.getOrDefault(false)

    private fun cancelAll() {
        val alarmMgr = alarm ?: return
        loadCodes().forEach { code -> alarmMgr.cancel(pending(code)) }
        storeCodes(emptySet())
    }

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
        )
    }

    /** 供 UI 在“已关闭提醒/数据无关”时调用，确保旧闹钟被清掉。 */
    suspend fun cancelAllReminders() = syncLock.withLock {
        withContext(Dispatchers.IO) { cancelAll() }
    }

    /**
     * 几秒后弹一条测试提醒，供用户自查"通知到底能不能响"（不改动已排的课前提醒）。
     * @return 是否成功排上
     */
    fun sendTestReminder(delaySeconds: Long = 5L): Boolean {
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

/** 闹钟到点：直接发一条系统通知（提醒内容已固化在 Intent 里，无需查库）。 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 广播里抛异常会连带进程一起挂掉，这里整体兜住：宁可少一条通知，不要连累整个 App
        try {
            deliver(context, intent)
        } catch (e: Exception) {
            Log.w("OrangeReminder", "课前提醒投递失败", e)
        }
    }

    private fun deliver(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ClassNotifier.EXTRA_TITLE) ?: return
        val time = intent.getStringExtra(ClassNotifier.EXTRA_TIME) ?: ""
        val location = intent.getStringExtra(ClassNotifier.EXTRA_LOCATION).orEmpty()
        val code = intent.getIntExtra(ClassNotifier.EXTRA_CODE, 0)
        val isTest = intent.getBooleanExtra(ClassNotifier.EXTRA_TEST, false)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val contentTitle = if (isTest) "测试提醒" else "$title 即将开始"
        val contentText = if (isTest) {
            "这条能弹出来，说明通知与提醒通道正常"
        } else {
            buildString {
                append("$time 上课")
                if (location.isNotBlank()) append(" · $location")
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ClassNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 两声急促震动 + 系统默认提示音（响铃/震动/静音遵从系统与渠道设置）
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setVibrate(longArrayOf(0L, 250L, 150L, 250L))
            .build()
        NotificationManagerCompat.from(context).notify(code, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 每次发通知都强制高优先级 + 允许震动（覆盖早期可能建的旧渠道）
        val channel = manager.getNotificationChannel(ClassNotifier.CHANNEL_ID)
            ?: NotificationChannel(ClassNotifier.CHANNEL_ID, "课前提醒", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "课程开始前的系统提醒"
        channel.importance = NotificationManager.IMPORTANCE_HIGH
        channel.enableVibration(true)
        channel.setVibrationPattern(longArrayOf(0L, 250L, 150L, 250L))
        manager.createNotificationChannel(channel)
    }
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
