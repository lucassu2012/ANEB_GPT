package com.aneb.probe.engine

import java.util.Locale
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
    val responseMs: Double? = null,
    val maxMissingRunFrames: Int? = null,
    val uplinkGoodputKbps: Double? = null,
    val downlinkGoodputKbps: Double? = null,
    val unplannedOverlap: Boolean? = null,
)

data class RealtimeSessionEvidence(
    val established: Boolean,
    val setupMs: Double?,
    val handshakeMs: Double?,
    val rttSamplesMs: List<Double>,
    val turns: List<RealtimeTurnEvidence>,
    val unexpectedDisconnect: Boolean,
    val error: String?,
    val loadedRttSamplesMs: List<Double?> = emptyList(),
    val recoveryMs: Double? = null,
    val reconnectEvents: Int = 0,
    val controlledDisconnectExpected: Boolean = false,
    val controlledDisconnectObserved: Boolean = false,
    val recoveryStimulusBaselineMs: Double? = null,
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
    val componentValues: Map<String, Double> = emptyMap(),
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
    val confidenceMethodId: String = "realtime-sample-coverage-v1",
    val coverageRatio: Double? = null,
    val minimumSampleSatisfied: Boolean? = null,
    val notComputableReason: String? = null,
)

object RealtimeSimulationScorer {
    private data class Spec(
        val minimum: Int,
        val targetCompliance: Double,
        val required: Boolean = false,
    )

    private val specs = linkedMapOf(
        "LIVE-B01" to Spec(10, 0.99, required = true),
        "LIVE-B02" to Spec(10, 0.95, required = true),
        "LIVE-B03" to Spec(10, 0.95),
        "LIVE-B04" to Spec(10, 0.95, required = true),
        "LIVE-B05" to Spec(500, 0.99, required = true),
        "LIVE-B06" to Spec(500, 0.99, required = true),
        "LIVE-B07" to Spec(500, 0.99, required = true),
        "LIVE-B08" to Spec(2, 0.95, required = true),
        "LIVE-B09" to Spec(10, 0.99, required = true),
        "LIVE-B10" to Spec(10, 0.99, required = true),
        "LIVE-B11" to Spec(2, 0.95),
        "LIVE-B12" to Spec(10, 0.99),
        "LIVE-N01" to Spec(10, 0.95, required = true),
        "LIVE-N02" to Spec(20, 0.95, required = true),
        "LIVE-N03" to Spec(100, 0.95, required = true),
        "LIVE-N04" to Spec(500, 0.99, required = true),
        "LIVE-N05" to Spec(500, 0.95),
        "LIVE-N06" to Spec(20, 0.95),
        "LIVE-N07" to Spec(20, 0.95),
        "LIVE-N08" to Spec(1, 0.0),
        "LIVE-R01" to Spec(1, 0.0),
    )

    private val recoveryMinimums = mapOf(
        "LIVE-B05" to 400,
        "LIVE-B09" to 6,
        "LIVE-B11" to 2,
        "LIVE-N02" to 10,
    )

    private val qualityGateLabels = mapOf(
        "LIVE-B01" to "会话建立成功率",
        "LIVE-B02" to "会话建立时延",
        "LIVE-B04" to "响应超额时延",
        "LIVE-B05" to "音频准时帧率",
        "LIVE-B06" to "音频卡顿率",
        "LIVE-B07" to "音频掩盖样本率",
        "LIVE-B08" to "打断响应时延",
        "LIVE-B09" to "轮次成功率",
        "LIVE-B10" to "会话中断率",
        "LIVE-N01" to "WebSocket 握手时延",
        "LIVE-N02" to "会话内 RTT",
        "LIVE-N03" to "帧到达变化",
        "LIVE-N04" to "应用音频帧未返回率",
    )

    fun score(
        evidence: RealtimeRunEvidence,
        scorePolicyId: String = "realtime-interaction-score-v1",
    ): RealtimeScoreResult {
        if (evidence.invalidReason != null) {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID,
                emptyMap(), emptyMap(), evidence.invalidReason,
                listOf("测试证据无效：${evidence.invalidReason}；原始数据已保留，评分被抑制。"),
                coverageRatio = null,
                minimumSampleSatisfied = false,
                notComputableReason = "invalid_run:${evidence.invalidReason}",
            )
        }
        val sessions = evidence.sessions
        val turns = sessions.flatMap { it.turns }
        val scoringSessions = if (evidence.variant == "recovery") sessions.filter { it.reconnectEvents > 0 } else sessions
        val scoringTurns = scoringSessions.flatMap { it.turns }
        val framesExpected = scoringTurns.sumOf { it.expectedFrames }
        val framesUnique = scoringTurns.sumOf { it.uniqueFrames }
        val onTimeFrames = scoringTurns.sumOf { it.onTimeFrames }
        val stallFrames = scoringTurns.sumOf { it.stallFrames }
        val concealFrames = scoringTurns.sumOf { it.concealFrames }
        val variations = turns.flatMap { it.arrivalVariationMs }
        val barge = turns.filter { it.interrupted }.mapNotNull { it.bargeResponseMs }
        val rtt = scoringSessions.flatMap { it.rttSamplesMs }
        val loadedRttAttempts = sessions.flatMap { it.loadedRttSamplesMs }
        val loadedRtt = loadedRttAttempts.filterNotNull()

        fun metric(
            id: String,
            value: Double?,
            compliance: Double?,
            count: Int,
            components: Map<String, Double> = emptyMap(),
        ): RealtimeMetricEvidence {
            val spec = checkNotNull(specs[id])
            val minimum = if (evidence.variant == "recovery") recoveryMinimums[id] ?: spec.minimum else spec.minimum
            return RealtimeMetricEvidence(
                id, value, compliance?.coerceIn(0.0, 1.0), count, minimum, spec.targetCompliance,
                compliance?.let(TokenSimulationScorer::complianceScore),
                components,
            )
        }

        val establishRatio = ratio(sessions.map { it.established })
        val setup = sessions.mapNotNull { it.setupMs }
        val setupPass = sessions.map { (it.setupMs ?: Double.POSITIVE_INFINITY) <= 2_000.0 }
        val responseRaw = turns.mapNotNull { it.responseMs }
        val response = turns.mapNotNull { it.responseExcessMs }
        val responsePass = response.map { it <= 200.0 }
        val onTimeRatio = framesExpected.takeIf { it > 0 }?.let { onTimeFrames.toDouble() / it }
        val stallRate = framesExpected.takeIf { it > 0 }?.let { stallFrames.toDouble() / it }
        val concealRate = framesExpected.takeIf { it > 0 }?.let { concealFrames.toDouble() / it }
        val bargePass = barge.map { it <= 300.0 }
        val turnSuccess = ratio(scoringTurns.map { it.success })
        val sessionContinuity = ratio(sessions.map { !it.unexpectedDisconnect })
        val handshake = sessions.mapNotNull { it.handshakeMs }
        val handshakePass = sessions.map { (it.handshakeMs ?: Double.POSITIVE_INFINITY) <= 1_000.0 }
        val rttPass = rtt.map { it <= 100.0 }
        val absoluteVariations = variations.map { kotlin.math.abs(it) }
        val variationPass = absoluteVariations.map { it <= 30.0 }
        val variationSpread = percentile(absoluteVariations, 0.95)?.let { p95 ->
            percentile(absoluteVariations, 0.50)?.let { p50 -> (p95 - p50).coerceAtLeast(0.0) }
        }
        val completeness = framesExpected.takeIf { it > 0 }?.let { framesUnique.toDouble() / it }
        val recoveries = sessions.mapNotNull { it.recoveryMs }
        val expectedRecoveries = sessions.count { it.controlledDisconnectExpected }
        val recoveryPass = recoveries.map { it <= 3_000.0 }
        val recoveryStimulusBaselines = sessions.mapNotNull { it.recoveryStimulusBaselineMs }
        val recoveryCompliance = if (evidence.variant == "recovery" && expectedRecoveries > 0) {
            recoveryPass.count { it }.toDouble() / expectedRecoveries
        } else {
            ratio(recoveryPass)
        }
        val overlaps = turns.mapNotNull { it.unplannedOverlap }
        val overlapRate = overlaps.takeIf { it.isNotEmpty() }?.let { values -> values.count { it }.toDouble() / values.size }
        val missingRuns = turns.mapNotNull { it.maxMissingRunFrames }
        val uplinkMbps = turns.mapNotNull { it.uplinkGoodputKbps?.div(1_000.0) }
        val downlinkMbps = turns.mapNotNull { it.downlinkGoodputKbps?.div(1_000.0) }
        val uplinkP05 = percentile(uplinkMbps, 0.05)
        val downlinkP05 = percentile(downlinkMbps, 0.05)
        val goodputComponents = buildMap {
            uplinkP05?.let { put("uplink_p05_mbps", it) }
            downlinkP05?.let { put("downlink_p05_mbps", it) }
        }
        val goodputCompliance = ratio(
            uplinkMbps.map { it >= 0.50 } + downlinkMbps.map { it >= 0.50 },
        )
        val loadedPass = loadedRttAttempts.map { it != null && it <= 150.0 }

        val metrics = linkedMapOf(
            "LIVE-B01" to metric("LIVE-B01", establishRatio, establishRatio, sessions.size),
            "LIVE-B02" to metric("LIVE-B02", percentile(setup, 0.95), ratio(setupPass), sessions.size),
            "LIVE-B03" to metric("LIVE-B03", percentile(responseRaw, 0.95), ratio(responsePass), responseRaw.size),
            "LIVE-B04" to metric("LIVE-B04", percentile(response, 0.95), ratio(responsePass), response.size),
            "LIVE-B05" to metric("LIVE-B05", onTimeRatio, onTimeRatio, framesExpected),
            "LIVE-B06" to metric("LIVE-B06", stallRate, stallRate?.let { 1.0 - it }, framesExpected),
            "LIVE-B07" to metric("LIVE-B07", concealRate, concealRate?.let { 1.0 - it }, framesExpected),
            "LIVE-B08" to metric("LIVE-B08", percentile(barge, 0.95), ratio(bargePass), barge.size),
            "LIVE-B09" to metric("LIVE-B09", turnSuccess, turnSuccess, scoringTurns.size),
            "LIVE-B10" to metric("LIVE-B10", sessionContinuity?.let { 1.0 - it }, sessionContinuity, sessions.size),
            "LIVE-B11" to metric(
                "LIVE-B11",
                percentile(recoveries, 0.95),
                recoveryCompliance,
                if (evidence.variant == "recovery") expectedRecoveries else recoveries.size,
                if (evidence.variant == "recovery") {
                    mapOf(
                        "expected_recoveries" to expectedRecoveries.toDouble(),
                        "successful_recoveries" to recoveries.size.toDouble(),
                        "probe_business_baseline_p95_ms" to (percentile(recoveryStimulusBaselines, 0.95) ?: 0.0),
                    )
                } else {
                    emptyMap()
                },
            ),
            "LIVE-B12" to metric("LIVE-B12", overlapRate, overlapRate?.let { 1.0 - it }, overlaps.size),
            "LIVE-N01" to metric("LIVE-N01", percentile(handshake, 0.95), ratio(handshakePass), sessions.size),
            "LIVE-N02" to metric("LIVE-N02", percentile(rtt, 0.95), ratio(rttPass), rtt.size),
            "LIVE-N03" to metric("LIVE-N03", variationSpread, ratio(variationPass), variations.size),
            "LIVE-N04" to metric("LIVE-N04", completeness?.let { 1.0 - it }, completeness, framesExpected),
            "LIVE-N05" to metric(
                "LIVE-N05",
                missingRuns.maxOrNull()?.toDouble(),
                missingRuns.maxOrNull()?.let { if (it <= 3) 1.0 else 0.0 },
                framesExpected,
            ),
            "LIVE-N06" to metric(
                "LIVE-N06",
                listOfNotNull(uplinkP05, downlinkP05).takeIf { it.size == 2 }?.minOrNull(),
                goodputCompliance,
                uplinkMbps.size + downlinkMbps.size,
                goodputComponents,
            ),
            "LIVE-N07" to metric("LIVE-N07", percentile(loadedRtt, 0.95), ratio(loadedPass), loadedRttAttempts.size),
            "LIVE-N08" to metric(
                "LIVE-N08",
                sessions.sumOf { it.reconnectEvents }.toDouble(),
                null,
                sessions.size,
                if (evidence.variant == "recovery") {
                    mapOf(
                        "controlled_expected" to expectedRecoveries.toDouble(),
                        "controlled_observed" to sessions.count { it.controlledDisconnectObserved }.toDouble(),
                    )
                } else {
                    emptyMap()
                },
            ),
            "LIVE-R01" to metric("LIVE-R01", null, null, 0),
        )
        if (scorePolicyId == "realtime-recovery-score-v2") {
            return scoreRecovery(evidence, metrics)
        }
        if (scorePolicyId != "realtime-interaction-score-v1") {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID,
                emptyMap(), metrics, "unsupported_score_policy:$scorePolicyId",
                listOf("评分策略不受当前引擎支持；原始证据已保留。"),
            )
        }
        val requiredIds = specs.filterValues { it.required }.keys
        val requiredMetrics = requiredIds.mapNotNull(metrics::get)
        val confidence = confidence(evidence.variant, requiredMetrics)
        val coverageRatio = coverageRatio(requiredMetrics)
        val minimumSampleSatisfied = requiredMetrics.isNotEmpty() &&
            requiredMetrics.all { it.sampleCount >= it.minimumSampleCount }
        val missing = requiredIds.filter { metrics[it]?.complianceRatio == null }
        if (missing.isNotEmpty()) {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, confidence, emptyMap(), metrics, null,
                listOf("必需指标缺失：${missing.joinToString()}；本次总分不可计算。"),
                coverageRatio = coverageRatio,
                minimumSampleSatisfied = minimumSampleSatisfied,
                notComputableReason = "missing_required_metrics:${missing.joinToString(",")}",
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
        val allTargetsMet = requiredMetrics.all { metric ->
            metric.complianceRatio?.let { it + 1e-12 >= metric.targetComplianceRatio } == true
        }
        val verdict = when {
            confidence != TokenConfidence.HIGH -> TokenVerdict.INCONCLUSIVE
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
            coverageRatio = coverageRatio,
            minimumSampleSatisfied = minimumSampleSatisfied,
        )
    }

    private fun scoreRecovery(
        evidence: RealtimeRunEvidence,
        metrics: Map<String, RealtimeMetricEvidence>,
    ): RealtimeScoreResult {
        val sessions = evidence.sessions
        val expected = sessions.count { it.controlledDisconnectExpected }
        val observed = sessions.count { it.controlledDisconnectObserved }
        val successful = sessions.count { it.recoveryMs != null }
        val requiredMetrics = recoveryMinimums.keys.mapNotNull(metrics::get)
        val coverage = requiredMetrics.map { it.sampleCount.toDouble() / it.minimumSampleCount }
        val coverageRatio = coverageRatio(requiredMetrics)
        val minimumSampleSatisfied = expected >= 2 && requiredMetrics.isNotEmpty() &&
            requiredMetrics.all { it.sampleCount >= it.minimumSampleCount }
        val confidence = when {
            expected >= 2 && coverage.all { it >= 1.0 } -> TokenConfidence.HIGH
            expected > 0 && coverage.all { it >= 0.5 } -> TokenConfidence.MEDIUM
            else -> TokenConfidence.LOW
        }
        val missing = recoveryMinimums.keys.filter { metrics[it]?.complianceRatio == null }
        if (expected == 0 || missing.isNotEmpty()) {
            return RealtimeScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, confidence, emptyMap(), metrics, null,
                listOf("受控恢复必需证据缺失：${(missing + if (expected == 0) listOf("controlled_disconnect") else emptyList()).joinToString()}。"),
                coverageRatio = coverageRatio,
                minimumSampleSatisfied = minimumSampleSatisfied,
                notComputableReason = "missing_recovery_evidence:" +
                    (missing + if (expected == 0) listOf("controlled_disconnect") else emptyList()).joinToString(","),
            )
        }

        val observedRatio = observed.toDouble() / expected
        val completionRatio = successful.toDouble() / expected
        val faultScore = TokenSimulationScorer.complianceScore(observedRatio)
        val completionScore = TokenSimulationScorer.complianceScore(completionRatio)
        val latencyScore = metrics.getValue("LIVE-B11").score ?: 0.0

        val recoveredSessions = sessions.filter { it.reconnectEvents > 0 }
        val recoveredTurns = recoveredSessions.flatMap { it.turns }
        val recoveredFrames = recoveredTurns.sumOf { it.expectedFrames }
        val recoveredOnTime = recoveredFrames.takeIf { it > 0 }?.let {
            recoveredTurns.sumOf { turn -> turn.onTimeFrames }.toDouble() / it
        } ?: 0.0
        val recoveredTurnSuccess = ratio(recoveredTurns.map { it.success }) ?: 0.0
        val recoveredRtt = recoveredSessions.flatMap { it.rttSamplesMs }
        val recoveredRttCompliance = ratio(recoveredRtt.map { it <= 100.0 }) ?: 0.0

        val groups = linkedMapOf(
            "recovery_path" to weighted(
                faultScore to 0.20,
                completionScore to 0.30,
                latencyScore to 0.50,
            ),
            "recovered_quality" to weighted(
                TokenSimulationScorer.complianceScore(recoveredOnTime) to 0.60,
                TokenSimulationScorer.complianceScore(recoveredTurnSuccess) to 0.25,
                TokenSimulationScorer.complianceScore(recoveredRttCompliance) to 0.15,
            ),
        )
        var total = weighted(
            groups.getValue("recovery_path") to 0.65,
            groups.getValue("recovered_quality") to 0.35,
        )
        val capReason = when {
            observed < expected -> "受控中断未全部被客户端观察"
            successful < expected -> "受控中断后未全部恢复到有效音频"
            else -> null
        }
        if (capReason != null) total = minOf(total, 54.0)
        val allTargetsMet = requiredMetrics.all { metric ->
            metric.complianceRatio?.let { it + 1e-12 >= metric.targetComplianceRatio } == true
        }
        val verdict = when {
            confidence != TokenConfidence.HIGH -> TokenVerdict.INCONCLUSIVE
            capReason != null || !allTargetsMet -> TokenVerdict.FAIL
            else -> TokenVerdict.PASS
        }
        val recoveryP95 = metrics["LIVE-B11"]?.value
        val recoveryBaselineP95 = metrics["LIVE-B11"]
            ?.componentValues
            ?.get("probe_business_baseline_p95_ms")
        val conclusions = buildList {
            add("结论：${verdict.name}；受控恢复证据置信度 ${confidence.name}。")
            add("计划受控中断 $expected 次，客户端观察到 $observed 次，成功恢复到有效音频 $successful 次。")
            add(
                "恢复到首个有效音频 P95 ${format(recoveryP95, "ms")}（目标 ≤3000ms，达标 " +
                    "${metrics["LIVE-B11"]?.complianceRatio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "不可用"}）。",
            )
            add("固定恢复刺激的业务计划基线 P95 ${format(recoveryBaselineP95, "ms")}（1.2 秒语音 + 350ms 模型等待）。")
            add(
                "恢复后音频准时率 ${String.format(Locale.ROOT, "%.1f%%", recoveredOnTime * 100)}，" +
                    "轮次成功率 ${String.format(Locale.ROOT, "%.1f%%", recoveredTurnSuccess * 100)}，" +
                    "RTT≤100ms 比例 ${String.format(Locale.ROOT, "%.1f%%", recoveredRttCompliance * 100)}。",
            )
            add("本结论只适用于 ANEB 节点受控服务端中断后的应用恢复，不代表蜂窝断网、跨网迁移或目标 AI 服务可用性。")
            if (capReason != null) add("评分封顶：$capReason，总分最高 54。")
        }
        return RealtimeScoreResult(
            totalScore = (total * 10.0).roundToInt() / 10.0,
            grade = grade(total),
            verdict = verdict,
            confidence = confidence,
            groupScores = groups.mapValues { (_, value) -> (value * 10.0).roundToInt() / 10.0 },
            metrics = metrics,
            capReason = capReason,
            conclusions = conclusions,
            coverageRatio = coverageRatio,
            minimumSampleSatisfied = minimumSampleSatisfied,
        )
    }

    private fun confidence(variant: String, metrics: Collection<RealtimeMetricEvidence>): TokenConfidence {
        val coverage = metrics.map { it.sampleCount.toDouble() / it.minimumSampleCount }
        if (variant == "standard" && coverage.all { it >= 1.0 }) return TokenConfidence.HIGH
        if (coverage.all { it >= 0.50 }) return TokenConfidence.MEDIUM
        return TokenConfidence.LOW
    }

    private fun coverageRatio(metrics: Collection<RealtimeMetricEvidence>): Double? = metrics
        .takeIf { it.isNotEmpty() }
        ?.minOf { metric ->
            if (metric.minimumSampleCount <= 0) 1.0
            else (metric.sampleCount.toDouble() / metric.minimumSampleCount).coerceIn(0.0, 1.0)
        }

    private fun conclusions(
        variant: String,
        metrics: Map<String, RealtimeMetricEvidence>,
        verdict: TokenVerdict,
        confidence: TokenConfidence,
        capReason: String?,
    ): List<String> = buildList {
        add("结论：${verdict.name}；证据置信度 ${confidence.name}。")
        fun percent(id: String) = metrics[id]?.complianceRatio
            ?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) }
            ?: "不可用"
        val failedQualityGates = specs
            .filterValues { it.required }
            .mapNotNull { (id, spec) ->
                metrics[id]?.complianceRatio
                    ?.takeIf { it + 1e-12 < spec.targetCompliance }
                    ?.let { compliance ->
                        "$id ${qualityGateLabels[id] ?: "必需指标"}达标率 " +
                            "${String.format(Locale.ROOT, "%.1f%%", compliance * 100)} < " +
                            "${String.format(Locale.ROOT, "%.1f%%", spec.targetCompliance * 100)}"
                    }
            }
        if (failedQualityGates.isNotEmpty()) {
            add("未达质量门限：${failedQualityGates.joinToString("；")}。")
            add("ANEB 采用必需门限优先：任一必需指标未达，即使综合分或等级较高，结论仍为 FAIL。")
        }
        if (variant == "quick") add("快测只覆盖 1 个会话和最多 3 轮，不能用于 95% 稳定性强结论。")
        add("相对业务计划的响应超额时延 P95 ${value("LIVE-B04", metrics, "ms")}（建议 ≤200ms，达标 ${percent("LIVE-B04")}）。")
        add("2 秒音频准时帧率 ${percent("LIVE-B05")}（目标 ≥99%，样本 ${metrics["LIVE-B05"]?.sampleCount ?: 0} 帧）。")
        add(
            "会话 RTT P95 ${value("LIVE-N02", metrics, "ms")}（建议 ≤100ms，达标 ${percent("LIVE-N02")}）；" +
                "到达变化 P95−P50 ${value("LIVE-N03", metrics, "ms")}（建议 ≤30ms）。",
        )
        val loaded = metrics["LIVE-N07"]
        add(
            if (loaded?.value == null) {
                "本次未取得有效负载 RTT 样本，不对通话负载时延作结论。"
            } else {
                "通话负载 RTT P95 ${value("LIVE-N07", metrics, "ms")}（建议 ≤150ms，达标 ${percent("LIVE-N07")}）。"
            },
        )
        val goodput = metrics["LIVE-N06"]?.componentValues.orEmpty()
        add(
            "持续净荷速率 P05：上行 ${format(goodput["uplink_p05_mbps"], "Mbps")}、" +
                "下行 ${format(goodput["downlink_p05_mbps"], "Mbps")}；本模型业务基线分别为 0.256/0.384Mbps。",
        )
        add("打断响应 P95 ${value("LIVE-B08", metrics, "ms")}（建议 ≤300ms，达标 ${percent("LIVE-B08")}）。")
        val recovery = metrics["LIVE-B11"]
        if (recovery?.sampleCount == 0) {
            val reconnects = metrics["LIVE-N08"]?.value?.toInt() ?: 0
            add(
                if (reconnects == 0) {
                    "本次未触发连接中断，不能评估恢复时延；恢复目标仍为 P95 ≤3000ms。"
                } else {
                    "检测到 $reconnects 次重连尝试但未取得成功恢复样本，不能宣称已恢复。"
                },
            )
        } else {
            add("连接恢复 P95 ${value("LIVE-B11", metrics, "ms")}（建议 ≤3000ms，样本 ${recovery?.sampleCount ?: 0}）。")
        }
        add("该业务需要持续双向小包、低尾时延、低到达变化和稳定长连接；带宽不是首要瓶颈。")
        if (capReason != null) add("评分封顶：$capReason，总分最高 54。")
    }

    private fun value(id: String, metrics: Map<String, RealtimeMetricEvidence>, unit: String): String =
        format(metrics[id]?.value, unit)

    private fun format(value: Double?, unit: String): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.1f%s", it, unit) } ?: "不可用"

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
