package com.aneb.probe.ui.theme

import com.aneb.probe.engine.KpiGrading
import com.aneb.probe.ui.ResultFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grade（展示层分级单一事实来源）与 engine/KpiGrading、ui/ResultFormat 的对齐锁。
 * 纯 JVM——Grade 不含 Compose/Android 依赖，可直接单测。
 */
class GradeTest {

    @Test
    fun fromKey_alignsWithKpiGradingConstants() {
        assertEquals(Grade.Excellent, Grade.fromKey(KpiGrading.EXCELLENT))
        assertEquals(Grade.Good, Grade.fromKey(KpiGrading.GOOD))
        assertEquals(Grade.Fair, Grade.fromKey(KpiGrading.FAIR))
        assertEquals(Grade.Poor, Grade.fromKey(KpiGrading.POOR))
    }

    @Test
    fun fromKey_unknownOrNull_isNull() {
        // R-10：失败/未知分级绝不折叠成某一档
        assertNull(Grade.fromKey(null))
        assertNull(Grade.fromKey("bogus"))
        assertNull(Grade.fromKey(""))
    }

    @Test
    fun keyRoundTrips() {
        Grade.entries.forEach { g -> assertEquals(g, Grade.fromKey(g.key)) }
    }

    @Test
    fun cnLabels_matchResultFormat() {
        // Grade.labelCn 必须与 ResultFormat.gradeLabel 逐档一致（同一套优/良/可/差）
        Grade.entries.forEach { g ->
            assertEquals(ResultFormat.gradeLabel(g.key), g.labelCn)
        }
    }

    @Test
    fun friendlyLabels() {
        assertEquals("优秀", Grade.Excellent.labelFriendly)
        assertEquals("良好", Grade.Good.labelFriendly)
        assertEquals("一般", Grade.Fair.labelFriendly)
        assertEquals("较差", Grade.Poor.labelFriendly)
    }

    @Test
    fun fromAqsScore_matchesResultFormatThresholds() {
        // Grade.fromAqsScore 与 ResultFormat.aqsGrade 同门限（≥85 优 / 70 / 55）
        val samples = listOf(0.0, 54.9, 55.0, 69.9, 70.0, 84.9, 85.0, 89.2, 100.0)
        samples.forEach { s ->
            assertEquals(
                "score=$s",
                Grade.fromKey(ResultFormat.aqsGrade(s)),
                Grade.fromAqsScore(s),
            )
        }
    }

    @Test
    fun fromAqsScore_boundaries() {
        assertEquals(Grade.Poor, Grade.fromAqsScore(54.9))
        assertEquals(Grade.Fair, Grade.fromAqsScore(55.0))
        assertEquals(Grade.Good, Grade.fromAqsScore(70.0))
        assertEquals(Grade.Excellent, Grade.fromAqsScore(85.0))
    }
}
