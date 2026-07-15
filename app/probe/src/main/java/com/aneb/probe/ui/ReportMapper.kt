package com.aneb.probe.ui

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.KpiCalculator
import com.aneb.probe.scoring.ReportAnalyzer
import com.aneb.probe.scoring.Validity

/**
 * Room 落库实体 → [ReportAnalyzer.RunSummary] 的**纯映射层**（analysis layer ③ 的入参装配）。
 *
 * 纯 JVM、无 Android 依赖，可单测。只读展平既有落库口径（与 [ResultFormat.runKpiRows] 同源：
 * 因变量按 AqsInputMapper 映射合同取来源场景中位数——T/U2←S2、N←S1、U1←S3），
 * 绝不重算 KPI/AQS，也不发网络、不落库（D-02 单一事实来源，R-10：缺失记 null 绝不 0）。
 *
 * netemProfile 恒 null（真机/模拟器无 netem 剖面）→ ReportAnalyzer 走相关模式或如实降级；
 * lossPct/rsrp 当前不从本层注入（radio_sample 需额外 IO，留后续接线），保持 null 由分析层跳过。
 */
object ReportMapper {

    /** run 内所有场景聚合为一条摘要（自变量：网络条件；因变量：AI 业务 KPI）。 */
    fun toRunSummary(run: TestRun, scenarios: List<ScenarioResultEntity>): ReportAnalyzer.RunSummary {
        // 只用 VALID / VALID_LOW_CONFIDENCE 场景的落库值参与聚合（INVALID 场景 KPI 已被 gate 置 null）。
        val byId = scenarios.groupBy { it.profileId }
        val s1 = byId["s1_chat"].orEmpty()
        val s2 = byId["s2_coding_agent"].orEmpty()
        val s3 = byId["s3_multimodal"].orEmpty()

        fun med(src: List<ScenarioResultEntity>, pick: (ScenarioResultEntity) -> Double?): Double? =
            KpiCalculator.percentileOrNull(src.mapNotNull(pick), 0.50)

        val ttft = med(s2) { it.t1TtftMs }
        val itl = med(s2) { it.t2ItlP95Ms }
        val stall = med(s2) { it.t3StallRate }
        val up = med(s3) { it.u1GoodputMbps }
        val rtt = med(s1) { it.n1RttP50Ms }
        val jitter = med(s1) { it.n2JitterMs }

        // 有效性：run 无任何可用因变量且无 AQS → 视作 INVALID（不进聚合）；否则按 run 低置信标透传。
        val allNull = ttft == null && itl == null && stall == null &&
            up == null && rtt == null && jitter == null && run.aqsScore == null
        val validity = when {
            allNull -> Validity.INVALID
            run.aqsLowConfidence == true -> Validity.VALID_LOW_CONFIDENCE
            else -> Validity.VALID
        }

        return ReportAnalyzer.RunSummary(
            runId = run.runId,
            transport = run.transport,
            rttMs = rtt,
            jitterMs = jitter,
            lossPct = null,
            rsrp = null,
            ttftMs = ttft,
            itlP95Ms = itl,
            stallRate = stall,
            upMbps = up,
            aqs = run.aqsScore,
            netemProfile = null,
            validity = validity,
            epochMs = run.startedAtEpochMs,
        )
    }

    /** 多个 run（各自已带其场景列表）→ 摘要列表，保持传入顺序（趋势排序在分析层按 epoch 处理）。 */
    fun toRunSummaries(runsWithScenarios: List<Pair<TestRun, List<ScenarioResultEntity>>>): List<ReportAnalyzer.RunSummary> =
        runsWithScenarios.map { (run, scenarios) -> toRunSummary(run, scenarios) }
}
