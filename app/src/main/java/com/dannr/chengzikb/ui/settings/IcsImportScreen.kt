package com.dannr.chengzikb.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.import.IcsImporter
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.domain.WeekMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 确认学期后选择 ICS，预览节次、真实钟点和可选的完整作息，再写入课表。
 */
@Composable
fun IcsImportScreen(
    onBack: () -> Unit,
    onOpenTerm: () -> Unit,
    onOpenPeriods: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val db = app.container.database
    val manager = app.container.timetableManager
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<IcsImporter.IcsResult?>(null) }
    var overwriteConfirm by remember { mutableStateOf(false) }
    var importedInfo by remember { mutableStateOf<IcsImporter.IcsResult?>(null) } // 导入成功提示
    var targetHasCourses by remember { mutableStateOf(false) }

    // 学期日期决定周次；文件中的钟点可更新作息，无需预先手工逐节填写。
    val curSettings by app.container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val step1Ready = curSettings != null &&
        (curSettings?.totalWeeks ?: 0) > 0 &&
        (curSettings?.termStartEpochDay ?: 0L) > 0L

    suspend fun currentHasCourses(): Boolean {
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
        return db.courseDao().getAllByTimetable(active).isNotEmpty()
    }

    suspend fun uniqueNewTableName(): String {
        val taken = db.timetableDao().getAll().map { it.name.trim() }.toSet()
        var n = 1
        while (true) {
            val candidate = if (n == 1) "导入课表" else "导入课表 $n"
            if (candidate !in taken) return candidate
            n++
        }
    }

    fun doImport(result: IcsImporter.IcsResult, overwriteCurrent: Boolean, createNew: Boolean) {
        scope.launch {
            busy = true
            val out = runCatching {
                withContext(Dispatchers.IO) {
                    if (createNew) {
                        val name = uniqueNewTableName()
                        val id = manager.create(name)
                        if (id < 0L) error("创建课表失败（名称重复？）")
                    }
                    if (overwriteCurrent || createNew) IcsImporter.overwriteActive(db, result)
                    else IcsImporter.write(db, result)
                }
            }
            busy = false
            parsed = null
            overwriteConfirm = false
            out.onSuccess {
                importedInfo = result
            }.onFailure { snackbar.showSnackbar("导入失败：${it.message}") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取文件")
                }
            }.getOrNull()
            if (text == null) {
                busy = false
                snackbar.showSnackbar("读取文件失败")
                return@launch
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val result = IcsImporter.buildForActive(db, text)
                    result to currentHasCourses()
                }
            }
            busy = false
            outcome.onSuccess { (result, hasCourses) ->
                if (result.courses.isEmpty()) {
                    snackbar.showSnackbar(result.warnings.firstOrNull() ?: "未识别到本学期课程，请检查文件与学期日期")
                } else {
                    targetHasCourses = hasCourses
                    parsed = result
                }
            }.onFailure { snackbar.showSnackbar("解析失败：${it.message}") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                Text("导入课表（.ics）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(470)) { it * dir } + fadeIn(tween(240)))
                        .togetherWith(slideOutHorizontally(tween(400)) { -it * dir } + fadeOut(tween(170)))
                },
                label = "icsStep",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { s ->
                if (s == 1) {
                    // —— 第 1 步：完善课表设置 ——
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        ) {
                            SectionHeading("第 1 步 · 完善课表设置")
                            Text(
                                "确认开学日期与总周数。导入后会按文件中的上课钟点更新作息；连堂课沿用当前单节时长与课间间隔。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    GuideRow("学期设置", "开学第一周与总周数", onOpenTerm)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    GuideRow("作息时间表", "用于识别节次和课间间隔，文件钟点将在导入时更新", onOpenPeriods)
                                }
                            }
                            if (!step1Ready) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "请先配置学期开始日期与总周数。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        Surface(
                            onClick = { if (step1Ready) step = 2 },
                            enabled = step1Ready,
                            shape = RoundedCornerShape(12.dp),
                            color = if (step1Ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "下一步",
                                    color = if (step1Ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else {
                    // —— 第 2 步：选择文件导入 ——
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        TextButton(onClick = {
                            step = 1
                            parsed = null // 回上一步改了设置后需重新选文件
                        }) { Text("‹ 上一步", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                        SectionHeading("第 2 步 · 选择文件导入")
                        Text(
                            "支持 WakeUp 等导出的 .ics。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("选择 .ics 文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (busy) "正在读取…" else "先预览课程、节次与上课时间，再确认导入",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("选择") }
                            }
                        }

                        importedInfo?.let { r ->
                            Spacer(Modifier.height(12.dp))
                            Box(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
                            ) {
                                Text(
                                    "✓ 已导入 ${r.courses.size} 门课程 · ${r.sessionCount} 个时间段" +
                                        (if (r.periods.isNotEmpty()) "，作息时间表已更新。" else "，课程钟点已保存。"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        parsed?.let { r ->
                            Spacer(Modifier.height(18.dp))
                            Text("解析结果", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("识别到 ${r.courses.size} 门课程、${r.sessionCount} 个时间段，已读取课程起止时间。请选择导入方式：", style = MaterialTheme.typography.bodyMedium)
                            if (r.periods.isNotEmpty()) {
                                Text(if (r.periodsAligned) "将按文件中的课程钟点更新作息时间表，单节时长与课间间隔沿用当前作息。"
                                    else "文件包含 ${r.periods.size} 节完整作息，将随课程一起导入。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (r.periodNotes.isNotEmpty()) {
                                Text(r.periodNotes.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (r.warnings.isNotEmpty()) {
                                Text("以下内容未能导入：\n" + r.warnings.take(5).joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(10.dp))

                            Surface(
                                onClick = {
                                    if (targetHasCourses) overwriteConfirm = true
                                    else doImport(r, overwriteCurrent = true, createNew = false)
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center) {
                                    Text(if (targetHasCourses) "覆盖当前课表" else "导入当前课表", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                onClick = { doImport(r, overwriteCurrent = false, createNew = true) },
                                enabled = !busy,
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center) {
                                    Text("新建课表并导入", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(16.dp))

                            r.courses.take(30).forEach { c ->
                                val first = c.sessions.firstOrNull()
                                val detail = first?.let {
                                    "${WeekMath.weekdayName(it.day)} 第${it.startPeriodIdx + 1}${if (it.endPeriodIdx > it.startPeriodIdx) "-${it.endPeriodIdx + 1}" else ""}节" +
                                        if (it.startMinute != null && it.endMinute != null) "\n${importClock(it.startMinute)}–${importClock(it.endMinute)}" else ""
                                } ?: ""
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (r.courses.size > 30) {
                                Text("…等共 ${r.courses.size} 门", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }

    if (overwriteConfirm && parsed != null) {
        val r = parsed!!
        AlertDialog(
            onDismissRequest = { overwriteConfirm = false },
            title = { Text("覆盖当前课表") },
            text = { Text("将清空当前课表并导入 ${r.courses.size} 门课程（${r.sessionCount} 个时间段），此操作会删除当前课表的全部课程。继续吗？") },
            confirmButton = {
                TextButton(onClick = { doImport(r, overwriteCurrent = true, createNew = false) }) {
                    Text("覆盖", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { overwriteConfirm = false }) { Text("取消") } },
        )
    }
}

private fun importClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun GuideRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
