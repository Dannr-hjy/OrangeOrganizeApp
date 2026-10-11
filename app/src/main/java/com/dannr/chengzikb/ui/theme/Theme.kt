package com.dannr.chengzikb.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.data.model.Course
import kotlinx.coroutines.delay

/**
 * 主题模式（设置页可覆盖，默认跟随系统）
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/* ---------- 暖橙 Material 3 调色板（两套主题均按可读性挑选） ---------- */

private val OrangeLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF9A4800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC6),
    onPrimaryContainer = Color(0xFF2E1500),
    inversePrimary = Color(0xFFFFB680),
    secondary = Color(0xFF745942),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2A1606),
    tertiary = Color(0xFF59633D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE9B9),
    onTertiaryContainer = Color(0xFF151E00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF5DED2),
    onSurfaceVariant = Color(0xFF52443B),
    surfaceTint = Color(0xFF9A4800),
    inverseSurface = Color(0xFF362F29),
    inverseOnSurface = Color(0xFFFFEDE3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF85736B),
    outlineVariant = Color(0xFFD8C3B6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceDim = Color(0xFFE0D3CB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFF9EAE2),
    surfaceContainerHigh = Color(0xFFF3E5DC),
    surfaceContainerHighest = Color(0xFFEDDFD7),
)

private val OrangeDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB78C),
    onPrimary = Color(0xFF4B2700),
    primaryContainer = Color(0xFF713800),
    onPrimaryContainer = Color(0xFFFFDBC6),
    inversePrimary = Color(0xFF9A4800),
    secondary = Color(0xFFE2BE9C),
    onSecondary = Color(0xFF402B12),
    secondaryContainer = Color(0xFF5B402C),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFC0CD9A),
    onTertiary = Color(0xFF273300),
    tertiaryContainer = Color(0xFF3E4B21),
    onTertiaryContainer = Color(0xFFDDE9B9),
    background = Color(0xFF1A120C),
    onBackground = Color(0xFFF0DED3),
    surface = Color(0xFF1A120C),
    onSurface = Color(0xFFF0DED3),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD8C3B6),
    surfaceTint = Color(0xFFFFB78C),
    inverseSurface = Color(0xFFF0DED3),
    inverseOnSurface = Color(0xFF362F29),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA18D83),
    outlineVariant = Color(0xFF52443B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF42332B),
    surfaceDim = Color(0xFF1A120C),
    surfaceContainerLowest = Color(0xFF140D08),
    surfaceContainerLow = Color(0xFF231A14),
    surfaceContainer = Color(0xFF271F19),
    surfaceContainerHigh = Color(0xFF322922),
    surfaceContainerHighest = Color(0xFF3D342C),
)

/* ---------- 自定义主题色：以种子色对整个色板做色相迁移，卡片/背景/次级色一起变 ---------- */

/** 依据底色亮度选对比文字（白 / 深棕） */
private fun contrastOn(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF221A14) else Color.White

/** 默认主题主色（暖橙），作为色相迁移的锚点：种子色与它同相 → 色板保持不变 */
private val DefaultThemePrimary = Color(0xFF9A4800)

private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

/** 把颜色色相平移 delta 度；真正无彩(灰/白/黑)才保持不动 */
private fun rotateHue(c: Color, delta: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    if (hsv[1] < 0.02f) return c
    var nh = (hsv[0] + delta) % 360f
    if (nh < 0f) nh += 360f
    val alpha = (c.alpha * 255f).toInt().coerceIn(0, 255)
    return Color(android.graphics.Color.HSVToColor(alpha, floatArrayOf(nh, hsv[1], hsv[2])))
}

/** 按比例压低饱和度（低饱和主题用）；近无彩色保持不动 */
private fun desaturate(c: Color, factor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    if (hsv[1] < 0.02f) return c
    hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
    val alpha = (c.alpha * 255f).toInt().coerceIn(0, 255)
    return Color(android.graphics.Color.HSVToColor(alpha, hsv))
}

/**
 * 对色板的全部“语义色”施加同一变换：主/次/第三主色与容器，以及
 * surfaceContainer 系列、surface/background、surfaceVariant、outline 与各 on-* 文字色。
 * 错误系(红)与 scrim 不参与。色相迁移与低饱和都走这里，保证两者覆盖面一致。
 */
private fun mapSchemeColors(base: ColorScheme, f: (Color) -> Color): ColorScheme = base.copy(
    primary = f(base.primary),
    onPrimary = f(base.onPrimary),
    primaryContainer = f(base.primaryContainer),
    onPrimaryContainer = f(base.onPrimaryContainer),
    secondary = f(base.secondary),
    onSecondary = f(base.onSecondary),
    secondaryContainer = f(base.secondaryContainer),
    onSecondaryContainer = f(base.onSecondaryContainer),
    tertiary = f(base.tertiary),
    onTertiary = f(base.onTertiary),
    tertiaryContainer = f(base.tertiaryContainer),
    onTertiaryContainer = f(base.onTertiaryContainer),
    background = f(base.background),
    onBackground = f(base.onBackground),
    surface = f(base.surface),
    onSurface = f(base.onSurface),
    surfaceVariant = f(base.surfaceVariant),
    onSurfaceVariant = f(base.onSurfaceVariant),
    inversePrimary = f(base.inversePrimary),
    inverseSurface = f(base.inverseSurface),
    inverseOnSurface = f(base.inverseOnSurface),
    outline = f(base.outline),
    outlineVariant = f(base.outlineVariant),
    surfaceBright = f(base.surfaceBright),
    surfaceDim = f(base.surfaceDim),
    surfaceContainerLowest = f(base.surfaceContainerLowest),
    surfaceContainerLow = f(base.surfaceContainerLow),
    surfaceContainer = f(base.surfaceContainer),
    surfaceContainerHigh = f(base.surfaceContainerHigh),
    surfaceContainerHighest = f(base.surfaceContainerHighest),
)

/**
 * 整个暖橙色板按 delta 做统一色相平移：主/次/第三主色与容器，连 surfaceContainer 系列、
 * surface/background、surfaceVariant、outline 与各 on-* 文字色都迁到新色相
 * （只改色相、亮度饱和度不变，对比度基本不受影响）。错误系(红)与 scrim 不迁移。
 */
private fun rotateScheme(base: ColorScheme, delta: Float): ColorScheme =
    mapSchemeColors(base) { rotateHue(it, delta) }

/**
 * 低饱和主题：整块色板压饱和度（只降饱和、不动色相亮度），主色按新亮度重算对比文字。
 * 叠加在「默认橙」或任意种子色之上都成立，亮/暗两套色板各自生效。
 */
private fun applyLowSaturation(scheme: ColorScheme): ColorScheme {
    val muted = mapSchemeColors(scheme) { desaturate(it, LowSaturationFactor) }
    val p = muted.primary
    return muted.copy(
        primary = p,
        onPrimary = contrastOn(p),
        surfaceTint = p,
        inversePrimary = p,
    )
}

/**
 * 炫彩主题：整块色板色相轮转 hueDeg 度（从所在模式的暖橙色板出发），
 * 主色仍按亮度选对比文字，保证任一色相下按钮文字都读得清。
 */
private fun rainbowScheme(base: ColorScheme, hueDeg: Float): ColorScheme {
    val rotated = rotateScheme(base, hueDeg)
    val p = rotated.primary
    return rotated.copy(primary = p, onPrimary = contrastOn(p), surfaceTint = p, inversePrimary = p)
}

/**
 * 自定义主题色 → 整套暖橙色板整体相迁移到种子色：
 * 卡片圆角底、选中态、今天高亮、次级/第三主色等所有彩色块都跟随主题色，不再残留淡橙；
 * primary 精确取种子色并按亮度选对比文字；错误红保留。
 */
fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val base = if (dark) OrangeDark else OrangeLight
    val delta = hueOf(seed) - hueOf(DefaultThemePrimary)
    return rotateScheme(base, delta).copy(
        primary = seed,
        onPrimary = contrastOn(seed),
        inversePrimary = seed,
        surfaceTint = seed,
    )
}

/* ---------- 课块配色：高饱和填充，文字色按背景亮度自动黑/白 ---------- */

private val courseBlockFills = listOf(
    Color(0xFFE8590C), // 橙
    Color(0xFFC47700), // 赭
    Color(0xFF2E9E44), // 绿
    Color(0xFF00A187), // 青绿
    Color(0xFF1E7BD8), // 蓝
    Color(0xFF5B47C9), // 靛紫
    Color(0xFF8E3DB8), // 紫
    Color(0xFFD12778), // 洋红
    Color(0xFFE2423A), // 红
    Color(0xFF0B7B9C), // 深青
)

/** 按课程 colorIndex 取填充色（自动轮替 / 手动换色共用） */
fun courseFillColor(index: Int): Color = courseBlockFills[((index % courseBlockFills.size) + courseBlockFills.size) % courseBlockFills.size]

/** 依据背景亮度自动选文字色：深底用白、浅底用近黑（对比清晰） */
fun blockTextColor(fill: Color): Color =
    if (fill.luminance() > 0.32f) Color(0xFF1E1A16) else Color.White

/** 兼容旧引用：现改为白/黑自适应，不再固定深棕 */
val CourseBlockTextColor = Color.White

/** 自定义颜色支持：以 #RRGGBB / #AARRGGBB 十六进制解析 */
fun parseHexColor(text: String): Color? {
    val t = text.trim().removePrefix("#")
    if (t.isEmpty()) return null
    val len = t.length
    val value = when (len) {
        6 -> t.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> t.toLongOrNull(16)
        else -> null
    } ?: return null
    return runCatching { Color(value) }.getOrNull()
}

fun formatColorHex(c: Color): String {
    val l = c.toArgb().toLong() and 0xFFFFFFFFL
    return "#%08X".format(l)
}

/** 解析一门课的实际显示色：优先自定义色，其次调色板索引 */
fun courseColor(course: Course): Color =
    course.colorHex?.let { parseHexColor(it) } ?: courseFillColor(course.colorIndex)

/** 依据课程色的亮度选文字色（白/黑自适应） */
fun courseTextColor(course: Course): Color = blockTextColor(courseColor(course))

/* ---------- 形状 ---------- */

private val OrangeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 低饱和主题：整块色板的饱和度统一压到这个比例 */
private const val LowSaturationFactor = 0.45f

/** 炫彩主题的轮转节奏：每 [RainbowStepMillis] 毫秒转 [RainbowStepDegrees] 度（约 7 秒一整圈） */
private const val RainbowStepMillis = 80L
private const val RainbowStepDegrees = 4f

/**
 * 全局主题：按设置选择 浅色 / 深色 / 跟随系统；
 * [seedColor] 非空时用该主色生成对应亮/暗色板（默认 null = 暖橙原色板）。
 * [lowSaturation] 叠加低饱和配色；[rainbow] 开隐藏炫彩主题（色相持续轮转，压过种子色）。
 * 三者（色板 / 低饱和 / 炫彩）在亮色与暗色下各自成立。
 */
@Composable
fun OrangeKeBiaoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    seedColor: Color? = null,
    lowSaturation: Boolean = false,
    rainbow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = if (dark) OrangeDark else OrangeLight
    val colorScheme = when {
        // 炫彩：整块色板色相持续轮转。分步推进（而非逐帧）以免每帧重组整棵界面。
        rainbow -> {
            val hue = remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(RainbowStepMillis)
                    hue.floatValue = (hue.floatValue + RainbowStepDegrees) % 360f
                }
            }
            val scheme = rainbowScheme(base, hue.floatValue)
            if (lowSaturation) applyLowSaturation(scheme) else scheme
        }
        seedColor == null -> if (lowSaturation) applyLowSaturation(base) else base
        else -> {
            val scheme = schemeFromSeed(seedColor, dark)
            if (lowSaturation) applyLowSaturation(scheme) else scheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = OrangeShapes,
        content = content,
    )
}
