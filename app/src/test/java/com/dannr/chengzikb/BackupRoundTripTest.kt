package com.dannr.chengzikb

import com.dannr.chengzikb.data.backup.BackupManager
import com.dannr.chengzikb.data.backup.BackupManager.CourseDump
import com.dannr.chengzikb.data.backup.BackupManager.TableDump
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.PeriodSetting
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRoundTripTest {
    @Test
    fun `imported clocks survive backup and restore`() {
        val session = CourseSession(courseId = 0, dayOfWeek = 1, startPeriodIdx = 0, endPeriodIdx = 1,
            activeWeeksText = "1-19", startMinute = 500, endMinute = 600)
        val source = listOf(TableDump("导入课表", settings, periods, listOf(CourseDump(course1, listOf(session)))))
        assertEquals(session, BackupManager.parse(BackupManager.buildTables(source)).single().courses.single().sessions.single())
    }

    @Test
    fun `old backups without imported clocks still follow periods`() {
        val session = BackupManager.parse(BackupManager.buildTables(singleTable())).single().courses.first().sessions.first()
        assertEquals(null, session.startMinute)
        assertEquals(null, session.endMinute)
    }

    private val settings = AppSettings(
        timetableId = 0L,
        termStartEpochDay = 20_000L,
        totalWeeks = 20,
        shownDays = 6,
        themeMode = 0,
        showLocation = false,
    )

    private val periods = listOf(
        PeriodSetting(order = 0, name = "第1节", startMinute = 480, endMinute = 525),
        PeriodSetting(order = 1, name = "第2节", startMinute = 535, endMinute = 580),
    )

    private val course1 = Course(name = "高等数学", shortName = "高数", teacher = "王老师", colorIndex = 2)
    private val course2 = Course(name = "大学英语", teacher = null, colorIndex = 5)

    // 同一门“高数”有两条安排（模板一次、多时段，地点各不同 → 逐安排保存）
    private val course1Sessions = listOf(
        CourseSession(courseId = 0L, dayOfWeek = 3, startPeriodIdx = 0, endPeriodIdx = 1, activeWeeksText = "7-10,15-18", location = "A101"),
        CourseSession(courseId = 0L, dayOfWeek = 5, startPeriodIdx = 2, endPeriodIdx = 2, activeWeeksText = "1-20", location = "B202"),
    )
    private val course2Sessions = listOf(
        CourseSession(courseId = 0L, dayOfWeek = 1, startPeriodIdx = 1, endPeriodIdx = 1, activeWeeksText = "1-20", location = null),
    )

    private fun singleTable() = listOf(
        TableDump(
            name = "我的课表",
            settings = settings,
            periods = periods,
            courses = listOf(
                CourseDump(course1, course1Sessions),
                CourseDump(course2, course2Sessions),
            ),
        ),
    )

    @Test
    fun `roundtrip 保持全部数据一致`() {
        val json = BackupManager.buildTables(singleTable())
        val back = BackupManager.parse(json)
        assertEquals(1, back.size)
        assertEquals("我的课表", back[0].name)
        assertEquals(settings, back[0].settings)
        assertEquals(periods, back[0].periods)
        assertEquals(2, back[0].courses.size)
        assertEquals(course1, back[0].courses[0].course)
        assertEquals(course1Sessions, back[0].courses[0].sessions)
    }

    @Test
    fun `模板与多安排原样保留`() {
        val json = BackupManager.buildTables(singleTable())
        val back = BackupManager.parse(json)[0]
        assertEquals("高等数学", back.courses[0].course.name)
        assertEquals(2, back.courses[0].sessions.size)
        assertEquals("7-10,15-18", back.courses[0].sessions[0].activeWeeksText)
        assertEquals(null, back.courses[1].course.teacher)
    }

    @Test
    fun `多课表分别导出`() {
        val two = singleTable() + TableDump("大一下", settings, periods, emptyList())
        val json = BackupManager.buildTables(two)
        val back = BackupManager.parse(json)
        assertEquals(2, back.size)
        assertEquals("大一下", back[1].name)
    }

    @Test
    fun `调休设置与手动覆盖原样往返`() {
        val d1 = LocalDate.of(2026, 2, 28)
        val d2 = LocalDate.of(2026, 5, 9)
        val overrides = listOf(
            DayOverride(0L, d1.toEpochDay(), DayOverride.KIND_FOLLOW, 1),
            DayOverride(0L, d2.toEpochDay(), DayOverride.KIND_REST),
        )
        val s = settings.copy(autoHoliday = true)
        val json = BackupManager.buildTables(
            listOf(TableDump("我的课表", s, periods, emptyList(), overrides)),
        )
        val back = BackupManager.parse(json)[0]
        assertTrue(back.settings.autoHoliday)
        assertEquals(2, back.dayOverrides.size)
        assertEquals(overrides, back.dayOverrides)
    }

    @Test
    fun `旧备份缺字段时按自动调休关 无手动覆盖解析`() {
        // v4 结构但完全没有 autoHoliday / dayOverrides —— 模拟升级前导出的备份
        val legacy = """
            {"version":4,"exportedAt":1,"tables":[{"name":"旧课表","settings":{
              "termStartEpochDay":20000,"totalWeeks":18,"shownDays":5,"themeMode":0,
              "showLocation":true,"showTeacher":true,"showOtherWeeks":false},
              "periods":[],"courses":[]}]}
        """.trimIndent()
        val back = BackupManager.parse(legacy)[0]
        assertEquals("旧课表", back.name)
        assertEquals(false, back.settings.autoHoliday)
        assertEquals(18, back.settings.totalWeeks)
        assertTrue(back.dayOverrides.isEmpty())
    }

    @Test
    fun `无手动覆盖时不写出 dayOverrides 字段`() {
        val json = BackupManager.buildTables(singleTable())
        assertTrue(!json.contains("dayOverrides"))
    }

    @Test
    fun `非法的调休行被跳过而不报错`() {
        val bad = """
            {"version":4,"exportedAt":1,"tables":[{"name":"课表","settings":{
              "termStartEpochDay":20000,"totalWeeks":20},
              "periods":[],"courses":[],
              "dayOverrides":[{"dateEpochDay":20000,"kind":7},
                              {"kind":0},
                              {"dateEpochDay":20001,"kind":0,"followDayOfWeek":9}]}]}
        """.trimIndent()
        val back = BackupManager.parse(bad)[0]
        // 第一条 kind 非法、第二条缺日期 → 跳过；第三条 kind=REST 合法（followDayOfWeek 越界被夹到 1..7）
        assertEquals(1, back.dayOverrides.size)
        assertEquals(20001L, back.dayOverrides[0].dateEpochDay)
        assertEquals(7, back.dayOverrides[0].followDayOfWeek)
    }

    @Test
    fun `非法备份报错`() {
        assertThrows(IllegalArgumentException::class.java) { BackupManager.parse("not json at all") }
        assertThrows(IllegalArgumentException::class.java) { BackupManager.parse("""{"version":99,"settings":{}}""") }
    }

    @Test
    fun `空课程也合法`() {
        val json = BackupManager.buildTables(
            listOf(TableDump("我的课表", settings, periods, emptyList())),
        )
        val back = BackupManager.parse(json)
        assertEquals(0, back[0].courses.size)
        assertEquals(periods.size, back[0].periods.size)
    }
}
