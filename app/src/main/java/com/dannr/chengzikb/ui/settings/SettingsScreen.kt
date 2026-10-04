package com.dannr.chengzikb.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.notify.ReminderStatus
import com.dannr.chengzikb.util.OemGuide

/**
 * 设置页（主 Tab）：显示 / 学期作息 / 数据。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    remindEnabled: Boolean,
    remindMinutes: Int,
    reminderStatus: ReminderStatus,
    onSetRemindEnabled: (Boolean) -> Unit,
    onSetRemindMinutes: (Int) -> Unit,
    onFixExactAlarm: () -> Unit,
    onTestReminder: () -> Unit,
    onOpenTerm: () -> Unit,
    onOpenPeriods: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenIcs: () -> Unit,
    onOpenHolidays: () -> Unit,
) {
    var showMinutePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        SectionTitle("外观")
        CardGroup {
            LabeledChips(
                label = "主题",
                options = listOf("亮色" to 1, "暗色" to 2, "跟随系统" to 0),
                selected = settings.themeMode,
            ) { onChange(settings.copy(themeMode = it)) }
            Spacer(Modifier.height(8.dp))
            Text("主题色", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            ThemeColorSwatches(seedHex = settings.themeSeedHex) {
                onChange(settings.copy(themeSeedHex = it))
            }
        }

        SectionTitle("布局")
        CardGroup {
            SwitchRow(label = "显示周末", checked = settings.shownDays >= 7) {
                onChange(settings.copy(shownDays = if (it) 7 else 5))
            }
            Spacer(Modifier.height(2.dp))
            SwitchRow(label = "课程格显示地点", checked = settings.showLocation) {
                onChange(settings.copy(showLocation = it))
            }
            SwitchRow(label = "课程格显示老师", checked = settings.showTeacher) {
                onChange(settings.copy(showTeacher = it))
            }
            SwitchRow(label = "展示非本周课程", checked = settings.showOtherWeeks) {
                onChange(settings.copy(showOtherWeeks = it))
            }
        }

        SectionTitle("显示课程简称")
        CardGroup {
            SwitchRow(label = "在课表中显示简称", checked = settings.showShortNameInGrid) {
                onChange(settings.copy(showShortNameInGrid = it))
            }
            SwitchRow(label = "在桌面小组件中显示简称", checked = settings.showShortNameInWidget) {
                onChange(settings.copy(showShortNameInWidget = it))
            }
            Text(
                "课程简称在「编辑课程」页填写，可选；未设置简称的课程仍显示全称。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        SectionTitle("学期与作息")
        CardGroup {
            NavRow(Icons.Default.DateRange, "学期设置", onOpenTerm)
            NavRow(Icons.Default.Create, "作息时间表", onOpenPeriods)
            SwitchRow(label = "自动套用节假日调休", checked = settings.autoHoliday) {
                onChange(settings.copy(autoHoliday = it))
            }
            Text(
                "放假当天不排课，补课日按对应星期的课表上课。\n" +
                    "在课表里点任意一天的日期头部，可单独把它改成休息或按别天的课表上课。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            NavRow(Icons.Default.Star, "节假日调休", onOpenHolidays, last = true)
        }

        SectionTitle("桌面小组件")
        CardGroup {
            NavRow(Icons.Default.Settings, "后台运行与自启动设置", onClick = {
                // 课前提醒与小组件刷新都依赖后台存活；各 ROM 需到对应设置页放行（通用电池优化页兜底）
                if (!OemGuide.openBackgroundSettings(context)) {
                    Toast.makeText(context, "无法打开系统设置，请到 设置→应用→橙子课表 手动放行", Toast.LENGTH_LONG).show()
                }
            }, last = true)
            Text(
                "在桌面直接看到当天/下一有课日的课程与下一节提醒，会随课表与时间自动刷新；\n" +
                    "手动添加：长按桌面空白处 → 添加小组件 → 找到「橙子课表」的 2×2 组件。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        SectionTitle("课前通知")
        CardGroup {
            SwitchRow(label = "开启课前提醒（需自启动权限）", checked = remindEnabled) {
                onSetRemindEnabled(it)
            }
            if (remindEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { showMinutePicker = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("提前提醒", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "$remindMinutes 分钟",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("＞", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 排程状态 + 自查入口：提醒失效多半发生在系统层（权限被回收/被强停/改过时区），
                // 与其让用户猜"为什么没响"，不如把当前状态和一条测试提醒摆在这里
                Spacer(Modifier.height(4.dp))
                Text(
                    reminderStatusText(reminderStatus),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!reminderStatus.exactAllowed) {
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "未授予「精确闹钟」权限，提醒可能延迟",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onFixExactAlarm) { Text("去授权") }
                    }
                }
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = onTestReminder,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("发一条测试提醒", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        SectionTitle("数据")
        CardGroup {
            NavRow(Icons.Default.Share, "备份 / 恢复课表", onOpenBackup)
            NavRow(Icons.Default.Refresh, "导入课表（.ics）", onOpenIcs)
            NavRow(Icons.Default.Info, "关于", onOpenAbout, last = true)
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showMinutePicker) {
        MinutePickerDialog(
            initial = remindMinutes,
            onPick = {
                onSetRemindMinutes(it)
                showMinutePicker = false
            },
            onDismiss = { showMinutePicker = false },
        )
    }

}

/** 课前提醒的一句话状态：让“没响”这件事在设置页可见，而不是无声失效。 */
private fun reminderStatusText(s: ReminderStatus): String = when {
    s.nextText != null -> "已排入 ${s.count} 条提醒 · 最近一条：${s.nextText}"
    s.syncedAtMillis > 0L -> "当前没有可排的提醒（检查学期周次、作息时间与调休设置）"
    else -> "正在排程…"
}

/** 可选的提前提醒分钟档位 */
private val REMIND_MINUTE_OPTIONS = listOf(5, 10, 20, 30, 40, 60, 75, 90)

/** 提前提醒分钟选择器：仅提供固定档位，点选 + 确定。 */
@Composable
private fun MinutePickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // 旧值可能不在档位内（如滑杆时代存过 15），就近落到一个档位
    var selected by remember {
        mutableStateOf(REMIND_MINUTE_OPTIONS.minByOrNull { kotlin.math.abs(it - initial) } ?: 10)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提前提醒") },
        text = {
            Column {
                Text(
                    "提前多少分钟提醒上课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(REMIND_MINUTE_OPTIONS, key = { it }) { m ->
                        val sel = m == selected
                        Surface(
                            onClick = { selected = m },
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$m 分钟",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun CardGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun LabeledChips(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, value) ->
                val sel = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit, last: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
