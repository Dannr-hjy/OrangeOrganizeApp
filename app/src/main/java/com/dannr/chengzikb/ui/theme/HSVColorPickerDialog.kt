package com.dannr.chengzikb.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return Color(r, g, b, 255)
}

/** 滑动选色器：SV 平面自由拖动 + 底部色相滑动条（画板式），确认后回调 [onPick]。 */
@Composable
fun HSVColorPickerDialog(
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    var hue by remember { mutableStateOf(0.0f) }
    var sat by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }
    var svW by remember { mutableStateOf(1) }
    var svH by remember { mutableStateOf(1) }
    var hueW by remember { mutableStateOf(1) }

    fun updateSv(x: Float, y: Float) {
        sat = (x / svW.coerceAtLeast(1)).coerceIn(0f, 1f)
        value = (1f - y / svH.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    fun updateHue(x: Float) {
        hue = ((x / hueW.coerceAtLeast(1)).coerceIn(0f, 1f) * 360f) % 360f
    }

    val hueColors = remember { (0..6).map { hsvToColor(it * 60f, 1f, 1f) } }
    val pure = hsvToColor(hue, 1f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(hsvToColor(hue, sat, value))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(formatColorHex(hsvToColor(hue, sat, value)), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                // SV 平面
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, pure)))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .onSizeChanged { svW = it.width; svH = it.height }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> updateSv(p.x, p.y) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateSv(change.position.x, change.position.y)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (sat * svW).roundToInt(),
                                    ((1f - value) * svH).roundToInt(),
                                )
                            }
                            .size(18.dp)
                            .offset(x = (-9).dp, y = (-9).dp)
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 色相滑条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(hueColors))
                        .onSizeChanged { hueW = it.width }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> updateHue(p.x) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateHue(change.position.x)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset {
                                IntOffset(((hue / 360f) * hueW).roundToInt(), 0)
                            }
                            .size(width = 3.dp, height = 24.dp)
                            .background(Color.White)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(hsvToColor(hue, sat, value)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
