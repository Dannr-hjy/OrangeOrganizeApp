package com.dannr.chengzikb.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dannr.chengzikb.data.model.AppSettings
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.model.DayOverride
import com.dannr.chengzikb.data.model.Occurrence
import com.dannr.chengzikb.data.model.PeriodSetting
import com.dannr.chengzikb.data.model.Timetable
import com.dannr.chengzikb.data.repo.CourseRepository
import com.dannr.chengzikb.data.repo.DayOverrideRepository
import com.dannr.chengzikb.data.repo.PeriodRepository
import com.dannr.chengzikb.data.repo.SettingsRepository
import com.dannr.chengzikb.data.repo.TimetableManager
import com.dannr.chengzikb.domain.DayPlanIndex
import com.dannr.chengzikb.domain.DayVariants
import com.dannr.chengzikb.domain.WeekMath
import com.dannr.chengzikb.domain.WeekView
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class MainUiState(
    val courses: List<Course> = emptyList(),
    val sessions: List<CourseSession> = emptyList(),
    val periods: List<PeriodSetting> = emptyList(),
    val settings: AppSettings? = null,
    val now: LocalDateTime = LocalDateTime.now(),
    val tables: List<Timetable> = emptyList(),
    val activeTimetableId: Long = 0L,
    /** 用户手动改过的日期（休息 / 按周几上课）；内置节假日方案不在这里 */
    val overrides: List<DayOverride> = emptyList(),
) {
    val termStartMonday: LocalDate? get() = settings?.let { LocalDate.ofEpochDay(it.termStartEpochDay) }
    val todayWeek: Int get() = termStartMonday?.let { WeekMath.weekIndexOf(now.toLocalDate(), it) } ?: 1

    /**
     * 「今天」在课表中的**列位置**——永远是真实日历星期，调休只改列的内容不改列的位置。
     * 想知道今天实际按哪天的课表上课，用 [dayPlans]。
     */
    val todayDayOfWeek: Int get() = WeekMath.dayIndexOf(now.toLocalDate())

    /** 日期 → 上法（手动覆盖 > 自动调休方案 > 真实星期），课表/小组件/提醒共用。
     *  自动调休开启时注入各星期的课表差异分组，以便补课目标星期不一致的日子标为「待选周次」。 */
    val dayPlans: DayPlanIndex
        get() {
            val auto = settings?.autoHoliday == true
            val total = settings?.totalWeeks ?: 0
            return DayPlanIndex.of(
                overrides,
                auto,
                variantsByDay = if (auto && total > 0) DayVariants.allFor(occurrences, total, periods) else emptyMap(),
            )
        }

    val pageCount: Int get() = settings?.totalWeeks?.coerceAtLeast(1) ?: 20
    val occurrences: List<Occurrence> get() = WeekView.occurrences(courses, sessions)
    val activeTimetableName: String
        get() = tables.firstOrNull { it.id == activeTimetableId }?.name ?: "课表"
}

class MainViewModel(
    private val courseRepository: CourseRepository,
    private val periodRepository: PeriodRepository,
    private val settingsRepository: SettingsRepository,
    private val timetableManager: TimetableManager,
    private val dayOverrideRepository: DayOverrideRepository,
) : ViewModel() {

    private val nowFlow = MutableStateFlow(LocalDateTime.now())
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    /** 首屏 combine 的中间聚合：combine 的类型化重载最多 5 路，先把四类列表并成一个 */
    private data class ScheduleData(
        val courses: List<Course>,
        val sessions: List<CourseSession>,
        val periods: List<PeriodSetting>,
        val overrides: List<DayOverride>,
    )

    init {
        viewModelScope.launch {
            val dataFlow = combine(
                courseRepository.allCourses,
                courseRepository.allSessions,
                periodRepository.all,
                dayOverrideRepository.overrides,
            ) { c, s, p, o -> ScheduleData(c, s, p, o) }
            combine(
                dataFlow,
                settingsRepository.settings,
                nowFlow,
                timetableManager.tables,
                timetableManager.activeId,
            ) { data, settings, now, tables, active ->
                MainUiState(
                    courses = data.courses,
                    sessions = data.sessions,
                    periods = data.periods,
                    settings = settings,
                    now = now,
                    tables = tables,
                    activeTimetableId = active,
                    overrides = data.overrides,
                )
            }.collect { _state.value = it }
        }
        // 30s 心跳：驱动倒计时/当前节次/今日高亮
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                nowFlow.value = LocalDateTime.now()
            }
        }
    }

    /* ----- 写操作 ----- */

    suspend fun deleteCourse(id: Long) {
        courseRepository.deleteCourse(id)
    }

    suspend fun deleteSession(session: CourseSession) {
        courseRepository.deleteSession(session)
    }

    suspend fun moveSession(session: CourseSession, day: Int, start: Int, end: Int, thisWeekOnly: Boolean, week: Int): Boolean =
        courseRepository.moveSession(session, day, start, end, thisWeekOnly, week)

    suspend fun saveSettings(s: AppSettings) {
        settingsRepository.save(s)
    }

    fun saveSettingsAsync(s: AppSettings) {
        viewModelScope.launch { settingsRepository.save(s) }
    }

    /* ----- 调休：某一天的手动覆盖 ----- */

    /** @param row null = 清除该日手动设置（恢复为跟随自动方案/当天真实星期） */
    fun setDayOverrideAsync(date: LocalDate, row: DayOverride?) {
        viewModelScope.launch { dayOverrideRepository.set(date, row) }
    }

    /* ----- 多课表 ----- */

    fun activateTable(id: Long) {
        viewModelScope.launch { timetableManager.activate(id) }
    }

    fun createTable(name: String, onDone: () -> Unit = {}) {
        viewModelScope.launch { timetableManager.create(name); onDone() }
    }

    fun renameTable(id: Long, name: String) {
        viewModelScope.launch { timetableManager.rename(id, name) }
    }

    fun deleteTable(id: Long) {
        viewModelScope.launch { timetableManager.delete(id) }
    }
}
