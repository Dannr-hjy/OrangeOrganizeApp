package com.dannr.chengzikb

import android.app.Application
import androidx.room.Room
import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.import.IcsImporter
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.repo.PeriodRepository
import com.dannr.chengzikb.data.repo.TimetableManager
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers the same Room DAOs and repository flow used by the period editor, not just parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class IcsPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: TimetableManager
    private lateinit var periods: PeriodRepository
    private var original = 0L
    private fun fixture(label: String) = checkNotNull(javaClass.getResourceAsStream("/ics/$label.ics"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        original = db.timetableDao().insert(Timetable(name = "原课表"))
        db.metaDao().put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, original.toString()))
        db.settingsDao().upsert(Defaults.defaultSettings(LocalDate.of(2026, 9, 7), original).copy(totalWeeks = 19))
        db.periodDao().insertAll(Defaults.defaultPeriods().map { it.copy(timetableId = original) })
        db.courseDao().upsert(Course(name = "已有课程", timetableId = original))
        manager = TimetableManager(db, db.timetableDao(), db.metaDao())
        periods = PeriodRepository(db, db.periodDao(), manager)
    }

    @After fun cleanup() { db.close() }

    private fun assertSchoolClocks(rows: List<com.dannr.chengzikb.data.model.PeriodSetting>) {
        assertEquals(listOf(500, 555, 620, 675, 840, 895, 960, 1015), rows.take(8).map { it.startMinute })
        assertEquals(listOf(545, 600, 665, 720, 885, 940, 1005, 1060), rows.take(8).map { it.endMinute })
    }

    @Test fun `ordinary WakeUp import updates the period editor repository flow`() = runBlocking {
        assertEquals(480, periods.all.first().first().startMinute)
        val result = IcsImporter.buildForActive(db, fixture("weekly-events"))
        assertTrue(result.periodsAligned)
        IcsImporter.overwriteActive(db, result)
        assertSchoolClocks(periods.all.first())
        assertSchoolClocks(periods.getAllOnce())
        assertEquals(9, db.courseDao().getAllByTimetable(original).size)
        val sessions = db.courseSessionDao().getAllByTimetable(original)
        assertEquals(247, sessions.sumOf { com.dannr.chengzikb.data.model.WeekSet.parse(it.activeWeeksText).weeks.size })
    }

    @Test fun `new timetable receives aligned clocks without changing the previous table`() = runBlocking {
        val result = IcsImporter.buildForActive(db, fixture("weekly-events"))
        val created = manager.create("新课表")
        assertTrue(created > 0)
        IcsImporter.overwriteActive(db, result)
        assertSchoolClocks(periods.all.first())
        assertEquals(480, db.periodDao().getAllByTimetable(original).first().startMinute)
        assertEquals(9, db.courseDao().getAllByTimetable(created).size)
        assertEquals("已有课程", db.courseDao().getAllByTimetable(original).single().name)
    }

    @Test fun `complete metadata replaces all eight period boundaries`() = runBlocking {
        val result = IcsImporter.buildForActive(db, fixture("complete-periods"))
        assertFalse(result.periodsAligned)
        IcsImporter.overwriteActive(db, result)
        val rows = periods.all.first()
        assertEquals(8, rows.size)
        assertSchoolClocks(rows)
    }

    @Test fun `failed session insertion rolls back both courses and period updates`() = runBlocking {
        val result = IcsImporter.buildForActive(db, fixture("weekly-events"))
        val broken = result.copy(courses = result.courses.map { course -> course.copy(
            sessions = course.sessions.map { it.copy(endMinute = null) },
        ) })
        try {
            IcsImporter.overwriteActive(db, broken)
            fail("Expected invalid paired clocks")
        } catch (_: IllegalArgumentException) { }
        assertEquals(480, periods.all.first().first().startMinute)
        assertEquals("已有课程", db.courseDao().getAllByTimetable(original).single().name)
    }
}
