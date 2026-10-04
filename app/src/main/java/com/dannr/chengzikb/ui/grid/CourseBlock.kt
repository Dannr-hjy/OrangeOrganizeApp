package com.dannr.chengzikb.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dannr.chengzikb.domain.WeekView
import com.dannr.chengzikb.ui.theme.courseColor
import com.dannr.chengzikb.ui.theme.courseTextColor

/**
 * 课程卡：圆角大、加粗、高饱和底色 + 亮度自适应白/黑文字。
 * 地点/老师各占一行（按设置开关）；地点取自该安排 ([WeekView.PlacedCourse.session].location)。
 * 非本周（幽灵）卡整体降透明、顶部浮一枚半透明的“非本周”小标签说明，其余信息照常显示。
 * 课名按卡宽自适应：常规列保证一行至少放下 4 个字，[narrow]（显示周末、列更窄）时改为
 * 让字号优先——每行少放一个字，换更大的字与更小的圆角，窄列里反而更好认。
 * 触摸：本卡负责“点按 → 详情”；长按拖动调课由外层 DayColumn 的拖拽手势处理。
 */
@Composable
fun CourseBlock(
    placed: WeekView.PlacedCourse,
    showLocation: Boolean,
    showTeacher: Boolean,
    showShortName: Boolean = false,
    narrow: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val course = placed.course
    // 简称开关开启且本课填了简称时用简称，否则照旧显示全称
    val title = course.displayName(showShortName)
    val location = placed.session.location // 地点逐安排
    val ghost = placed.ghost
    val fill = courseColor(course).let { if (ghost) it.copy(alpha = 0.30f) else it }
    val text = courseTextColor(course).let { if (ghost) it.copy(alpha = 0.55f) else it }
    val wide = placed.lanes == 1
    // 圆角：narrow（显示周末、列更窄）时整体收小，避免窄卡被圆角吃掉太多面积
    val corner = when {
        narrow && wide -> 11.dp
        narrow -> 7.dp
        wide -> 16.dp
        else -> 10.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .clickable(onClick = onClick),
    ) {
        // 名字号：CJK 字宽≈fontSize×fontScale，故用 (可用宽)/(每字系数×fontScale)。
        // 常规列每行保证 ≥4 字（系数 4.3）；narrow（显示周末、列窄）列固定每行 2 字（系数 2.1）——
        // 字号用**左右内边距**来调，想要小一点就加大内边距，而不是靠砍字数或改系数。
        val fontScale = LocalDensity.current.fontScale
        // 课名单独加内边距（地点/老师行用列内边距，别被一起挤到省略号）
        val colPadH = if (narrow) 2.dp else 3.dp
        val namePadH = if (narrow) 7.dp else 3.dp
        val usable = maxWidth.value - namePadH.value * 2f
        val perChar = if (narrow) 2.1f else 4.3f
        val nameCap = if (narrow) 15f else if (wide) 13f else 10.5f
        val nameBase = (usable / (perChar * fontScale)).coerceIn(8f, nameCap)
        // 单节且带地点/老师时，收紧行距确保老师/地点不被压出格底；≥2 节保持原行距
        val hasMeta = (showLocation && !location.isNullOrBlank()) || (showTeacher && !course.teacher.isNullOrBlank())
        val isShort = maxHeight.value < 80f
        val compact = isShort && hasMeta && !ghost
        // narrow 时基础字号大得多，同样的收缩比例仍会溢出，故收得更狠一点
        val nameSp = if (compact) (nameBase * if (narrow) 0.72f else 0.8f).coerceAtLeast(7.5f) else nameBase
        // 地点/老师的字号**不随 narrow 变**：基数按列宽算（不含课名的额外内边距），
        // 于是 5 天模式下与改动前完全一致，7 天模式下也不会跟着课名一起缩水。
        val metaBase = ((maxWidth.value - colPadH.value * 2f) / (4.3f * fontScale))
            .coerceIn(8f, if (wide) 13f else 10.5f)
        val metaSp = if (compact) metaBase * 0.8f else metaBase
        val lineSp = nameSp * (if (compact) 1.08f else 1.28f)
        val padV = if (compact) 1.dp else 2.dp
        // narrow 每行只有 2 个字，需要更多行；单节紧缩卡也给 2 行，否则课名几乎全被省略号吃掉
        val nameLines = when {
            compact -> if (narrow) 2 else 1
            narrow -> 3
            wide -> 2
            else -> 3
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = colPadH, vertical = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = text,
                fontSize = nameSp.sp,
                lineHeight = lineSp.sp,
                fontWeight = FontWeight.Bold,
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = namePadH - colPadH),
            )
            // 第二行：地点；第三行：老师（各自一行）
            if (showLocation && !location.isNullOrBlank()) {
                Text(
                    text = location,
                    color = text.copy(alpha = 0.92f),
                    fontSize = (metaSp * 0.78f).coerceIn(6f, 10.5f).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (showTeacher && !course.teacher.isNullOrBlank()) {
                Text(
                    text = course.teacher,
                    color = text.copy(alpha = 0.85f),
                    fontSize = (metaSp * 0.68f).coerceIn(6f, 9.5f).sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 非本周说明标签（半透明胶囊，浮于卡片顶部）
        if (ghost) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.26f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "非本周",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
