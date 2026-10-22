package com.dannr.chengzikb.ui.main

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dannr.chengzikb.data.repo.WallpaperPrefs
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 暗色模式下自动调暗壁纸时额外叠的黑色比例 */
private const val AutoDimAlpha = 0.35f

/** 导入壁纸时的最长边（像素）：够高清屏用，又不会把内存/模糊渲染撑爆 */
private const val MaxWallpaperDimension = 2560

/** 模糊档位拉满时对应的实际模糊半径（dp） */
private const val MaxBlurRadiusDp = 30f

/**
 * 模糊档位（0..100）→ 实际模糊半径（dp）。
 *
 * 用平方曲线而不是线性映射：低档位变化非常细腻（0~20 只是轻微柔化，不会一调就糊成一片），
 * 越往高档走半径增长越快，满档 [MaxBlurRadiusDp]。
 */
private fun blurLevelToRadiusDp(level: Int): Float {
    val t = level.coerceIn(0, 100) / 100f
    return t * t * MaxBlurRadiusDp
}

/**
 * 课表主界面的背景壁纸：整屏铺满（Crop），按设置做模糊 / 亮度 / 暗色自动调暗。
 *
 * - 模糊用 Compose 的 [blur]（RenderEffect），**需要 Android 12（API 31）及以上**；
 *   更低版本系统会忽略模糊，只保留亮度与自动调暗。
 * - 亮度用同尺寸半透明蒙层近似：负值压黑、正值提亮；暗色自动调暗再叠一层黑。
 * - 图片解码在 IO 线程，并按路径缓存一份，切回课表时不必重复解码。
 */
@Composable
fun WallpaperBackground(
    wallpaper: WallpaperPrefs?,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val path = wallpaper?.path ?: return
    val bitmap by produceState<ImageBitmap?>(initialValue = cachedWallpaper(path), key1 = path) {
        value = withContext(Dispatchers.IO) { loadWallpaper(path) }
    }
    val image = bitmap ?: return
    val blurRadiusDp = blurLevelToRadiusDp(wallpaper.blur)

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurRadiusDp > 0f) {
                        // Unbounded：模糊边缘向外溢出，避免整屏图四边出现一圈生硬裁切
                        Modifier.blur(blurRadiusDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    },
                ),
        )
        val brightness = wallpaper.brightness.coerceIn(-100, 100)
        if (brightness < 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = -brightness / 100f * 0.72f)))
        } else if (brightness > 0) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = brightness / 100f * 0.45f)))
        }
        if (dark && wallpaper.autoDim) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = AutoDimAlpha)))
        }
    }
}

/* ---------- 图片加载缓存（同一张壁纸反复进出课表页时只解码一次） ---------- */

@Volatile private var cachedPath: String? = null
@Volatile private var cachedImage: ImageBitmap? = null

private fun cachedWallpaper(path: String): ImageBitmap? =
    if (path == cachedPath) cachedImage else null

private fun loadWallpaper(path: String): ImageBitmap? {
    if (path == cachedPath) return cachedImage
    val decoded = runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    cachedPath = path
    cachedImage = decoded
    return decoded
}

/* ---------- 导入 / 删除 ---------- */

/**
 * 把用户选择的图片复制进应用私有目录并返回新文件路径；失败返回 null。
 * 先按最长边 [MaxWallpaperDimension] 降采样，按 EXIF 修正方向后再压成 JPEG，
 * 避免几千万像素的原图把内存吃满。
 */
suspend fun importWallpaper(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 模式下 decodeStream 本来就返回 null，
        // 所以判空的对象必须是"流"，不能是 use 的返回值，否则永远判定失败。
        val boundsStream = resolver.openInputStream(uri) ?: return@runCatching null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(srcWidth, srcHeight) / sample > MaxWallpaperDimension) sample *= 2

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return@runCatching null

        val oriented = applyExifRotation(resolver, uri, decoded)
        if (oriented !== decoded) decoded.recycle()

        val file = File(context.filesDir, "wallpaper_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        oriented.recycle()

        // 新图就绪后再让缓存失效，避免旧路径缓存一直占着内存
        cachedPath = null
        cachedImage = null
        file.absolutePath
    }.onFailure { Log.w("Wallpaper", "导入背景图片失败", it) }.getOrNull()
}

/** 删除上一张壁纸；只允许删应用私有目录里的文件，避免误删相册原图。 */
fun deleteWallpaper(context: Context, path: String?) {
    if (path.isNullOrBlank()) return
    runCatching {
        val file = File(path)
        if (file.parentFile?.absolutePath == context.filesDir.absolutePath) file.delete()
    }
    if (path == cachedPath) {
        cachedPath = null
        cachedImage = null
    }
}

/** 相机/相册图片可能带 EXIF 方向信息，BitmapFactory 不会自动转正。 */
private fun applyExifRotation(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: return bitmap

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}
