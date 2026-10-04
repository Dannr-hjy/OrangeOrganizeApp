package com.dannr.chengzikb.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannr.chengzikb.OrangeApp
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.ui.theme.courseColor

/**
 * “添加到已有课程”：长按空白格 → 选已有课程时进入。
 * 点选一门课即安排到 (day, period)；若该课此时段已有安排则不重复添加，
 * 两种情况都会进入该课的编辑页（预设好新增/既有安排，供微调后保存）。
 */
@Composable
fun CoursePickerScreen(
    day: Int,
    period: Int,
    onBack: () -> Unit,
    onPick: (courseId: Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val courses by app.container.courseRepository.allCourses.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by app.container.courseRepository.allSessions.collectAsStateWithLifecycle(initialValue = emptyList())

    val sorted = courses.sortedBy { it.name }

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
                Text("添加到已有课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "将排到 ${WeekMath.weekdayName(day)} 第${period + 1}节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有课程，可返回选择「添加新课程」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sorted, key = { it.id }) { course ->
                val mine = sessions.filter { it.courseId == course.id }
                val covered = mine.any { it.dayOfWeek == day && period in it.startPeriodIdx..it.endPeriodIdx }
                CoursePickRow(
                    course = course,
                    subtitle = pickerInfo(course, mine, covered),
                    covered = covered,
                    onClick = { onPick(course.id) },
                )
            }
        }
    }
}

@Composable
private fun CoursePickRow(
    course: Course,
    subtitle: String,
    covered: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 10.dp, height = 40.dp)
                .background(courseColor(course), RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                course.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (covered) "该时段已有" else "＋ 安排",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun pickerInfo(course: Course, sessions: List<CourseSession>, covered: Boolean): String {
    val parts = buildList {
        if (!course.teacher.isNullOrBlank()) add(course.teacher!!)
        val locations = sessions.mapNotNull { it.location }.filter { it.isNotBlank() }.distinct()
        if (locations.isNotEmpty()) add(locations.joinToString("、"))
    }.toMutableList()
    if (sessions.size > 0) parts.add("已有 ${sessions.size} 段")
    val base = if (parts.isEmpty()) "暂无其他时段" else parts.joinToString(" · ")
    return base + if (covered) "；该时段已在上课" else ""
}
