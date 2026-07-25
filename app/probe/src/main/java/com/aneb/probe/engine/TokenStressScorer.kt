package com.aneb.probe.engine

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 100MiB Token 大对象压力测试的独立评分。
 *
 * 它只评价本次明确的容量/负载响应性任务，不把单次 Stress 样本混成 Standard 的
 * 95% 长期稳定性结论。组权重由 token-stress-score-v1 冻结。
 */
object TokenStressScorer {
    private data class Spec(val minimum: Int, val targetCompliance: Double = 0.95)

    private val specs = linkedMapOf(
        "TOK-B01" to Spec(1, 0.99),
        "TOK-B02" to Spec(1),
        "TOK-B11" to Spec(1, 0.99),
        "TOK-N05" to Spec(1, 0.98),
        "TOK-N06" to Spec(1),
        "TOK-N07" to Spec(1),
        "TOK-N08" to Spec(20),
        "TOK-N09" to Spec(20),
    )

    private val optionalSpecs = linkedMapOf(
        "TOK-B03" to Spec(1, 0.0),
        "TOK-B04" to Spec(10),
    )

    private val allSpecs = specs + optionalSpecs

    fun score(
        evidence: TokenRunEvidence,
        behaviorFeatureIds: List<String> = defaultBehaviorFeatureIds,
    ): TokenScoreResult {
        if (evidence.invalidReason != null) {
            val friendlyReason = tokenInvalidReasonText(evidence.invalidReason)
            return TokenScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID,
                emptyMap(), emptyMap(), evidence.invalidReason,
                listOf(
                    AnebConclusionItem(
                        "token-stress-invalid-evidence",
                        AnebConclusionSeverity.FAILURE,
                        "测试证据无效：$friendlyReason；原始数据已保留，评分被抑制。",
                        listOf("evidence:token-raw", "invalid_reason:${evidence.invalidReason}"),
                    ),
                ),
                confidenceMethodId = "token-stress-sample-coverage-v1",
                coverageRatio = null,
                minimumSampleSatisfied = false,
                notComputableReason = evidence.invalidReason,
            )
        }
        val tasks = evidence.tasks.filter { it.workloadKind == "video" }
        val loaded = evidence.loadedRttSamplesMs.filterNotNull()
        val idle = evidence.rttSamplesMs.filterNotNull()
        val idleMedian = percentile(idle, 0.50)
        val loadedP95 = percentile(loaded, 0.95)

        fun ratio(values: List<Boolean>): Double? = values.takeIf { it.isNotEmpty() }
            ?.let { list -> list.count { it }.toDouble() / list.size }

        fun metric(id: String, value: Double?, compliance: Double?, count: Int): TokenMetricEvidence {
            val spec = checkNotNull(allSpecs[id])
            return TokenMetricEvidence(
                metricId = id,
                value = value,
                complianceRatio = compliance,
                sampleCount = count,
                minimumSampleCount = spec.minimum,
                targetComplianceRatio = spec.targetCompliance,
                score = compliance?.let(TokenSimulationScorer::complianceScore),
            )
        }

        val taskSuccess = tasks.takeIf { it.isNotEmpty() }
            ?.let { list -> list.count { it.success }.toDouble() / list.size }
        val uploadDeadline = tasks.mapNotNull { task ->
            task.clickToNodeReceiveMs?.let { it <= TokenSimulationScorer.uploadDeadlineMs("video", task.uploadBytes) }
        }
        val endToEndTtftPass = tasks.mapNotNull { task ->
            val ttft = task.ttftMs ?: return@mapNotNull null
            val processing = task.serverProcessingMs ?: return@mapNotNull null
            ttft <= processing + 200.0
        }
        val streamCompleteness = tasks.sumOf { it.expectedTokens }.takeIf { it > 0 }?.let { expected ->
            tasks.sumOf { it.uniqueTokens }.toDouble() / expected
        }
        val requests = evidence.rttSamplesMs.size + evidence.loadedRttSamplesMs.size + tasks.sumOf { it.requestCount }
        val failed = evidence.rttSamplesMs.count { it == null } + evidence.loadedRttSamplesMs.count { it == null } + tasks.sumOf { it.failedRequestCount }
        val requestSuccess = requests.takeIf { it > 0 }?.let { (requests - failed).toDouble() / it }
        val uploadRates = tasks.mapNotNull { it.uploadGoodputMbps }
        val downloadRates = tasks.mapNotNull { it.downloadGoodputMbps }
        val loadedDelta = if (loadedP95 == null || idleMedian == null) null else loadedP95 - idleMedian

        val metrics = linkedMapOf(
            "TOK-B01" to metric("TOK-B01", taskSuccess, taskSuccess, tasks.size),
            "TOK-B02" to metric("TOK-B02", percentile(tasks.mapNotNull { it.clickToNodeReceiveMs }, 0.95), ratio(uploadDeadline), uploadDeadline.size),
            "TOK-B03" to metric("TOK-B03", percentile(tasks.mapNotNull { it.serverProcessingMs }, 0.95), null, tasks.count { it.serverProcessingMs != null }),
            "TOK-B04" to metric("TOK-B04", percentile(tasks.mapNotNull { it.ttftMs }, 0.95), ratio(endToEndTtftPass), endToEndTtftPass.size),
            "TOK-B11" to metric("TOK-B11", streamCompleteness, streamCompleteness, tasks.sumOf { it.expectedTokens }),
            "TOK-N05" to metric("TOK-N05", requestSuccess?.let { 1.0 - it }, requestSuccess, requests),
            "TOK-N06" to metric("TOK-N06", percentile(uploadRates, 0.05), ratio(uploadRates.map { it >= 20.0 }), uploadRates.size),
            "TOK-N07" to metric("TOK-N07", percentile(downloadRates, 0.05), ratio(downloadRates.map { it >= 25.0 }), downloadRates.size),
            "TOK-N08" to metric("TOK-N08", loadedP95, ratio(loaded.map { it <= 200.0 }), evidence.loadedRttSamplesMs.size),
            "TOK-N09" to metric(
                "TOK-N09",
                loadedDelta,
                idleMedian?.let { baseline -> ratio(loaded.map { it - baseline <= 100.0 }) },
                evidence.loadedRttSamplesMs.size,
            ),
        )
        val missing = specs.keys.filter { id ->
            val metric = metrics[id]
            metric?.complianceRatio == null || metric.sampleCount < specs.getValue(id).minimum
        }
        val requiredMetrics = specs.keys.mapNotNull(metrics::get)
        val coverageRatio = requiredMetrics.minOf { metric ->
            if (metric.minimumSampleCount <= 0) 1.0
            else (metric.sampleCount.toDouble() / metric.minimumSampleCount).coerceIn(0.0, 1.0)
        }
        val minimumSampleSatisfied = requiredMetrics.all { it.sampleCount >= it.minimumSampleCount }
        if (missing.isNotEmpty()) {
            return TokenScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, TokenConfidence.LOW,
                emptyMap(), metrics, null,
                listOf(
                    taskCompletionConclusion(evidence),
                    AnebConclusionItem(
                        "token-stress-missing-required-metrics",
                        AnebConclusionSeverity.WARNING,
                        "必需指标缺失：${missing.joinToString()}；按策略不重分权，本次总分不可计算。",
                        missing.map { "metric:$it" },
                    ),
                    behaviorConclusion(behaviorFeatureIds),
                ),
                confidenceMethodId = "token-stress-sample-coverage-v1",
                coverageRatio = coverageRatio,
                minimumSampleSatisfied = minimumSampleSatisfied,
                notComputableReason = "missing_required_metrics:${missing.joinToString(",")}",
            )
        }

        fun score(id: String) = checkNotNull(metrics[id]?.score)
        val groups = linkedMapOf(
            "task_completion" to weighted(score("TOK-B01") to 0.50, score("TOK-B11") to 0.25, score("TOK-N05") to 0.25),
            "uplink_capacity" to weighted(score("TOK-B02") to (1.0 / 3.0), score("TOK-N06") to (2.0 / 3.0)),
            "downlink_capacity" to score("TOK-N07"),
            "loaded_responsiveness" to weighted(score("TOK-N08") to 0.50, score("TOK-N09") to 0.50),
        )
        var total = weighted(
            groups.getValue("task_completion") to 0.20,
            groups.getValue("uplink_capacity") to 0.30,
            groups.getValue("downlink_capacity") to 0.25,
            groups.getValue("loaded_responsiveness") to 0.25,
        )
        val capReason = when {
            taskSuccess != null && taskSuccess < 1.0 -> "100MiB 压力任务未完整完成"
            streamCompleteness != null && streamCompleteness < 0.99 -> "Token 流完整率低于 99%"
            else -> null
        }
        if (capReason != null) total = minOf(total, 54.0)
        val rounded = (total * 10.0).roundToInt() / 10.0
        val verdict = if (capReason != null) TokenVerdict.FAIL else TokenVerdict.INCONCLUSIVE
        return TokenScoreResult(
            totalScore = rounded,
            grade = grade(rounded),
            verdict = verdict,
            confidence = TokenConfidence.LOW,
            groupScores = groups.mapValues { (_, value) -> (value * 10.0).roundToInt() / 10.0 },
            metrics = metrics,
            capReason = capReason,
            conclusionItems = conclusions(evidence, metrics, verdict, capReason, behaviorFeatureIds),
            confidenceMethodId = "token-stress-sample-coverage-v1",
            coverageRatio = coverageRatio,
            minimumSampleSatisfied = minimumSampleSatisfied,
        )
    }

    private fun conclusions(
        evidence: TokenRunEvidence,
        metrics: Map<String, TokenMetricEvidence>,
        verdict: TokenVerdict,
        capReason: String?,
        behaviorFeatureIds: List<String>,
    ): List<AnebConclusionItem> = buildList {
        add(AnebConclusionItem("token-stress-verdict", verdictSeverity(verdict), "结论：${verdict.name}；Stress 为单次方向性证据，置信度 LOW，不支持 95% 长期稳定性承诺。", listOf("score:verdict", "score:confidence", "profile:evidence_tier")))
        add(taskCompletionConclusion(evidence))
        add(behaviorConclusion(behaviorFeatureIds))
        add(AnebConclusionItem("token-stress-target-uplink", targetSeverity(metrics["TOK-N06"]), "100MiB 上行有效速率 ${value("TOK-N06", metrics, "Mbps")}（建议 ≥20Mbps，并在重复测试中达到 95%）。", listOf("metric:TOK-N06")))
        add(AnebConclusionItem("token-stress-target-downlink", targetSeverity(metrics["TOK-N07"]), "100MiB 下行有效速率 ${value("TOK-N07", metrics, "Mbps")}（建议 ≥25Mbps，并在重复测试中达到 95%）。", listOf("metric:TOK-N07")))
        add(AnebConclusionItem("token-stress-target-loaded-latency", if (listOf("TOK-N08", "TOK-N09").all { metrics[it]?.complianceRatio?.let { ratio -> ratio >= 0.95 } == true }) AnebConclusionSeverity.INFO else AnebConclusionSeverity.RECOMMENDATION, "负载 RTT P95 ${value("TOK-N08", metrics, "ms")}；负载时延增量 ${value("TOK-N09", metrics, "ms")}（建议分别 ≤200ms/≤100ms）。", listOf("metric:TOK-N08", "metric:TOK-N09")))
        specs.keys.mapNotNull(metrics::get).filter { it.score != null }.minByOrNull { it.score!! }?.let { bottleneck ->
            add(AnebConclusionItem("token-stress-primary-bottleneck", AnebConclusionSeverity.RECOMMENDATION, "本次主要瓶颈：${bottleneck.metricId}，达标比例 ${percent(bottleneck.complianceRatio)}；优先改善该指标。", listOf("metric:${bottleneck.metricId}")))
        }
        if (capReason != null) add(AnebConclusionItem("token-stress-score-cap", AnebConclusionSeverity.FAILURE, "评分封顶：$capReason，总分最高 54。", listOf("score:cap_reason")))
    }

    private fun behaviorConclusion(featureIds: List<String>) = AnebConclusionItem(
        "token-stress-behavior-profile",
        AnebConclusionSeverity.INFO,
        AnebBehaviorFeatureCatalogV1.sentence(featureIds, defaultBehaviorFeatureIds),
        listOf(if (featureIds.isEmpty()) "policy:behavior-feature-catalog-v1" else "profile:business.behavior_feature_ids"),
    )

    private fun taskCompletionConclusion(evidence: TokenRunEvidence): AnebConclusionItem {
        val videoTasks = evidence.tasks.filter { it.workloadKind == "video" }
        val completed = videoTasks.count(TokenTaskEvidence::success)
        val severity = when {
            videoTasks.isEmpty() -> AnebConclusionSeverity.WARNING
            completed == videoTasks.size -> AnebConclusionSeverity.INFO
            else -> AnebConclusionSeverity.FAILURE
        }
        return AnebConclusionItem(
            "token-stress-task-completion",
            severity,
            "100MiB 压力任务完成 $completed/${videoTasks.size}。",
            listOf("metric:TOK-B01", "evidence:token-raw"),
        )
    }

    private fun targetSeverity(metric: TokenMetricEvidence?): AnebConclusionSeverity = when {
        metric?.complianceRatio == null -> AnebConclusionSeverity.WARNING
        metric.complianceRatio + 1e-12 < metric.targetComplianceRatio -> AnebConclusionSeverity.RECOMMENDATION
        else -> AnebConclusionSeverity.INFO
    }

    private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "不可用"

    private val defaultBehaviorFeatureIds = listOf(
        "very_large_uplink_burst", "large_downlink", "loaded_latency_sensitive", "stream_continuity",
    )

    private fun value(id: String, metrics: Map<String, TokenMetricEvidence>, unit: String): String =
        metrics[id]?.value?.let { String.format(Locale.ROOT, "%.1f%s", it, unit) } ?: "不可用"

    private fun weighted(vararg terms: Pair<Double, Double>): Double = terms.sumOf { (value, weight) -> value * weight }

    private fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
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
