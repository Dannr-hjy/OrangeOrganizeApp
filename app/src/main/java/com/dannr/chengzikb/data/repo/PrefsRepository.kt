package com.dannr.chengzikb.data.repo

import com.dannr.chengzikb.data.db.MetaDao
import com.dannr.chengzikb.data.model.MetaEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 课前提醒的两个偏好，成对出现——分开收集会经过"开关读到了、分钟数还没读到"的中间态。 */
data class ReminderPrefs(val enabled: Boolean, val minutes: Int)

/**
 * 与课表无关的全局偏好（键值表）：课前提醒开关与提前分钟数。
 */
class PrefsRepository(private val metaDao: MetaDao) {

    companion object {
        const val KEY_REMIND_ENABLED = "remind_enabled"
        const val KEY_REMIND_MINUTES = "remind_minutes"

        /** 主题色被切换过的累计次数：解锁隐藏炫彩主题用，全局一份（换课表也不重置）。 */
        const val KEY_THEME_SWITCH_COUNT = "theme_switch_count"
    }

    val remindersEnabled: Flow<Boolean> = metaDao.observe(KEY_REMIND_ENABLED).map { it?.value == "1" }

    val remindMinutes: Flow<Int> = metaDao.observe(KEY_REMIND_MINUTES).map { it?.value?.toIntOrNull() ?: 10 }

    /**
     * 主题色切换总次数（全局，跨课表累计）。null = 还没从库里读出来，
     * 此时调用方不要回写，否则会用初值覆盖掉真实计数。
     */
    val themeSwitchCount: Flow<Int?> =
        metaDao.observe(KEY_THEME_SWITCH_COUNT).map { it?.value?.toIntOrNull() }

    suspend fun setThemeSwitchCount(count: Int) =
        metaDao.put(MetaEntry(KEY_THEME_SWITCH_COUNT, count.coerceAtLeast(0).toString()))

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
