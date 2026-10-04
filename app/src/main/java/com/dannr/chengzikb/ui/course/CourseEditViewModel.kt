package com.dannr.chengzikb.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dannr.chengzikb.data.model.Course
import com.dannr.chengzikb.data.model.CourseSession
import com.dannr.chengzikb.data.repo.CourseRepository
import kotlinx.coroutines.launch

/**
 * 课程（模板）+ 其一周内多条安排的增删改。
 */
class CourseEditViewModel(
    private val courseRepository: CourseRepository,
    private val courseId: Long?,
) : ViewModel() {

    val isEdit: Boolean get() = courseId != null && courseId > 0

    suspend fun loadCourse(): Course? = courseId?.takeIf { it > 0 }?.let { courseRepository.courseById(it) }

    suspend fun loadSessions(): List<CourseSession> =
        courseId?.takeIf { it > 0 }?.let { courseRepository.sessionsFor(it) } ?: emptyList()

    /** @param onConflict 保存会与别的课冲突时回调（不落库） */
    fun save(course: Course, sessions: List<CourseSession>, onDone: () -> Unit, onConflict: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = courseRepository.trySaveCourseWithSessions(course, sessions)
            if (ok) onDone() else onConflict()
        }
    }

    fun deleteCourse(courseId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            courseRepository.deleteCourse(courseId)
            onDone()
        }
    }
}
