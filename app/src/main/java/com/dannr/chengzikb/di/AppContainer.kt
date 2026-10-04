package com.dannr.chengzikb.di

import android.content.Context
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.db.DatabaseSeeder
import com.dannr.chengzikb.data.notify.ClassNotifier
import com.dannr.chengzikb.data.repo.CourseRepository
import com.dannr.chengzikb.data.repo.DayOverrideRepository
import com.dannr.chengzikb.data.repo.PeriodRepository
import com.dannr.chengzikb.data.repo.PrefsRepository
import com.dannr.chengzikb.data.repo.SettingsRepository
import com.dannr.chengzikb.data.repo.TimetableManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 手动依赖容器（进程级单例，由 [com.dannr.chengzikb.OrangeApp] 持有）。
 */
class AppContainer(appContext: Context) {

    /** 建库后立即同步播种默认课表/作息/设置，保证首帧即有可用数据与“当前课表”。 */
    val database: AppDatabase = AppDatabase.getInstance(appContext).also { db ->
        runBlocking(Dispatchers.IO) { DatabaseSeeder.ensureSeeded(db) }
    }

    val timetableManager = TimetableManager(database, database.timetableDao(), database.metaDao())
    val courseRepository = CourseRepository(database, database.courseDao(), database.courseSessionDao(), timetableManager)
    val periodRepository = PeriodRepository(database, database.periodDao(), timetableManager)
    val settingsRepository = SettingsRepository(database, database.settingsDao(), timetableManager)
    val dayOverrideRepository = DayOverrideRepository(database.dayOverrideDao(), timetableManager)
    val prefsRepository = PrefsRepository(database.metaDao())
    val classNotifier = ClassNotifier(appContext.applicationContext, database)
}
