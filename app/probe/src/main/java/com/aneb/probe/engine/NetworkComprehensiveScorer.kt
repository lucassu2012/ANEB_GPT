package com.aneb.probe.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class NetworkHandshakeEvidence(
    val dnsMs: Double?,
    val tcpMs: Double?,
    val tlsMs: Double?,
    val success: Boolean,
    val syntheticImpairment: String? = null,
)

data class SyntheticNetworkEvidence(
    val profileId: String,
    val profileVersion: String,
    val downlinkMbps: Double,
    val uplinkMbps: Double,
    val addedRttMs: Int,
    val jitterMs: Int,
    val outageDurationMs: Int = 0,
    val appliesTo: List<String>,
    val excludedFromShaping: List<String>,
    val serverAcknowledged: Boolean,
)

data class GatewayNetworkEvidence(
    val experimentId: String,
    val profileRef: String,
    val profileFingerprint: String,
    val impairmentLayer: String,
    val downlink: ProfileGatewayDirection,
    val uplink: ProfileGatewayDirection,
    val excludedFromImpairment: List<String>,
    val gatewayAcknowledged: Boolean,
    val cleanupAcknowledged: Boolean,
    val bypassObserved: Boolean,
)

data class NetworkComprehensiveEvidence(
    val variant: String,
    val idleRttMs: List<Double?>,
    val loadedRttMs: List<Double?>,
    val downloadWindowsMbps: List<Double>,
    val uploadWindowsMbps: List<Double>,
    val appRequestAttempts: Int,
    val appRequestSuccesses: Int,
    val udpPacketsSent: Int,
    val udpReceivedSeqs: List<Int>,
    val udpUnavailableReason: String?,
    val handshakes: List<NetworkHandshakeEvidence>,
    val syntheticImpairment: SyntheticNetworkEvidence? = null,
    val gatewayImpairment: GatewayNetworkEvidence? = null,
    val invalidReason: String? = null,
)

data class NetworkMetricEvidence(
    val metricId: String,
    val value: Double?,
    val complianceRatio: Double?,
    val sampleCount: Int,
    val minimumSampleCount: Int,
    val score: Double?,
)

data class NetworkComprehensiveScoreResult(
    val totalScore: Double?,
    val grade: String?,
    val verdict: TokenVerdict,
    val confidence: TokenConfidence,
    val groupScores: Map<String, Double>,
    val metrics: Map<String, NetworkMetricEvidence>,
    val conclusions: List<String>,
    val confidenceMethodId: String = "network-sample-coverage-v1",
    val coverageRatio: Double? = null,
    val minimumSampleSatisfied: Boolean? = null,
    val notComputableReason: String? = null,
)

/** D-37 冻结的网络综合独立评分；不与 Token、实时交互或旧 AQS 混分。 */
object NetworkComprehensiveScorer {
    private val minimums = mapOf(
        "NET-B01" to 10, "NET-B02" to 10, "NET-B03" to 20, "NET-B04" to 20,
        "NET-B05" to 20, "NET-B06" to 20, "NET-B07" to 10, "NET-B09" to 20,
        "NET-B10" to 100, "NET-B12" to 3,
    )

    fun score(evidence: NetworkComprehensiveEvidence): NetworkComprehensiveScoreResult {
        if (evidence.invalidReason != null) {
            return NetworkComprehensiveScoreResult(
                null, null, TokenVerdict.INVALID, TokenConfidence.INVALID, emptyMap(), emptyMap(),
                listOf("测试证据无效：${evidence.invalidReason}；原始证据保留，评分被抑制。"),
                coverageRatio = null,
                minimumSampleSatisfied = false,
                notComputableReason = "invalid_run:${evidence.invalidReason}",
            )
        }
        val idle = evidence.idleRttMs.filterNotNull()
        val loaded = evidence.loadedRttMs.filterNotNull()
        val idleP50 = percentile(idle, 0.50)
        val idleP95 = percentile(idle, 0.95)
        val loadedP95 = percentile(loaded, 0.95)
        val latencyDelta = if (idleP50 != null && loadedP95 != null) loadedP95 - idleP50 else null
        val allWindows = evidence.downloadWindowsMbps + evidence.uploadWindowsMbps
        val robustCv = listOfNotNull(
            robustCv(evidence.downloadWindowsMbps),
            robustCv(evidence.uploadWindowsMbps),
        ).maxOrNull()
        val udpAvailable = evidence.udpUnavailableReason == null && evidence.udpReceivedSeqs.isNotEmpty()
        val uniqueUdp = evidence.udpReceivedSeqs.distinct()
        val udpNonReturn = if (udpAvailable && evidence.udpPacketsSent > 0) {
            (evidence.udpPacketsSent - uniqueUdp.size.coerceAtMost(evidence.udpPacketsSent)).toDouble() / evidence.udpPacketsSent
        } else null

        fun metric(id: String, value: Double?, compliance: Double?, count: Int) =
            NetworkMetricEvidence(
                metricId = id,
                value = value,
                complianceRatio = compliance?.coerceIn(0.0, 1.0),
                sampleCount = count,
                minimumSampleCount = minimums.getValue(id),
                score = compliance?.let(TokenSimulationScorer::complianceScore),
            )

        val downloadCompliance = ratio(evidence.downloadWindowsMbps.map { it >= 25.0 })
        val uploadCompliance = ratio(evidence.uploadWindowsMbps.map { it >= 10.0 })
        val idleCompliance = ratio(evidence.idleRttMs.map { it != null && it <= 100.0 })
        val loadedCompliance = ratio(evidence.loadedRttMs.map { it != null && it <= 200.0 })
        val deltaCompliance = idleP50?.let { baseline ->
            ratio(evidence.loadedRttMs.map { it != null && it - baseline <= 100.0 })
        }
        val variationCompliance = idleP50?.let { baseline ->
            ratio(evidence.idleRttMs.map { it != null && it - baseline <= 30.0 })
        }
        val stabilityChecks = buildList {
            median(evidence.downloadWindowsMbps)?.takeIf { it > 0.0 }?.let { center ->
                addAll(evidence.downloadWindowsMbps.map { kotlin.math.abs(it - center) / center <= 0.20 })
            }
            median(evidence.uploadWindowsMbps)?.takeIf { it > 0.0 }?.let { center ->
                addAll(evidence.uploadWindowsMbps.map { kotlin.math.abs(it - center) / center <= 0.20 })
            }
        }
        val stabilityCompliance = ratio(stabilityChecks)
        val appCompliance = evidence.appRequestAttempts.takeIf { it > 0 }?.let {
            evidence.appRequestSuccesses.coerceIn(0, it).toDouble() / it
        }
        val handshakeCompliance = ratio(evidence.handshakes.map { handshakePass(it) })
        val udpCompliance = udpNonReturn?.let { 1.0 - it }

        val metrics = linkedMapOf(
            "NET-B01" to metric("NET-B01", percentile(evidence.downloadWindowsMbps, 0.05), downloadCompliance, evidence.downloadWindowsMbps.size),
            "NET-B02" to metric("NET-B02", percentile(evidence.uploadWindowsMbps, 0.05), uploadCompliance, evidence.uploadWindowsMbps.size),
            "NET-B03" to metric("NET-B03", idleP95, idleCompliance, evidence.idleRttMs.size),
            "NET-B04" to metric("NET-B04", loadedP95, loadedCompliance, evidence.loadedRttMs.size),
            "NET-B05" to metric("NET-B05", latencyDelta, deltaCompliance, evidence.loadedRttMs.size),
            "NET-B06" to metric("NET-B06", if (idleP95 != null && idleP50 != null) idleP95 - idleP50 else null, variationCompliance, evidence.idleRttMs.size),
            "NET-B07" to metric("NET-B07", robustCv, stabilityCompliance, allWindows.size),
            "NET-B09" to metric("NET-B09", appCompliance?.let { 1.0 - it }, appCompliance, evidence.appRequestAttempts),
            "NET-B10" to metric("NET-B10", udpNonReturn, udpCompliance, if (udpAvailable) evidence.udpPacketsSent else 0),
            "NET-B12" to metric("NET-B12", handshakeCompliance, handshakeCompliance, evidence.handshakes.size),
        )
        val confidence = confidence(evidence.variant, metrics.values)
        val coverageRatio = coverageRatio(metrics.values)
        val minimumSampleSatisfied = metrics.values.isNotEmpty() &&
            metrics.values.all { it.sampleCount >= it.minimumSampleCount }
        val missing = metrics.values.filter { it.value == null || it.complianceRatio == null }.map { it.metricId }
        if (missing.isNotEmpty()) {
            return NetworkComprehensiveScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, confidence, emptyMap(), metrics,
                conclusions(evidence, metrics, TokenVerdict.INCONCLUSIVE, confidence, missing),
                coverageRatio = coverageRatio,
                minimumSampleSatisfied = minimumSampleSatisfied,
                notComputableReason = "missing_required_metrics:${missing.joinToString(",")}",
            )
        }

        fun s(id: String) = checkNotNull(metrics[id]?.score)
        val groups = linkedMapOf(
            "responsiveness" to weighted(
                s("NET-B03") to (10.0 / 30.0), s("NET-B04") to (10.0 / 30.0),
                s("NET-B06") to (5.0 / 30.0), s("NET-B12") to (5.0 / 30.0),
            ),
            "capacity" to weighted(s("NET-B01") to (20.0 / 35.0), s("NET-B02") to (15.0 / 35.0)),
            "stability" to weighted(
                s("NET-B10") to (10.0 / 35.0), s("NET-B09") to (5.0 / 35.0),
                s("NET-B07") to (10.0 / 35.0), s("NET-B05") to (10.0 / 35.0),
            ),
        )
        val total = weighted(
            groups.getValue("responsiveness") to 0.30,
            groups.getValue("capacity") to 0.35,
            groups.getValue("stability") to 0.35,
        )
        val allTargetsMet = metrics.values.all { (it.complianceRatio ?: 0.0) + 1e-12 >= 0.95 }
        val verdict = when {
            evidence.variant == "quick" || confidence == TokenConfidence.LOW -> TokenVerdict.INCONCLUSIVE
            allTargetsMet -> TokenVerdict.PASS
            else -> TokenVerdict.FAIL
        }
        return NetworkComprehensiveScoreResult(
            totalScore = round1(total),
            grade = grade(total),
            verdict = verdict,
            confidence = confidence,
            groupScores = groups.mapValues { round1(it.value) },
            metrics = metrics,
            conclusions = conclusions(evidence, metrics, verdict, confidence, emptyList()),
            coverageRatio = coverageRatio,
            minimumSampleSatisfied = minimumSampleSatisfied,
        )
    }

    private fun conclusions(
        evidence: NetworkComprehensiveEvidence,
        metrics: Map<String, NetworkMetricEvidence>,
        verdict: TokenVerdict,
        confidence: TokenConfidence,
        missing: List<String>,
    ) = buildList {
        add("结论：${verdict.name}；证据置信度 ${confidence.name}。")
        evidence.syntheticImpairment?.let { synthetic ->
            add(
                "本次为服务器确认的合成弱网：下行 ${synthetic.downlinkMbps.toDisplay()}Mbps、" +
                    "上行 ${synthetic.uplinkMbps.toDisplay()}Mbps、应用请求附加 RTT " +
                    "${synthetic.addedRttMs}±${synthetic.jitterMs}ms" +
                    (synthetic.outageDurationMs.takeIf { it > 0 }?.let { "、应用请求中断 ${it}ms。" } ?: "。"),
            )
            add(
                "未模拟 ${synthetic.excludedFromShaping.joinToString("、")}；" +
                    "真实 RSRP/SINR 与 UDP 指标仅作现场协变量，不代表受控无线弱网。",
            )
        }
        evidence.gatewayImpairment?.let { gateway ->
            add(
                "本次为专用网关确认的 IP 转发层弱网：下行 ${gateway.downlink.rateMbps.toDisplay()}Mbps、" +
                    "上行 ${gateway.uplink.rateMbps.toDisplay()}Mbps、双向单程附加时延 " +
                    "${gateway.downlink.delayMs}/${gateway.uplink.delayMs}ms、丢包 " +
                    "${gateway.downlink.lossPct.toDisplay()}%/${gateway.uplink.lossPct.toDisplay()}%。",
            )
            add(
                "网关实验与清理均有回执；未改变 ${gateway.excludedFromImpairment.joinToString("、")}，" +
                    "因此不得把本结果解释为真实无线信号或基站调度变化。",
            )
        }
        if (evidence.variant == "quick") add("快测样本不足以证明 95% 长期稳定性，只允许方向性判断。")
        if (missing.isNotEmpty()) add("必需指标缺失：${missing.joinToString()}；本次总分不可计算。")
        if (evidence.udpUnavailableReason != null) {
            add("UDP 应用探针不可达（${evidence.udpUnavailableReason}），未把零回包伪装成精确 IP 丢包率。")
        }
        fun pct(id: String) = metrics[id]?.complianceRatio?.let { "%.1f%%".format(it * 100.0) } ?: "不可用"
        add("下载 ≥25Mbps 达标比例 ${pct("NET-B01")}；上传 ≥10Mbps 达标比例 ${pct("NET-B02")}。")
        add("空闲 RTT ≤100ms 达标比例 ${pct("NET-B03")}；负载 RTT ≤200ms 达标比例 ${pct("NET-B04")}。")
        add("该路径需要持续上下行容量、负载下低时延与长期稳定性；峰值带宽不能替代负载响应性。")
        metrics.values.filter { it.score != null }.minByOrNull { it.score!! }?.let {
            add("本次主要瓶颈：${it.metricId}，达标比例 ${"%.1f%%".format((it.complianceRatio ?: 0.0) * 100.0)}。")
        }
    }

    private fun handshakePass(value: NetworkHandshakeEvidence): Boolean {
        if (!value.success) return false
        if (value.tcpMs == null || value.tlsMs == null) return false
        return (value.dnsMs == null || value.dnsMs <= 500.0) && value.tcpMs <= 500.0 && value.tlsMs <= 1_000.0
    }

    private fun confidence(variant: String, metrics: Collection<NetworkMetricEvidence>): TokenConfidence {
        if (variant == "quick") return TokenConfidence.LOW
        val coverage = metrics.map { it.sampleCount.toDouble() / it.minimumSampleCount }
        if (coverage.all { it >= 1.0 }) return TokenConfidence.HIGH
        if (coverage.all { it >= 0.50 }) return TokenConfidence.MEDIUM
        return TokenConfidence.LOW
    }

    private fun coverageRatio(metrics: Collection<NetworkMetricEvidence>): Double? = metrics
        .takeIf { it.isNotEmpty() }
        ?.minOf { metric ->
            if (metric.minimumSampleCount <= 0) 1.0
            else (metric.sampleCount.toDouble() / metric.minimumSampleCount).coerceIn(0.0, 1.0)
        }

    private fun ratio(values: List<Boolean>): Double? =
        values.takeIf { it.isNotEmpty() }?.let { list -> list.count { it }.toDouble() / list.size }

    private fun robustCv(values: List<Double>): Double? {
        val center = median(values)?.takeIf { it > 0.0 } ?: return null
        val mad = median(values.map { kotlin.math.abs(it - center) }) ?: return null
        return 1.4826 * mad / center
    }

    private fun median(values: List<Double>): Double? = percentile(values, 0.50)

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

    private fun weighted(vararg terms: Pair<Double, Double>) = terms.sumOf { it.first * it.second }
    private fun Double.toDisplay() = if (this % 1.0 == 0.0) toInt().toString() else toString()
    private fun round1(value: Double) = (value * 10.0).roundToInt() / 10.0
    private fun grade(score: Double) = when { score >= 85 -> "A"; score >= 70 -> "B"; score >= 55 -> "C"; else -> "D" }
}
