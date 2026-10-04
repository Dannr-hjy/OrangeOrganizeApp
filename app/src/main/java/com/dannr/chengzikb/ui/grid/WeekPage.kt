package com.dannr.chengzikb.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.WeekView
import java.time.LocalDate

/**
 * 某一周页：顶部星期+日期栏（固定），下方整行按“节次时长累积”布局（无课间空隙），
 * 左栏=第X节 + 起止两行时间；右侧各天课程卡整体纵向滚动。
 *
 * [effectiveDayByDay]：该周每天实际按哪天的课表上课（1..7），null = 当天休息（节假日）。
 * 列的位置与日期文案始终是真实日历值，只有格子内容与是否可排课跟着它走。
 */
@Composable
fun WeekPage(
    weekStart: LocalDate,
    periods: List<PeriodSetting>,
    placementsByDay: Map<Int, List<WeekView.PlacedCourse>>,
    effectiveDayByDay: Map<Int, Int?>,
    shownDays: Int,
    isTodayWeek: Boolean,
    todayDay: Int,
    showLocation: Boolean,
    showTeacher: Boolean,
    showShortName: Boolean,
    onEmptySlotLongPress: (day: Int, periodIndex: Int) -> Unit,
    onEditDay: (LocalDate) -> Unit,
    onCourseInfo: (WeekView.PlacedCourse) -> Unit,
    onCourseDrag: suspend (course: Course, session: com.dannr.chengzikb.data.model.CourseSession, toDay: Int, toStart: Int) -> Boolean,
) {
    if (periods.isEmpty()) return
    val (tops, totalH) = GridSizes.cumulativeOffsets(periods)
    // 当前被拖动的安排 id（非空时其余课程降透明，避免遮挡被拖课程）
    val draggingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val draggingId = draggingState.value

    Column(modifier = Modifier.fillMaxSize()) {
        // —— 顶部星期 + 日期栏 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GridSizes.DateHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(GridSizes.GutterWidth))
            for (d in 1..shownDays) {
                val date = weekStart.plusDays((d - 1).toLong())
                val today = isTodayWeek && d == todayDay
                // 首行恒为**真实**星期几，不因调休改写（休/补等状态由列内容与灰底体现）。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onEditDay(date) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (today) {
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            WeekMath.weekdayChar(d),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (today) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            fontSize = 11.sp,
                            color = if (today) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        // —— 时间轴内容（占满剩余高度，内容不足时向上对齐不居中）——
        Box(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // 左时间栏：第X节 + 起/止各一行
            Box(Modifier.width(GridSizes.GutterWidth).height(totalH)) {
                periods.forEachIndexed { idx, p ->
                    val dur = GridSizes.dpForMinutes(p.endMinute - p.startMinute)
                    Column(
                        modifier = Modifier
                            .offset(x = 0.dp, y = tops[idx])
                            .height(dur)
                            .width(GridSizes.GutterWidth)
                            .padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 只显示大数字；第/节两字省去
                        Text(
                            "${idx + 1}",
                            fontSize = 17.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            clock(p.startMinute),
                            fontSize = 8.sp,
                            lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            clock(p.endMinute),
                            fontSize = 8.sp,
                            lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 各天列
            for (d in 1..shownDays) {
                val effDay = effectiveDayByDay[d]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(totalH),
                    // 课程卡横/纵间距统一由 DayColumn 内部卡片留白(CARD_GAP)决定
                ) {
                    DayColumn(
                        column = d,
                        effectiveDay = effDay ?: d,
                        isRest = effDay == null,
                        periods = periods,
                        tops = tops,
                        placements = placementsByDay[d].orEmpty(),
                        isTodayColumn = isTodayWeek && d == todayDay,
                        shownDays = shownDays,
                        showLocation = showLocation,
                        showTeacher = showTeacher,
                        showShortName = showShortName,
                        effectiveDayOf = { col -> effectiveDayByDay[col] },
                        onEmptySlotLongPress = onEmptySlotLongPress,
                        onCourseInfo = onCourseInfo,
                        onCourseDrag = onCourseDrag,
                        draggingId = draggingId,
                        onDragActive = { id -> draggingState.value = id },
                    )
                }
            }
            }

            // 休息日（放假当天）在列的纵向居中处标一个「休」。放在滚动容器**之外**，
            // 所以它钉在可视区中央、不随课表上下滚动，也不会抢走 Pager/课程卡的手势。
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(GridSizes.GutterWidth).fillMaxHeight())
                for (d in 1..shownDays) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (effectiveDayByDay[d] == null) {
                            Text(
                                "休",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
