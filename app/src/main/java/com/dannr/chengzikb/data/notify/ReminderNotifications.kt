package com.dannr.chengzikb.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dannr.chengzikb.MainActivity
import com.dannr.chengzikb.R

/** 渠道创建、权限诊断与投递共用同一渠道；系统里的用户选择始终保留。 */
object ReminderNotifications {
    private val vibration = longArrayOf(0L, 250L, 150L, 250L)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Android 8+ 的重要级别、声音等在渠道创建后不能由应用升级。
        // 保留渠道 ID 与用户设置，通过设置页直达渠道设置来修复旧的静默/低重要级别渠道。
        if (manager.getNotificationChannel(ClassNotifier.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(ClassNotifier.CHANNEL_ID, "课前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "课程开始前的声音、振动与横幅提醒"
                enableVibration(true)
                vibrationPattern = vibration
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            },
        )
    }

    fun warning(context: Context): String? {
        ensureChannel(context)
        if ((Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) return "通知权限已关闭，无法收到课前提醒"
        val manager = context.getSystemService(NotificationManager::class.java) ?: return "无法读取系统通知设置"
        val channel = manager.getNotificationChannel(ClassNotifier.CHANNEL_ID) ?: return "课前提醒通知类别尚未创建"
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> "「课前提醒」通知类别已关闭"
            channel.importance < NotificationManager.IMPORTANCE_HIGH -> "「课前提醒」重要程度较低，请开启横幅/悬浮通知"
            channel.sound == null -> "「课前提醒」提示音已关闭，请检查静默与横幅设置"
            else -> null
        }
    }

    fun deliver(context: Context, intent: Intent): Boolean {
        val title = intent.getStringExtra(ClassNotifier.EXTRA_TITLE) ?: return false
        ensureChannel(context)
        if ((Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(ClassNotifier.CHANNEL_ID) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val code = intent.getIntExtra(ClassNotifier.EXTRA_CODE, 0)
        val isTest = intent.getBooleanExtra(ClassNotifier.EXTRA_TEST, false)
        val time = intent.getStringExtra(ClassNotifier.EXTRA_TIME).orEmpty()
        val location = intent.getStringExtra(ClassNotifier.EXTRA_LOCATION).orEmpty()
        val contentText = if (isTest) "请同时检查声音、振动与横幅是否正常" else buildString {
            append("$time 上课")
            if (location.isNotBlank()) append(" · $location")
        }
        val contentIntent = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ClassNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(if (isTest) "测试提醒" else "$title 即将开始")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setVibrate(vibration)
            .build()
        NotificationManagerCompat.from(context).notify(code, notification)
        return true
    }
}
