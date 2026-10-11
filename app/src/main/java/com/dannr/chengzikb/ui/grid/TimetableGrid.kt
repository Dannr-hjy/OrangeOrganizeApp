package com.dannr.chengzikb.ui.grid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.data.model.isActiveIn
import com.dannr.chengzikb.domain.DayPlan
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.WeekView
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/** 拖拽课程产生的“待移动”请求（乐观落位：选择期间课程先显示在新位置） */
private data class MoveRequest(
    val course: Course,
    val session: CourseSession,
    val toDay: Int,
    val toStart: Int,
    val toEnd: Int,
)

/**
 * 周课表：顶部“第X周+日期区间 + 状态/回到本周 + 右操作区”，下方按周横向滑动。
 * 课程卡长按可拖拽换时间：先判定能否移动（冲突则弹回），可行则乐观落位到新格子并弹“仅本周/所有周”。
 */
@Composable
fun TimetableGrid(
    occurrences: List<Occurrence>,
    periods: List<PeriodSetting>,
    termStartMonday: LocalDate,
    totalWeeks: Int,
    shownDays: Int,
    now: LocalDateTime,
    showLocation: Boolean,
    showTeacher: Boolean,
    showShortName: Boolean,
    showOtherWeeks: Boolean,
    dayPlans: DayPlanIndex,
    onEmptySlotLongPress: (day: Int, periodIndex: Int) -> Unit,
    onCourseInfo: (Occurrence) -> Unit, // 点按课程格 → 详情
    onCourseMove: suspend (session: CourseSession, day: Int, start: Int, end: Int, thisWeekOnly: Boolean, week: Int) -> Unit,
    onEditDay: (LocalDate) -> Unit = {}, // 点日期头部 → 手动调休（休息 / 按周几上课）
    initialWeek: Int? = null, // 非空=回到此前所选周（1-based）；null=默认本周
    onWeekViewed: (Int) -> Unit = {}, // 翻到某周时回调（供上层记住，切走再回来仍停在该周）
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    val pageCount = totalWeeks.coerceAtLeast(1)
    // rawTodayWeek：真实周号（不裁剪）。开学前 <1，结课后 >pageCount。
    val rawTodayWeek = WeekMath.weekIndexOf(now.toLocalDate(), termStartMonday)
    val inTerm = rawTodayWeek in 1..pageCount
    val todayWeek = rawTodayWeek.coerceIn(1, pageCount)
    val initPage = (initialWeek?.let { it - 1 } ?: (todayWeek - 1)).coerceIn(0, pageCount - 1)
    val pagerState = rememberPagerState(initialPage = initPage) { pageCount }
    val scope = rememberCoroutineScope()
    var showJump by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf<MoveRequest?>(null) }
    var preview by remember { mutableStateOf<MoveRequest?>(null) } // 乐观落位预览
    val visibleDays = shownDays.coerceIn(1, 7)

    val settledWeek = pagerState.settledPage.coerceIn(0, pageCount - 1) + 1
    LaunchedEffect(settledWeek) { onWeekViewed(settledWeek) }

    // —— 乐观落位：把被拖的那条安排(当前周)挪到新位置，其余周暂回原处 ——
    val effectiveOccurrences = preview?.let { p ->
        val allWeeks = runCatching { WeekSet.parse(p.session.activeWeeksText) }.getOrDefault(WeekSet.EMPTY).weeks
        val restWeeks = allWeeks - settledWeek
        occurrences.flatMap { o ->
            if (o.session.id != p.session.id) return@flatMap listOf(o)
            val s = o.session
            val keep = if (restWeeks.isNotEmpty()) {
                listOf(Occurrence(o.course, s.copy(activeWeeksText = WeekSet.fromIterable(restWeeks).toText())))
            } else emptyList()
            val moved = Occurrence(o.course, s.copy(dayOfWeek = p.toDay, startPeriodIdx = p.toStart, endPeriodIdx = p.toEnd, activeWeeksText = WeekSet.of(settledWeek).toText()))
            keep + moved
        }
    } ?: occurrences

    fun occupiedThisWeek(excludeId: Long, day: Int, start: Int, end: Int): Boolean =
        occurrences.any { o ->
            val s = o.session
            s.id != excludeId && s.dayOfWeek == day && s.startPeriodIdx <= end && start <= s.endPeriodIdx && s.isActiveIn(settledWeek)
        }

    // 每天实际按哪天的课表上课、取哪一周的内容。列位置仍是真实日历列，只有内容是映射后的。
    val effectiveDays: List<Map<Int, EffectiveDay>> = remember(pageCount, termStartMonday, dayPlans) {
        (0 until pageCount).map { w ->
            val pageWeek = w + 1
            val weekStart = termStartMonday.plusWeeks(w.toLong())
            (1..7).associateWith { d ->
                when (val plan = dayPlans.planFor(weekStart.plusDays((d - 1).toLong()))) {
                    is DayPlan.Rest -> EffectiveDay.Rest
                    is DayPlan.PendingWeek -> EffectiveDay.Pending(plan.dayOfWeek)
                    is DayPlan.Follow -> EffectiveDay.Follow(
                        dayOfWeek = plan.dayOfWeek,
                        // 「补哪一周」：选了具体周次就用它，否则用当天所在的周
                        contentWeek = plan.sourceWeek ?: pageWeek,
                    )
                }
            }
        }
    }

    val perWeekPlacements = remember(effectiveOccurrences, effectiveDays, showOtherWeeks) {
        (0 until pageCount).map { w ->
            effectiveDays[w].mapValues { (_, eff) ->
                when (eff) {
                    is EffectiveDay.Rest, is EffectiveDay.Pending -> emptyList()
                    is EffectiveDay.Follow -> WeekView.placementsForWeek(
                        effectiveOccurrences, eff.contentWeek, eff.dayOfWeek, showOtherWeeks,
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部：第X周(状态) + 右操作区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.clickable { showJump = true }.heightIn(min = 42.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 $settledWeek 周", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    when {
                        inTerm && settledWeek == todayWeek -> Text(
                            " · 本周",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        rawTodayWeek < 1 && settledWeek == 1 -> Text(
                            "（未开学）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        rawTodayWeek > pageCount && settledWeek == pageCount -> Text(
                            "（学期结束）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val (s0, e0) = WeekMath.dateRangeForWeek(termStartMonday, settledWeek)
                Text(
                    "${WeekMath.shortDate(s0)} - ${WeekMath.shortDate(e0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (settledWeek != todayWeek) {
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(todayWeek - 1) } }) {
                    Text("回本周", fontSize = 12.sp)
                }
            }
            headerActions()
        }

        if (periods.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("请先在设置中添加作息时间段", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            WeekPage(
                weekStart = termStartMonday.plusWeeks(page.toLong()),
                periods = periods,
                placementsByDay = perWeekPlacements[page],
                effectiveDayByDay = effectiveDays[page],
                pageWeek = page + 1,
                shownDays = visibleDays,
                isTodayWeek = page == (todayWeek - 1),
                todayDay = WeekMath.dayIndexOf(now.toLocalDate()),
                showLocation = showLocation,
                showTeacher = showTeacher,
                showShortName = showShortName,
                onEmptySlotLongPress = onEmptySlotLongPress,
                onEditDay = onEditDay,
                onCourseInfo = { pc -> onCourseInfo(Occurrence(pc.course, pc.session)) },
                onCourseDrag = { course, session, toDay, toStart ->
                    val span = session.endPeriodIdx - session.startPeriodIdx
                    val toEnd = (toStart + span).coerceAtMost(periods.lastIndex)
                    val ok = !occupiedThisWeek(session.id, toDay, toStart, toEnd)
                    if (ok) {
                        val req = MoveRequest(course, session, toDay, toStart, toEnd)
                        preview = req
                        pendingMove = req
                    }
                    ok
                },
            )
        }
    }

    if (showJump) {
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("跳到第几周？") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed((1..pageCount).toList()) { i, w ->
                        val suffix = when {
                            inTerm && w == todayWeek -> "（本周）"
                            rawTodayWeek < 1 && w == 1 -> "（未开学）"
                            rawTodayWeek > pageCount && w == pageCount -> "（学期结束）"
                            else -> ""
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(i) }
                                    showJump = false
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                "第 $w 周$suffix",
                                fontWeight = if (w == settledWeek) FontWeight.Bold else FontWeight.Normal,
                                color = if (w == settledWeek) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("取消") } },
        )
    }

    pendingMove?.let { m ->
        val startLabel = if (m.toEnd > m.toStart) "第${m.toStart + 1}-${m.toEnd + 1}节" else "第${m.toStart + 1}节"
        AlertDialog(
            onDismissRequest = {
                pendingMove = null
                preview = null
            },
            title = { Text("调整时间") },
            text = { Text("把「${m.course.name}」挪到 ${WeekMath.weekdayName(m.toDay)} $startLabel ？") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val req = m
                        pendingMove = null
                        preview = null
                        scope.launch { onCourseMove(req.session, req.toDay, req.toStart, req.toEnd, true, settledWeek) }
                    }) { Text("仅本周", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        val req = m
                        pendingMove = null
                        preview = null
                        scope.launch { onCourseMove(req.session, req.toDay, req.toStart, req.toEnd, false, settledWeek) }
                    }) { Text("所有周") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingMove = null
                    preview = null
                }) { Text("取消") }
            },
        )
    }
}
