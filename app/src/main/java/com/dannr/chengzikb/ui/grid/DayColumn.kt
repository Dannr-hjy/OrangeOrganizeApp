package com.dannr.chengzikb.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.domain.WeekView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 课程卡之间统一的纵向间距（dp）。卡上下各留其一半，卡与相邻卡间距即为其值。 */
private const val CARD_GAP_DP = 6f

/** 显示周末（列变窄）时的横向卡间距：比 [CARD_GAP_DP] 小得多，把宽度尽量让给课名。 */
private const val CARD_HGAP_NARROW_DP = 3f

/** 拖拽结束后待判定的落点（交由 LaunchedEffect 异步判定 + 弹回动画） */
private data class DropCandidate(
    val course: Course,
    val session: CourseSession,
    val toDay: Int,
    val toStart: Int,
    val from: Offset,
    val setOffset: (Offset) -> Unit,
    val setDragging: (Boolean) -> Unit,
)

/**
 * 一天列：底层节次空白带，顶层课程卡。
 * 课程卡点击/拖动（与最初版本一致，无放大/阴影/落位蒙版等效果）：
 *  - 点按卡片 → 详情弹窗（onCourseInfo，由 CourseBlock 的 clickable 触发）；
 *  - 长按后拖动 → 调课（detectDragGesturesAfterLongPress：跟手移动、松手判定落位，冲突则弹回）。
 *
 * 调休相关：[column] 是视觉列号（1..shownDays，只用于把横向位移换算成列），
 * [effective] 是该列实际按哪天的课表上课、内容取哪一周——空白格排课要落在它上面，而不是列号上。
 * [effectiveDayOf] 把落点列号翻成生效星期，null = 不接受排课（休息/待选/内容不是本页周）。
 * 只有「按真实星期且内容就是本页周」的列才可交互：其余（休息、待选、复制了别周的课表）
 * 整列不可排课/拖拽，避免把改动写到错误的周次上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayColumn(
    column: Int,
    effective: EffectiveDay,
    pageWeek: Int,
    periods: List<PeriodSetting>,
    tops: List<Dp>,
    placements: List<WeekView.PlacedCourse>,
    isTodayColumn: Boolean,
    shownDays: Int,
    showLocation: Boolean,
    showTeacher: Boolean,
    showShortName: Boolean,
    effectiveDayOf: (Int) -> Int?,
    onEmptySlotLongPress: (day: Int, periodIndex: Int) -> Unit,
    onCourseInfo: (WeekView.PlacedCourse) -> Unit,
    onCourseDrag: suspend (Course, CourseSession, toDay: Int, toStart: Int) -> Boolean,
    draggingId: Long?,
    onDragActive: (Long?) -> Unit,
) {
    // 无课内容的列（休息/待选）整列灰底；指定了复制周次的列仍有课，只是不可交互
    val blank = effective !is EffectiveDay.Follow
    val interactive = effective is EffectiveDay.Follow && effective.contentWeek == pageWeek
    val followDay = (effective as? EffectiveDay.Follow)?.dayOfWeek ?: column
    val tint = when {
        blank -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        isTodayColumn -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val bandAlt = if (isTodayColumn && !blank) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
    } else {
        Color.Transparent
    }
    val haptic = LocalHapticFeedback.current
    val pressFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)

    var dropReq by remember { mutableStateOf<DropCandidate?>(null) }

    LaunchedEffect(dropReq) {
        val c = dropReq ?: return@LaunchedEffect
        val allowed = onCourseDrag(c.course, c.session, c.toDay, c.toStart)
        if (allowed) {
            // 上层已乐观落位到新格子，这里直接释放拖动偏移
            c.setOffset(Offset.Zero)
        } else {
            // 冲突 → 从停留处弹回原处（缓出 + 轻微过冲）
            val n = 14
            for (i in 1..n) {
                val x = i.toFloat() / n
                val ease = 1f - (1f - x) * (1f - x)
                val bounce = (1f + 0.15f * (1f - x)) * ease
                c.setOffset(Offset(c.from.x * (1f - bounce), c.from.y * (1f - bounce)))
                delay(12L)
            }
            c.setOffset(Offset.Zero)
        }
        c.setDragging(false)
        onDragActive(null)
        dropReq = null
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(tint),
    ) {
        val density = LocalDensity.current
        val w = (constraints.maxWidth / density.density).dp
        // 显示周末 → 列更窄：横向间距收紧、圆角变小、字号放大（见 CourseBlock 的 narrow）
        val narrow = shownDays >= 6
        val hGap = if (narrow) CARD_HGAP_NARROW_DP else CARD_GAP_DP
        val vInset = CARD_GAP_DP / 2f // 卡片上下留白 = 半格间距
        val hInset = hGap / 2f        // 卡片左右留白 = 半格横向间距

        // 空白时段带（休息日整列不可交互：当天没课，排了也不会显示）
        periods.forEachIndexed { idx, p ->
            val dur = GridSizes.dpForMinutes(p.endMinute - p.startMinute)
            val interaction = remember(idx) { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val band = Modifier
                .offset(x = 0.dp, y = tops[idx])
                .width(w)
                .height(dur)
                .background(if (pressed) pressFill else if (idx % 2 == 0) bandAlt else Color.Transparent)
            Box(
                modifier = if (!interactive) {
                    band
                } else {
                    band.combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // 传给上层的是**生效星期**：调休日排在周六列的课，其实属于周一
                            onEmptySlotLongPress(followDay, idx)
                        },
                    )
                },
            )
        }

        fun periodIndexAt(contentY: Float): Int {
            var hit = periods.lastIndex
            for (idx in periods.indices) {
                val dur = GridSizes.dpForMinutes(periods[idx].endMinute - periods[idx].startMinute)
                if (contentY >= tops[idx].value && contentY < tops[idx].value + dur.value) return idx
                if (contentY < tops[idx].value) { hit = idx; break }
            }
            return hit.coerceIn(0, periods.lastIndex)
        }

        for (placed in placements) {
            val s = placed.session
            if (s.startPeriodIdx < 0 || s.endPeriodIdx >= periods.size) continue
            val top = tops[s.startPeriodIdx]
            val h = GridSizes.spanHeight(periods, s.startPeriodIdx, s.endPeriodIdx)
            // 卡片在其所在 lane 内两端各留 hGap/2 → 相邻列/相邻 lane 卡间距 = hGap
            val laneW = w / placed.lanes
            val cardW = (laneW.value - hGap).coerceAtLeast(8f)
            val x = w.value * placed.lane / placed.lanes + hInset
            val canDrag = !placed.ghost

            val drag = remember(s.id) { mutableStateOf(Offset.Zero) }
            val isDragging = remember(s.id) { mutableStateOf(false) }
            val dragOffset = drag.value

            Box(
                modifier = Modifier
                    .offset(x = x.dp, y = top + vInset.dp)
                    .width(cardW.dp)
                    .height(h - CARD_GAP_DP.dp)
                    .zIndex(if (isDragging.value) 6f else 0f)
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                    .then(
                        if (canDrag && interactive) {
                            Modifier.pointerInput(s.id, column, periods, placements) {
                                var accX = 0f
                                var accY = 0f
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isDragging.value = true
                                        onDragActive(s.id)
                                        accX = 0f
                                        accY = 0f
                                        drag.value = Offset.Zero
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        accX += amount.x
                                        accY += amount.y
                                        drag.value = Offset(accX, accY)
                                    },
                                    onDragEnd = {
                                        val dx = with(density) { accX.toDp().value }
                                        val dy = with(density) { accY.toDp().value }
                                        val toCol = (column + (dx / w.value).roundToInt()).coerceIn(1, shownDays)
                                        val toStart = periodIndexAt(top.value + vInset + dy)
                                        val from = drag.value
                                        // 落点列 → 生效星期；落在休息日或原地不动都直接弹回
                                        val toDay = effectiveDayOf(toCol)
                                        if (from == Offset.Zero || toDay == null ||
                                            (toDay == s.dayOfWeek && toStart == s.startPeriodIdx)
                                        ) {
                                            drag.value = Offset.Zero
                                            isDragging.value = false
                                            onDragActive(null)
                                            return@detectDragGesturesAfterLongPress
                                        }
                                        // 先停在新位置不归位，等上层判定落位后再处理（避免弹窗瞬间闪回）
                                        dropReq = DropCandidate(
                                            placed.course, s, toDay, toStart, from,
                                            setOffset = { drag.value = it },
                                            setDragging = { isDragging.value = it },
                                        )
                                    },
                                    onDragCancel = {
                                        isDragging.value = false
                                        onDragActive(null)
                                        drag.value = Offset.Zero
                                    },
                                )
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CourseBlock(
                    placed = placed,
                    showLocation = showLocation,
                    showTeacher = showTeacher,
                    showShortName = showShortName,
                    narrow = narrow,
                    onClick = { onCourseInfo(placed) },
                    modifier = if (draggingId != null && placed.session.id != draggingId) Modifier.alpha(0.25f) else Modifier,
                )
            }
        }
    }
}
