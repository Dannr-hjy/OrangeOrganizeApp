package com.dannr.chengzikb.ui.main

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.ui.theme.courseColor

/**
 * 课程列表：每门课只出现一次（不按星期重复）。
 * 点击整行编辑该课；垃圾桶删除整门课。
 */
@Composable
fun CourseListScreen(
    courses: List<Course>,
    sessionCountOf: (courseId: Long) -> Int,
    locationSummaryOf: (courseId: Long) -> String?, // 该课各安排地点的去重摘要
    showLocation: Boolean,
    onEdit: (Course) -> Unit,
    onDeleteRequest: (Course) -> Unit,
) {
    val sorted = courses.sortedBy { it.name }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sorted.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                    Text("还没有课程，返回课表点右上角 ＋ 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(sorted, key = { it.id }) { course ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
                    .clickable { onEdit(course) }
                    .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                    val n = sessionCountOf(course.id)
                    val parts = buildList {
                        if (!course.teacher.isNullOrBlank()) add(course.teacher!!)
                        val loc = locationSummaryOf(course.id)
                        if (showLocation && !loc.isNullOrBlank()) add(loc)
                    }.toMutableList()
                    parts.add("$n 个时间段")
                    val info = parts.joinToString(" · ")
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDeleteRequest(course) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除课程", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
