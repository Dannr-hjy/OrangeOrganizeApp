package com.dannr.chengzikb.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.backup.BackupManager
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入预览信息 */
private data class ImportPreview(val text: String, val tables: Int, val periods: Int, val courses: Int)

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val db = app.container.database
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ImportPreview?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExport
        pendingExport = null
        if (uri != null && json != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                            ?: error("无法写入文件")
                    }
                }.onSuccess { snackbar.showSnackbar("备份已导出") }
                    .onFailure { snackbar.showSnackbar("导出失败：${it.message}") }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("无法读取文件")
                    }
                }.getOrNull()
                if (text == null) {
                    snackbar.showSnackbar("读取备份失败")
                    return@launch
                }
                runCatching {
                    val tables = BackupManager.parse(text)
                    pendingImport = ImportPreview(
                        text = text,
                        tables = tables.size,
                        periods = tables.sumOf { it.periods.size },
                        courses = tables.sumOf { it.courses.size },
                    )
                }.onFailure { snackbar.showSnackbar("备份无效：${it.message}") }
            }
        }
    }

    fun doExport() {
        if (busy) return
        scope.launch {
            busy = true
            val json = runCatching { BackupManager.export(db) }.getOrNull()
            busy = false
            if (json == null) {
                snackbar.showSnackbar("导出失败：读取数据出错")
            } else {
                val name = "橙子课表备份-${LocalDate.now().year}${LocalDate.now().monthValue}${LocalDate.now().dayOfMonth}.json"
                pendingExport = json
                exportLauncher.launch(name)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 标题栏自带 statusBarsPadding，避免双重顶距
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
                Text("备份 / 恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                ActionCard(
                    title = "导出备份",
                    subtitle = "把作息表、学期设置与全部课程保存为一个 JSON 文件，可分享或留存。",
                    onClick = { doExport() },
                    enabled = !busy,
                    button = "导出",
                )
                Spacer(Modifier.height(14.dp))
                ActionCard(
                    title = "恢复备份",
                    subtitle = "选择一个橙子课表备份文件，将整体替换当前数据（先请确认）。",
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    button = "选择文件",
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "提示：恢复会覆盖当前全部课程与作息。建议先导出旧数据再恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingImport?.let { preview ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("恢复备份") },
            text = {
                Text(
                    "将导入 ${preview.tables} 个课表（共 ${preview.periods} 个作息节次、" +
                        "${preview.courses} 门课程），并覆盖当前全部课表。继续吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        runCatching { BackupManager.import(db, preview.text) }
                            .onSuccess { firstId ->
                                // 恢复会重建全部课表，让内存态切到备份里的第一个课表
                                scope.launch { app.container.timetableManager.activate(firstId) }
                                snackbar.showSnackbar("已恢复 ${preview.tables} 个课表")
                            }
                            .onFailure { snackbar.showSnackbar("恢复失败：${it.message}") }
                    }
                }) { Text("恢复", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean,
    button: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}
