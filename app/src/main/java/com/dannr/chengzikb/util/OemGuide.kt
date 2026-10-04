package com.dannr.chengzikb.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 国内/海外 ROM 的「后台运行 / 自启动」设置跳转引导。
 *
 * 背景：桌面小组件自刷新与课前提醒都靠 AlarmManager 精确闹钟驱动，而国产 ROM（尤其小米/荣耀/OPPO）
 * 各自的后台/自启动策略会拖慢甚至冻结闹钟广播，开机后未授权还可能吞掉 BOOT_COMPLETED 导致不再排程。
 * App 无法为自己授予这些权限，唯一可行路径是跳到对应设置页让用户手动放行。
 *
 * 候选组件多为厂商内部 activity，可能随版本改名或被拦截（如荣耀需签名权限 USE_COMPONENT、
 * HyperOS 收紧旧跳转），因此逐个「resolveActivity 探测 → startActivity 吞错」，全失败降级到
 * 系统通用「电池优化」页，再失败才落到系统设置首页——保证永不抛错、永不失手。
 *
 * [familyOf]/[candidateSpecs] 是纯函数（无 android 依赖），可 JVM 单测。
 */
enum class OemFamily { VIVO, OPPO, XIAOMI, HUAWEI, HONOR, SAMSUNG, OTHER }

/** 一个"打开系统某设置页"的候选：显式组件(package+class) 或 action（可带 extras / 限包）。 */
data class OemSpec(
    val packageName: String? = null,
    val className: String? = null,
    val action: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

object OemGuide {

    fun familyOf(manufacturer: String, brand: String): OemFamily {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        fun isAny(vararg keys: String): Boolean = keys.any { it == m || it == b }
        return when {
            isAny("vivo", "iqoo") -> OemFamily.VIVO
            isAny("oppo", "oneplus", "realme") -> OemFamily.OPPO
            isAny("xiaomi", "redmi", "poco") -> OemFamily.XIAOMI
            isAny("honor") -> OemFamily.HONOR // 须在 huawei 之前判（荣耀多报 manufacturer=huawei/brand=honor）
            isAny("huawei") -> OemFamily.HUAWEI
            isAny("samsung") -> OemFamily.SAMSUNG
            else -> OemFamily.OTHER
        }
    }

    /** 按厂商给出「后台/自启动设置页」候选，按可靠度排序；SAMSUNG/原生走通用页即可。 */
    fun candidateSpecs(family: OemFamily): List<OemSpec> = when (family) {
        OemFamily.VIVO -> listOf(
            OemSpec(
                packageName = "com.vivo.permissionmanager",
                className = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        )
        OemFamily.OPPO -> listOf(
            OemSpec(
                action = "com.oplus.security.action.REQUEST_PERMISSION",
                packageName = "com.oplus.security",
                extras = mapOf("operation" to "bg_startup"),
            ),
            OemSpec(
                packageName = "com.coloros.safecenter",
                className = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )
        OemFamily.XIAOMI -> listOf(
            OemSpec(
                packageName = "com.miui.securitycenter",
                className = "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        OemFamily.HONOR -> listOf(
            OemSpec(
                packageName = "com.huawei.systemmanager",
                className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            OemSpec(
                packageName = "com.hihonor.systemmanager",
                className = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        OemFamily.HUAWEI -> listOf(
            OemSpec(
                packageName = "com.huawei.systemmanager",
                className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        OemFamily.SAMSUNG, OemFamily.OTHER -> emptyList()
    }

    /**
     * 打开系统「后台运行/自启动」设置（含通用电池优化兜底）。
     * @return 是否成功打开了某个页面（成功也可能只是通用页/设置首页）。
     */
    fun openBackgroundSettings(context: Context): Boolean {
        val pm = context.packageManager
        fun start(intent: Intent): Boolean {
            return try {
                // 先探测可解析，再启动；启动还可能抛 SecurityException(厂商管控) 等，一并吞掉
                if (intent.resolveActivity(pm) == null) false
                else {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        val family = familyOf(Build.MANUFACTURER, Build.BRAND)
        for (spec in candidateSpecs(family)) {
            val intent = when {
                spec.action != null -> Intent(spec.action).apply {
                    spec.extras.forEach { (k, v) -> putExtra(k, v) }
                    spec.packageName?.let(::setPackage)
                }
                spec.packageName != null && spec.className != null ->
                    Intent().setComponent(ComponentName(spec.packageName, spec.className))
                else -> null
            }
            if (intent != null && start(intent)) return true
        }

        // 兜底 1：电池优化豁免设置页（AOSP 通用、各 ROM 普遍存在、打开无需额外权限）
        if (start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        // 兜底 2：系统设置首页
        return start(Intent(Settings.ACTION_SETTINGS))
    }
}
