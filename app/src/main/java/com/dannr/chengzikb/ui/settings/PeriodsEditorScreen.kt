package com.dannr.chengzikb.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.repo.CourseRepository
import com.dannr.chengzikb.data.repo.PeriodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PeriodsUi(
    val periods: List<PeriodSetting>,
    val sessions: List<CourseSession>,
)

class PeriodsEditorViewModel(
    private val periodRepository: PeriodRepository,
    courseRepository: CourseRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PeriodsUi(emptyList(), emptyList()))
    val state = _state

    init {
        val periodsFlow: kotlinx.coroutines.flow.Flow<List<PeriodSetting>> = periodRepository.all
        val sessionsFlow: kotlinx.coroutines.flow.Flow<List<CourseSession>> = courseRepository.allSessions
        viewModelScope.launch {
            periodsFlow.combine(sessionsFlow) { p: List<PeriodSetting>, s: List<CourseSession> -> PeriodsUi(p, s) }
                .collect { _state.value = it }
        }
    }

    fun append(name: String, startMinute: Int, endMinute: Int) {
        viewModelScope.launch { periodRepository.append(name, startMinute, endMinute) }
    }

    fun update(period: PeriodSetting) {
        viewModelScope.launch { periodRepository.update(period) }
    }

    /** @return true=删除成功；false=被安排占用 */
    suspend fun deleteSafely(period: PeriodSetting): Boolean =
        periodRepository.deleteSafely(period, _state.value.sessions)
}

@Composable
fun PeriodsEditorScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OrangeApp
    val vm: PeriodsEditorViewModel = viewModel(factory = viewModelFactory {
        initializer { PeriodsEditorViewModel(app.container.periodRepository, app.container.courseRepository) }
    })
    val ui by vm.state.collectAsStateWithLifecycle()
    val periods = ui.periods

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PeriodSetting?>(null) } // null = 关闭；带 id=0 表示新增
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = PeriodSetting(order = periods.size, name = "", startMinute = 8 * 60, endMinute = 8 * 60 + 45); showEditor = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加节次")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                Text("作息时间表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "节次按 第1、2、3…节 顺序自动编号，只需设置各节起止时间（时间会用于渲染课表与倒计时）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(periods, key = { it.id }) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = p; showEditor = true }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("第${p.order + 1}节", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(clock(p.startMinute) + " - " + clock(p.endMinute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val ok = vm.deleteSafely(p)
                                if (!ok) snackbar.showSnackbar("该节次及以上有课程占用，先删除相关课程再试")
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showEditor && editing != null) {
        PeriodEditDialog(
            initial = editing!!,
            isNew = editing!!.id == 0L,
            onDismiss = { showEditor = false },
            onSave = { s, e ->
                if (editing!!.id == 0L) vm.append("", s, e) else vm.update(editing!!.copy(startMinute = s, endMinute = e))
                showEditor = false
            },
        )
    }
}

private enum class PickField { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodEditDialog(
    initial: PeriodSetting,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (start: Int, end: Int) -> Unit,
) {
    var start by remember { mutableIntStateOf(initial.startMinute) }
    var end by remember { mutableIntStateOf(initial.endMinute) }
    var pick by remember { mutableStateOf<PickField?>(null) }
    val valid = end > start

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加节次" else "编辑节次") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { TimeFieldButton("开始", start) { pick = PickField.START } }
                    Box(Modifier.weight(1f)) { TimeFieldButton("结束", end) { pick = PickField.END } }
                }
                if (!valid) {
                    Spacer(Modifier.height(6.dp))
                    Text("结束时间需晚于开始时间", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (valid) onSave(start, end) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    pick?.let { field ->
        val cur = if (field == PickField.START) start else end
        val timeState = rememberTimePickerState(initialHour = cur / 60, initialMinute = cur % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(if (field == PickField.START) "选择开始时间" else "选择结束时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val m = timeState.hour * 60 + timeState.minute
                    if (field == PickField.START) {
                        start = m
                        if (end <= m) end = (m + 45).coerceAtMost(23 * 60 + 59)
                    } else {
                        end = m
                        if (m <= start) start = (m - 45).coerceAtLeast(0)
                    }
                    pick = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TimeFieldButton(label: String, minute: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock(minute), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
