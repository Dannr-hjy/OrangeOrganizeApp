package com.dannr.chengzikb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.model.WeekSet

@Database(
    entities = [
        Course::class,
        CourseSession::class,
        PeriodSetting::class,
        AppSettings::class,
        Timetable::class,
        MetaEntry::class,
        DayOverride::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun courseSessionDao(): CourseSessionDao
    abstract fun periodDao(): PeriodDao
    abstract fun settingsDao(): SettingsDao
    abstract fun timetableDao(): TimetableDao
    abstract fun metaDao(): MetaDao
    abstract fun dayOverrideDao(): DayOverrideDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "orange_kebiao.db",
                )
                    // v2：课程/安排拆表。开发期真机上多为试排数据，直接重建最稳妥；
                    // v5：支持多课表。开发期破坏性重建，正式上线前应补 Migration 保留数据。
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        /**
         * v6 → v7：上课地点从课程下放到每条上课安排。
         * 1) course_sessions 增加 location 列；
         * 2) 把各课程的整课地点复制到它每条安排上；
         * 3) 旧数据里按“名称+地点+老师”拆开（地点不同）的同名同老师课程，
         *    重新按「名称+老师」合并为一门课（保留最早 id 的颜色），安排照旧归属；
         *    合并后若出现同课同星期同节次的多条安排，则周次取并集、地点取其一。
         * courses.location 列保留为遗留列（实体字段仍在但不再使用），避免重建带外键的父表。
         */
        /**
         * v7 → v8：课程简称 + “哪里显示简称”的两个开关。
         * 1) courses 增加 shortName 列（可空，旧课一律没有简称 → 各处回退全称）；
         * 2) settings 增加 showShortNameInGrid / showShortNameInWidget（默认 0=关，行为与升级前一致）。
         * 两列都带 DEFAULT，纯增量迁移，不动任何既有数据。
         */
        /** Optional imported clocks; existing sessions keep following their periods. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course_sessions ADD COLUMN startMinute INTEGER")
                db.execSQL("ALTER TABLE course_sessions ADD COLUMN endMinute INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN shortName TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN showShortNameInGrid INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN showShortNameInWidget INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 → v9：节假日调休。
         * 1) settings 增加 autoHoliday（默认 0=关，升级后行为与之前完全一致）；
         * 2) 新增 day_overrides 表存「用户手动改过的某一天」（休息 / 按周几上课）。
         * 建表语句必须与 Room 由实体生成的 schema 逐字一致，否则启动时报校验失败。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN autoHoliday INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_overrides` (" +
                        "`timetable_id` INTEGER NOT NULL, " +
                        "`date_epoch_day` INTEGER NOT NULL, " +
                        "`kind` INTEGER NOT NULL, " +
                        "`follow_day_of_week` INTEGER NOT NULL DEFAULT 1, " +
                        "PRIMARY KEY(`timetable_id`, `date_epoch_day`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_day_overrides_timetable_id` " +
                        "ON `day_overrides` (`timetable_id`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course_sessions ADD COLUMN location TEXT")
                db.execSQL(
                    "UPDATE course_sessions SET location = " +
                        "(SELECT location FROM courses WHERE courses.id = course_sessions.course_id)",
                )
                mergeDuplicateCourses(db)
            }
        }
    }
}

/** 迁移里读出来的一行课程 */
private data class CourseRow(val id: Long, val timetableId: Long, val name: String, val teacher: String)

/** 迁移里读出来的一行安排 */
private data class SessionRow(
    val id: Long,
    val courseId: Long,
    val day: Int,
    val start: Int,
    val end: Int,
    val weeksText: String,
    val location: String?,
)

/** 把旧版里“同名同老师”（可能被地点拆开）的课程合并为一门；随后去重幸存课内同槽位的多条安排。 */
private fun mergeDuplicateCourses(db: SupportSQLiteDatabase) {
    val courses = mutableListOf<CourseRow>()
    db.query("SELECT id, name, teacher, timetable_id FROM courses")
        .use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iName = c.getColumnIndexOrThrow("name")
            val iTeacher = c.getColumnIndexOrThrow("teacher")
            val iTid = c.getColumnIndexOrThrow("timetable_id")
            while (c.moveToNext()) {
                courses += CourseRow(
                    id = c.getLong(iId),
                    timetableId = c.getLong(iTid),
                    name = c.getString(iName) ?: "",
                    teacher = c.getString(iTeacher) ?: "",
                )
            }
        }

    // 组内保留 id 最小的那门，其余课程的安排改挂到它，再删掉空壳课程。
    val survivorOf = mutableMapOf<Long, Long>()
    courses.groupBy { Triple(it.timetableId, it.name.trim(), it.teacher) }
        .values
        .forEach { members ->
            if (members.size <= 1) return@forEach
            val keep = members.minOf { it.id }
            members.filter { it.id != keep }.forEach { survivorOf[it.id] = keep }
        }
    if (survivorOf.isNotEmpty()) {
        for ((old, keep) in survivorOf) {
            db.execSQL("UPDATE course_sessions SET course_id = ? WHERE course_id = ?", arrayOf(keep, old))
            db.execSQL("DELETE FROM courses WHERE id = ?", arrayOf(old))
        }
        dedupeOverlappingSessions(db)
    }
}

/** 幸存课后，把 (course_id, 星期, 节次起, 节次止) 相同的多条安排合并：周次取并集、地点取首个非空。 */
private fun dedupeOverlappingSessions(db: SupportSQLiteDatabase) {
    val sessions = mutableListOf<SessionRow>()
    db.query(
        "SELECT id, course_id, dayOfWeek, startPeriodIdx, endPeriodIdx, active_weeks_text, location FROM course_sessions",
    ).use { c ->
        val iId = c.getColumnIndexOrThrow("id")
        val iCid = c.getColumnIndexOrThrow("course_id")
        val iDay = c.getColumnIndexOrThrow("dayOfWeek")
        val iStart = c.getColumnIndexOrThrow("startPeriodIdx")
        val iEnd = c.getColumnIndexOrThrow("endPeriodIdx")
        val iWeeks = c.getColumnIndexOrThrow("active_weeks_text")
        val iLoc = c.getColumnIndexOrThrow("location")
        while (c.moveToNext()) {
            sessions += SessionRow(
                id = c.getLong(iId),
                courseId = c.getLong(iCid),
                day = c.getInt(iDay),
                start = c.getInt(iStart),
                end = c.getInt(iEnd),
                weeksText = c.getString(iWeeks) ?: "",
                location = if (c.isNull(iLoc)) null else c.getString(iLoc),
            )
        }
    }

    sessions.groupBy { SessionKey(it.courseId, it.day, it.start, it.end) }
        .values
        .forEach { rows ->
            if (rows.size <= 1) return@forEach
            val sorted = rows.sortedBy { it.id }
            val keep = sorted.first()
            val union = sorted.fold(emptySet<Int>()) { acc, r ->
                val ws = runCatching { WeekSet.parse(r.weeksText) }.getOrDefault(WeekSet.EMPTY)
                acc + ws.weeks
            }
            val location = sorted.firstNotNullOfOrNull { it.location }
            db.execSQL(
                "UPDATE course_sessions SET active_weeks_text = ?, location = ? WHERE id = ?",
                arrayOf(WeekSet.fromIterable(union).toText(), location, keep.id),
            )
            sorted.drop(1).forEach { db.execSQL("DELETE FROM course_sessions WHERE id = ?", arrayOf(it.id)) }
        }
}

/** 幸存课后按 (课程, 星期, 节次) 是否同槽位的分组键 */
private data class SessionKey(val courseId: Long, val day: Int, val start: Int, val end: Int)
