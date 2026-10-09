package com.dannr.chengzikb.ui.course

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.data.model.describe
import com.dannr.chengzikb.data.model.weekSet
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.ui.theme.courseFillColor
import com.dannr.chengzikb.ui.theme.formatColorHex
import com.dannr.chengzikb.ui.theme.parseHexColor

/**
 * 编辑中的一条安排草稿；arrId<=0 表示尚未入库的新安排。地点逐安排保存。
 *
 * [start]<0 表示"节次尚未选定"：只有「＋ 添加一段时间」进入编辑器时用作占位，
 * 该占位不会进入 [CourseEditScreen] 的 arrangements 列表（保存时才落库）。
 */
private data class ArrDraft(
    val arrId: Long,
    val day: Int,
    val start: Int,
    val end: Int,
    val weekSet: WeekSet,
    val location: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
)

private enum class EditorView { SUMMARY, ARRANGEMENT }

/**
 * 新增 / 编辑课程页。先填课程信息，再为它挂 1..N 条“一周内的时间安排”。
 */
@Composable
fun CourseEditScreen(
    courseId: Long?,
    defaultDay: Int,
    defaultPeriod: Int,
    preseedDay: Int? = null,
    preseedPeriod: Int? = null,
    seedFromSlot: Boolean = false, // true=长按空白格进入：预填当前格一条安排；false(右上角＋)=不预填
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val vm: CourseEditViewModel = viewModel(factory = viewModelFactory {
        initializer { CourseEditViewModel(app.container.courseRepository, courseId) }
    })
    val periods by app.container.periodRepository.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val totalWeeks = settings?.totalWeeks ?: 20

    // ---- 课程信息草稿（地点已逐安排，见 ArrDraft.location）----
    var existingId by remember { mutableLongStateOf(0L) }
    var name by remember { mutableStateOf("") }
    var shortName by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var colorIndex by remember { mutableIntStateOf(0) }
    var colorHex by remember { mutableStateOf("") }
    // ---- 安排列表 ----
    var arrangements by remember { mutableStateOf(emptyList<ArrDraft>()) }
    var seeded by remember { mutableStateOf(false) }

    var view by remember { mutableStateOf(EditorView.SUMMARY) }
    var editingArr by remember { mutableStateOf<ArrDraft?>(null) }
    var nextNewId by remember { mutableLongStateOf(-1L) }
    var confirmDeleteCourse by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    // 首次加载（等作息表就绪，才能把默认“两节连堂”卡在上限内）
    LaunchedEffect(courseId, periods) {
        if (seeded) return@LaunchedEffect
        if (periods.isEmpty()) return@LaunchedEffect
        val course = vm.loadCourse()
        if (course != null) {
            existingId = course.id
            name = course.name
            shortName = course.shortName.orEmpty()
            teacher = course.teacher.orEmpty()
            colorIndex = course.colorIndex
            colorHex = course.colorHex.orEmpty()
            val loaded = vm.loadSessions().map {
                ArrDraft(it.id, it.dayOfWeek, it.startPeriodIdx, it.endPeriodIdx, it.weekSet(), it.location, it.startMinute, it.endMinute)
            }.toMutableList()
            // 从“选已有课加到某空格”进入：该时段若尚无安排则预填一条（周次与地点沿用首段，风格一致；仍待“保存”落库）
            val pd = preseedDay
            val pe = preseedPeriod
            if (pd != null && pe != null && loaded.none { it.day == pd && pe >= it.start && pe <= it.end }) {
                val first = loaded.firstOrNull()
                val base = first?.weekSet ?: WeekSet.weekly(totalWeeks)
                val endP = (pe + 1).coerceAtMost(periods.lastIndex)
                nextNewId--
                loaded.add(ArrDraft(nextNewId, pd, pe, endP, base, first?.location))
            }
            arrangements = loaded
        } else if (seedFromSlot) {
            // 长按空白格进入：默认从该格起连排两节（沿用之前行为）
            val d = defaultDay.coerceIn(1, 7)
            val s = defaultPeriod.coerceAtLeast(0)
            val e = (s + 1).coerceAtMost(periods.lastIndex)
            arrangements = listOf(
                ArrDraft(arrId = -1L, day = d, start = s, end = e, weekSet = WeekSet.weekly(totalWeeks))
            )
        } else {
            // 右上角“＋ 添加课程”进入：不预填上课安排，由用户自行“＋ 添加一段时间”
            arrangements = emptyList()
        }
        seeded = true
    }

    fun upsertDraft(draft: ArrDraft) {
        val idx = arrangements.indexOfFirst { it.arrId == draft.arrId }
        arrangements = if (idx >= 0) {
            arrangements.mapIndexed { i, a -> if (i == idx) draft else a }
        } else {
            arrangements + draft
        }
    }

    val colorValid = colorHex.isBlank() || parseHexColor(colorHex) != null
    val canSave = name.isNotBlank() && periods.isNotEmpty() && arrangements.isNotEmpty() &&
        colorValid &&
        arrangements.all { it.end >= it.start && !it.weekSet.isEmpty }

    /** 汇总页保存（底部主按钮；冲突时 Toast 提示） */
    fun performSave() {
        val course = Course(
            id = existingId,
            name = name.trim(),
            shortName = shortName.trim().ifBlank { null },
            teacher = teacher.trim().ifBlank { null },
            colorIndex = colorIndex,
            colorHex = colorHex.trim().ifBlank { null },
        )
        val sessions = arrangements.map {
            CourseSession(
                id = if (it.arrId > 0) it.arrId else 0L,
                courseId = 0L, // 由仓库补
                dayOfWeek = it.day,
                startPeriodIdx = it.start,
                endPeriodIdx = it.end,
                activeWeeksText = it.weekSet.toText(),
                location = it.location?.trim()?.ifBlank { null },
                startMinute = it.startMinute,
                endMinute = it.endMinute,
            )
        }
        vm.save(
            course,
            sessions,
            onDone = { onBack() },
            onConflict = {
                Toast.makeText(context, "该时段已有其他课，请换个时间", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // 汇总视图 / 上课安排：同一 AnimatedContent 内左右切换，进入与退出均有过渡
    BackHandler(enabled = view == EditorView.ARRANGEMENT && editingArr != null) { view = EditorView.SUMMARY }

    AnimatedContent(
        targetState = view,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(470)) { it * dir } + fadeIn(tween(240)))
                .togetherWith(slideOutHorizontally(tween(400)) { -it * dir } + fadeOut(tween(180)))
        },
        label = "courseEditView",
    ) { v ->
        if (v == EditorView.ARRANGEMENT && editingArr != null) {
            val editing = editingArr!!
            ArrangementEditor(
                initial = editing,
                periods = periods,
                totalWeeks = totalWeeks,
                locationSuggestions = arrangements.filterNot { it.arrId == editing.arrId }
                    .mapNotNull { it.location }.filter { it.isNotBlank() }.distinct(),
                onBack = { view = EditorView.SUMMARY },
                onSave = { updated ->
                    upsertDraft(updated)
                    view = EditorView.SUMMARY
                },
                onDelete = {
                    arrangements = arrangements.filterNot { it.arrId == editing.arrId }
                    view = EditorView.SUMMARY
                },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text(
                if (existingId > 0) "编辑课程" else "新建课程",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (existingId > 0) {
                IconButton(onClick = { confirmDeleteCourse = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除这门课程及全部安排")
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = shortName,
                onValueChange = { shortName = it },
                label = { Text("课程简称（可选）") },
                placeholder = { Text("例如 高数") },
                singleLine = true,
                supportingText = { Text("用于课表格子、桌面小组件等空间紧张处；留空则处处显示全称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("老师（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 地点已逐安排保存：请在下方「上课安排」里为每个时段单独设置
            Spacer(Modifier.height(8.dp))
            SectionLabel("课程颜色")
            val customColor = parseHexColor(colorHex)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 7 个预设色
                for (i in 0 until 7) {
                    val sel = colorHex.isBlank() && colorIndex == i
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clip(CircleShape)
                            .background(courseFillColor(i))
                            .clickable { colorIndex = i; colorHex = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sel) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                    }
                }
                // 第 8 格：彩虹 → 打开选色板（已自选时显示所选色）
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .then(if (customColor != null) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(
                            if (customColor != null) customColor
                            else Color.Transparent,
                        )
                        .then(
                            if (customColor == null) {
                                Modifier.background(
                                    Brush.sweepGradient(
                                        listOf(
                                            Color.Red, Color(0xFFFF9800), Color.Yellow, Color.Green,
                                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                                        ),
                                    )
                                )
                            } else Modifier
                        )
                        .clickable { showColorPicker = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (customColor == null) {
                        Icon(Icons.Default.Add, contentDescription = "自定义颜色", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (!colorValid) {
                Text(
                    "自定义颜色格式不正确",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(18.dp))

            SectionLabel("上课安排")
            if (arrangements.isEmpty()) {
                Text("还没有安排", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            }
            arrangements.forEach { a ->
                ArrangementCard(
                    draft = a,
                    periods = periods,
                    onClick = {
                        editingArr = a
                        view = EditorView.ARRANGEMENT
                    },
                    onDelete = { arrangements = arrangements.filterNot { it.arrId == a.arrId } },
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = {
                    nextNewId--
                    // start=-1：全新一段，编辑器里不预选节次，等用户点
                    editingArr = ArrDraft(nextNewId, 1, -1, -1, WeekSet.weekly(totalWeeks))
                    view = EditorView.ARRANGEMENT
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("＋ 添加一段时间", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Surface(
            onClick = { performSave() },
            enabled = canSave,
            shape = RoundedCornerShape(12.dp),
            color = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (existingId > 0) "保存修改" else "保存",
                    color = if (canSave) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    }
}

    if (showColorPicker) {
        ColorPickerDialog(
            onDismiss = { showColorPicker = false },
            onPick = { c ->
                colorIndex = 0
                colorHex = formatColorHex(c)
                showColorPicker = false
            },
        )
    }

    if (confirmDeleteCourse) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCourse = false },
            title = { Text("删除课程") },
            text = { Text("将删除「$name」及它的全部时间安排，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteCourse(existingId) { onBack() } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCourse = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ArrangementCard(
    draft: ArrDraft,
    periods: List<PeriodSetting>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val first = periods.getOrNull(draft.start)
    val last = periods.getOrNull(draft.end)
    val startMinute = draft.startMinute ?: first?.startMinute
    val endMinute = draft.endMinute ?: last?.endMinute
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${WeekMath.weekdayName(draft.day)} · 第${draft.start + 1}-${draft.end + 1}节" +
                    if (startMinute != null && endMinute != null) "（${clock(startMinute)}-${clock(endMinute)}）" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!draft.location.isNullOrBlank()) {
                Text(
                    draft.location!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                draft.weekSet.describe(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除该安排", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 单条安排的编辑器：星期 + 节次（滚动列表点首尾选连堂）+ 周次 + 该时段的上课地点。
 */
@Composable
private fun ArrangementEditor(
    initial: ArrDraft,
    periods: List<PeriodSetting>,
    totalWeeks: Int,
    locationSuggestions: List<String>, // 本课其它时段已用的地点，供点选复用
    onBack: () -> Unit,
    onSave: (ArrDraft) -> Unit,
    onDelete: () -> Unit,
) {
    // 星期/周次/节次都沿用当前值（进来先看到现状，点一下即可改）；
    // 只有「＋ 添加一段时间」传进来的占位草稿 start<0，才从"未选"开始
    var day by remember { mutableIntStateOf(initial.day.coerceIn(1, 7)) }
    var weekSet by remember { mutableStateOf(initial.weekSet) }
    var locationText by remember { mutableStateOf(initial.location.orEmpty()) }
    var start by remember { mutableIntStateOf(initial.start) }
    var end by remember { mutableIntStateOf(initial.end) }
    var spanSet by remember { mutableStateOf(initial.start >= 0) }
    var useImportedClock by remember { mutableStateOf(initial.startMinute != null && initial.endMinute != null) }

    val valid = periods.isNotEmpty() && spanSet && !weekSet.isEmpty

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("上课安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SectionLabel("星期")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        val sel = day == d
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (sel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .clickable { day = d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                WeekMath.weekdayChar(d),
                                fontSize = 13.sp,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                SectionLabel("第几节到第几节")
                PeriodSpanPicker(
                    periods = periods,
                    start = start,
                    end = end,
                    onChange = { s, e ->
                        start = s
                        end = e
                        spanSet = true
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (!spanSet) "上下滑动浏览；点一下定起点，再点一下定终点，即为连堂范围"
                    else "已选：第${start + 1}${if (end > start) "-${end + 1}" else ""}节；重选请再点起点、终点",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (spanSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                SectionLabel("哪些周开课")
                if (useImportedClock && initial.startMinute != null && initial.endMinute != null) {
                    Text("文件中的上课时间：${clock(initial.startMinute)}–${clock(initial.endMinute)}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { useImportedClock = false }) { Text("改用作息时间表") }
                }
                WeekPicker(totalWeeks = totalWeeks, value = weekSet, onChange = { weekSet = it })
                Spacer(Modifier.height(20.dp))

                SectionLabel("上课地点")
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("地点（可选）") },
                    placeholder = { Text("例如 研C102") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (locationSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "从其它时段选用：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        locationSuggestions.forEach { s ->
                            Surface(
                                onClick = { locationText = s },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    s,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // 底部操作：删除（仅已有安排时）+ 完成
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (initial.arrId > 0) {
                        Surface(
                            onClick = { onDelete() },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                Modifier.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            val keepClock = useImportedClock && start == initial.start && end == initial.end
                            onSave(ArrDraft(initial.arrId, day, start, end, weekSet, locationText.trim().ifBlank { null },
                                if (keepClock) initial.startMinute else null, if (keepClock) initial.endMinute else null))
                        },
                        enabled = valid,
                        shape = RoundedCornerShape(12.dp),
                        color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text("完成", color = if (valid) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
    }
}

/** 节次连堂选择：滚动列表点两次（起点/终点）。 */
@Composable
private fun PeriodSpanPicker(
    periods: List<PeriodSetting>,
    start: Int,
    end: Int,
    onChange: (start: Int, end: Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Int?>(null) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(periods) { p ->
            val idx = p.order
            val chosen = start >= 0
            val inSpan = chosen && idx >= start && idx <= end
            val isAnchor = idx == anchor
            Surface(
                onClick = {
                    val a = anchor
                    if (a == null) {
                        anchor = idx // 只定起点，不高亮成已选
                    } else {
                        val lo = minOf(a, idx)
                        val hi = maxOf(a, idx)
                        anchor = null
                        onChange(lo, hi)
                    }
                },
                shape = RoundedCornerShape(10.dp),
                color = when {
                    inSpan -> MaterialTheme.colorScheme.primaryContainer
                    isAnchor -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "第${idx + 1}节", // 节次按顺序编号，不再使用自定义名称
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (inSpan || isAnchor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (inSpan) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isAnchor) "起点" else "${clock(p.startMinute)} - ${clock(p.endMinute)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inSpan || isAnchor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return Color(r, g, b, 255)
}

/** 滑动选色器：SV 平面自由拖动 + 底部色相滑动条（常用画板式） */
@Composable
private fun ColorPickerDialog(
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    var hue by remember { mutableStateOf(0.0f) }
    var sat by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }
    var svW by remember { mutableStateOf(1) }
    var svH by remember { mutableStateOf(1) }
    var hueW by remember { mutableStateOf(1) }

    fun updateSv(x: Float, y: Float) {
        sat = (x / svW.coerceAtLeast(1)).coerceIn(0f, 1f)
        value = (1f - y / svH.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    fun updateHue(x: Float) {
        hue = ((x / hueW.coerceAtLeast(1)).coerceIn(0f, 1f) * 360f) % 360f
    }

    val hueColors = remember { (0..6).map { hsvToColor(it * 60f, 1f, 1f) } }
    val pure = hsvToColor(hue, 1f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(hsvToColor(hue, sat, value))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatColorHex(hsvToColor(hue, sat, value)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // SV 平面
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, pure)))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .onSizeChanged { svW = it.width; svH = it.height }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> updateSv(p.x, p.y) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateSv(change.position.x, change.position.y)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (sat * svW).roundToInt(),
                                    ((1f - value) * svH).roundToInt(),
                                )
                            }
                            .size(18.dp)
                            .offset(x = (-9).dp, y = (-9).dp)
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 色相滑条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(hueColors))
                        .onSizeChanged { hueW = it.width }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> updateHue(p.x) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateHue(change.position.x)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    ((hue / 360f) * hueW).roundToInt(),
                                    0,
                                )
                            }
                            .size(width = 3.dp, height = 24.dp)
                            .background(Color.White)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(hsvToColor(hue, sat, value)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
