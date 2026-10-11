package com.dannr.chengzikb.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayVariant
import com.dannr.chengzikb.domain.DayVariants
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
 * @param variantsFor 取某星期几在本学期的课表差异分组（0/1 组 = 各周一致，无需选周；
 *   ≥2 组时要求用户选复制哪一周）。关自动调休时也要能用，故由调用方独立提供。
 * @param dateWeek [date] 所在的学期周号（1 基；未知传 0）。选周默认值用它挑「当天所在的周」那一组。
 * @param onConfirm 回调参数 null 表示清除该日手动设置
 */
@Composable
fun DayOverrideDialog(
    date: LocalDate,
    current: DayOverride?,
    autoPlan: DayPlan,
    variantsFor: (dayOfWeek: Int) -> List<DayVariant> = { emptyList() },
    dateWeek: Int = 0,
    onConfirm: (DayOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    val realDay = WeekMath.dayIndexOf(date)
    val autoFollow = autoPlan as? DayPlan.Follow

    /** 自动方案判定的调休补课目标星期（目标星期 ≠ 当天真实星期，见 HolidaySchemes）；不是补课日则为 null。 */
    val autoMakeupDay = autoFollow?.takeIf { it.dayOfWeek != realDay }?.dayOfWeek

    val initFollowDay = remember(date, current) {
        current?.takeIf { it.kind == DayOverride.KIND_FOLLOW }?.followDayOfWeek
            ?: autoMakeupDay
            ?: realDay
    }
    var mode by remember(date, current) {
        mutableIntStateOf(
            when (current?.kind) {
                DayOverride.KIND_REST -> MODE_REST
                DayOverride.KIND_FOLLOW -> MODE_FOLLOW
                else -> MODE_DEFAULT
            },
        )
    }
    var followDay by remember(date, current) { mutableStateOf(initFollowDay) }
    var showWeekPicker by remember(date) { mutableStateOf(false) }

    /**
     * 选周的默认值：多组时优先沿用已存周次，其次沿用自动方案定下的周次；
     * 自动方案没指定周次（当天所在的周本来就有课）时取「离当天所在周最近」的那一组。
     * 普通日子上新建的手动改法仍留空强制选择。
     */
    fun defaultWeekFor(d: Int): Int? {
        val vs = variantsFor(d)
        if (vs.size < 2) return null
        val kept = current?.takeIf { it.kind == DayOverride.KIND_FOLLOW }?.followWeek
        if (kept != null && vs.any { kept in it.weeks }) return kept
        val autoWeek = autoFollow?.takeIf { it.dayOfWeek == d }?.sourceWeek
        // d == autoMakeupDay 即内置方案判定的调休补课日（见 HolidaySchemes）：自动方案已替用户做过决定
        if (current != null || d == autoMakeupDay) {
            return autoWeek?.takeIf { w -> vs.any { w in it.weeks } }
                ?: DayVariants.nearestWeek(vs, dateWeek)
        }
        return null
    }

    /**
     * 落库时该存哪个复制周次：该星期有多套课表 → 存 [week]（用户选的或自动挑的）；
     * 只有一套 → 沿用自动方案为这天指定的周次（自动可能因「当天所在的周没课」而指定过），
     * 都没有则 null = 跟随当天所在的周。
     */
    fun storedWeekFor(d: Int, week: Int?): Int? {
        val vs = variantsFor(d)
        if (vs.size >= 2) return week
        return autoFollow?.takeIf { it.dayOfWeek == d }?.sourceWeek
    }

    var selectedWeek by remember(date, current) { mutableStateOf(defaultWeekFor(initFollowDay)) }
    val chosenWeek = selectedWeek

    val variants = if (mode == MODE_FOLLOW) variantsFor(followDay) else emptyList()
    val needWeekChoice = mode == MODE_FOLLOW && variants.size >= 2

    // 自动调休已经替这一天挑好了一周。即便停在「按正常课表」，也要把「复制哪一周」摆出来，
    // 否则用户看不到自动挑的是哪一周、更没法改。（该星期只有一套课表时本就无从选择，不显示。）
    val autoWeekVariants = autoMakeupDay?.let { variantsFor(it) }.orEmpty()
    val showAutoWeekChoice = mode == MODE_DEFAULT && autoWeekVariants.size >= 2
    /** 二级弹窗要列的那组课表：手动按星期时是选中的那天，否则是自动调休的目标星期 */
    val pickerVariants = if (mode == MODE_FOLLOW) variants else autoWeekVariants

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
                if (showAutoWeekChoice) {
                    WeekChoiceRow(
                        variants = autoWeekVariants,
                        chosenWeek = chosenWeek,
                        onClick = { showWeekPicker = true },
                    )
                }
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
                                onClick = {
                                    followDay = d
                                    selectedWeek = defaultWeekFor(d)
                                },
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
                    if (needWeekChoice) {
                        Spacer(Modifier.height(4.dp))
                        WeekChoiceRow(
                            variants = variants,
                            chosenWeek = chosenWeek,
                            onClick = { showWeekPicker = true },
                        )
                    }
                    Text(
                        "当天将显示「${WeekMath.weekdayName(followDay)}」的课程" +
                            (chosenWeek?.let { w ->
                                variants.firstOrNull { w in it.weeks }?.let { "（${it.label}那一段）" }.orEmpty()
                            }) + "。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !(needWeekChoice && selectedWeek == null),
                onClick = {
                    when (mode) {
                        MODE_REST -> onConfirm(
                            DayOverride(0L, date.toEpochDay(), DayOverride.KIND_REST),
                        )
                        MODE_FOLLOW -> onConfirm(
                            DayOverride(
                                timetableId = 0L,
                                dateEpochDay = date.toEpochDay(),
                                kind = DayOverride.KIND_FOLLOW,
                                followDayOfWeek = followDay,
                                followWeek = storedWeekFor(followDay, selectedWeek),
                            ),
                        )
                        else -> onConfirm(null)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // 选周列表可能很长（一学期好几段），单独放一级弹窗并限高滚动，
    // 免得把主弹窗撑到超出屏幕、下面几行选项被压掉看不到。
    if (showWeekPicker) {
        AlertDialog(
            onDismissRequest = { showWeekPicker = false },
            title = { Text("复制哪一周的课表？", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "「${WeekMath.weekdayName(followDay)}」在学期内各周课表不一样，选一周作为当天要上的课表。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pickerVariants.forEach { v ->
                        WeekOptionRow(
                            variant = v,
                            selected = selectedWeek == v.representativeWeek,
                            onClick = {
                                selectedWeek = v.representativeWeek
                                // 从「按正常课表」直接改周次 = 明确要按自动那天的课表上课，
                                // 切到该模式，好让界面如实反映这是一次手动改法。
                                if (mode != MODE_FOLLOW) {
                                    autoMakeupDay?.let { followDay = it }
                                    mode = MODE_FOLLOW
                                }
                                showWeekPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showWeekPicker = false }) { Text("取消") } },
        )
    }
}

/**
 * 「复制哪一周的课表」的**入口行**——点它进二级弹窗。
 *
 * 它是某张一级卡片的「下一层」，不能再套用一级那种整块填充的卡片，否则两层看起来一样、
 * 分不出哪个是选项哪个是入口。改用本 App 既有的下钻行样式（扁平 + 缩进 + 右侧箭头图标，
 * 见 SettingsScreen 的 NavRow），一眼就看出它挂在上面那张卡片底下。
 */
@Composable
private fun WeekChoiceRow(
    variants: List<DayVariant>,
    chosenWeek: Int?,
    onClick: () -> Unit,
) {
    val chosen = chosenWeek?.let { w -> variants.firstOrNull { w in it.weeks } }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                "复制哪一周的课表",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                chosen?.let { "${it.label} · ${variantPreview(it)}" }
                    ?: "该星期的课表各周不一样，点击选择",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 二级弹窗里的一个周次选项。刻意不走一级那种「整块填充的卡片」，改用**单选列表**样式
 * （前置圆点 + 透明底 + 选中时主色文字），好让"从列表里挑一个"和"选上法"两层一眼分得开。
 */
@Composable
private fun WeekOptionRow(
    variant: DayVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    variant.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    variantPreview(variant),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 选周项的副标题：代表周该星期上哪些课 */
private fun variantPreview(v: DayVariant): String {
    val names = v.sample.map { it.course.displayName(useShortName = false) }.distinct()
    if (names.isEmpty()) return "该周无课"
    val head = names.take(3).joinToString("、")
    return if (names.size > 3) "$head 等 ${names.size} 门" else head
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
