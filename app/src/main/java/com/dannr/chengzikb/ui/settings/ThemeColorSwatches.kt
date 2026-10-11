package com.dannr.chengzikb.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.ui.theme.HSVColorPickerDialog
import com.dannr.chengzikb.ui.theme.formatColorHex
import com.dannr.chengzikb.ui.theme.mutedThemeColor
import com.dannr.chengzikb.ui.theme.parseHexColor

/** 主题色被切换多少次后解锁隐藏的炫彩主题 */
const val ThemeSwitchUnlockCount = 7

private val RainbowSweep = listOf(
    Color.Red, Color(0xFFFF9800), Color.Yellow, Color.Green,
    Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

/**
 * 主题色选择：几个预设色 + 一个打开自由色板的彩格。返回 null 表示“默认橙”。
 * [rainbowUnlocked] 为真时（切换主题色 [ThemeSwitchUnlockCount] 次后）在末尾多出一枚炫彩格；
 * [rainbowSelected] 为真时炫彩生效，全部预设色/自定义色取消选中。
 * [lowSaturation] 为真时预设格**显示**低饱和版（选了之后界面真正会变成的样子）；
 * 点选写入的仍是原始色，关掉开关即还原，不受影响。
 */
@Composable
fun ThemeColorSwatches(
    seedHex: String?,
    onPick: (String?) -> Unit,
    rainbowUnlocked: Boolean = false,
    rainbowSelected: Boolean = false,
    onPickRainbow: () -> Unit = {},
    lowSaturation: Boolean = false,
) {
    var showPicker by remember { mutableStateOf(false) }
    val defaultColor = Color(0xFF9A4800)
    val presets = listOf(
        defaultColor,
        Color(0xFFC62828), // 红
        Color(0xFF2E7D32), // 绿
        Color(0xFF00838F), // 青
        Color(0xFF1565C0), // 蓝
        Color(0xFF6A1B9A), // 紫
        Color(0xFFAD1457), // 玫红
    )
    val customColor = seedHex?.let { parseHexColor(it) }
    val selected = customColor ?: defaultColor

    // 等宽铺满整行，避免右侧留白
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEachIndexed { i, color ->
            val isDefault = i == 0
            val sel = !rainbowSelected && color == selected
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(if (lowSaturation) mutedThemeColor(color) else color)
                        .clickable { onPick(if (isDefault) null else formatColorHex(color)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sel && !isDefault) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                }
            }
        }
        // 彩格 → 自由色板
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .then(if (!rainbowSelected && customColor != null) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(RainbowSweep))
                    .clickable { showPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "自定义主题色", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        // 隐藏炫彩格：切换够次数后才出现
        if (rainbowUnlocked) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .then(if (rainbowSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(RainbowSweep.reversed()))
                        .clickable { onPickRainbow() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "炫彩主题",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }

    if (showPicker) {
        HSVColorPickerDialog(
            onDismiss = { showPicker = false },
            onPick = { color ->
                onPick(formatColorHex(color))
                showPicker = false
            },
        )
    }
}
