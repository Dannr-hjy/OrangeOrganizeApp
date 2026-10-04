package com.dannr.chengzikb.ui.main

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.notify.ClassNotifier
import com.dannr.chengzikb.data.repo.ReminderPrefs
import com.dannr.chengzikb.util.OemGuide
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.describe
import com.dannr.chengzikb.data.model.weekSet
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.ui.day.DayOverrideDialog
import com.dannr.chengzikb.ui.grid.TimetableGrid
import com.dannr.chengzikb.ui.settings.SettingsScreen
import java.time.LocalDate
import kotlinx.coroutines.launch

private enum class MainView(val label: String) {
    GRID("课表"), LIST("课程"), SETTINGS("设置")
}

/** 长按空白格选中的（日列, 节次）位置 */
private data class EmptySlot(val day: Int, val period: Int)

/**
 * 主屏（无底部导航）。课表为默认全屏视图；
 * 右上角的三个小悬浮按钮：＋（加课）、列表、设置。
 * 课程/设置为覆盖视图，左箭头返回。
 */
@Composable
fun MainScreen(
    // seedSlot=true 表示来自“长按空白格”，新建时在当前位置预填一条上课安排；右上角＋则 false
    onOpenAdd: (day: Int, periodIndex: Int, seedSlot: Boolean) -> Unit,
    onPickExistingCourse: (day: Int, periodIndex: Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onOpenTerm: () -> Unit,
    onOpenPeriods: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenIcs: () -> Unit,
    onOpenHolidays: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val vm: MainViewModel = viewModel(factory = viewModelFactory {
        initializer {
            MainViewModel(
                app.container.courseRepository,
                app.container.periodRepository,
                app.container.settingsRepository,
                app.container.timetableManager,
                app.container.dayOverrideRepository,
            )
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // —— 课前通知偏好（全局）——
    // null = 偏好尚未从数据库读出。占位值(false)绝不能用去重排——那会被当成"用户关闭了提醒"，
    // 把上次排好的闹钟全部清空（若此后没有改动触发重排，提醒就永久哑了）。
    val remindPrefs by produceState<ReminderPrefs?>(initialValue = null) {
        app.container.prefsRepository.reminderPrefs.collect { value = it }
    }
    val remindEnabled = remindPrefs?.enabled ?: false
    val remindMinutes = remindPrefs?.minutes ?: 10
    // 最近一次排程结果（条数/最近一条/精确闹钟是否可用），设置页用它做“提醒到底排上没有”的自查
    val reminderStatus by ClassNotifier.status.collectAsStateWithLifecycle()

    // 打开系统「后台运行/自启动」设置：按厂商分发并自动兜底（vivo/OPPO/小米/荣耀/华为/三星/原生通用电池优化页）
    fun openAutoStartSettings() {
        OemGuide.openBackgroundSettings(context)
    }

    // “精确闹钟”授权页返回后，接着引导自启动（后台运行），保证划掉后台也能提醒
    val exactAlarmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        openAutoStartSettings()
    }
    fun askAlarmAndAutoStart() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val needExact = Build.VERSION.SDK_INT >= 31 && (alarm == null || !alarm.canScheduleExactAlarms())
        if (needExact) {
            runCatching {
                exactAlarmLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                )
            }.onFailure { openAutoStartSettings() }
        } else {
            openAutoStartSettings()
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch { app.container.prefsRepository.setRemindersEnabled(true) }
            askAlarmAndAutoStart()
        } else {
            Toast.makeText(context, "未开启通知权限，将无法收到课前提醒", Toast.LENGTH_SHORT).show()
        }
    }
    fun requestReminder(on: Boolean) {
        if (on) {
            val grantedNow = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (grantedNow) {
                scope.launch { app.container.prefsRepository.setRemindersEnabled(true) }
                askAlarmAndAutoStart()
            } else {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            scope.launch { app.container.prefsRepository.setRemindersEnabled(false) }
        }
    }

    // 测试提醒：5 秒后弹一条通知，让用户不用等到下一节课就能确认整条链路是通的
    fun testReminder() {
        val notificationsOn = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notificationsOn) {
            Toast.makeText(context, "请先允许通知权限，再试测试提醒", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = app.container.classNotifier.sendTestReminder()
        Toast.makeText(
            context,
            if (ok) "约 5 秒后会弹出一条测试提醒" else "测试提醒排程失败，请检查系统闹钟权限",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // 只在课表内容/提醒设置真的变化时重排闹钟（用内容指纹做 key，避免 30s 心跳反复重建）
    val scheduleKey = remember(
        state.courses, state.sessions, state.periods, state.settings,
        state.activeTimetableId, state.overrides,
    ) {
        buildString {
            append(state.activeTimetableId).append('|')
            state.settings?.let {
                append(it.termStartEpochDay).append(',').append(it.totalWeeks)
                    .append(',').append(if (it.autoHoliday) 1 else 0).append(',')
            }
            // 调休：休息日不该排提醒、补课日要按映射后的星期排，所以指纹里必须带上
            state.overrides.forEach { o ->
                append(o.dateEpochDay).append('-').append(o.kind).append('-').append(o.followDayOfWeek).append(';')
            }
            append('|')
            state.periods.forEach { p -> append(p.id).append('-').append(p.startMinute).append('-').append(p.endMinute).append(';') }
            state.sessions.forEach { s ->
                append(s.id).append('-').append(s.dayOfWeek).append('-').append(s.startPeriodIdx)
                    .append('-').append(s.endPeriodIdx).append('-').append(s.activeWeeksText)
                    .append('-').append(s.location ?: "").append(';') // 地点变化也要重排提醒
            }
        }
    }
    LaunchedEffect(scheduleKey, remindPrefs) {
        val prefs = remindPrefs ?: return@LaunchedEffect
        app.container.classNotifier.sync(prefs.enabled, prefs.minutes)
    }

    // rememberSaveable：从设置子页(作息/学期/备份等 NavHost 覆盖页)返回后仍留在设置页而非回到课表
    var viewIdx by rememberSaveable { mutableIntStateOf(MainView.GRID.ordinal) }
    val view: MainView = MainView.entries[viewIdx]
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    var pendingSlot by remember { mutableStateOf<EmptySlot?>(null) } // 长按空白格 → 选“新建/已有”
    var infoOcc by remember { mutableStateOf<Occurrence?>(null) } // 点按课程格 → 详情弹窗
    var editingDate by remember { mutableStateOf<LocalDate?>(null) } // 点日期头部 → 手动调休弹窗
    var viewedTableWeek by remember { mutableStateOf(0L to 1) } // (课表id, 正在看的周)：切走再回来保持所在周

    // 原生返回手势/按键：在课程列表、设置等“覆盖视图”里返回课表，而不是退出应用
    BackHandler(enabled = view != MainView.GRID) {
        viewIdx = MainView.GRID.ordinal
    }

    pendingDelete?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除课程") },
            text = { Text("删除「${course.name}」及其全部时间安排？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.deleteCourse(course.id) }
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // 长按空白格后的加课选择
    pendingSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { pendingSlot = null },
            title = { Text("这个空位想怎么加？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddChoiceRow(
                        icon = Icons.Default.Add,
                        title = "添加新课程",
                        subtitle = "新建一门课并排到这个时间",
                        onClick = {
                            pendingSlot = null
                            onOpenAdd(slot.day, slot.period, true)
                        },
                    )
                    AddChoiceRow(
                        icon = Icons.Default.List,
                        title = "添加已有课程",
                        subtitle = "从现有课程里选一门安排到此时间",
                        onClick = {
                            pendingSlot = null
                            onPickExistingCourse(slot.day, slot.period)
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingSlot = null }) { Text("取消") } },
        )
    }

    editingDate?.let { date ->
        DayEditDialogHost(
            date = date,
            overrides = state.overrides,
            autoEnabled = state.settings?.autoHoliday == true,
            onConfirm = { row ->
                vm.setDayOverrideAsync(date, row)
                editingDate = null
            },
            onDismiss = { editingDate = null },
        )
    }

    // 点按课程格 → 课程详情（名称 / 老师 / 地点 / 上课时间 / 周次）
    infoOcc?.let { occ ->
        val course = occ.course
        val session = occ.session
        val periodTimes = state.periods
        val startMin = periodTimes.getOrNull(session.startPeriodIdx)?.startMinute
        val endMin = periodTimes.getOrNull(session.endPeriodIdx)?.endMinute
        val spanLabel = if (session.endPeriodIdx > session.startPeriodIdx) {
            "第${session.startPeriodIdx + 1}-${session.endPeriodIdx + 1}节"
        } else {
            "第${session.startPeriodIdx + 1}节"
        }
        val clockLabel = if (startMin != null && endMin != null) {
            "（${clock(startMin)}-${clock(endMin)}）"
        } else ""
        AlertDialog(
            onDismissRequest = { infoOcc = null },
            title = { Text(course.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!course.teacher.isNullOrBlank()) {
                        InfoLine("老师", course.teacher!!)
                    }
                    if (!session.location.isNullOrBlank()) {
                        InfoLine("地点", session.location!!)
                    }
                    InfoLine("上课时间", "${WeekMath.weekdayName(session.dayOfWeek)} $spanLabel$clockLabel")
                    val ws = session.weekSet()
                    if (!ws.isEmpty) InfoLine("周次", ws.describe())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    infoOcc = null
                    onEditCourse(course)
                }) { Text("编辑") }
            },
            dismissButton = {
                TextButton(onClick = { infoOcc = null }) { Text("关闭") }
            },
        )
    }

    val s = state.settings
    val monday = state.termStartMonday

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(440)) { it * dir } + fadeIn(tween(260)))
                .togetherWith(slideOutHorizontally(tween(440)) { -it * dir } + fadeOut(tween(190)))
        },
        label = "mainView",
    ) { v ->
        Box(Modifier.fillMaxSize()) {
            when (v) {
                MainView.GRID -> {
                    if (s != null && monday != null) {
                        // key 当前课表 id：切换课表后整棵网格重建（翻页状态归零、避免越界）
                        key(state.activeTimetableId) {
                            TimetableGrid(
                                occurrences = state.occurrences,
                                periods = state.periods,
                                termStartMonday = monday,
                                totalWeeks = s.totalWeeks,
                                shownDays = s.shownDays,
                                now = state.now,
                                showLocation = s.showLocation,
                                showTeacher = s.showTeacher,
                                showShortName = s.showShortNameInGrid,
                                showOtherWeeks = s.showOtherWeeks,
                                dayPlans = state.dayPlans,
                                onEmptySlotLongPress = { day, idx -> pendingSlot = EmptySlot(day, idx) },
                                onEditDay = { editingDate = it },
                                onCourseInfo = { occ -> infoOcc = occ }, // 点按 → 详情弹窗（编辑按钮在弹窗内）
                                onCourseMove = { session, day, start, end, thisWeekOnly, week ->
                                    scope.launch {
                                        val ok = vm.moveSession(session, day, start, end, thisWeekOnly, week)
                                        if (!ok) Toast.makeText(context, "该时段已有其他课", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                // 记住所选周：切到列表/设置再回来仍停在该周（仅限同一课表）
                                initialWeek = if (viewedTableWeek.first == state.activeTimetableId) {
                                    viewedTableWeek.second.coerceIn(1, s.totalWeeks.coerceAtLeast(1))
                                } else null,
                                onWeekViewed = { w -> viewedTableWeek = state.activeTimetableId to w },
                                headerActions = {
                                    GhostButton(Icons.Default.DateRange, desc = "切换课表") { onOpenTables() }
                                    GhostButton(Icons.Default.Add, desc = "添加课程") {
                                        // 右上角 ＋：不预填上课安排，进入后自行“＋ 添加一段时间”
                                        val today = state.todayDayOfWeek
                                        val day = if (today <= (s.shownDays)) today else 1
                                        onOpenAdd(day, 0, false)
                                    }
                                    GhostButton(Icons.Default.List, desc = "课程列表") { viewIdx = MainView.LIST.ordinal }
                                    GhostButton(Icons.Default.Settings, desc = "设置") { viewIdx = MainView.SETTINGS.ordinal }
                                },
                            )
                        }
                    }
                }
                MainView.LIST -> {
                    Column(Modifier.fillMaxSize()) {
                        SubPageHeader(title = "课程列表", onBack = { viewIdx = MainView.GRID.ordinal })
                        Box(Modifier.weight(1f)) {
                            CourseListScreen(
                                courses = state.courses,
                                sessionCountOf = { id -> state.sessions.count { it.courseId == id } },
                                locationSummaryOf = { id ->
                                    state.sessions.filter { it.courseId == id }
                                        .mapNotNull { it.location }.filter { it.isNotBlank() }.distinct()
                                        .joinToString("、").ifBlank { null }
                                },
                                showLocation = s?.showLocation ?: true,
                                onEdit = { onEditCourse(it) },
                                onDeleteRequest = { pendingDelete = it },
                            )
                        }
                    }
                }
                MainView.SETTINGS -> {
                    Column(Modifier.fillMaxSize()) {
                        SubPageHeader(title = "设置", onBack = { viewIdx = MainView.GRID.ordinal })
                        if (s != null) {
                            Box(Modifier.weight(1f)) {
                                SettingsScreen(
                                    settings = s,
                                    onChange = { vm.saveSettingsAsync(it) },
                                    remindEnabled = remindEnabled,
                                    remindMinutes = remindMinutes,
                                    reminderStatus = reminderStatus,
                                    onSetRemindEnabled = { on -> requestReminder(on) },
                                    onSetRemindMinutes = { m -> scope.launch { app.container.prefsRepository.setRemindMinutes(m) } },
                                    onFixExactAlarm = { askAlarmAndAutoStart() },
                                    onTestReminder = { testReminder() },
                                    onOpenTerm = onOpenTerm,
                                    onOpenPeriods = onOpenPeriods,
                                    onOpenBackup = onOpenBackup,
                                    onOpenAbout = onOpenAbout,
                                    onOpenIcs = onOpenIcs,
                                    onOpenHolidays = onOpenHolidays,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 覆盖视图顶栏：左返回箭头 + 标题 */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonLike(onBack, Icons.AutoMirrored.Filled.ArrowBack, "返回")
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun IconButtonLike(onClick: () -> Unit, icon: ImageVector, desc: String) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 无阴影悬浮小圆钮：底色与页面一致、小而克制（课表/＋/列表/设置同一式样） */
@Composable
private fun GhostButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** 长按空白格弹窗里的两个选项行 */
@Composable
private fun AddChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 点日期头部 → 改这一天上什么（休息 / 按其他星期的课表） */
@Composable
private fun DayEditDialogHost(
    date: LocalDate,
    overrides: List<DayOverride>,
    autoEnabled: Boolean,
    onConfirm: (DayOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    DayOverrideDialog(
        date = date,
        current = overrides.firstOrNull { it.dateEpochDay == date.toEpochDay() },
        autoPlan = DayPlanIndex.autoOnly(date, autoEnabled),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** 详情弹窗里的一行“标签：值” */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
