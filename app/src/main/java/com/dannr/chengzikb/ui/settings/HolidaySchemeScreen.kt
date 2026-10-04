package com.dannr.chengzikb.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.HolidayScheme
import com.dannr.chengzikb.domain.HolidaySchemes
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.toPlan
import com.dannr.chengzikb.ui.day.DayOverrideDialog
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 节假日调休：列出内置方案的放假区间与补课日，并允许把任意一天改掉。
 * 改动落在 [DayOverride] 上（只存手动改过的日期），内置方案本身不可编辑。
 */
@Composable
fun HolidaySchemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val overrides by app.container.dayOverrideRepository.overrides
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<LocalDate?>(null) }
    val autoEnabled = settings?.autoHoliday == true

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("节假日调休", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动套用节假日调休", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (autoEnabled) "放假当天不排课，补课日按对应星期的课表上课" else "已关闭，下面仅作参考",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = { on ->
                            settings?.let { s ->
                                scope.launch { app.container.settingsRepository.save(s.copy(autoHoliday = on)) }
                            }
                        },
                    )
                }
            }

            HolidaySchemes.ALL.forEach { scheme ->
                SchemeSection(
                    scheme = scheme,
                    autoEnabled = autoEnabled,
                    overrides = overrides,
                    onEdit = { editing = it },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "补课日按哪天的课表上课，各校教务处口径并不一致（官方通知只规定哪天放假、哪天上班）。" +
                    "这里用的是多数高校的做法，请以你学校的通知为准：点任意一个补课日即可改成你学校的安排，" +
                    "也可以在课表里直接点日期头部修改。手动改过的日期始终优先于上面的自动开关。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
        }
    }

    editing?.let { date ->
        DayOverrideDialog(
            date = date,
            current = overrides.firstOrNull { it.dateEpochDay == date.toEpochDay() },
            autoPlan = DayPlanIndex.autoOnly(date, autoEnabled),
            onConfirm = { row ->
                scope.launch { app.container.dayOverrideRepository.set(date, row) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SchemeSection(
    scheme: HolidayScheme,
    autoEnabled: Boolean,
    overrides: List<DayOverride>,
    onEdit: (LocalDate) -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(
        "${scheme.year} 年放假",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            scheme.holidays.forEachIndexed { i, h ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${WeekMath.shortDate(h.start)} - ${WeekMath.shortDate(h.endInclusive)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        " ${h.days}天",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (i != scheme.holidays.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Text(
        "${scheme.year} 年补课 / 补班",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            scheme.makeupDays.forEachIndexed { i, m ->
                val realDay = WeekMath.dayIndexOf(m.date)
                val manual = overrides.firstOrNull { it.dateEpochDay == m.date.toEpochDay() }
                // 手动覆盖优先，其次内置方案。这里**恒按开启**求值：本页展示的是方案本身，
                // 若按开关求值，关掉开关时每行都会退化成「→ 按周六课表」这种废话。
                // 开关是否真的生效只体现在强调色上（见下方 inEffect）。
                val effective = manual?.toPlan() ?: DayPlanIndex.autoOnly(m.date, autoEnabled = true)
                val inEffect = manual != null || autoEnabled
                Surface(
                    onClick = { onEdit(m.date) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${WeekMath.shortDate(m.date)} ${WeekMath.weekdayName(realDay)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (manual != null) {
                                Text(
                                    "已手动修改",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        Text(
                            when (effective) {
                                is DayPlan.Rest -> "休息"
                                is DayPlan.Follow -> "→ 按${WeekMath.weekdayName(effective.dayOfWeek)}课表"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (inEffect) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            "  ＞",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (i != scheme.makeupDays.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
