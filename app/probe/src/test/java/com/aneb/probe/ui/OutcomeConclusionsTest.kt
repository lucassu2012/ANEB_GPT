package com.aneb.probe.ui

import com.aneb.probe.engine.KpiGrading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeConclusionsTest {
    private fun input(
        status: String? = "completed",
        score: Double? = 72.0,
        validity: String? = "valid",
        reasons: String? = "",
        c1: Double? = null,
    ) = OutcomeConclusions.Input(
        runStatus = status,
        score = score,
        codingValidity = validity,
        codingInvalidReasons = reasons,
        ttftMs = 640.0,
        ttftGrade = KpiGrading.FAIR,
        stallRate = 0.01,
        stallGrade = KpiGrading.GOOD,
        uploadMbps = 12.0,
        uploadGrade = KpiGrading.GOOD,
        sessionDropRate = c1,
    )

    @Test
    fun `流截断明确归因到应用路径且不冒充引擎错误`() {
        val first = OutcomeConclusions.build(
            input(score = null, validity = "invalid", reasons = "TRUNCATED,GAP_EXCEEDED"),
        ).first()
        assertEquals("编码任务未完成", first.title)
        assertTrue(first.body.contains("流式应用路径"))
        assertEquals(OutcomeConclusions.Evidence.MEASURED, first.evidence)
    }

    @Test
    fun `引擎异常明确声明不是网络质量结论`() {
        val first = OutcomeConclusions.build(
            input(score = null, validity = "invalid", reasons = "ENGINE_ERROR"),
        ).first()
        assertTrue(first.body.contains("不是网络质量结论"))
    }

    @Test
    fun `有连续性中断率时才给 Token 百分比估算并标非计费实测`() {
        val token = OutcomeConclusions.build(input(c1 = 0.125)).last()
        assertEquals(OutcomeConclusions.Evidence.ESTIMATED, token.evidence)
        assertTrue(token.body.contains("12.5%"))
        assertTrue(token.body.contains("派生估算"))
        assertTrue(token.body.contains("不是计费实测"))
    }

    @Test
    fun `无中断率或 usage 对照时拒绝编造 Token 增量`() {
        val token = OutcomeConclusions.build(input(c1 = null)).last()
        assertEquals(OutcomeConclusions.Evidence.UNAVAILABLE, token.evidence)
        assertTrue(token.body.contains("不能给出 Token 增加百分比"))
    }
}
