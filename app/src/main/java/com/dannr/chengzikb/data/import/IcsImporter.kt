package com.dannr.chengzikb.data.import

import androidx.room.withTransaction
import com.dannr.chengzikb.data.db.AppDatabase
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.MetaEntry
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.model.WeekSet
import com.dannr.chengzikb.domain.WeekMath
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 导入 WakeUp 导出的 .ics 课程表。
 * 每个 VEVENT 是一次 WEEKLY 重复（带起止周），同一门课可能拆成多段；
 * 本解析器把它们**合并同类项**：相同「名称+老师」的归为一门课（不再按地点/颜色拆分）；
 * 同一门课里相同 星期+节次 的只保留一条安排，周次取并集，上课地点逐安排保存（取首个非空）。
 */
object IcsImporter {

    /** 某一节次槽（同课同星期同时段）聚合出的安排；地点为该槽首个非空教室 */
    private class SlotAcc {
        val weeks = mutableSetOf<Int>()
        var location: String? = null
    }

    data class SessionSpec(
        val day: Int, // 1..7
        val startPeriodIdx: Int,
        val endPeriodIdx: Int,
        val weeks: Set<Int>,
        val location: String? = null,
    )

    data class MergedCourse(
        val name: String,
        val teacher: String?,
        val colorIndex: Int,
        val sessions: List<SessionSpec>,
    )

    data class IcsResult(
        val courses: List<MergedCourse>,
        val sessionCount: Int,
    )

    // ---------------- 纯解析 ----------------

    /** 解析出每个 VEVENT 对应的一条“周课”，week 集是相对 [termStartMonday]/[totalWeeks] 裁剪后的。 */
    fun parseEvents(termStartMonday: LocalDate, totalWeeks: Int, ics: String): List<SessionSpec> {
        val blocks = extractBlocks(ics)
        val out = mutableListOf<SessionSpec>()
        for (block in blocks) {
            val map = fieldMap(block) ?: continue
            val summary = map["SUMMARY"]?.trim() ?: continue
            if (summary.isEmpty()) continue
            val dtStart = map["DTSTART"] ?: continue
            val startLdt = parseLocal(dtStart) ?: continue
            val endLdt = map["DTEND"]?.let { parseLocal(it) } ?: startLdt.plusMinutes(45)

            val rrule = map["RRULE"] ?: ""
            val untilUtc = Regex("UNTIL=(\\d{8}T\\d{6})Z?").find(rrule)?.groupValues?.get(1)
            val lastLdt = untilUtc?.let {
                runCatching {
                    LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(ZoneOffset.ofHours(8))
                        .toLocalDateTime()
                }.getOrNull()
            }

            // 依次生成每周一次的日期，直到超出表内周次
            val weekSet = sortedSetOf<Int>()
            var cursor = startLdt
            while (lastLdt == null || !cursor.isAfter(lastLdt)) {
                val date = cursor.toLocalDate()
                val w = WeekMath.weekIndexOf(date, termStartMonday)
                if (w in 1..totalWeeks) weekSet += w
                cursor = cursor.plusWeeks(1)
            }
            if (weekSet.isEmpty()) continue

            val day = WeekMath.dayIndexOf(startLdt.toLocalDate())
            val (si, ei) = parsePeriodSpan(map["DESCRIPTION"]) ?: mapByTime(emptyList(), startLdt, endLdt)
            out += SessionSpec(day, si, ei, weekSet.toSet())
        }
        return out
    }

    /**
     * 解析 DESCRIPTION 中的“第x - y节”文字 → 返回 0 基节次区间（**按节数定位，与具体几点无关**）。
     * 在整个描述文本中搜索该模式（可能不落在首行）。
     */
    fun parsePeriodSpan(description: String?): Pair<Int, Int>? {
        if (description.isNullOrBlank()) return null
        val text = description.replace("\\n", "\n")
        val m = Regex("第\\s*(\\d+)\\s*(?:[-~至]\\s*(\\d+))?\\s*节").find(text) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: a
        if (a < 1 || b < a) return null
        return (a - 1) to (b - 1)
    }

    /** 无节次文字时按起止时间在作息表中就近映射。periods 供外部使用；此处为空时按整点猜测。 */
    fun mapByTime(periods: List<PeriodSetting>, startLdt: LocalDateTime, endLdt: LocalDateTime): Pair<Int, Int> {
        val sm = startLdt.hour * 60 + startLdt.minute
        val em = endLdt.hour * 60 + endLdt.minute
        if (periods.isEmpty()) {
            // 无作息时粗略按小时段：第1节 08:00 起、其后每 ~45min 一节
            val si = ((sm - 480).coerceAtLeast(0) / 45).coerceAtLeast(0)
            val ei = ((em - 480).coerceAtLeast(45) / 45 - 1).coerceAtLeast(si)
            return si to ei
        }
        var si = periods.indexOfLast { it.startMinute <= sm }
        if (si < 0) si = 0
        var ei = periods.indexOfFirst { it.endMinute >= em }
        if (ei < 0) ei = periods.lastIndex
        if (ei < si) ei = si
        return si to ei
    }

    /** 解析单个 VEVENT 文本块 → 字段表。 */
    private fun fieldMap(block: List<String>): Map<String, String>? {
        val map = mutableMapOf<String, String>()
        for (raw in block) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("BEGIN:") || line.startsWith("END:")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            // head 可能是 “DTSTART;TZID=Asia/Shanghai”
            val key = head.substringBefore(';').uppercase()
            map[key] = value
        }
        return map
    }

    private fun extractBlocks(ics: String): List<List<String>> {
        val unfolded = ics.replace("\r\n", "\n").replace("\r", "\n")
            .lines()
        val clean = mutableListOf<String>()
        for (l in unfolded) {
            if (l.startsWith(" ") || l.startsWith("\t")) {
                if (clean.isNotEmpty()) clean[clean.size - 1] = clean.last() + l.trim()
            } else {
                clean += l
            }
        }
        val blocks = mutableListOf<List<String>>()
        var cur: MutableList<String>? = null
        for (l in clean) {
            when {
                l.startsWith("BEGIN:VEVENT") -> cur = mutableListOf()
                l.startsWith("END:VEVENT") -> {
                    cur?.let { blocks += it }
                    cur = null
                }
                cur != null -> cur += l
            }
        }
        return blocks
    }

    /** "20261027T080000" → LocalDateTime；容忍无秒/带 Z */
    private fun parseLocal(v: String): LocalDateTime? {
        val t = v.trim().removeSuffix("Z").substringBeforeLast(':').trim()
        return runCatching {
            val core = t.substringBefore('+').substringBefore('-')
            if (core.length >= 15) {
                LocalDateTime.parse(core.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            } else {
                // 形如 20261027T0800
                val dt = core.replace("T", "")
                LocalDateTime.parse(dt, DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
            }
        }.getOrNull()
    }

    /** 从每个 VEVENT 里取出课程资料：名称 / 地点 / 老师（DESCRIPTION 第2/3行优先，LOCATION 兜底）。 */
    private fun courseMeta(block: List<String>): Triple<String, String?, String?>? {
        val map = fieldMap(block) ?: return null
        val name = map["SUMMARY"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val descLines = map["DESCRIPTION"]?.replace("\\n", "\n")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
        var location: String? = null
        var teacher: String? = null
        if (descLines != null && descLines.size >= 2) {
            location = descLines.getOrNull(1)
            teacher = descLines.getOrNull(2)
        }
        if (location.isNullOrEmpty()) {
            val locRaw = map["LOCATION"]?.trim() ?: ""
            if (locRaw.isNotEmpty()) {
                val idx = locRaw.lastIndexOf(' ')
                if (idx > 0) {
                    location = locRaw.substring(0, idx).trim()
                    teacher = locRaw.substring(idx + 1).trim()
                } else {
                    location = locRaw
                }
            }
        }
        return Triple(name, teacher?.takeIf { it.isNotEmpty() }, location?.takeIf { it.isNotEmpty() })
    }

    // ---------------- 汇总为课程（合并同类项） ----------------

    fun buildCourses(termStartMonday: LocalDate, totalWeeks: Int, periods: List<PeriodSetting>, ics: String): IcsResult {
        val blocks = extractBlocks(ics)
        // 课程级合并键 = 名称 + 老师（地点已逐安排，不再参与课程归属）
        val metaOf = mutableMapOf<Pair<String, String?>, Triple<String, String?, String?>>()
        val specsOf = mutableMapOf<Pair<String, String?>, MutableMap<Triple<Int, Int, Int>, SlotAcc>>()
        for (block in blocks) {
            val meta = courseMeta(block) ?: continue
            val courseKey = meta.first to meta.second
            val fmap = fieldMap(block) ?: continue
            val dtStart = fmap["DTSTART"] ?: continue
            val startLdt = parseLocal(dtStart) ?: continue
            val endLdt = fmap["DTEND"]?.let { parseLocal(it) } ?: startLdt.plusMinutes(45)
            val rrule = fmap["RRULE"] ?: ""
            val untilUtc = Regex("UNTIL=(\\d{8}T\\d{6})Z?").find(rrule)?.groupValues?.get(1)
            val lastLdt = untilUtc?.let {
                runCatching {
                    LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime()
                }.getOrNull()
            }
            val weekSet = sortedSetOf<Int>()
            var cursor = startLdt
            while (lastLdt == null || !cursor.isAfter(lastLdt)) {
                val w = WeekMath.weekIndexOf(cursor.toLocalDate(), termStartMonday)
                if (w in 1..totalWeeks) weekSet += w
                cursor = cursor.plusWeeks(1)
            }
            if (weekSet.isEmpty()) continue

            val day = WeekMath.dayIndexOf(startLdt.toLocalDate())
            val span = parsePeriodSpan(fmap["DESCRIPTION"]) ?: mapByTime(periods, startLdt, endLdt)
            val slotKey = Triple(day, span.first, span.second)
            metaOf.getOrPut(courseKey) { meta }
            val acc = specsOf.getOrPut(courseKey) { mutableMapOf() }.getOrPut(slotKey) { SlotAcc() }
            acc.weeks += weekSet
            if (acc.location == null && !meta.third.isNullOrBlank()) acc.location = meta.third
        }

        val courses = mutableListOf<MergedCourse>()
        val sortedMeta = metaOf.keys.sortedBy { metaOf.getValue(it).first }
        sortedMeta.forEachIndexed { index, courseKey ->
            val meta = metaOf.getValue(courseKey)
            val sessions = specsOf[courseKey]?.entries
                ?.filter { it.value.weeks.isNotEmpty() }
                ?.sortedWith(compareBy({ it.key.first }, { it.key.second }))
                ?.map { SessionSpec(it.key.first, it.key.second, it.key.third, it.value.weeks.toSet(), it.value.location) }
                ?: emptyList()
            if (sessions.isEmpty()) return@forEachIndexed
            courses += MergedCourse(
                name = meta.first,
                teacher = meta.second,
                colorIndex = index % 7,
                sessions = sessions,
            )
        }
        return IcsResult(courses, courses.sumOf { it.sessions.size })
    }

    // ---------------- 写入当前课表 ----------------

    /** 读取当前课表上下文并构建（不写库），供预览。 */
    suspend fun buildForActive(db: AppDatabase, ics: String): IcsResult {
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
        val settings = db.settingsDao().getByTimetable(active)
        val periods = db.periodDao().getAllByTimetable(active)
        val termStart = settings?.let { LocalDate.ofEpochDay(it.termStartEpochDay) } ?: WeekMath.mondayOfWeek(LocalDate.now())
        val totalWeeks = settings?.totalWeeks ?: 20
        return buildCourses(termStart, totalWeeks, periods, ics)
    }

    /** 覆盖当前课表：先清空当前课表的课程（含安排），再导入。 */
    suspend fun overwriteActive(db: AppDatabase, result: IcsResult) {
        if (result.courses.isEmpty()) return
        val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
            ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
        if (active > 0) db.courseDao().deleteByTimetable(active) // 级联删安排
        write(db, result)
    }

    /** 把结果写入当前课表（新增课程；与现有课程同名会新建而不是并入）。 */
    suspend fun write(db: AppDatabase, result: IcsResult) {
        if (result.courses.isEmpty()) return
        db.withTransaction {
            val active = db.metaDao().get(MetaEntry.KEY_ACTIVE_TIMETABLE)?.toLongOrNull()
                ?: (db.timetableDao().getAll().firstOrNull()?.id ?: 0L)
            // 没有课表（理论不可能）则建默认课表
            val tid = if (active <= 0) db.timetableDao().insert(Timetable(name = "导入课表")) else active
            result.courses.forEach { c ->
                val courseId = db.courseDao().upsert(
                    Course(
                        name = c.name,
                        teacher = c.teacher,
                        colorIndex = c.colorIndex,
                        colorHex = null,
                        timetableId = tid,
                    ),
                )
                c.sessions.forEach { s ->
                    db.courseSessionDao().upsert(
                        CourseSession(
                            courseId = courseId,
                            dayOfWeek = s.day,
                            startPeriodIdx = s.startPeriodIdx,
                            endPeriodIdx = s.endPeriodIdx,
                            activeWeeksText = WeekSet.fromIterable(s.weeks).toText(),
                            location = s.location,
                        ),
                    )
                }
            }
        }
    }
}
