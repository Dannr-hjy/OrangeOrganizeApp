package com.dannr.chengzikb.ui.course

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.data.model.describe

/**
 * 任意周次选择器：预设（每周/单周/双周/清空） + 逐周 toggle + 手动文本输入，三者实时同步。
 * [value] 为当前选中周集；[onChange] 上抛新周集。
 */
@Composable
fun WeekPicker(
    totalWeeks: Int,
    value: WeekSet,
    onChange: (WeekSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxWeek = totalWeeks.coerceAtLeast(1)
    var manualOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value.toText()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(next: WeekSet) {
        onChange(next)
        draft = next.toText()
        error = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // —— 预设 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("每周") { apply(WeekSet.weekly(maxWeek)) }
            PresetChip("单周") { apply(WeekSet.oddWeeks(maxWeek)) }
            PresetChip("双周") { apply(WeekSet.evenWeeks(maxWeek)) }
            PresetChip("清空") { apply(WeekSet.EMPTY) }
        }

        // —— 逐周 toggle ——
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items((1..maxWeek).toList()) { w ->
                val sel = w in value
                Surface(
                    onClick = {
                        apply(
                            WeekSet.fromIterable(
                                if (sel) value.weeks - w else value.weeks + w
                            )
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        w.toString(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // —— 汇总 & 手动 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (value.isEmpty) "（未选择任何周次）" else value.describe(),
                style = MaterialTheme.typography.labelMedium,
                color = if (value.isEmpty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (!manualOpen) draft = value.toText()
                error = null
                manualOpen = !manualOpen
            }) { Text(if (manualOpen) "收起" else "手动") }
        }

        if (manualOpen) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // 输入即实时校验，非法不提交
                    val parsed = runCatching { WeekSet.parse(it) }
                    if (parsed.isSuccess) {
                        error = null
                        val p = parsed.getOrThrow()
                        onChange(p)
                    } else {
                        error = "格式示例：1-3,5,7-20"
                    }
                },
                singleLine = true,
                isError = error != null,
                supportingText = {
                    Text(error ?: "示例：1-3,5,7-20 表示第1~3周、第5周、第7~20周")
                },
                trailingIcon = {
                    if (draft.isNotEmpty()) {
                        IconButton(onClick = {
                            draft = ""
                            apply(WeekSet.EMPTY)
                        }) { Icon(Icons.Default.Close, contentDescription = "清空") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PresetChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.height(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}
