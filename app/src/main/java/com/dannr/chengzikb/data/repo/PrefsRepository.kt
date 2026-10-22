package com.dannr.chengzikb.data.repo

import com.dannr.chengzikb.data.db.MetaDao
import com.dannr.chengzikb.data.model.MetaEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 课前提醒的两个偏好，成对出现——分开收集会经过"开关读到了、分钟数还没读到"的中间态。 */
data class ReminderPrefs(val enabled: Boolean, val minutes: Int)

/**
 * 课表主界面的背景壁纸。
 * [path] 为应用私有目录下的图片绝对路径（null = 未设置）；
 * [blur] 模糊档位 0..100（非线性映射到实际模糊半径，低档很轻）；[brightness] -100..100（负=压暗、正=提亮）；
 * [autoDim] 暗色模式下是否自动再调暗一层。
 */
data class WallpaperPrefs(
    val path: String?,
    val blur: Int,
    val brightness: Int,
    val autoDim: Boolean,
) {
    companion object {
        val DEFAULT = WallpaperPrefs(path = null, blur = 50, brightness = -15, autoDim = true)
    }
}

/**
 * 与课表无关的全局偏好（键值表）：课前提醒开关与提前分钟数。
 */
class PrefsRepository(private val metaDao: MetaDao) {

    companion object {
        const val KEY_REMIND_ENABLED = "remind_enabled"
        const val KEY_REMIND_MINUTES = "remind_minutes"

        /** 主题色被切换过的累计次数：解锁隐藏炫彩主题用，全局一份（换课表也不重置）。 */
        const val KEY_THEME_SWITCH_COUNT = "theme_switch_count"

        const val KEY_WALLPAPER_PATH = "wallpaper_path"
        // 键名带 _level：模糊改成了 0..100 的档位（非线性映射），与旧的 0..40 像素值不兼容，
        // 换键后旧值自然失效，用户下次设置壁纸就会落到新的默认档位。
        const val KEY_WALLPAPER_BLUR = "wallpaper_blur_level"
        const val KEY_WALLPAPER_BRIGHTNESS = "wallpaper_brightness"
        const val KEY_WALLPAPER_AUTO_DIM = "wallpaper_auto_dim"

        /** 主界面课程块透明度（百分数 20..100），全局一份。 */
        const val KEY_COURSE_BLOCK_OPACITY = "course_block_opacity"
    }

    val remindersEnabled: Flow<Boolean> = metaDao.observe(KEY_REMIND_ENABLED).map { it?.value == "1" }

    val remindMinutes: Flow<Int> = metaDao.observe(KEY_REMIND_MINUTES).map { it?.value?.toIntOrNull() ?: 10 }

    /**
     * 主题色切换总次数（全局，跨课表累计）。
     *
     * 注意：**没有记录时必须发射 0，而不是 null**——首次安装库里根本没有这一行，
     * 若映射成 null，设置页会一直把状态当成"还没读出来"而不计数，导致炫彩主题永远无法解锁。
     * 真正的"尚未读库"由收集方自己的占位值（produceState 的 initialValue）区分。
     */
    val themeSwitchCount: Flow<Int> =
        metaDao.observe(KEY_THEME_SWITCH_COUNT).map { it?.value?.toIntOrNull() ?: 0 }

    suspend fun setThemeSwitchCount(count: Int) =
        metaDao.put(MetaEntry(KEY_THEME_SWITCH_COUNT, count.coerceAtLeast(0).toString()))

    /**
     * 背景壁纸的四个值合并发射，避免"—读到了新图、亮度还是旧的"这类中间态。
     * 未设置时回落到 [WallpaperPrefs.DEFAULT]，因此一旦发射就是完整可用的配置。
     */
    val wallpaperPrefs: Flow<WallpaperPrefs> = combine(
        metaDao.observe(KEY_WALLPAPER_PATH).map { it?.value?.takeIf { v -> v.isNotBlank() } },
        metaDao.observe(KEY_WALLPAPER_BLUR)
            .map { it?.value?.toIntOrNull()?.coerceIn(0, 100) ?: WallpaperPrefs.DEFAULT.blur },
        metaDao.observe(KEY_WALLPAPER_BRIGHTNESS)
            .map { it?.value?.toIntOrNull()?.coerceIn(-100, 100) ?: WallpaperPrefs.DEFAULT.brightness },
        metaDao.observe(KEY_WALLPAPER_AUTO_DIM).map { it?.value != "0" }, // 无记录 = 默认开
    ) { path, blur, brightness, autoDim -> WallpaperPrefs(path, blur, brightness, autoDim) }

    /** 一次性写入整套壁纸偏好（滑杆只在拖动结束时提交，不会每帧写库）。 */
    suspend fun setWallpaper(prefs: WallpaperPrefs) {
        val path = prefs.path?.takeIf { it.isNotBlank() }
        if (path == null) metaDao.delete(KEY_WALLPAPER_PATH)
        else metaDao.put(MetaEntry(KEY_WALLPAPER_PATH, path))
        metaDao.put(MetaEntry(KEY_WALLPAPER_BLUR, prefs.blur.coerceIn(0, 100).toString()))
        metaDao.put(MetaEntry(KEY_WALLPAPER_BRIGHTNESS, prefs.brightness.coerceIn(-100, 100).toString()))
        metaDao.put(MetaEntry(KEY_WALLPAPER_AUTO_DIM, if (prefs.autoDim) "1" else "0"))
    }

    /** 主界面课程块透明度（百分数）。未设置时 100（完全不透明）。 */
    val courseBlockOpacity: Flow<Int> =
        metaDao.observe(KEY_COURSE_BLOCK_OPACITY).map { it?.value?.toIntOrNull()?.coerceIn(20, 100) ?: 100 }

    suspend fun setCourseBlockOpacity(percent: Int) =
        metaDao.put(MetaEntry(KEY_COURSE_BLOCK_OPACITY, percent.coerceIn(20, 100).toString()))

    /**
     * 两者合并发射，排程只认这一个 flow。UI 里 `collectAsStateWithLifecycle(initialValue = false)`
     * 的占位值会被当成"用户关掉了提醒"，拿它去重排就会把已排好的闹钟全清光。
     */
    val reminderPrefs: Flow<ReminderPrefs> =
        combine(remindersEnabled, remindMinutes) { enabled, minutes -> ReminderPrefs(enabled, minutes) }

    suspend fun setRemindersEnabled(enabled: Boolean) =
        metaDao.put(MetaEntry(KEY_REMIND_ENABLED, if (enabled) "1" else "0"))

    suspend fun setRemindMinutes(minutes: Int) =
        metaDao.put(MetaEntry(KEY_REMIND_MINUTES, minutes.coerceIn(1, 90).toString()))
}
