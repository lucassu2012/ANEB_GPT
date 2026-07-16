package com.aneb.probe.ui

import com.aneb.probe.ui.theme.Grade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VerdictText（普通用户"一句人话"结论生成器）单测。纯 JVM，锚定四档 + 不可计算 +
 * 低置信 + T4 否决 + 点名最拖后腿 KPI 逻辑。断言用子串（文案措辞可微调，语义锚点稳定）。
 */
class VerdictTextTest {

    private fun gen(
        score: Double?,
        lowConf: Boolean = false,
        veto: Boolean = false,
        reason: String? = null,
        grades: Map<String, Grade?> = emptyMap(),
    ) = VerdictText.generate(
        VerdictText.Input(
            score = score,
            lowConfidence = lowConf,
            vetoApplied = veto,
            notComputableReason = reason,
            kpiGrades = grades,
        ),
    )

    @Test
    fun excellent_isPositiveNoWeakness() {
        val v = gen(89.2, grades = mapOf("T1" to Grade.Excellent, "U1" to Grade.Good))
        assertTrue(v, v.contains("很适合 AI 助手"))
        // 全优/良不点名弱项
        assertFalse(v, v.contains("偏慢"))
    }

    @Test
    fun good_dailyUsable() {
        val v = gen(78.0, grades = mapOf("T1" to Grade.Excellent, "U1" to Grade.Good))
        assertTrue(v, v.contains("日常够用"))
    }

    @Test
    fun good_pointsOutWeakestKpi() {
        // 良档但 U1 只到"可"→ 应点名上传速度
        val v = gen(72.0, grades = mapOf("T1" to Grade.Excellent, "U1" to Grade.Fair))
        assertTrue(v, v.contains("日常够用"))
        assertTrue(v, v.contains("上传速度偏慢"))
    }

    @Test
    fun fair_canUseButStalls() {
        val v = gen(60.0, grades = mapOf("N1" to Grade.Fair))
        assertTrue(v, v.contains("能用但会卡"))
        assertTrue(v, v.contains("网络延迟偏高"))
    }

    @Test
    fun poor_badExperience() {
        val v = gen(40.0, grades = mapOf("T3" to Grade.Poor))
        assertTrue(v, v.contains("体验较差"))
        assertTrue(v, v.contains("卡顿"))
    }

    @Test
    fun notComputable_givesRetestGuidance_noGrade() {
        val v = gen(null, reason = "kpi_missing")
        assertTrue(v, v.contains("没能测出有效结果"))
        assertTrue(v, v.contains("重测"))
        // 不可计算绝不套四档主干
        assertFalse(v, v.contains("很适合 AI 助手"))
        assertFalse(v, v.contains("日常够用"))
    }

    @Test
    fun notComputable_reasonTranslatedNotRawCode() {
        // 英文机器码不得直接泄露给普通用户
        val v = gen(null, reason = "guard_rejected")
        assertFalse(v, v.contains("guard_rejected"))
        assertTrue(v, v.contains("守卫"))
    }

    @Test
    fun lowConfidence_appendsCaveat() {
        val v = gen(89.0, lowConf = true, grades = mapOf("T1" to Grade.Excellent))
        assertTrue(v, v.contains("很适合 AI 助手"))
        assertTrue(v, v.contains("仅供参考"))
    }

    @Test
    fun veto_overridesWithSevereStall() {
        // T4 否决即便分数被封在 54 附近，也必须点严重卡顿这一封顶主因
        val v = gen(54.0, veto = true, grades = mapOf("U1" to Grade.Fair))
        assertTrue(v, v.contains("严重卡顿"))
        // 否决主因压过普通点名（不应只说"上传偏慢"）
        assertTrue(v, v.contains("体验较差"))
    }

    @Test
    fun weakest_picksWorstBySeverity() {
        // 同时有 可 与 差 → 点名"差"那项（U2 工具循环）
        val v = gen(58.0, grades = mapOf("N1" to Grade.Fair, "U2" to Grade.Poor))
        assertTrue(v, v.contains("工具调用往返偏慢"))
        assertFalse(v, v.contains("网络延迟偏高"))
    }

    @Test
    fun nullKpiGrades_ignoredInMention() {
        // null 分级（缺失项）绝不参与点名，也不崩
        val v = gen(60.0, grades = mapOf("T1" to null, "N2" to Grade.Fair))
        assertTrue(v, v.contains("能用但会卡"))
        assertTrue(v, v.contains("网络抖动明显"))
    }
}
