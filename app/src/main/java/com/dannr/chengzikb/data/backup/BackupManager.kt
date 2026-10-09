package com.dannr.chengzikb.data.backup

import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.Timetable
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表备份：导出【全部课表】（每表含作息/学期设置/课程/安排）为单一 JSON；
 * 恢复时在单个 Room 事务内整库替换（原子）。
 * v4 结构：{version:4, exportedAt, tables:[{name, settings, periods, courses:[{course, sessions}]}]}
 * —— v1.1 起地点下放到安排：course 不含 location，session 各带 location。
 * —— v1.3 起 course 可含 shortName（可选）；settings 可含 showShortNameInGrid/InWidget。二者均为
 *    增量可选项，字段缺失时按“无简称 / 开关关”解析，故沿用 v4 版本号，旧备份照常可导入。
 * —— v1.4 起 settings 可含 autoHoliday，table 可含 dayOverrides（手动改过的某天）。同样为增量可选项：
 *    旧备份没有就按“自动调休关、无手动覆盖”解析，新备份在旧版本 App 上导入时会忽略这两项（不报错）。
 * 兼容解析 v3（地点仍在 course 上）与 v2（单课表旧格式）：读到课程级地点后下放到该课各安排。
 */
object BackupManager {

    const val FORMAT_VERSION = 4
    const val LEGACY_VERSION = 3 // 兼容的最低旧版本（继续兼容 2）
    const val OLDEST_LEGACY_VERSION = 2

    /** 一门课及其（该课表内的）全部安排 */
    data class CourseDump(val course: Course, val sessions: List<CourseSession>)

    /** 单个课表的完整内容 */
    data class TableDump(
        val name: String,
        val settings: AppSettings,
        val periods: List<PeriodSetting>,
        val courses: List<CourseDump>,
        val dayOverrides: List<DayOverride> = emptyList(),
    )

    // ---------- 序列化（纯函数，可单测） ----------

    fun buildTables(tables: List<TableDump>): String {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        val ta = JSONArray()
        tables.forEach { t ->
            val tb = JSONObject()
            tb.put("name", t.name)
            tb.put("settings", settingsJson(t.settings))
            tb.put("periods", periodsJson(t.periods))
            val ca = JSONArray()
            t.courses.forEach { cd ->
                val co = JSONObject()
                co.put("course", courseJson(cd.course))
                co.put("sessions", sessionsJson(cd.sessions))
                ca.put(co)
            }
            tb.put("courses", ca)
            if (t.dayOverrides.isNotEmpty()) tb.put("dayOverrides", overridesJson(t.dayOverrides))
            ta.put(tb)
        }
        root.put("tables", ta)
        return root.toString(1)
    }

    private fun settingsJson(s: AppSettings): JSONObject {
        val o = JSONObject()
        o.put("termStartEpochDay", s.termStartEpochDay)
        o.put("totalWeeks", s.totalWeeks)
        o.put("shownDays", s.shownDays)
        o.put("themeMode", s.themeMode)
        o.put("showLocation", s.showLocation)
        o.put("showTeacher", s.showTeacher)
        o.put("showOtherWeeks", s.showOtherWeeks)
        o.put("showShortNameInGrid", s.showShortNameInGrid)
        o.put("showShortNameInWidget", s.showShortNameInWidget)
        o.put("autoHoliday", s.autoHoliday)
        return o
    }

    private fun overridesJson(rows: List<DayOverride>): JSONArray {
        val a = JSONArray()
        rows.sortedBy { it.dateEpochDay }.forEach { r ->
            val o = JSONObject()
            o.put("dateEpochDay", r.dateEpochDay)
            o.put("kind", r.kind)
            o.put("followDayOfWeek", r.followDayOfWeek)
            a.put(o)
        }
        return a
    }

    private fun periodsJson(periods: List<PeriodSetting>): JSONArray {
        val a = JSONArray()
        periods.sortedBy { it.order }.forEach { p ->
            val o = JSONObject()
            o.put("name", p.name)
            o.put("pos", p.order)
            o.put("startMinute", p.startMinute)
            o.put("endMinute", p.endMinute)
            a.put(o)
        }
        return a
    }

    private fun courseJson(c: Course): JSONObject {
        val o = JSONObject()
        o.put("name", c.name)
        c.shortName?.takeIf { it.isNotBlank() }?.let { o.put("shortName", it) }
        c.teacher?.let { o.put("teacher", it) }
        o.put("colorIndex", c.colorIndex)
        c.colorHex?.let { o.put("colorHex", it) }
        return o
    }

    private fun sessionsJson(sessions: List<CourseSession>): JSONArray {
        val a = JSONArray()
        sessions.forEach { sn ->
            val o = JSONObject()
            o.put("dayOfWeek", sn.dayOfWeek)
            o.put("startPeriodIdx", sn.startPeriodIdx)
            o.put("endPeriodIdx", sn.endPeriodIdx)
            o.put("activeWeeksText", sn.activeWeeksText)
            sn.location?.takeIf { it.isNotBlank() }?.let { o.put("location", it) }
            sn.startMinute?.let { o.put("startMinute", it) }
            sn.endMinute?.let { o.put("endMinute", it) }
            a.put(o)
        }
        return a
    }

    /** 解析并校验。非法输入抛 [IllegalArgumentException]。返回全部课表。 */
    fun parse(json: String): List<TableDump> {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("文件不是有效的课表备份") }
        val version = root.optInt("version", -1)
        return when (version) {
            FORMAT_VERSION, LEGACY_VERSION -> parseV3Plus(root) // v4 原生；v3 旧版地点在课程 → 统一分发到安排
            OLDEST_LEGACY_VERSION -> listOf(parseV2(root))
            else -> throw IllegalArgumentException("备份版本不受支持（v$version）")
        }
    }

    private fun parseV3Plus(root: JSONObject): List<TableDump> {
        val tables = root.optJSONArray("tables")
            ?: throw IllegalArgumentException("备份缺少 tables")
        if (tables.length() == 0) throw IllegalArgumentException("备份中没有课表")
        val out = mutableListOf<TableDump>()
        for (i in 0 until tables.length()) {
            val tb = tables.optJSONObject(i) ?: continue
            val name = tb.optString("name", "课表")
            val s = tb.optJSONObject("settings") ?: throw IllegalArgumentException("备份缺少课表设置")
            val settings = AppSettings(
                timetableId = 0L,
                termStartEpochDay = s.optLong("termStartEpochDay", 0L),
                totalWeeks = s.optInt("totalWeeks", 20),
                shownDays = s.optInt("shownDays", 5),
                themeMode = s.optInt("themeMode", 0),
                showLocation = s.optBoolean("showLocation", true),
                showTeacher = s.optBoolean("showTeacher", true),
                showOtherWeeks = s.optBoolean("showOtherWeeks", false),
                showShortNameInGrid = s.optBoolean("showShortNameInGrid", false),
                showShortNameInWidget = s.optBoolean("showShortNameInWidget", false),
                autoHoliday = s.optBoolean("autoHoliday", false), // 旧备份无此字段 → 关
            )
            val periods = mutableListOf<PeriodSetting>()
            tb.optJSONArray("periods")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val o = arr.optJSONObject(j) ?: continue
                    periods += PeriodSetting(
                        id = 0L,
                        order = o.optInt("pos", periods.size),
                        name = o.optString("name", "第${periods.size + 1}节"),
                        startMinute = o.optInt("startMinute", 0),
                        endMinute = o.optInt("endMinute", 0),
                        timetableId = 0L,
                    )
                }
            }
            val courses = mutableListOf<CourseDump>()
            tb.optJSONArray("courses")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val o = arr.optJSONObject(j) ?: continue
                    val co = o.optJSONObject("course") ?: continue
                    val courseLocation = co.optString("location", "").ifBlank { null } // v3 遗留：课程级地点
                    val course = Course(
                        id = 0L,
                        name = co.optString("name", ""),
                        shortName = co.optString("shortName", "").ifBlank { null }, // 旧备份无此字段 → 无简称
                        teacher = co.optString("teacher", "").ifBlank { null },
                        colorIndex = co.optInt("colorIndex", 0),
                        colorHex = co.optString("colorHex", "").ifBlank { null },
                        timetableId = 0L,
                    )
                    val sessions = mutableListOf<CourseSession>()
                    o.optJSONArray("sessions")?.let { sarr ->
                        for (k in 0 until sarr.length()) {
                            val so = sarr.optJSONObject(k) ?: continue
                            val own = so.optString("location", "").ifBlank { null }
                            sessions += CourseSession(
                                id = 0L,
                                courseId = 0L,
                                dayOfWeek = so.optInt("dayOfWeek", 1),
                                startPeriodIdx = so.optInt("startPeriodIdx", 0),
                                endPeriodIdx = so.optInt("endPeriodIdx", 0),
                                activeWeeksText = so.optString("activeWeeksText", ""),
                                location = own ?: courseLocation, // 旧版无地点 → 沿用该课整课地点
                                startMinute = optionalMinute(so, "startMinute"),
                                endMinute = optionalMinute(so, "endMinute"),
                            )
                        }
                    }
                    courses += CourseDump(course, sessions)
                }
            }
            out += TableDump(name, settings, periods.sortedBy { it.order }, courses, parseOverrides(tb))
        }
        return out
    }

    private fun optionalMinute(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val minute = o.getInt(key)
        require(minute in 0..1439) { "备份中的课程时间无效" }
        return minute
    }

    /** 解析 dayOverrides（旧备份无此数组 → 空）。日期或 kind 明显非法的行直接跳过。 */
    private fun parseOverrides(table: JSONObject): List<DayOverride> {
        val arr = table.optJSONArray("dayOverrides") ?: return emptyList()
        val out = mutableListOf<DayOverride>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val epochDay = o.optLong("dateEpochDay", Long.MIN_VALUE)
            if (epochDay == Long.MIN_VALUE) continue
            val kind = o.optInt("kind", DayOverride.KIND_REST)
            if (kind != DayOverride.KIND_REST && kind != DayOverride.KIND_FOLLOW) continue
            out += DayOverride(
                timetableId = 0L,
                dateEpochDay = epochDay,
                kind = kind,
                followDayOfWeek = o.optInt("followDayOfWeek", 1).coerceIn(1, 7),
            )
        }
        return out
    }

    /** 旧 v2 单课表结构 → 一个默认命名课表。地点在课程上 → 下放到它全部安排。 */
    private fun parseV2(root: JSONObject): TableDump {
        val s = root.optJSONObject("settings") ?: throw IllegalArgumentException("备份缺少 settings")
        val settings = AppSettings(
            timetableId = 0L,
            termStartEpochDay = s.optLong("termStartEpochDay", 0L),
            totalWeeks = s.optInt("totalWeeks", 20),
            shownDays = s.optInt("shownDays", 5),
            themeMode = s.optInt("themeMode", 0),
            showLocation = s.optBoolean("showLocation", true),
            showTeacher = s.optBoolean("showTeacher", true),
            showOtherWeeks = s.optBoolean("showOtherWeeks", false),
            showShortNameInGrid = s.optBoolean("showShortNameInGrid", false),
            showShortNameInWidget = s.optBoolean("showShortNameInWidget", false),
        )
        val periods = mutableListOf<PeriodSetting>()
        root.optJSONArray("periods")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                periods += PeriodSetting(
                    id = 0L,
                    order = o.optInt("pos", i),
                    name = o.optString("name", "第${i + 1}节"),
                    startMinute = o.optInt("startMinute", 0),
                    endMinute = o.optInt("endMinute", 0),
                    timetableId = 0L,
                )
            }
        }
        // 保留原始课程 id 便于把 sessions 归到对应课程，最后统一清零
        val locationOf = mutableMapOf<Long, String?>()
        val courses = mutableListOf<CourseDump>()
        root.optJSONArray("courses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cid = o.optLong("id", 0L)
                val courseLocation = o.optString("location", "").ifBlank { null }
                if (cid != 0L) locationOf[cid] = courseLocation
                courses += CourseDump(
                    Course(
                        id = cid,
                        name = o.optString("name", ""),
                        teacher = o.optString("teacher", "").ifBlank { null },
                        colorIndex = o.optInt("colorIndex", 0),
                        colorHex = o.optString("colorHex", "").ifBlank { null },
                        timetableId = 0L,
                    ),
                    emptyList(),
                )
            }
        }
        root.optJSONArray("sessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cid = o.optLong("courseId", 0L)
                val idx = courses.indexOfFirst { it.course.id == cid && it.course.id != 0L }
                val session = CourseSession(
                    id = 0L,
                    courseId = 0L,
                    dayOfWeek = o.optInt("dayOfWeek", 1),
                    startPeriodIdx = o.optInt("startPeriodIdx", 0),
                    endPeriodIdx = o.optInt("endPeriodIdx", 0),
                    activeWeeksText = o.optString("activeWeeksText", ""),
                    location = locationOf[cid],
                )
                if (idx >= 0) courses[idx] = courses[idx].copy(sessions = courses[idx].sessions + session)
            }
        }
        return TableDump(
            "导入课表",
            settings,
            periods.sortedBy { it.order },
            courses.map { cd -> cd.copy(course = cd.course.copy(id = 0L)) },
        )
    }

    // ---------- 数据库级读写 ----------

    suspend fun export(db: AppDatabase): String {
        val tables = db.timetableDao().getAll().map { t ->
            val tid = t.id
            val settings = db.settingsDao().getByTimetable(tid)
                ?: com.dannr.chengzikb.data.Defaults.defaultSettings(LocalDate.now(), tid)
            val periods = db.periodDao().getAllByTimetable(tid)
            val allSessions = db.courseSessionDao().getAllByTimetable(tid).groupBy { it.courseId }
            val courses = db.courseDao().getAllByTimetable(tid).map { c ->
                CourseDump(c, allSessions[c.id].orEmpty())
            }
            TableDump(t.name, settings, periods, courses, db.dayOverrideDao().getAllByTimetable(tid))
        }
        return buildTables(tables)
    }

    /** 导入（整体替换，事务原子）：用备份的全部课表重建数据库。返回首个课表 id。 */
    suspend fun import(db: AppDatabase, json: String): Long {
        val tables = parse(json)
        return db.withTransaction {
            // 清库：先删安排/课程，再删作息/设置/课表/元数据
            db.courseSessionDao().deleteAll()
            db.courseDao().deleteAll()
            db.periodDao().deleteAll()
            db.settingsDao().deleteAll()
            db.dayOverrideDao().deleteAll()
            db.timetableDao().deleteAll()
            db.metaDao().deleteAll()
            var firstId = 0L
            tables.forEach { t ->
                val tid = db.timetableDao().insert(Timetable(name = t.name.ifBlank { "课表" }))
                if (firstId == 0L) firstId = tid
                db.settingsDao().upsert(t.settings.copy(timetableId = tid))
                if (t.dayOverrides.isNotEmpty()) {
                    db.dayOverrideDao().insertAll(t.dayOverrides.map { it.copy(timetableId = tid) })
                }
                if (t.periods.isNotEmpty()) {
                    db.periodDao().insertAll(t.periods.map { it.copy(id = 0L, timetableId = tid) })
                }
                t.courses.forEach { cd ->
                    val cid = db.courseDao().upsert(cd.course.copy(id = 0L, timetableId = tid))
                    if (cd.sessions.isNotEmpty()) {
                        db.courseSessionDao().insertAll(cd.sessions.map { it.copy(id = 0L, courseId = cid) })
                    }
                }
            }
            if (firstId > 0) db.metaDao().put(MetaEntry(MetaEntry.KEY_ACTIVE_TIMETABLE, firstId.toString()))
            firstId
        }
    }
}
