package com.dannr.chengzikb.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.dannr.chengzikb.MainActivity
import com.dannr.chengzikb.R
import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.isActiveIn
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.WidgetEngine
import com.dannr.chengzikb.domain.WidgetEngine.FocusKind
import com.dannr.chengzikb.ui.theme.courseColor
import com.dannr.chengzikb.util.OemFamily
import com.dannr.chengzikb.util.OemGuide
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 小组件亮/暗配色：文字分级色 + 图标色 + 整卡/高亮卡背景 drawable。 */
private data class Palette(
    val text: Int,
    val done: Int,
    val week: Int,
    val message: Int,
    val icon: Int,
    val rootBgRes: Int,
    val cardBgRes: Int,
)

/**
 * 桌面小组件的“渲染 + 自刷新调度”执行体。
 * 单次任务：读当前活跃课表 → [WidgetEngine] 算出显示内容 → 用【固定槽位】拼 RemoteViews 更新各组件
 * → 按时间推进排下一次精确刷新，并配一个兜底的周期闹钟。
 */
class WidgetUpdateJob(private val context: Context) {

    private val app = context.applicationContext

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(app)
        val provider = ComponentName(app, OrangeWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(provider)
        if (ids.isEmpty()) {
            cancelSchedule()
            return@withContext
        }
        val data = loadData() ?: return@withContext
        val now = LocalDateTime.now()
        val nowMinute = now.hour * 60 + now.minute
        val content = WidgetEngine.content(now.toLocalDate(), nowMinute, data.activeFor)
        ids.forEach { id ->
            try {
                val sizing = widgetSizing(manager, id)
                logDiagIfChanged(manager, id, sizing, content)
                manager.updateAppWidget(id, buildRemoteViews(content, sizing.widthPx, sizing.heightPx, data.dark))
            } catch (e: Exception) {
                // 单个组件失败不影响其余；下次刷新再试
                Log.w(TAG, "widget $id 刷新失败：${e.message}")
            }
        }
        scheduleNext(now, content)
        scheduleRepeatFallback()
    }

    /* ------------------------- 数据加载 ------------------------- */

    private class Loaded(
        val activeFor: (LocalDate) -> List<WidgetEngine.WCourse>,
        val dark: Boolean,
    )

    private suspend fun loadData(): Loaded? {
        val db = AppDatabase.getInstance(app)
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: db.timetableDao().getAll().firstOrNull()?.id
            ?: 0L
        if (active <= 0) return null
        val settings = db.settingsDao().getByTimetable(active)
            ?: Defaults.defaultSettings(LocalDate.now(), active)
        var periods = db.periodDao().getAllByTimetable(active)
        if (periods.isEmpty()) periods = Defaults.defaultPeriods()
        val sessions = db.courseSessionDao().getAllByTimetable(active)
        val courseById = db.courseDao().getAllByTimetable(active).associateBy { it.id }
        val termStart = LocalDate.ofEpochDay(settings.termStartEpochDay)
        val totalWeeks = settings.totalWeeks.coerceAtLeast(1)

        // 调休：休息日无课、补课日按映射后的星期取课——与课表网格走同一套解析
        val dayPlans = DayPlanIndex.of(
            db.dayOverrideDao().getAllByTimetable(active),
            settings.autoHoliday,
        )

        val activeFor: (LocalDate) -> List<WidgetEngine.WCourse> = forDate@{ date ->
            val week = WeekMath.weekIndexOf(date, termStart)
            if (week !in 1..totalWeeks) return@forDate emptyList()
            val dow = when (val plan = dayPlans.planFor(date)) {
                is DayPlan.Rest -> return@forDate emptyList()
                is DayPlan.Follow -> plan.dayOfWeek
            }
            sessions.asSequence()
                .filter { it.dayOfWeek == dow && it.isActiveIn(week) }
                .mapNotNull { s ->
                    val startP = periods.getOrNull(s.startPeriodIdx)
                    val endP = periods.getOrNull(s.endPeriodIdx)
                    val course = courseById[s.courseId]
                    if (startP == null || endP == null || course == null) return@mapNotNull null
                    WidgetEngine.WCourse(
                        courseId = course.id,
                        // 简称开关开启且该课填了简称时用简称（未填仍回退全称）
                        name = course.displayName(settings.showShortNameInWidget),
                        location = s.location,
                        colorArgb = courseColor(course).toArgb(),
                        startMinute = startP.startMinute,
                        endMinute = endP.endMinute,
                    )
                }
                .sortedWith(compareBy({ it.startMinute }, { it.courseId }))
                .toList()
        }
        return Loaded(activeFor, dark = isWidgetDark(settings))
    }

    /** 组件是否走暗色：跟随 App 设置的 themeMode（0=跟随系统 1=浅 2=深）。 */
    private fun isWidgetDark(settings: AppSettings): Boolean = when (settings.themeMode) {
        1 -> false
        2 -> true
        else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /* ------------------------- RemoteViews 拼装（固定槽位） ------------------------- */

    private fun buildRemoteViews(
        content: WidgetEngine.WidgetDayContent,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(app.packageName, R.layout.widget_today)
        // 颜色/背景跟随 App 主题（亮/暗）。每次整组重绘都重设，切换主题即可生效。
        val p = if (dark) DARK else LIGHT
        rv.setInt(R.id.wgt_root, "setBackgroundResource", p.rootBgRes)
        rv.setInt(R.id.wgt_focus, "setBackgroundResource", p.cardBgRes)
        rv.setTextColor(R.id.wgt_header_date, p.text)
        rv.setTextColor(R.id.wgt_header_week, p.week)
        rv.setTextColor(R.id.wgt_focus_name, p.text)
        rv.setTextColor(R.id.wgt_focus_status, p.text)
        rv.setTextColor(R.id.wgt_focus_time, p.text)
        rv.setTextColor(R.id.wgt_focus_loc, p.text)
        rv.setTextColor(R.id.wgt_message, p.message)
        rv.setInt(R.id.wgt_focus_clock, "setColorFilter", p.icon)
        rv.setInt(R.id.wgt_focus_pin, "setColorFilter", p.icon)

        rv.setTextViewText(R.id.wgt_header_date, content.headerDateText)
        rv.setTextViewText(R.id.wgt_header_week, content.headerWeekText)
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.wgt_root, open)

        // —— 每次整组重绘都先显式隐藏全部槽位 ——
        // 桌面对同一组件走 reapply，会沿用上一次的视图树；若不清空，上一帧多余的槽位/文案会残留，
        // 表现为“多出一行/同一节重复/挤出格子”。先全部 GONE 再按需置可见，等效于重新添加组件。
        repeat(ROW_SLOTS) { rv.setViewVisibility(ROW_ROOT[it], View.GONE) }
        rv.setViewVisibility(R.id.wgt_focus, View.GONE)
        rv.setViewVisibility(R.id.wgt_focus_loc_row, View.GONE)
        rv.setViewVisibility(R.id.wgt_message, View.GONE)

        val message = content.message
        if (message != null) {
            rv.setViewVisibility(R.id.wgt_message, View.VISIBLE)
            rv.setTextViewText(R.id.wgt_message, message)
            return rv
        }

        val timePrefix = content.timePrefix // 显示非今天时给行时间加“明天/后天/周X”前缀

        // “次日预览”模式（今日已收工且未到 20:00）：无高亮卡，未来课按序排满（每行带前缀）。这是唯一全列不高亮的情形。
        if (content.focus == null && content.future.isNotEmpty()) {
            content.future.take(ROW_SLOTS).forEachIndexed { i, c -> bindRow(rv, i, c, false, p, prefix = timePrefix) }
            return rv
        }

        // 行槽位布局：r1..r3 在高亮卡上方（已上完/灰），r4..r6 在下方（未上/黑）。
        val past = content.past
        val future = content.future
        val focus = content.focus

        if (focus != null) {
            rv.setViewVisibility(R.id.wgt_focus, View.VISIBLE)
            val c = focus.course
            rv.setTextViewText(R.id.wgt_focus_name, c.name)
            rv.setTextViewText(R.id.wgt_focus_status, focus.statusText)
            rv.setTextViewText(R.id.wgt_focus_time, formatRange(c.startMinute, c.endMinute))
            rv.setInt(R.id.wgt_focus_bar, "setColorFilter", c.colorArgb)
            if (c.location.isNullOrBlank()) {
                rv.setViewVisibility(R.id.wgt_focus_loc_row, View.GONE)
            } else {
                rv.setViewVisibility(R.id.wgt_focus_loc_row, View.VISIBLE)
                rv.setTextViewText(R.id.wgt_focus_loc, c.location)
            }
        }

        // 行高/卡高/头部开销按真实 inflate+measure 实测（跟随密度与系统字体缩放），
        // 得到这次最多能放几行普通行而不挤出格子（高亮卡固定保留）。
        val rowBudget = computeBudget(heightPx, focus, WidgetMetrics.measure(app, widthPx))

        // 非高亮行规则：依高亮在当日的位置决定上(已上/灰)/下(未上/黑)各显示几节（见 WidgetEngine.nonHighlightRows）。
        val (above, below) = WidgetEngine.nonHighlightRows(past.size, future.size, rowBudget)
        past.takeLast(above).forEachIndexed { i, c -> bindRow(rv, i, c, true, p) }
        // 下方：未上（黑）
        future.take(below).forEachIndexed { i, c -> bindRow(rv, ABOVE_CAP + i, c, false, p, prefix = timePrefix) }
        return rv
    }

    /** slotIndex: 0..5 对应 wgt_r1..r6；[prefix] 为“明天/后天/周X”，非今天显示时给时间加前缀。 */
    private fun bindRow(rv: RemoteViews, slotIndex: Int, c: WidgetEngine.WCourse, grey: Boolean, p: Palette, prefix: String = "") {
        if (slotIndex !in 0 until ROW_SLOTS) return
        // 高亮让给下一节后，本节按灰行显示，但时间栏标“即将下课”
        val color = if (grey) p.done else p.text
        val timeText = when {
            c.endingSoon -> ENDING_SOON_TEXT
            prefix.isEmpty() -> formatClock(c.startMinute)
            else -> "$prefix ${formatClock(c.startMinute)}"
        }
        rv.setViewVisibility(ROW_ROOT[slotIndex], View.VISIBLE)
        rv.setTextViewText(ROW_NAME[slotIndex], c.name)
        rv.setTextViewText(ROW_TIME[slotIndex], timeText)
        rv.setTextColor(ROW_NAME[slotIndex], color)
        rv.setTextColor(ROW_TIME[slotIndex], color)
        rv.setInt(ROW_DOT[slotIndex], "setColorFilter", c.colorArgb)
    }

    /**
     * 组件当前尺寸(px) + 决策来源。各桌面上报 OPTION 的语义不同（OriginOS 上报偏小、其余≈真实），
     * 统一交给 [WidgetSizePolicy]：OriginOS 保 168dp 地板不回归，其它桌面信上报且 MIN 优先、
     * 以 provider.min 兜底——只求“预算 ≤ 真实”，绝不裁底。
     */
    private fun widgetSizing(manager: AppWidgetManager, id: Int): WidgetSizing {
        val density = app.resources.displayMetrics.density
        val isOrigin = OemGuide.familyOf(Build.MANUFACTURER, Build.BRAND) == OemFamily.VIVO
        val options = manager.getAppWidgetOptions(id)
        val info = manager.getAppWidgetInfo(id)
        return WidgetSizePolicy.decideSizing(
            minWdp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            maxWdp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            minHdp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            maxHdp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
            infoMinWidthPx = info?.minWidth ?: 0,
            infoMinHeightPx = info?.minHeight ?: 0,
            density = density,
            isOrigin = isOrigin,
        )
    }

    /** 高亮卡占用高(已含卡片上下 margin)；无高亮卡为 0。 */
    private fun focusUseH(focus: WidgetEngine.Focus?, m: WidgetMetrics): Int = when {
        focus == null -> 0
        focus.course.location.isNullOrBlank() -> m.focusHNoLoc
        else -> m.focusHLoc
    }

    /** 这次最多能放几行普通行而不挤出格子；与 buildRemoteViews 共用同一公式，避免行数口径漂移。 */
    private fun computeBudget(heightPx: Int, focus: WidgetEngine.Focus?, m: WidgetMetrics): Int {
        val availPx = heightPx - m.overheadH - focusUseH(focus, m)
        return (availPx / m.rowH).coerceIn(0, ROW_SLOTS)
    }

    /**
     * 诊断日志：把“这次在什么设备/桌面、按什么尺寸、画了几行”记成一行，只在内容变化时打一次。
     * 去重存 SharedPreferences（闹钟每次可能起新进程，静态去重会每 15 分钟刷屏）。
     * 各 ROM 实机校准看 hpx/heightSource 是否≈真实高度、有无裁底（budget 与 used 是否一致）。
     */
    private fun logDiagIfChanged(manager: AppWidgetManager, id: Int, s: WidgetSizing, content: WidgetEngine.WidgetDayContent) {
        val res = app.resources
        val opt = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
        val minW = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val maxW = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val minH = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxH = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        val m = WidgetMetrics.measure(app, s.widthPx)
        val budget = if (content.message != null) 0 else computeBudget(s.heightPx, content.focus, m)
        val (above, below) =
            if (content.message != null) 0 to 0
            else WidgetEngine.nonHighlightRows(content.past.size, content.future.size, budget)
        val line = buildString {
            append("man=").append(Build.MANUFACTURER)
            append("|brand=").append(Build.BRAND)
            append("|model=").append(Build.MODEL)
            append("|sdk=").append(Build.VERSION.SDK_INT)
            append("|launcher=").append(launcherPackage())
            append("|opt=(").append(minW).append('x').append(maxW).append(")x(").append(minH).append('x').append(maxH).append(")dp")
            append("|d=").append(res.displayMetrics.density)
            append("|f=").append(res.configuration.fontScale)
            append("|wpx=").append(s.widthPx)
            append("|hpx=").append(s.heightPx)
            append("|src=").append(s.heightSource.name)
            append("|ovh=").append(m.overheadH)
            append("|row=").append(m.rowH)
            append("|fNL=").append(m.focusHNoLoc)
            append("|fL=").append(m.focusHLoc)
            append("|fh=").append(focusUseH(content.focus, m))
            append("|budget=").append(budget)
            append("|used=").append(above).append('/').append(below)
            append("|msg=").append(if (content.message != null) 1 else 0)
        }
        val prefs = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
        val key = diagKey(id)
        if (line != prefs.getString(key, null)) {
            Log.i(TAG, line)
            prefs.edit().putString(key, line).apply()
        }
    }

    private fun launcherPackage(): String {
        launcherCache?.let { return it }
        val pkg = try {
            app.packageManager
                .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
        launcherCache = pkg ?: ""
        return pkg.orEmpty()
    }

    /**
     * 实测 widget_today 各组成块在“当前字体缩放 + 当前密度 + 该宽度”下的真实高度(px)。
     * 因为普通行/卡片都是单行不换行(wrap_content 高度与宽度无关)，实测值可直接用于行数预算。
     * inflate+measure 可在 IO 线程进行；失败时退回 dp 估算值。
     */
    private class WidgetMetrics(
        val overheadH: Int, // 根上下 padding + 头部行（不含任何内容行）
        val rowH: Int, // 一条普通行高
        val focusHNoLoc: Int, // 高亮卡（无地点），已含卡片上下 margin
        val focusHLoc: Int, // 高亮卡（有地点），已含卡片上下 margin
    ) {
        companion object {
            private data class CacheKey(val widthPx: Int, val densityBp: Int, val fontScaleBp: Int)

            private val cache = HashMap<CacheKey, WidgetMetrics>()
            private val lock = Any()

            /** 按 (宽度,密度,字体缩放) 缓存实测结果，避免每回自刷新都重复 inflate。 */
            fun measure(context: Context, widthPx: Int): WidgetMetrics {
                val res = context.resources
                val key = CacheKey(
                    widthPx,
                    (res.displayMetrics.density * 100).roundToInt(),
                    (res.configuration.fontScale * 100).roundToInt(),
                )
                synchronized(lock) {
                    cache[key]?.let { return it }
                }
                val m = measureInternal(context, widthPx)
                synchronized(lock) {
                    if (cache.size >= 8) cache.clear()
                    cache[key] = m
                }
                return m
            }

            private fun measureInternal(context: Context, widthPx: Int): WidgetMetrics {
                val density = context.resources.displayMetrics.density
                val fallback = WidgetMetrics(
                    overheadH = (40 * density).roundToInt(),
                    rowH = (24 * density).roundToInt(),
                    focusHNoLoc = ((46 + FOCUS_MARGIN_DP) * density).roundToInt(),
                    focusHLoc = ((62 + FOCUS_MARGIN_DP) * density).roundToInt(),
                )
                return try {
                    val inflater = LayoutInflater.from(context)
                    val root = inflater.inflate(R.layout.widget_today, null) as ViewGroup
                    val focus = root.findViewById<View>(R.id.wgt_focus) ?: return fallback
                    val locRow = root.findViewById<View>(R.id.wgt_focus_loc_row) ?: return fallback
                    val rowIds = intArrayOf(R.id.wgt_r1, R.id.wgt_r2, R.id.wgt_r3, R.id.wgt_r4, R.id.wgt_r5, R.id.wgt_r6)
                    val rows = rowIds.toList().mapNotNull { root.findViewById<View>(it) }
                    if (rows.size != rowIds.size) return fallback

                    fun setOnly(showRow: Boolean, showFocus: Boolean, showLoc: Boolean) {
                        rows.forEach { it.visibility = View.GONE }
                        focus.visibility = if (showFocus) View.VISIBLE else View.GONE
                        locRow.visibility = if (showLoc) View.VISIBLE else View.GONE
                        root.findViewById<View>(R.id.wgt_message)?.visibility = View.GONE
                        if (showRow) rows.first().visibility = View.VISIBLE
                    }
                    fun height(): Int {
                        val w = View.MeasureSpec.makeMeasureSpec(widthPx.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
                        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        root.measure(w, h)
                        return root.measuredHeight
                    }

                    setOnly(showRow = false, showFocus = false, showLoc = false)
                    val overhead = height()
                    setOnly(showRow = true, showFocus = false, showLoc = false)
                    val withRow = height()
                    setOnly(showRow = false, showFocus = true, showLoc = false)
                    val withFocusNoLoc = height()
                    setOnly(showRow = false, showFocus = true, showLoc = true)
                    val withFocusLoc = height()

                    WidgetMetrics(
                        overheadH = overhead,
                        rowH = (withRow - overhead).coerceAtLeast(1),
                        focusHNoLoc = (withFocusNoLoc - overhead).coerceAtLeast(1),
                        focusHLoc = (withFocusLoc - overhead).coerceAtLeast(1),
                    )
                } catch (_: Throwable) {
                    fallback
                }
            }

            // 估算值用的视觉常量（measure 失败才用），与布局实测同源
            private const val FOCUS_MARGIN_DP = 8
        }
    }

    /* ------------------------- 自刷新调度 ------------------------- */

    private fun scheduleNext(now: LocalDateTime, content: WidgetEngine.WidgetDayContent) {
        val at = nextRefreshAt(now, content)
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        try {
            if (canExact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, tickPending())
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, tickPending())
        } catch (_: Exception) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, tickPending())
        }
    }

    /** 兜底周期闹钟：即使精确链被系统限制/挂掉，也能定期整组刷新（日期、状态都纠正） */
    private fun scheduleRepeatFallback() {
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        try {
            alarm.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                now + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                repeatPending(),
            )
        } catch (_: Exception) {
            // 周期闹钟失败可忽略，精确链仍在
        }
    }

    fun cancelSchedule() {
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarm.cancel(tickPending())
        alarm.cancel(repeatPending())
    }

    private fun tickPending(): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_TICK,
        Intent(app, WidgetClockReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun repeatPending(): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_REPEAT,
        Intent(app, WidgetClockReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_TICK = "com.dannr.chengzikb.action.WIDGET_TICK"
        const val REQUEST_TICK = 0x7B16
        const val REQUEST_REPEAT = 0x7B17

        private const val TAG = "OrangeWidget"

        private const val ABOVE_CAP = 3 // 高亮卡上方灰行槽位数
        private const val BELOW_CAP = 3 // 高亮卡下方黑行槽位数
        private const val ROW_SLOTS = ABOVE_CAP + BELOW_CAP

        private const val DAY_CLOSE_HOUR = 20 // 与 WidgetEngine.DAY_CLOSE_MINUTE 对应：20:00 后高亮次日首节

        // 2×2 真实高度地板(168dp)已随决策逻辑移入 WidgetSizePolicy.ORIGIN_FLOOR_DP，仅 OriginOS 分支使用。

        private const val DIAG_PREFS = "widget_diag"
        private fun diagKey(widgetId: Int) = "diag:$widgetId"

        @Volatile private var launcherCache: String? = null

        // 亮/暗配色（暗色取自 App 深色主题 surface/onSurface 系，带暖橙同调）
        private val LIGHT = Palette(
            text = 0xFF1A1A1A.toInt(),
            done = 0xFF9A9A9A.toInt(),
            week = 0xFF937B35.toInt(),
            message = 0xFF757575.toInt(),
            icon = 0xFF1A1A1A.toInt(),
            rootBgRes = R.drawable.wgt_bg_root,
            cardBgRes = R.drawable.wgt_bg_card,
        )
        private val DARK = Palette(
            text = 0xFFF0DED3.toInt(),
            done = 0xFFB6A397.toInt(),
            week = 0xFFFFB98A.toInt(),
            message = 0xFFC9B8AD.toInt(),
            icon = 0xFFF0DED3.toInt(),
            rootBgRes = R.drawable.wgt_bg_root_dark,
            cardBgRes = R.drawable.wgt_bg_card_dark,
        )

        /** 高亮让给下一节后，仍在上课的当前节灰行时间栏显示 */
        const val ENDING_SOON_TEXT = "即将下课"

        private val ROW_ROOT = intArrayOf(R.id.wgt_r1, R.id.wgt_r2, R.id.wgt_r3, R.id.wgt_r4, R.id.wgt_r5, R.id.wgt_r6)
        private val ROW_DOT = intArrayOf(R.id.wgt_dot1, R.id.wgt_dot2, R.id.wgt_dot3, R.id.wgt_dot4, R.id.wgt_dot5, R.id.wgt_dot6)
        private val ROW_NAME = intArrayOf(R.id.wgt_name1, R.id.wgt_name2, R.id.wgt_name3, R.id.wgt_name4, R.id.wgt_name5, R.id.wgt_name6)
        private val ROW_TIME = intArrayOf(R.id.wgt_time1, R.id.wgt_time2, R.id.wgt_time3, R.id.wgt_time4, R.id.wgt_time5, R.id.wgt_time6)

        fun formatClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

        fun formatRange(startMinute: Int, endMinute: Int): String =
            "%s-%s".format(formatClock(startMinute), formatClock(endMinute))

        /**
         * 推算“下一次必须刷新”的时刻（取各边界与次日零点里最早的一个）。
         * 只在“状态切换”整点安排一次性刷新，不再逐分钟刷新：
         *  上课中→(下课)；下一节→(开课前 [WidgetEngine.UPCOMING_SOON_MINUTE] 分钟切“即将上课”，开课时切“上课中”)；
         *  次日预览当天 20:00 切“高亮首节”；次日零点按新的一天重算。
         */
        fun nextRefreshAt(now: LocalDateTime, content: WidgetEngine.WidgetDayContent): LocalDateTime {
            val candidates = mutableListOf(now.toLocalDate().plusDays(1).atStartOfDay()) // 兜底跨天
            // “次日预览”(20:00 前不高亮)需在当天 20:00 切换为“高亮首节”；已过点的候选会被末尾 filter 滤掉
            candidates += LocalDateTime.of(now.toLocalDate(), LocalTime.of(DAY_CLOSE_HOUR, 0))
            val focus = content.focus
                ?: return candidates.filter { it.isAfter(now) }.minOrNull() ?: candidates.first()
            val date = content.date
            if (focus.kind == FocusKind.ONGOING) {
                // 下课那一刻 → 重算（下课/整日收工等）
                candidates += LocalDateTime.of(
                    date,
                    LocalTime.of(focus.course.endMinute / 60, focus.course.endMinute % 60),
                )
                // 下一节开课前 SWITCH_AHEAD_MINUTE 分钟：高亮提前让给下一节、本节转“即将下课”灰行
                content.future.firstOrNull()?.let { n ->
                    candidates += LocalDateTime.of(
                        date,
                        LocalTime.of(n.startMinute / 60, n.startMinute % 60),
                    ).minusMinutes(WidgetEngine.SWITCH_AHEAD_MINUTE.toLong())
                }
            } else if (date == now.toLocalDate()) {
                // “下一节”同日：开课前 UPCOMING_SOON_MINUTE 分钟切“即将上课”，开课时切“上课中”
                val startTime = LocalTime.of(focus.course.startMinute / 60, focus.course.startMinute % 60)
                candidates += LocalDateTime.of(date, startTime)
                candidates += LocalDateTime.of(date, startTime).minusMinutes(WidgetEngine.UPCOMING_SOON_MINUTE.toLong())
            } else if (date.isAfter(now.toLocalDate())) {
                candidates += date.atStartOfDay() // 届时该日变“今天”，需重算
            }
            return candidates.filter { it.isAfter(now) }.minOrNull() ?: candidates.first()
        }
    }
}
