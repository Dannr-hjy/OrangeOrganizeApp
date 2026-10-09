package com.dannr.chengzikb.data.import

import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.domain.WeekMath
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** ICS parsing without database or Android dependencies. Timetable clocks use Shanghai time. */
internal object IcsParser {
    private val timetableZone = ZoneId.of("Asia/Shanghai")
    private val spanRegex = Regex("第\\s*(\\d+)\\s*(?:[-–—－~～至]\\s*(\\d+))?\\s*节")
    private data class Field(val key: String, val params: Map<String, String>, val value: String)
    private data class Slot(val day: Int, val first: Int, val last: Int, val room: String?, val start: Int, val end: Int)

    fun parsePeriodSpan(description: String?): Pair<Int, Int>? {
        val match = spanRegex.find(unescape(description.orEmpty())) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val last = match.groupValues[2].toIntOrNull() ?: first
        return if (first in 1..64 && last in first..64) first - 1 to last - 1 else null
    }

    /** Map overlapping configured periods, never guess a 45-minute lesson or a break. */
    fun mapByTime(periods: List<PeriodSetting>, start: LocalDateTime, end: LocalDateTime): Pair<Int, Int> {
        val sm = start.hour * 60 + start.minute
        val em = end.hour * 60 + end.minute
        val matches = periods.filter { it.startMinute < em && it.endMinute > sm }.map { it.order }
        require(matches.isNotEmpty()) { "上课时间 ${start.toLocalTime()}–${end.toLocalTime()} 不在作息表内，请设置作息或在文件中注明节次" }
        return matches.min() to matches.max()
    }

    fun buildCourses(termStart: LocalDate, totalWeeks: Int, configured: List<PeriodSetting>, ics: String): IcsImporter.IcsResult {
        require(totalWeeks in 1..104) { "学期总周数应在 1–104 周之间" }
        val lines = unfold(ics)
        val calendarFields = fields(lines.dropWhile { !it.equals("BEGIN:VCALENDAR", true) }.drop(1))
        val calendarZone = calendarFields.firstOrNull { it.key == "X-WR-TIMEZONE" }?.value
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: timetableZone
        val importedPeriods = calendarFields.firstOrNull { it.key == "X-ORANGE-PERIODS" }?.let { parsePeriods(unescape(it.value)) }.orEmpty()
        val periods = if (importedPeriods.isNotEmpty()) importedPeriods else configured
        val groups = linkedMapOf<Pair<String, String?>, MutableMap<Slot, MutableSet<Int>>>()
        val warnings = mutableListOf<String>()
        for (block in eventBlocks(lines)) {
            val props = fields(block)
            fun field(key: String) = props.firstOrNull { it.key == key }
            val rawName = field("SUMMARY")?.value?.let(::unescape)?.trim().orEmpty()
            if (rawName.isBlank()) continue
            if (field("STATUS")?.value.equals("CANCELLED", true)) continue
            try {
                require(field("RECURRENCE-ID") == null) { "暂不支持单次重复事件的改期，请先展开为单次日程" }
                val start = field("DTSTART")?.let { parseTime(it, calendarZone) }
                    ?: error("缺少有效的开始时间")
                val end = field("DTEND")?.let { parseTime(it, start.zone) }
                    ?: field("DURATION")?.value?.let { start.plus(Duration.parse(it)) }
                    ?: error("缺少有效的结束时间")
                require(end.isAfter(start)) { "结束时间必须晚于开始时间" }
                val description = field("DESCRIPTION")?.value?.let(::unescape).orEmpty()
                val descLines = description.lines().map { it.trim() }
                val wakeUp = descLines.firstOrNull()?.let { spanRegex.matches(it) } == true
                val teacher = if (wakeUp) descLines.getOrNull(2)?.ifBlank { null }
                    else descLines.firstNotNullOfOrNull { Regex("^(?:教师|老师)[:：]\\s*(.+)$").matchEntire(it)?.groupValues?.get(1) }
                val room = (if (wakeUp) descLines.getOrNull(1)?.ifBlank { null } else null)
                    ?: field("LOCATION")?.value?.let(::unescape)?.trim()?.ifBlank { null }
                val name = rawName.replace(Regex("[（(]第\\s*\\d+\\s*(?:[-–—－~～至]\\s*\\d+)?\\s*节[）)]$"), "").trim()
                val span = parsePeriodSpan(description) ?: parsePeriodSpan(rawName)
                val duration = Duration.between(start, end)
                val excluded = props.filter { it.key == "EXDATE" }.flatMap { f ->
                    f.value.split(',').mapNotNull { parseTime(f.copy(value = it), start.zone)?.toInstant() }
                }.toSet()
                val extra = props.filter { it.key == "RDATE" }.flatMap { f ->
                    f.value.split(',').mapNotNull { parseTime(f.copy(value = it), start.zone) }
                }
                val dates = (occurrences(start, field("RRULE")?.value, termStart, totalWeeks) + extra)
                    .distinctBy { it.toInstant() }.filterNot { it.toInstant() in excluded }
                val eventSlots = linkedMapOf<Slot, MutableSet<Int>>()
                for (occurrence in dates) {
                    val localStart = occurrence.withZoneSameInstant(timetableZone)
                    val localEnd = occurrence.plus(duration).withZoneSameInstant(timetableZone)
                    val date = localStart.toLocalDate()
                    if (date < termStart || date >= termStart.plusWeeks(totalWeeks.toLong())) continue
                    require(localEnd.toLocalDate() == date) { "暂不支持跨午夜的课程" }
                    val (first, last) = span ?: mapByTime(periods, localStart.toLocalDateTime(), localEnd.toLocalDateTime())
                    val slot = Slot(date.dayOfWeek.value, first, last, room,
                        localStart.hour * 60 + localStart.minute, localEnd.hour * 60 + localEnd.minute)
                    eventSlots.getOrPut(slot) { sortedSetOf() }
                        .add(WeekMath.weekIndexOf(date, termStart))
                }
                eventSlots.forEach { (slot, weeks) ->
                    groups.getOrPut(name to teacher) { linkedMapOf() }.getOrPut(slot) { sortedSetOf() }.addAll(weeks)
                }
            } catch (e: Exception) {
                warnings += "$rawName：${e.message ?: "无法解析"}"
            }
        }
        val courses = groups.entries.sortedBy { it.key.first }.mapIndexed { index, (meta, slots) ->
            IcsImporter.MergedCourse(meta.first, meta.second, index % 7,
                slots.entries.sortedWith(compareBy({ it.key.day }, { it.key.first }, { it.key.start })).map { (slot, weeks) ->
                    IcsImporter.SessionSpec(slot.day, slot.first, slot.last, weeks.toSet(), slot.room, slot.start, slot.end)
                })
        }
        val alignment = if (importedPeriods.isEmpty()) alignPeriods(configured, courses.flatMap { it.sessions }) else emptyList<PeriodSetting>() to emptyList()
        return IcsImporter.IcsResult(
            courses = courses, sessionCount = courses.sumOf { it.sessions.size },
            periods = importedPeriods.ifEmpty { alignment.first }, warnings = warnings.distinct(),
            periodsAligned = importedPeriods.isEmpty() && alignment.first.isNotEmpty(), periodNotes = alignment.second,
        )
    }

    /**
     * A normal ICS supplies the outer clock of a lesson span, not its internal breaks.
     * When that duration matches the configured span, translate the whole span while
     * retaining the user's lesson lengths and gaps. Single-period events are exact.
     */
    private fun alignPeriods(configured: List<PeriodSetting>, sessions: List<IcsImporter.SessionSpec>): Pair<List<PeriodSetting>, List<String>> {
        if (configured.isEmpty() || sessions.isEmpty()) return emptyList<PeriodSetting>() to emptyList()
        val notes = mutableListOf<String>()
        val proposed = mutableMapOf<Int, MutableSet<Pair<Int, Int>>>()
        val byOrder = configured.associateBy { it.order }
        for ((span, group) in sessions.groupBy { it.startPeriodIdx to it.endPeriodIdx }) {
            val label = "第${span.first + 1}–${span.second + 1}节"
            val clocks = group.mapNotNull { s -> s.startMinute?.let { a -> s.endMinute?.let { b -> a to b } } }.distinct()
            if (clocks.size != 1) {
                notes += "$label 有多个不同上课时间，已按课程分别保存，无法统一作息。"
                continue
            }
            val (start, end) = clocks.single()
            val block = (span.first..span.second).mapNotNull { byOrder[it] }
            if (block.size != span.second - span.first + 1) {
                notes += "$label 尚未在作息表中配置。"
                continue
            }
            val replacements = if (block.size == 1) listOf(block.single().copy(startMinute = start, endMinute = end)) else {
                if (block.last().endMinute - block.first().startMinute != end - start) {
                    notes += "$label 的连堂时长与当前作息不同；文件没有课间边界，课程钟点已保留，请补充完整作息。"
                    continue
                }
                val delta = start - block.first().startMinute
                block.map { it.copy(startMinute = it.startMinute + delta, endMinute = it.endMinute + delta) }
            }
            replacements.forEach { p -> proposed.getOrPut(p.order) { mutableSetOf() }.add(p.startMinute to p.endMinute) }
        }
        if (proposed.values.any { it.size != 1 }) {
            return emptyList<PeriodSetting>() to (notes + "文件中的节次时间互相冲突，课程钟点已分别保存，未统一作息。")
        }
        val updated = configured.sortedBy { it.order }.map { p ->
            proposed[p.order]?.single()?.let { (a, b) -> p.copy(startMinute = a, endMinute = b) } ?: p
        }
        if (updated.any { it.startMinute !in 0..1439 || it.endMinute !in 0..1439 || it.startMinute >= it.endMinute } ||
            !updated.zipWithNext().all { (a, b) -> a.endMinute <= b.startMinute }) {
            return emptyList<PeriodSetting>() to (notes + "更新后的节次会与其他作息重叠，课程钟点已分别保存，请检查作息表。")
        }
        return (if (updated == configured.sortedBy { it.order }) emptyList() else updated) to notes.distinct()
    }

    /** Weekly recurrence is bounded by the selected semester even without UNTIL. */
    private fun occurrences(start: ZonedDateTime, rule: String?, termStart: LocalDate, totalWeeks: Int): List<ZonedDateTime> {
        if (rule.isNullOrBlank()) return listOf(start)
        val parts = rule.uppercase().split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        require(parts["FREQ"] == "WEEKLY") { "暂只支持按周重复或单次日程" }
        require(parts.keys.all { it in setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "BYDAY", "WKST") }) { "包含暂不支持的重复规则" }
        val interval = parts["INTERVAL"]?.let { it.toIntOrNull() ?: error("重复间隔无效") } ?: 1
        require(interval in 1..104) { "重复间隔无效" }
        val count = parts["COUNT"]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: error("重复次数无效") }
        val until = parts["UNTIL"]?.let { parseTime(Field("UNTIL", emptyMap(), it), start.zone) ?: error("重复结束时间无效") }
        val codes = mapOf("MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6, "SU" to 7)
        val weekStart = parts["WKST"]?.let { codes[it] ?: error("周起始日无效") } ?: 1
        val days = parts["BYDAY"]?.split(',')?.map { codes[it] ?: error("重复星期无效") } ?: listOf(start.dayOfWeek.value)
        var anchor = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekStart)))
        val limit = termStart.plusWeeks(totalWeeks.toLong()).plusDays(2)
        val out = mutableListOf<ZonedDateTime>()
        var emitted = 0
        var iterations = 0
        while (anchor < limit) {
            require(iterations++ < 100000) { "重复日程范围过长" }
            for (offset in days.map { (it - weekStart + 7) % 7 }.distinct().sorted()) {
                val candidate = ZonedDateTime.of(anchor.plusDays(offset.toLong()), start.toLocalTime(), start.zone)
                if (candidate < start) continue
                if (until != null && candidate > until) return out
                if (count != null && emitted >= count) return out
                emitted++
                out += candidate
            }
            anchor = anchor.plusWeeks(interval.toLong())
        }
        return out
    }

    private fun parseTime(field: Field, fallbackZone: ZoneId): ZonedDateTime? = runCatching {
        require(field.params["VALUE"] != "DATE") { "全天日程没有上课钟点" }
        val value = field.value.trim()
        val utc = value.endsWith('Z', true)
        val zone = if (utc) ZoneOffset.UTC else field.params["TZID"]?.let { ZoneId.of(it.trim('"')) } ?: fallbackZone
        val core = if (utc) value.dropLast(1) else value
        val format = when (core.length) {
            15 -> "yyyyMMdd'T'HHmmss"
            13 -> "yyyyMMdd'T'HHmm"
            else -> error("无效的日期时间")
        }
        LocalDateTime.parse(core, DateTimeFormatter.ofPattern(format)).atZone(zone)
    }.getOrNull()

    /** X-ORANGE-PERIODS:1=08:20-09:05,2=09:15-10:00,...; optional exact school timetable. */
    private fun parsePeriods(value: String): List<PeriodSetting> {
        val periods = value.split(',').map { raw ->
            val m = Regex("(\\d+)=(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})").matchEntire(raw.trim())
                ?: error("文件中的作息时间格式无效")
            fun minute(h: Int, n: Int): Int {
                val hour = m.groupValues[h].toInt()
                val min = m.groupValues[n].toInt()
                require(hour in 0..23 && min in 0..59) { "文件中的作息时间无效" }
                return hour * 60 + min
            }
            val order = m.groupValues[1].toInt() - 1
            val start = minute(2, 3)
            val end = minute(4, 5)
            require(order in 0..63 && start < end) { "文件中的节次或起止时间无效" }
            PeriodSetting(order = order, name = "第${order + 1}节", startMinute = start, endMinute = end)
        }.sortedBy { it.order }
        require(periods.map { it.order } == periods.indices.toList()) { "文件中的节次必须从第1节开始连续排列" }
        require(periods.zipWithNext().all { (a, b) -> a.endMinute <= b.startMinute }) { "文件中的作息时段重叠" }
        return periods
    }

    private fun unescape(text: String): String = Regex("\\\\([nN,;\\\\])").replace(text) {
        when (it.groupValues[1]) { "n", "N" -> "\n"; else -> it.groupValues[1] }
    }

    private fun unfold(ics: String): List<String> {
        val out = mutableListOf<String>()
        for (line in ics.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()) {
            if ((line.startsWith(' ') || line.startsWith('\t')) && out.isNotEmpty()) out[out.lastIndex] += line.drop(1)
            else out += line
        }
        return out
    }

    private fun eventBlocks(lines: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        for (line in lines) when {
            line.equals("BEGIN:VEVENT", true) -> current = mutableListOf()
            line.equals("END:VEVENT", true) -> { current?.let { out += it }; current = null }
            else -> current?.add(line)
        }
        return out
    }

    /** Only direct properties: a VALARM's SUMMARY/DESCRIPTION must never overwrite the course. */
    private fun fields(lines: List<String>): List<Field> {
        val out = mutableListOf<Field>()
        var depth = 0
        for (line in lines) {
            if (line.startsWith("BEGIN:", true)) { depth++; continue }
            if (line.startsWith("END:", true)) { if (depth == 0) break else depth--; continue }
            if (depth != 0) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val head = line.substring(0, colon).split(';')
            val params = head.drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
            out += Field(head.first().uppercase(), params, line.substring(colon + 1))
        }
        return out
    }
}
