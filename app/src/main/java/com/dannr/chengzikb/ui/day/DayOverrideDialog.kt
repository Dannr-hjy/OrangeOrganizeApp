package com.dannr.chengzikb.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate

/** 弹窗里的三种选择 */
private const val MODE_DEFAULT = 0 // 跟随默认（= 清除该日手动设置）
private const val MODE_REST = 1    // 当天无课
private const val MODE_FOLLOW = 2  // 按周 X 的课表上课

/**
 * 「编辑某一天」弹窗：课表点日期头部、以及设置里的调休方案页共用。
 *
 * @param current 该日期已有的手动覆盖；null = 尚未手动改过
 * @param autoPlan 若移除手动覆盖，这一天会怎么上（用于「跟随默认」一行的说明文案）
 * @param onConfirm 回调参数 null 表示清除该日手动设置
 */
@Composable
fun DayOverrideDialog(
    date: LocalDate,
    current: DayOverride?,
    autoPlan: DayPlan,
    onConfirm: (DayOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    val realDay = WeekMath.dayIndexOf(date)
    var mode by remember(date, current) {
        mutableIntStateOf(
            when (current?.kind) {
                DayOverride.KIND_REST -> MODE_REST
                DayOverride.KIND_FOLLOW -> MODE_FOLLOW
                else -> MODE_DEFAULT
            },
        )
    }
    var followDay by remember(date, current) {
        mutableStateOf(
            current?.takeIf { it.kind == DayOverride.KIND_FOLLOW }?.followDayOfWeek
                ?: if (autoPlan is DayPlan.Follow) autoPlan.dayOfWeek else realDay,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "编辑 ${WeekMath.shortDate(date)}（${WeekMath.weekdayName(realDay)}）",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow(
                    title = "按正常课表",
                    subtitle = autoDescription(autoPlan, realDay),
                    selected = mode == MODE_DEFAULT,
                    onClick = { mode = MODE_DEFAULT },
                )
                ChoiceRow(
                    title = "设为休息",
                    subtitle = "当天没有任何课",
                    selected = mode == MODE_REST,
                    onClick = { mode = MODE_REST },
                )
                ChoiceRow(
                    title = "按其他星期的课表上课",
                    subtitle = "选一天，当天就上那天的课",
                    selected = mode == MODE_FOLLOW,
                    onClick = { mode = MODE_FOLLOW },
                )
                if (mode == MODE_FOLLOW) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (1..7).forEach { d ->
                            val sel = d == followDay
                            Surface(
                                onClick = { followDay = d },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    WeekMath.weekdayChar(d),
                                    fontSize = 13.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Text(
                        "当天将显示「${WeekMath.weekdayName(followDay)}」的全部课程。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (mode) {
                    MODE_REST -> onConfirm(
                        DayOverride(0L, date.toEpochDay(), DayOverride.KIND_REST),
                    )
                    MODE_FOLLOW -> onConfirm(
                        DayOverride(0L, date.toEpochDay(), DayOverride.KIND_FOLLOW, followDay),
                    )
                    else -> onConfirm(null)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 「跟随默认」一行的说明：那天既没手动改、又没命中节假日方案时会是什么样 */
private fun autoDescription(autoPlan: DayPlan, realDay: Int): String = when (autoPlan) {
    is DayPlan.Rest -> "自动方案：当天放假，无课"
    is DayPlan.Follow -> if (autoPlan.dayOfWeek == realDay) {
        "自动方案：按当天（${WeekMath.weekdayName(realDay)}）课表上课"
    } else {
        "自动方案：按${WeekMath.weekdayName(autoPlan.dayOfWeek)}课表上课（调休补课）"
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
