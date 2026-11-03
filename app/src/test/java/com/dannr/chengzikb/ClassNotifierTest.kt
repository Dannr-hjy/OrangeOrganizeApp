package com.dannr.chengzikb

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.room.Room
import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.notify.ClassNotifier
import com.dannr.chengzikb.data.notify.ClassReminderReceiver
import com.dannr.chengzikb.data.notify.ReminderNotifications
import com.dannr.chengzikb.data.repo.PrefsRepository
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ClassNotifierTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var notifier: ClassNotifier
    private lateinit var alarm: AlarmManager
    private lateinit var notifications: NotificationManager
    private val date = LocalDate.now().plusDays(1)
    private var table = 0L
    private var course = 0L

    @Before fun prepare() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("orange_reminders", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        table = db.timetableDao().insert(Timetable(name = "课表"))
        db.metaDao().put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, table.toString()))
        db.settingsDao().upsert(Defaults.defaultSettings(WeekMath.mondayOfWeek(date), table))
        db.periodDao().insertAll(Defaults.defaultPeriods().map { it.copy(timetableId = table) })
        course = db.courseDao().upsert(Course(name = "数学", timetableId = table))
        db.courseSessionDao().insertAll(listOf(CourseSession(courseId = course, dayOfWeek = date.dayOfWeek.value,
            startPeriodIdx = 0, endPeriodIdx = 0, activeWeeksText = "1-20")))
        db.metaDao().put(MetaEntry(PrefsRepository.KEY_REMIND_ENABLED, "1"))
        db.metaDao().put(MetaEntry(PrefsRepository.KEY_REMIND_MINUTES, "10"))
        alarm = app.getSystemService(AlarmManager::class.java)
        notifications = app.getSystemService(NotificationManager::class.java)
        notifier = ClassNotifier(app, db)
    }

    @After fun cleanup() { db.close() }

    private fun reminders() = shadowOf(alarm).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.action == ClassNotifier.ACTION_REMIND
    }
    private fun renewalCount() = shadowOf(alarm).scheduledAlarms.count {
        shadowOf(it.operation).savedIntent.action == ClassNotifier.ACTION_RENEW
    }

    @Test fun `persisted preferences schedule the right absolute time and one renewal`() = runBlocking {
        notifier.sync()
        val expected = date.atTime(7, 50).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, reminders().minOf { it.triggerAtTime })
        assertEquals(1, renewalCount())
        assertTrue(ClassNotifier.status.value.nextText!!.contains("07:50 提醒 · 08:00"))
        notifier.sync()
        assertEquals(1, renewalCount())
        assertEquals(1, reminders().size) // repeated sync replaces the same PendingIntent
    }

    @Test fun `disabling reminders cancels both classes and renewal`() = runBlocking {
        notifier.sync()
        db.metaDao().put(MetaEntry(PrefsRepository.KEY_REMIND_ENABLED, "0"))
        notifier.sync()
        assertTrue(reminders().isEmpty())
        assertEquals(0, renewalCount())
    }

    @Test fun `another notifier instance uses the latest persisted preferences`() = runBlocking {
        notifier.sync()
        db.metaDao().put(MetaEntry(PrefsRepository.KEY_REMIND_MINUTES, "30"))
        ClassNotifier(app, db).sync()
        val expected = date.atTime(7, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, reminders().minOf { it.triggerAtTime })
    }

    @Test fun `fresh database snapshot updates the notification title after rename`() = runBlocking {
        notifier.sync()
        db.courseDao().upsert(Course(id = course, name = "新课名", timetableId = table))
        notifier.sync()
        assertTrue(reminders().all { shadowOf(it.operation).savedIntent.getStringExtra(ClassNotifier.EXTRA_TITLE) == "新课名" })
    }

    @Test fun `an empty schedule still renews without needing to open the app`() = runBlocking {
        db.courseSessionDao().deleteAll()
        notifier.sync()
        assertTrue(reminders().isEmpty())
        assertEquals(1, renewalCount())
    }

    @Test fun `new channel has high importance and an explicit notification sound`() {
        ReminderNotifications.ensureChannel(app)
        val channel = checkNotNull(notifications.getNotificationChannel(ClassNotifier.CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
    }

    @Test fun `legacy low channel is diagnosed and user settings are preserved`() {
        notifications.deleteNotificationChannel(ClassNotifier.CHANNEL_ID)
        notifications.createNotificationChannel(NotificationChannel(ClassNotifier.CHANNEL_ID, "旧提醒", NotificationManager.IMPORTANCE_LOW))
        ReminderNotifications.ensureChannel(app)
        assertEquals(NotificationManager.IMPORTANCE_LOW, checkNotNull(notifications.getNotificationChannel(ClassNotifier.CHANNEL_ID)).importance)
        assertTrue(ReminderNotifications.warning(app)!!.contains("横幅"))
    }

    private fun dueIntent() = Intent(ClassNotifier.ACTION_REMIND)
        .putExtra(ClassNotifier.EXTRA_CODE, 123)
        .putExtra(ClassNotifier.EXTRA_TITLE, "数学")
        .putExtra(ClassNotifier.EXTRA_START_MILLIS, System.currentTimeMillis() + 600_000L)
        .putExtra(ClassNotifier.EXTRA_FIRE_MILLIS, System.currentTimeMillis() - 1_000L)

    @Test fun `duplicate delivery does not bring back a dismissed notification`() {
        val intent = dueIntent()
        ClassReminderReceiver().onReceive(app, intent)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        notifications.cancel(123)
        ClassReminderReceiver().onReceive(app, intent)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun `disabling reminders suppresses an in-flight broadcast`() = runBlocking {
        db.metaDao().put(MetaEntry(PrefsRepository.KEY_REMIND_ENABLED, "0"))
        notifier.sync()
        ClassReminderReceiver().onReceive(app, dueIntent())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun `notification delayed until after class starts is discarded`() {
        val intent = dueIntent().putExtra(ClassNotifier.EXTRA_START_MILLIS, System.currentTimeMillis() - 1L)
        ClassReminderReceiver().onReceive(app, intent)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }
}
