package com.aneb.probe.engine

import kotlin.math.floor
import kotlin.math.roundToInt

internal fun tokenInvalidReasonText(reasonCode: String): String = when (reasonCode) {
    "receipt_missing" -> "节点没有返回机器可读的能力回执，测试已在发送首个业务请求前停止"
    else -> "测试前置校验未通过，测试已停止；具体机器原因已保留在结果证据中"
}

data class TokenTaskEvidence(
    val workloadKind: String,
    val uploadBytes: Long,
    val responseArtifactBytes: Long,
    val success: Boolean,
    val networkFailure: Boolean,
    val error: String?,
    val clickToNodeReceiveMs: Double?,
    val ttftExcessMs: Double?,
    val uploadGoodputMbps: Double?,
    val downloadGoodputMbps: Double?,
    val expectedTokens: Int,
    val uniqueTokens: Int,
    val duplicateTokens: Int,
    val tokenLatenessMs: List<Double>,
    val itlResidualMs: List<Double>,
    val requestCount: Int,
    val failedRequestCount: Int,
    val artifactDownloadDurationMs: Double? = null,
    /** Stable runtime-plan identity used to align the same task across repeated runs. */
    val taskId: String? = null,
    /** Server-monotonic upload-received -> first scheduled token interval. */
    val serverProcessingMs: Double? = null,
    /** Upload received by ANEB node -> first token observed by the App. */
    val ttftMs: Double? = null,
)

data class TokenRunEvidence(
    val variant: String,
    val tasks: List<TokenTaskEvidence>,
    val rttSamplesMs: List<Double?>,
    val invalidReason: String? = null,
    val loadedRttSamplesMs: List<Double?> = emptyList(),
)

data class TokenMetricEvidence(
    val metricId: String,
    val value: Double?,
    val complianceRatio: Double?,
    val sampleCount: Int,
    val minimumSampleCount: Int,
    val targetComplianceRatio: Double,
    val score: Double?,
)

enum class TokenVerdict { PASS, FAIL, INCONCLUSIVE, INVALID }
enum class TokenConfidence { HIGH, MEDIUM, LOW, INVALID }

data class TokenScoreResult(
    val totalScore: Double?,
    val grade: String?,
    val verdict: TokenVerdict,
    val confidence: TokenConfidence,
    val groupScores: Map<String, Double>,
    val metrics: Map<String, TokenMetricEvidence>,
    val capReason: String?,
    val conclusionItems: List<AnebConclusionItem>,
    /** Frozen audit basis emitted by the scorer; exporters must not derive it again. */
    val confidenceMethodId: String = "token-sample-coverage-v1",
    val coverageRatio: Double? = null,
    val minimumSampleSatisfied: Boolean? = null,
    val notComputableReason: String? = null,
) {
    val conclusions: List<String> get() = conclusionItems.map(AnebConclusionItem::text)
}

/** Token Simulation Score v1 + compliance-anchors-v1 (D-37/D-39). */
object TokenSimulationScorer {
    private data class MetricSpec(val minimum: Int, val targetCompliance: Double)

    private val requiredSpecs = linkedMapOf(
        "TOK-B01" to MetricSpec(20, 0.99),
        "TOK-B02" to MetricSpec(3, 0.95),
        "TOK-B05" to MetricSpec(10, 0.95),
        "TOK-B07" to MetricSpec(100, 0.95),
        "TOK-B09" to MetricSpec(100, 0.98),
        "TOK-B10" to MetricSpec(100, 1.00),
        "TOK-B11" to MetricSpec(100, 0.99),
        "TOK-B14" to MetricSpec(1, 0.95),
        "TOK-N03" to MetricSpec(20, 0.95),
        "TOK-N04" to MetricSpec(20, 0.95),
        "TOK-N05" to MetricSpec(20, 0.98),
        "TOK-N06" to MetricSpec(5, 0.95),
    )

    private val optionalSpecs = linkedMapOf(
        "TOK-B03" to MetricSpec(1, 0.0),
        "TOK-B04" to MetricSpec(10, 0.95),
    )

    private val allSpecs = requiredSpecs + optionalSpecs

    fun score(
        evidence: TokenRunEvidence,
        behaviorFeatureIds: List<String> = defaultBehaviorFeatureIds(evidence.variant),
    ): TokenScoreResult {
        if (evidence.invalidReason != null) {
            val friendlyReason = tokenInvalidReasonText(evidence.invalidReason)
            return TokenScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID,
                emptyMap(), emptyMap(), evidence.invalidReason,
                listOf(
                    AnebConclusionItem(
                        conclusionId = "token-invalid-evidence",
                        severity = AnebConclusionSeverity.FAILURE,
                        text = "测试证据无效：$friendlyReason；原始数据已保留，评分被抑制。",
                        basis = listOf("evidence:token-raw", "invalid_reason:${evidence.invalidReason}"),
                    ),
                ),
                coverageRatio = null,
                minimumSampleSatisfied = false,
                notComputableReason = evidence.invalidReason,
            )
        }
        val tasks = evidence.tasks
        val tokens = tasks.sumOf { it.expectedTokens }
        val uniqueTokens = tasks.sumOf { it.uniqueTokens }
        val duplicates = tasks.sumOf { it.duplicateTokens }
        val lateness = tasks.flatMap { it.tokenLatenessMs }
        val residuals = tasks.flatMap { it.itlResidualMs }
        val validRtt = evidence.rttSamplesMs.filterNotNull()
        val requestCount = evidence.rttSamplesMs.size + evidence.loadedRttSamplesMs.size + tasks.sumOf { it.requestCount }
        val failedRequests = evidence.rttSamplesMs.count { it == null } + evidence.loadedRttSamplesMs.count { it == null } + tasks.sumOf { it.failedRequestCount }

        fun metric(id: String, value: Double?, compliance: Double?, count: Int): TokenMetricEvidence {
            val spec = checkNotNull(allSpecs[id])
            return TokenMetricEvidence(
                id, value, compliance?.coerceIn(0.0, 1.0), count, spec.minimum,
                spec.targetCompliance, compliance?.let(::complianceScore),
            )
        }

        val taskSuccess = tasks.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.success }.toDouble() / list.size }
        val uploadDeadlinePass = tasks.mapNotNull { task ->
            val actual = task.clickToNodeReceiveMs ?: return@mapNotNull null
            actual <= uploadDeadlineMs(task.workloadKind, task.uploadBytes)
        }
        val ttftPass = tasks.mapNotNull { it.ttftExcessMs?.let { value -> value <= 200.0 } }
        val endToEndTtftPass = tasks.mapNotNull { task ->
            val ttft = task.ttftMs ?: return@mapNotNull null
            val processing = task.serverProcessingMs ?: return@mapNotNull null
            ttft <= processing + 200.0
        }
        val onTime = lateness.takeIf { it.isNotEmpty() }?.let { list -> list.count { it <= 200.0 }.toDouble() / list.size }
        val stallRate = residuals.takeIf { it.isNotEmpty() }?.let { list -> list.count { it > 200.0 }.toDouble() / list.size }
        val severeRate = residuals.takeIf { it.isNotEmpty() }?.let { list -> list.count { it > 1_000.0 }.toDouble() / list.size }
        val completeness = tokens.takeIf { it > 0 }?.let { uniqueTokens.toDouble() / it }
        val redundancy = uniqueTokens.takeIf { it > 0 }?.let { duplicates.toDouble() / it }
        val rttMedian = percentile(validRtt, 0.50)
        val rttCompliance = validRtt.takeIf { it.isNotEmpty() }?.let { list -> list.count { it <= 100.0 }.toDouble() / list.size }
        val variationCompliance = if (rttMedian == null) null else validRtt.count { kotlin.math.abs(it - rttMedian) <= 30.0 }.toDouble() / validRtt.size
        val requestSuccess = requestCount.takeIf { it > 0 }?.let { (requestCount - failedRequests).toDouble() / it }
        val uploadRatePass = tasks.mapNotNull { task ->
            val rate = task.uploadGoodputMbps ?: return@mapNotNull null
            rate >= uploadTargetMbps(task.workloadKind)
        }

        val metrics = linkedMapOf(
            "TOK-B01" to metric("TOK-B01", taskSuccess, taskSuccess, tasks.size),
            "TOK-B02" to metric("TOK-B02", percentile(tasks.mapNotNull { it.clickToNodeReceiveMs }, 0.95), ratio(uploadDeadlinePass), uploadDeadlinePass.size),
            "TOK-B03" to metric("TOK-B03", percentile(tasks.mapNotNull { it.serverProcessingMs }, 0.95), null, tasks.count { it.serverProcessingMs != null }),
            "TOK-B04" to metric("TOK-B04", percentile(tasks.mapNotNull { it.ttftMs }, 0.95), ratio(endToEndTtftPass), endToEndTtftPass.size),
            "TOK-B05" to metric("TOK-B05", percentile(tasks.mapNotNull { it.ttftExcessMs }, 0.95), ratio(ttftPass), ttftPass.size),
            "TOK-B07" to metric("TOK-B07", onTime, onTime, lateness.size),
            "TOK-B09" to metric("TOK-B09", stallRate, stallRate?.let { 1.0 - it }, residuals.size),
            "TOK-B10" to metric("TOK-B10", severeRate, severeRate?.let { 1.0 - it }, residuals.size),
            "TOK-B11" to metric("TOK-B11", completeness, completeness, tokens),
            "TOK-B14" to metric("TOK-B14", redundancy, redundancy?.let { 1.0 - it }, 1.takeIf { uniqueTokens > 0 } ?: 0),
            "TOK-N03" to metric("TOK-N03", percentile(validRtt, 0.95), rttCompliance, validRtt.size),
            "TOK-N04" to metric("TOK-N04", percentile(validRtt, 0.95)?.let { p95 -> rttMedian?.let { p50 -> p95 - p50 } }, variationCompliance, validRtt.size),
            "TOK-N05" to metric("TOK-N05", requestSuccess?.let { 1.0 - it }, requestSuccess, requestCount),
            "TOK-N06" to metric("TOK-N06", percentile(tasks.mapNotNull { it.uploadGoodputMbps }, 0.05), ratio(uploadRatePass), uploadRatePass.size),
        )

        val missing = requiredSpecs.keys.filter { metrics[it]?.complianceRatio == null }
        val requiredMetrics = requiredSpecs.keys.mapNotNull(metrics::get)
        val confidence = confidence(evidence.variant, requiredMetrics)
        val coverageRatio = coverageRatio(requiredMetrics)
        val minimumSampleSatisfied = requiredMetrics.all { it.sampleCount >= it.minimumSampleCount }
        if (missing.isNotEmpty()) {
            return TokenScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, confidence, emptyMap(), metrics, null,
                listOf(
                    taskCompletionConclusion(evidence),
                    AnebConclusionItem(
                        conclusionId = "token-missing-required-metrics",
                        severity = AnebConclusionSeverity.WARNING,
                        text = "必需指标缺失：${missing.joinToString()}；按策略不重分权，本次总分不可计算。",
                        basis = missing.map { "metric:$it" },
                    ),
                    behaviorConclusion(behaviorFeatureIds, evidence.variant),
                ),
                coverageRatio = coverageRatio,
                minimumSampleSatisfied = minimumSampleSatisfied,
                notComputableReason = "missing_required_metrics:${missing.joinToString(",")}",
            )
        }

        fun metricScore(id: String) = checkNotNull(metrics[id]?.score)
        val groupScores = linkedMapOf(
            "task_completion" to weighted(metricScore("TOK-B01") to 0.60, metricScore("TOK-B11") to 0.40),
            "interaction" to weighted(metricScore("TOK-B05") to 0.50, metricScore("TOK-B07") to (1.0 / 3.0), metricScore("TOK-B09") to (1.0 / 6.0)),
            "multimodal_transfer" to multimodalScore(tasks, metricScore("TOK-B02"), metricScore("TOK-N06")),
            "network_stability" to weighted(metricScore("TOK-N03") to (1.0 / 3.0), metricScore("TOK-N04") to (1.0 / 3.0), metricScore("TOK-N05") to (1.0 / 3.0)),
            "efficiency" to metricScore("TOK-B14"),
        )
        var total = weighted(
            groupScores.getValue("task_completion") to 0.25,
            groupScores.getValue("interaction") to 0.30,
            groupScores.getValue("multimodal_transfer") to 0.25,
            groupScores.getValue("network_stability") to 0.15,
            groupScores.getValue("efficiency") to 0.05,
        )
        val capReason = when {
            taskSuccess != null && taskSuccess < 0.80 -> "任务成功率低于 80%"
            severeRate != null && severeRate > 0.01 -> "严重卡顿率高于 1%"
            else -> null
        }
        if (capReason != null) total = minOf(total, 54.0)
        val allTargetsMet = requiredMetrics.all { metric ->
            val compliance = metric.complianceRatio
            compliance != null && compliance + 1e-12 >= metric.targetComplianceRatio
        }
        val verdict = when {
            confidence == TokenConfidence.LOW -> TokenVerdict.INCONCLUSIVE
            capReason != null || !allTargetsMet -> TokenVerdict.FAIL
            else -> TokenVerdict.PASS
        }
        return TokenScoreResult(
            totalScore = (total * 10.0).roundToInt() / 10.0,
            grade = grade(total),
            verdict = verdict,
            confidence = confidence,
            groupScores = groupScores.mapValues { (_, value) -> (value * 10.0).roundToInt() / 10.0 },
            metrics = metrics,
            capReason = capReason,
            conclusionItems = buildConclusions(evidence, metrics, verdict, confidence, capReason, behaviorFeatureIds),
            coverageRatio = coverageRatio,
            minimumSampleSatisfied = minimumSampleSatisfied,
        )
    }

    fun complianceScore(ratio: Double): Double {
        val x = ratio.coerceIn(0.0, 1.0)
        val anchors = listOf(0.0 to 0.0, 0.80 to 55.0, 0.90 to 70.0, 0.95 to 85.0, 1.0 to 100.0)
        val upperIndex = anchors.indexOfFirst { x <= it.first }.takeIf { it >= 0 } ?: anchors.lastIndex
        if (upperIndex == 0) return anchors[0].second
        val lower = anchors[upperIndex - 1]
        val upper = anchors[upperIndex]
        val fraction = (x - lower.first) / (upper.first - lower.first)
        return lower.second + fraction * (upper.second - lower.second)
    }

    fun uploadDeadlineMs(workloadKind: String, bytes: Long): Double {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return when (workloadKind) {
            "text" -> 1_000.0
            "document" -> maxOf(2_000.0, 1_000.0 + mib * 1_000.0)
            "image" -> maxOf(2_000.0, mib * 1_000.0)
            "video" -> 60_000.0
            else -> Double.NaN
        }
    }

    private fun uploadTargetMbps(workloadKind: String): Double = when (workloadKind) {
        "text" -> 1.0
        "document" -> 10.0
        "image" -> 12.0
        "video" -> 20.0
        else -> Double.POSITIVE_INFINITY
    }

    private fun multimodalScore(tasks: List<TokenTaskEvidence>, b02: Double, n06: Double): Double {
        val artifactTasks = tasks.filter { it.responseArtifactBytes > 0 }
        val downCompliance = artifactTasks.mapNotNull { task -> task.downloadGoodputMbps?.let { it >= 25.0 } }
        if (downCompliance.isEmpty()) return weighted(b02 to 0.50, n06 to 0.50)
        return weighted(b02 to 0.30, n06 to 0.30, complianceScore(ratio(downCompliance)!!) to 0.40)
    }

    private fun confidence(variant: String, metrics: Collection<TokenMetricEvidence>): TokenConfidence {
        if (variant == "repeatability_qualification") return TokenConfidence.LOW
        val coverage = metrics.map { it.sampleCount.toDouble() / it.minimumSampleCount }
        if (variant == "standard" && coverage.all { it >= 1.0 }) return TokenConfidence.HIGH
        if (coverage.all { it >= 0.50 }) return TokenConfidence.MEDIUM
        return TokenConfidence.LOW
    }

    private fun coverageRatio(metrics: Collection<TokenMetricEvidence>): Double? = metrics
        .takeIf { it.isNotEmpty() }
        ?.minOf { metric ->
            if (metric.minimumSampleCount <= 0) 1.0
            else (metric.sampleCount.toDouble() / metric.minimumSampleCount).coerceIn(0.0, 1.0)
        }

    private fun buildConclusions(
        evidence: TokenRunEvidence,
        metrics: Map<String, TokenMetricEvidence>,
        verdict: TokenVerdict,
        confidence: TokenConfidence,
        capReason: String?,
        behaviorFeatureIds: List<String>,
    ): List<AnebConclusionItem> = buildList {
        add(
            AnebConclusionItem(
                conclusionId = "token-verdict",
                severity = verdictSeverity(verdict),
                text = "结论：${verdict.name}；证据置信度 ${confidence.name}。",
                basis = listOf("score:verdict", "score:confidence"),
            ),
        )
        add(taskCompletionConclusion(evidence))
        add(behaviorConclusion(behaviorFeatureIds, evidence.variant))
        if (evidence.variant == "quick") {
            add(
                AnebConclusionItem(
                    conclusionId = "token-quick-evidence-limit",
                    severity = AnebConclusionSeverity.WARNING,
                    text = "快测仅覆盖文本、文档、图片各一个任务，不用于 95% 稳定性强结论。",
                    basis = listOf("profile:evidence_tier", "evidence:token-raw"),
                ),
            )
        }
        fun percent(id: String) = metrics[id]?.complianceRatio?.let { "%.1f%%".format(it * 100.0) } ?: "不可用"
        add(
            AnebConclusionItem(
                "token-target-rtt",
                targetSeverity(metrics["TOK-N03"]),
                "应用 RTT <100ms 达标比例 ${percent("TOK-N03")}（目标 ≥95%）。",
                listOf("metric:TOK-N03"),
            ),
        )
        add(
            AnebConclusionItem(
                "token-target-uplink",
                targetSeverity(metrics["TOK-N06"]),
                "上行速率达到各业务门限比例 ${percent("TOK-N06")}（目标 ≥95%）。",
                listOf("metric:TOK-N06"),
            ),
        )
        add(
            AnebConclusionItem(
                "token-target-stream-timeliness",
                targetSeverity(metrics["TOK-B07"]),
                "仿真 Token 准时到达比例 ${percent("TOK-B07")}（目标 ≥95%）。",
                listOf("metric:TOK-B07"),
            ),
        )
        val networkFailures = evidence.tasks.count { it.networkFailure }
        if (networkFailures > 0) {
            add(
                AnebConclusionItem(
                    "token-network-failures",
                    AnebConclusionSeverity.FAILURE,
                    "观察到 $networkFailures 个任务因应用层网络请求失败而未完成。",
                    listOf("metric:TOK-B01", "metric:TOK-N05", "evidence:token-raw"),
                ),
            )
        }
        val overhead = metrics["TOK-B14"]?.value
        if (overhead != null && overhead > 0.0) {
            add(
                AnebConclusionItem(
                    "token-retry-overhead",
                    AnebConclusionSeverity.WARNING,
                    "重试/重复发送使仿真 Token 传输量增加 ${"%.1f".format(overhead * 100)}%。",
                    listOf("metric:TOK-B14", "evidence:token-raw"),
                ),
            )
        }
        requiredSpecs.keys.mapNotNull(metrics::get).filter { it.score != null }.minByOrNull { it.score!! }?.let { bottleneck ->
            add(
                AnebConclusionItem(
                    "token-primary-bottleneck",
                    AnebConclusionSeverity.RECOMMENDATION,
                    "本次主要瓶颈：${bottleneck.metricId}，达标比例 ${percent(bottleneck.metricId)}；优先改善该指标。",
                    listOf("metric:${bottleneck.metricId}"),
                ),
            )
        }
        if (capReason != null) {
            add(
                AnebConclusionItem(
                    "token-score-cap",
                    AnebConclusionSeverity.FAILURE,
                    "评分封顶：$capReason，总分最高 54。",
                    listOf("score:cap_reason"),
                ),
            )
        }
    }

    private fun behaviorConclusion(featureIds: List<String>, variant: String) = AnebConclusionItem(
        conclusionId = "token-behavior-profile",
        severity = AnebConclusionSeverity.INFO,
        text = AnebBehaviorFeatureCatalogV1.sentence(featureIds, defaultBehaviorFeatureIds(variant)),
        basis = listOf(if (featureIds.isEmpty()) "policy:behavior-feature-catalog-v1" else "profile:business.behavior_feature_ids"),
    )

    private fun taskCompletionConclusion(evidence: TokenRunEvidence): AnebConclusionItem {
        val completed = evidence.tasks.count(TokenTaskEvidence::success)
        val total = evidence.tasks.size
        val severity = when {
            total == 0 -> AnebConclusionSeverity.WARNING
            completed == total -> AnebConclusionSeverity.INFO
            else -> AnebConclusionSeverity.FAILURE
        }
        return AnebConclusionItem(
            conclusionId = "token-task-completion",
            severity = severity,
            text = "任务完成 $completed/$total；任务成功率 ${if (total > 0) "%.1f%%".format(completed * 100.0 / total) else "不可用"}。",
            basis = listOf("metric:TOK-B01", "evidence:token-raw"),
        )
    }

    private fun targetSeverity(metric: TokenMetricEvidence?): AnebConclusionSeverity = when {
        metric?.complianceRatio == null -> AnebConclusionSeverity.WARNING
        metric.complianceRatio + 1e-12 < metric.targetComplianceRatio -> AnebConclusionSeverity.RECOMMENDATION
        else -> AnebConclusionSeverity.INFO
    }

    private fun defaultBehaviorFeatureIds(variant: String): List<String> = if (variant == "stress") {
        listOf("very_large_uplink_burst", "large_downlink", "loaded_latency_sensitive", "stream_continuity")
    } else {
        listOf("uplink_burst", "low_latency_start", "stream_continuity", "large_downlink_optional")
    }

    private fun ratio(values: List<Boolean>): Double? = values.takeIf { it.isNotEmpty() }?.let { list -> list.count { it }.toDouble() / list.size }

    private fun weighted(vararg terms: Pair<Double, Double>): Double = terms.sumOf { (value, weight) -> value * weight }

    private fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    private fun grade(score: Double): String = when {
        score >= 85.0 -> "A"
        score >= 70.0 -> "B"
        score >= 55.0 -> "C"
        else -> "D"
    }
}
