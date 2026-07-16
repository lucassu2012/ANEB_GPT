package com.aneb.probe.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class RealtimeTurnEvidence(
    val responseExcessMs: Double?,
    val expectedFrames: Int,
    val uniqueFrames: Int,
    val onTimeFrames: Int,
    val stallFrames: Int,
    val concealFrames: Int,
    val arrivalVariationMs: List<Double>,
    val bargeResponseMs: Double?,
    val interrupted: Boolean,
    val success: Boolean,
)

data class RealtimeSessionEvidence(
    val established: Boolean,
    val setupMs: Double?,
    val handshakeMs: Double?,
    val rttSamplesMs: List<Double>,
    val turns: List<RealtimeTurnEvidence>,
    val unexpectedDisconnect: Boolean,
    val error: String?,
)

data class RealtimeRunEvidence(
    val variant: String,
    val sessions: List<RealtimeSessionEvidence>,
    val invalidReason: String? = null,
)

data class RealtimeMetricEvidence(
    val metricId: String,
    val value: Double?,
    val complianceRatio: Double?,
    val sampleCount: Int,
    val minimumSampleCount: Int,
    val targetComplianceRatio: Double,
    val score: Double?,
)

data class RealtimeScoreResult(
    val totalScore: Double?,
    val grade: String?,
    val verdict: TokenVerdict,
    val confidence: TokenConfidence,
    val groupScores: Map<String, Double>,
    val metrics: Map<String, RealtimeMetricEvidence>,
    val capReason: String?,
    val conclusions: List<String>,
)

object RealtimeSimulationScorer {
    private data class Spec(val minimum: Int, val target: Double)

    private val specs = linkedMapOf(
        "LIVE-B01" to Spec(10, 0.99),
        "LIVE-B02" to Spec(10, 0.95),
        "LIVE-B04" to Spec(10, 0.95),
        "LIVE-B05" to Spec(500, 0.99),
        "LIVE-B06" to Spec(500, 0.99),
        "LIVE-B07" to Spec(500, 0.99),
        "LIVE-B08" to Spec(2, 0.95),
        "LIVE-B09" to Spec(10, 0.99),
        "LIVE-B10" to Spec(10, 0.99),
        "LIVE-N01" to Spec(10, 0.95),
        "LIVE-N02" to Spec(20, 0.95),
        "LIVE-N03" to Spec(100, 0.95),
        "LIVE-N04" to Spec(500, 0.99),
    )

    fun score(evidence: RealtimeRunEvidence): RealtimeScoreResult {
        if (evidence.invalidReason != null) {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID,
                emptyMap(), emptyMap(), evidence.invalidReason,
                listOf("测试证据无效：${evidence.invalidReason}；原始数据已保留，评分被抑制。"),
            )
        }
        val sessions = evidence.sessions
        val turns = sessions.flatMap { it.turns }
        val framesExpected = turns.sumOf { it.expectedFrames }
        val framesUnique = turns.sumOf { it.uniqueFrames }
        val onTimeFrames = turns.sumOf { it.onTimeFrames }
        val stallFrames = turns.sumOf { it.stallFrames }
        val concealFrames = turns.sumOf { it.concealFrames }
        val variations = turns.flatMap { it.arrivalVariationMs }
        val barge = turns.filter { it.interrupted }.mapNotNull { it.bargeResponseMs }
        val rtt = sessions.flatMap { it.rttSamplesMs }

        fun metric(id: String, value: Double?, compliance: Double?, count: Int): RealtimeMetricEvidence {
            val spec = checkNotNull(specs[id])
            return RealtimeMetricEvidence(
                id, value, compliance?.coerceIn(0.0, 1.0), count, spec.minimum, spec.target,
                compliance?.let(TokenSimulationScorer::complianceScore),
            )
        }

        val establishRatio = ratio(sessions.map { it.established })
        val setup = sessions.mapNotNull { it.setupMs }
        val setupPass = sessions.map { (it.setupMs ?: Double.POSITIVE_INFINITY) <= 2_000.0 }
        val response = turns.mapNotNull { it.responseExcessMs }
        val responsePass = response.map { it <= 200.0 }
        val onTimeRatio = framesExpected.takeIf { it > 0 }?.let { onTimeFrames.toDouble() / it }
        val stallRate = framesExpected.takeIf { it > 0 }?.let { stallFrames.toDouble() / it }
        val concealRate = framesExpected.takeIf { it > 0 }?.let { concealFrames.toDouble() / it }
        val bargePass = barge.map { it <= 300.0 }
        val turnSuccess = ratio(turns.map { it.success })
        val sessionContinuity = ratio(sessions.map { !it.unexpectedDisconnect })
        val handshake = sessions.mapNotNull { it.handshakeMs }
        val handshakePass = sessions.map { (it.handshakeMs ?: Double.POSITIVE_INFINITY) <= 1_000.0 }
        val rttPass = rtt.map { it <= 100.0 }
        val variationPass = variations.map { kotlin.math.abs(it) <= 30.0 }
        val completeness = framesExpected.takeIf { it > 0 }?.let { framesUnique.toDouble() / it }

        val metrics = linkedMapOf(
            "LIVE-B01" to metric("LIVE-B01", establishRatio, establishRatio, sessions.size),
            "LIVE-B02" to metric("LIVE-B02", percentile(setup, 0.95), ratio(setupPass), sessions.size),
            "LIVE-B04" to metric("LIVE-B04", percentile(response, 0.95), ratio(responsePass), response.size),
            "LIVE-B05" to metric("LIVE-B05", onTimeRatio, onTimeRatio, framesExpected),
            "LIVE-B06" to metric("LIVE-B06", stallRate, stallRate?.let { 1.0 - it }, framesExpected),
            "LIVE-B07" to metric("LIVE-B07", concealRate, concealRate?.let { 1.0 - it }, framesExpected),
            "LIVE-B08" to metric("LIVE-B08", percentile(barge, 0.95), ratio(bargePass), barge.size),
            "LIVE-B09" to metric("LIVE-B09", turnSuccess, turnSuccess, turns.size),
            "LIVE-B10" to metric("LIVE-B10", sessionContinuity?.let { 1.0 - it }, sessionContinuity, sessions.size),
            "LIVE-N01" to metric("LIVE-N01", percentile(handshake, 0.95), ratio(handshakePass), sessions.size),
            "LIVE-N02" to metric("LIVE-N02", percentile(rtt, 0.95), ratio(rttPass), rtt.size),
            "LIVE-N03" to metric("LIVE-N03", percentile(variations.map { kotlin.math.abs(it) }, 0.95), ratio(variationPass), variations.size),
            "LIVE-N04" to metric("LIVE-N04", completeness?.let { 1.0 - it }, completeness, framesExpected),
        )
        val confidence = confidence(evidence.variant, metrics.values)
        val missing = specs.keys.filter { metrics[it]?.complianceRatio == null }
        if (missing.isNotEmpty()) {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, confidence, emptyMap(), metrics, null,
                listOf("必需指标缺失：${missing.joinToString()}；本次总分不可计算。"),
            )
        }

        fun score(id: String) = checkNotNull(metrics[id]?.score)
        val groups = linkedMapOf(
            "conversation_response" to weighted(
                score("LIVE-B04") to (20.0 / 35.0),
                score("LIVE-B08") to (10.0 / 35.0),
                score("LIVE-B02") to (2.5 / 35.0),
                score("LIVE-N01") to (2.5 / 35.0),
            ),
            "playout_continuity" to weighted(
                score("LIVE-B05") to (15.0 / 35.0),
                score("LIVE-B06") to (15.0 / 35.0),
                score("LIVE-B07") to (5.0 / 35.0),
            ),
            "session_reliability" to weighted(
                score("LIVE-B09") to 0.40,
                score("LIVE-B10") to 0.40,
                score("LIVE-B01") to 0.20,
            ),
            "network_readiness" to weighted(
                score("LIVE-N02") to 0.40,
                score("LIVE-N03") to 0.30,
                score("LIVE-N04") to 0.30,
            ),
        )
        var total = weighted(
            groups.getValue("conversation_response") to 0.35,
            groups.getValue("playout_continuity") to 0.35,
            groups.getValue("session_reliability") to 0.20,
            groups.getValue("network_readiness") to 0.10,
        )
        val capReason = when {
            turnSuccess != null && turnSuccess < 0.80 -> "轮次成功率低于 80%"
            stallRate != null && stallRate > 0.05 -> "音频卡顿率高于 5%"
            else -> null
        }
        if (capReason != null) total = minOf(total, 54.0)
        val allTargetsMet = metrics.values.all { metric ->
            metric.complianceRatio?.let { it + 1e-12 >= metric.targetComplianceRatio } == true
        }
        val verdict = when {
            confidence == TokenConfidence.LOW -> TokenVerdict.INCONCLUSIVE
            capReason != null || !allTargetsMet -> TokenVerdict.FAIL
            else -> TokenVerdict.PASS
        }
        return RealtimeScoreResult(
            totalScore = (total * 10.0).roundToInt() / 10.0,
            grade = grade(total),
            verdict = verdict,
            confidence = confidence,
            groupScores = groups.mapValues { (_, value) -> (value * 10.0).roundToInt() / 10.0 },
            metrics = metrics,
            capReason = capReason,
            conclusions = conclusions(evidence.variant, metrics, verdict, confidence, capReason),
        )
    }

    private fun confidence(variant: String, metrics: Collection<RealtimeMetricEvidence>): TokenConfidence {
        val coverage = metrics.map { it.sampleCount.toDouble() / it.minimumSampleCount }
        if (variant == "standard" && coverage.all { it >= 1.0 }) return TokenConfidence.HIGH
        if (coverage.all { it >= 0.50 }) return TokenConfidence.MEDIUM
        return TokenConfidence.LOW
    }

    private fun conclusions(
        variant: String,
        metrics: Map<String, RealtimeMetricEvidence>,
        verdict: TokenVerdict,
        confidence: TokenConfidence,
        capReason: String?,
    ): List<String> = buildList {
        add("结论：${verdict.name}；证据置信度 ${confidence.name}。")
        if (variant == "quick") add("快测只覆盖 1 个会话和最多 3 轮，不能用于 95% 稳定性强结论。")
        fun percent(id: String) = metrics[id]?.complianceRatio?.let { "%.1f%%".format(it * 100) } ?: "不可用"
        add("2 秒音频准时帧目标达标比例 ${percent("LIVE-B05")}（目标 ≥99%）。")
        add("会话内 RTT <100ms 达标比例 ${percent("LIVE-N02")}（目标 ≥95%）。")
        add("打断响应 <300ms 达标比例 ${percent("LIVE-B08")}（目标 ≥95%）。")
        add("该业务需要持续双向小包、低尾时延、低到达变化和稳定长连接；带宽不是首要瓶颈。")
        if (capReason != null) add("评分封顶：$capReason，总分最高 54。")
    }

    private fun ratio(values: List<Boolean>): Double? =
        values.takeIf { it.isNotEmpty() }?.let { list -> list.count { it }.toDouble() / list.size }

    private fun weighted(vararg terms: Pair<Double, Double>): Double = terms.sumOf { it.first * it.second }

    private fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun grade(score: Double): String = when {
        score >= 85 -> "A"
        score >= 70 -> "B"
        score >= 55 -> "C"
        else -> "D"
    }
}
