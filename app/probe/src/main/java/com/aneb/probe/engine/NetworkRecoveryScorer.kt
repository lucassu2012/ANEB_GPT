package com.aneb.probe.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class NetworkRecoveryEvidence(
    val serverAcknowledged: Boolean,
    val triggerAcknowledged: Boolean,
    val declaredOutageMs: Int,
    val outageFailureCount: Int,
    val recoveryTimeMs: Double?,
    val postRecoveryRttMs: List<Double?>,
    val invalidReason: String? = null,
    val impairmentLayer: String = "application_http",
    val bypassObserved: Boolean = false,
)

/**
 * 独立恢复评分 v1。计时口径严格限定为“触发响应到首个成功 echo”，不是物理链路恢复时间。
 * 单次受控事件只能给 LOW 置信度；通过门限时仍为 INCONCLUSIVE，失败门控则明确 FAIL。
 */
object NetworkRecoveryScorer {
    private const val REQUIRED_POST_SAMPLES = 12

    fun score(evidence: NetworkRecoveryEvidence): NetworkComprehensiveScoreResult {
        val invalid = evidence.invalidReason ?: when {
            !evidence.serverAcknowledged -> "synthetic_impairment_not_acknowledged"
            !evidence.triggerAcknowledged -> "outage_trigger_not_acknowledged"
            evidence.declaredOutageMs <= 0 -> "outage_duration_not_declared"
            evidence.bypassObserved -> "gateway_bypass_observed"
            else -> null
        }
        if (invalid != null) {
            return NetworkComprehensiveScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID, emptyMap(), emptyMap(),
                listOf("恢复测试证据无效：$invalid；原始证据保留，评分被抑制。"),
            )
        }

        val observed = evidence.outageFailureCount > 0
        val recovered = evidence.recoveryTimeMs != null
        val validPost = evidence.postRecoveryRttMs.filterNotNull()
        val postSuccessRatio = evidence.postRecoveryRttMs.takeIf { it.isNotEmpty() }
            ?.let { validPost.size.toDouble() / it.size }
        val postRttP95 = percentile(validPost, 0.95)
        val postRttCompliance = ratio(evidence.postRecoveryRttMs.map { it != null && it <= 300.0 })

        fun metric(id: String, value: Double?, compliance: Double?, count: Int, minimum: Int, score: Double?) =
            NetworkMetricEvidence(id, value, compliance, count, minimum, score)

        val recoveryCompliance = evidence.recoveryTimeMs?.let { if (it <= 3_000.0) 1.0 else 0.0 }
        val metrics = linkedMapOf(
            "RCV-B01" to metric("RCV-B01", if (observed) 1.0 else 0.0, if (observed) 1.0 else 0.0, 1, 1, if (observed) 100.0 else 0.0),
            "RCV-B02" to metric(
                "RCV-B02", evidence.recoveryTimeMs, recoveryCompliance, if (recovered) 1 else 0, 1,
                evidence.recoveryTimeMs?.let(::recoveryTimeScore),
            ),
            "RCV-B03" to metric(
                "RCV-B03", postSuccessRatio, postSuccessRatio, evidence.postRecoveryRttMs.size, REQUIRED_POST_SAMPLES,
                postSuccessRatio?.let(TokenSimulationScorer::complianceScore),
            ),
            "RCV-B04" to metric(
                "RCV-B04", postRttP95, postRttCompliance, evidence.postRecoveryRttMs.size, REQUIRED_POST_SAMPLES,
                postRttCompliance?.let(TokenSimulationScorer::complianceScore),
            ),
        )
        val missing = metrics.values.filter { it.value == null || it.complianceRatio == null || it.score == null }
        if (missing.isNotEmpty()) {
            return NetworkComprehensiveScoreResult(
                null, null, TokenVerdict.FAIL, TokenConfidence.LOW, emptyMap(), metrics,
                conclusions(evidence, metrics, TokenVerdict.FAIL, missing.map { it.metricId }),
            )
        }

        fun s(id: String) = checkNotNull(metrics[id]?.score)
        val groups = linkedMapOf(
            "recovery" to weighted(s("RCV-B01") to 0.20, s("RCV-B02") to 0.80),
            "continuity" to s("RCV-B03"),
            "responsiveness" to s("RCV-B04"),
        )
        val total = weighted(
            groups.getValue("recovery") to 0.45,
            groups.getValue("continuity") to 0.30,
            groups.getValue("responsiveness") to 0.25,
        )
        val gatesMet = observed && checkNotNull(evidence.recoveryTimeMs) <= 3_000.0 &&
            checkNotNull(postSuccessRatio) >= 0.95 && checkNotNull(postRttCompliance) >= 0.95 &&
            evidence.postRecoveryRttMs.size >= REQUIRED_POST_SAMPLES
        val verdict = if (gatesMet) TokenVerdict.INCONCLUSIVE else TokenVerdict.FAIL
        return NetworkComprehensiveScoreResult(
            totalScore = round1(total),
            grade = grade(total),
            verdict = verdict,
            confidence = TokenConfidence.LOW,
            groupScores = groups.mapValues { round1(it.value) },
            metrics = metrics,
            conclusions = conclusions(evidence, metrics, verdict, emptyList()),
        )
    }

    private fun conclusions(
        evidence: NetworkRecoveryEvidence,
        metrics: Map<String, NetworkMetricEvidence>,
        verdict: TokenVerdict,
        missing: List<String>,
    ) = buildList {
        val layerLabel = if (evidence.impairmentLayer == "ip_forwarding") "IP 转发层" else "应用请求层"
        add("结论：${verdict.name}，证据置信度 LOW；仅完成 1 次确定性的${layerLabel}中断，不能证明长期恢复率。")
        add(
            "本次模拟 ${evidence.declaredOutageMs}ms ${layerLabel}不可用窗口；观察到 ${evidence.outageFailureCount} 次失败，" +
                "中断激活确认到首个成功请求 ${evidence.recoveryTimeMs?.let { "%.0fms".format(it) } ?: "未恢复"}。",
        )
        val success = metrics["RCV-B03"]?.value
        val rtt = metrics["RCV-B04"]?.value
        add("恢复后请求成功率 ${success?.let { "%.1f%%".format(it * 100.0) } ?: "不可用"}；恢复后 RTT P95 ${rtt?.let { "%.1fms".format(it) } ?: "不可用"}。")
        add("建议目标：恢复用时 ≤3000ms；恢复后请求成功率 ≥95%；恢复后 RTT ≤300ms 的样本比例 ≥95%。")
        add(
            if (evidence.impairmentLayer == "ip_forwarding") {
                "边界：这是专用网关的 IP 转发层受控中断，不是无线切网，也未改变 RSRP、RSRQ 或 SINR。"
            } else {
                "边界：这是 ANEB HTTP 请求可用性模拟，不是 IP 断网、丢包、切网、RSRP 或 SINR 变化。"
            },
        )
        if (missing.isNotEmpty()) add("必需指标缺失：${missing.joinToString()}；按合同总分不可计算。")
    }

    private fun recoveryTimeScore(value: Double): Double = when {
        value <= 2_200.0 -> 100.0
        value <= 3_000.0 -> 100.0 - (value - 2_200.0) / 800.0 * 15.0
        value >= 8_000.0 -> 0.0
        else -> 85.0 - (value - 3_000.0) / 5_000.0 * 85.0
    }

    private fun ratio(values: List<Boolean>): Double? =
        values.takeIf { it.isNotEmpty() }?.let { list -> list.count { it }.toDouble() / list.size }

    private fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        return if (lower == upper) sorted[lower] else sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun weighted(vararg terms: Pair<Double, Double>) = terms.sumOf { it.first * it.second }
    private fun round1(value: Double) = (value * 10.0).roundToInt() / 10.0
    private fun grade(score: Double) = when { score >= 85 -> "A"; score >= 70 -> "B"; score >= 55 -> "C"; else -> "D" }
}
