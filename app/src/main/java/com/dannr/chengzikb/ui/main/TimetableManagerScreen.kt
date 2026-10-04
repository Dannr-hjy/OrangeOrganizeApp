package com.dannr.chengzikb.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.model.Timetable
import kotlinx.coroutines.launch

/**
 * 课表管理页：切换 / 新建 / 重命名 / 删除课表。
 * 点某一行＝切换到它并返回课表；新建默认继承当前课表的作息与学期设置。
 */
@Composable
fun TimetableManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val manager = app.container.timetableManager
    val tables = manager.tables.collectAsStateWithLifecycle(initialValue = emptyList()).value
    val activeId = manager.activeId.collectAsStateWithLifecycle(initialValue = 0L).value
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Timetable?>(null) }
    var deleting by remember { mutableStateOf<Timetable?>(null) }
    var showDup by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text("课表管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "每个课表有独立的课程、作息与设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tables, key = { it.id }) { t ->
                val isActive = t.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = {
                            if (!isActive) scope.launch { manager.activate(t.id) }
                            onBack()
                        },
                        color = Color.Transparent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isActive) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "使用中",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { renaming = t }) {
                        Icon(Icons.Default.Edit, contentDescription = "重命名", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    if (tables.size > 1) {
                        IconButton(onClick = { deleting = t }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            item {
                if (tables.size <= 1) {
                    Text(
                        "当前仅有一个课表（至少保留一个）。可新建多个课表分别排不同学期/校历。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = { creating = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("＋ 新建课表", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "新建课表",
            initial = "课表 ${tables.size + 1}",
            onDismiss = { creating = false },
            onConfirm = { name ->
                scope.launch {
                    val id = manager.create(name)
                    if (id < 0L) showDup = true else creating = false
                }
            },
        )
    }
    renaming?.let { t ->
        NameDialog(
            title = "重命名课表",
            initial = t.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                scope.launch {
                    val ok = manager.rename(t.id, name)
                    renaming = null
                    if (!ok) showDup = true
                }
            },
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除课表") },
            text = { Text("删除「${t.name}」及其全部课程与作息？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { manager.delete(t.id) }
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (showDup) {
        AlertDialog(
            onDismissRequest = { showDup = false },
            title = { Text("名称重复") },
            text = { Text("已有同名课表，请换一个名称。") },
            confirmButton = { TextButton(onClick = { showDup = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("课表名称") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
