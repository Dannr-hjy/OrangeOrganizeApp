package com.dannr.chengzikb

import com.dannr.chengzikb.data.Defaults
import com.dannr.chengzikb.data.import.IcsImporter
import com.dannr.chengzikb.ui.settings.conflictCurrentLine
import com.dannr.chengzikb.ui.settings.conflictFileLine
import com.dannr.chengzikb.ui.settings.conflictKeepLine
import com.dannr.chengzikb.ui.settings.conflictProposalLine
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片文案必须让"文件的钟点"和"当前作息"分得清：曾经"保持当前作息"下面跟着文件的
 * 14:30–16:00，读起来像是当前作息被改成了 14:30–16:00。
 */
class IcsConflictCardTextTest {
    private val conflict = IcsImporter
        .buildCourses(LocalDate.of(2026, 9, 7), 19, Defaults.defaultPeriods(),
            checkNotNull(javaClass.getResourceAsStream("/ics/wakeup-reference.ics"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
        .periodConflicts.single { it.label == "第5–6节" }

    @Test fun `file and current clocks are labelled separately`() {
        assertEquals("文件钟点 14:30–16:00（90 分钟）", conflictFileLine(conflict))
        assertEquals("当前作息 14:00–15:40（100 分钟）", conflictCurrentLine(conflict))
    }

    @Test fun `accepting says what the timetable becomes`() {
        assertEquals("作息改为 14:30–15:10、15:20–16:00（课间不变）", conflictProposalLine(conflict))
    }

    @Test fun `declining says the timetable is untouched and the course keeps the file clock`() {
        val line = conflictKeepLine(conflict)
        assertEquals("作息不动，课程按 14:30–16:00 显示", line)
        // 不能把当前作息的时间说成文件的钟点，也不能把文件的钟点说成作息被改了
        assertTrue("保持作息不应出现推算结果", !line.contains("15:10"))
        assertTrue("保持作息应先声明作息不动", line.startsWith("作息不动"))
    }
}
