package com.aneb.probe.engine

import com.aneb.probe.data.ContinuityResultEntity
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.scoring.KpiValue

/**
 * AQS v0.2 run 级出分接线（阶段2 C03 遗留接线，phase2 账本 pending 项）。
 *
 * 数据可用性合同：
 * - 候选＝Room 最近 [CONTINUITY_MAX_AGE_MS]（24h）内的 continuity_result；
 * - **可用**＝C1（c1DropRate）与 C2（c2RecoveryMsP50）均非 null 的最新一条
 *   （部分缺失的 continuity 行不算可用——避免 v0.2 恒 KPI_MISSING 的噪声展示；
 *   R-10：缺失字段本就是 null 语义，绝不以 0/封顶值顶替）；
 * - 无可用数据 → 不出 v0.2，run 只显 v0.1（AqsScorer 语义完全不变）。
 *
 * AqsScorer 不改（已有 score(kpi, continuity) 双入口），本层只做选择与映射。
 * 纯函数、无 Android 依赖，可 JVM 单测。
 */
object AqsV02Gate {

    /** continuity 数据的最大可用年龄（24h；超龄的连续性证据与本次 run 环境不可比） */
    const val CONTINUITY_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * 从候选中选出 v0.2 可用的 continuity 结果：窗口内（含边界，未来时刻的脏数据剔除）
     * 且 C1/C2 均非 null 的最新一条；无 → null（只出 v0.1）。
     */
    fun select(candidates: List<ContinuityResultEntity>, nowEpochMs: Long): ContinuityResultEntity? =
        candidates
            .filter { nowEpochMs - it.startedAtEpochMs in 0..CONTINUITY_MAX_AGE_MS }
            .filter { it.c1DropRate != null && it.c2RecoveryMsP50 != null }
            .maxByOrNull { it.startedAtEpochMs }

    /**
     * ContinuityResultEntity → AqsScorer C 组输入。
     * sampleCount：C1＝流式段总数，C2＝恢复样本条数（recoveryMsCsv 非空项计数）。
     * lowConfidence 恒 false——continuity 实验无 per-KPI 低置信口径（阶段2 语义），
     * v0.2 的 lowConfidence 仍由 T/U/N 组与 validity 决定。
     */
    fun toContinuityKpi(e: ContinuityResultEntity): AqsScorer.ContinuityKpi =
        AqsScorer.ContinuityKpi(
            c1SessionDropRate = KpiValue(
                value = e.c1DropRate,
                unit = "ratio",
                sampleCount = e.segmentsTotal,
                lowConfidence = false,
            ),
            c2RecoveryMs = KpiValue(
                value = e.c2RecoveryMsP50,
                unit = "ms",
                sampleCount = recoverySampleCount(e),
                lowConfidence = false,
            ),
        )

    /** recoveryMsCsv（逗号分隔恢复样本）非空项计数；空串=0。 */
    fun recoverySampleCount(e: ContinuityResultEntity): Int =
        e.recoveryMsCsv.split(',').count { it.isNotBlank() }
}
