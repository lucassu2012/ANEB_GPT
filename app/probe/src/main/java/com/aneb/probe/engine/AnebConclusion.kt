package com.aneb.probe.engine

/**
 * Frozen semantic conclusion emitted by a category scorer.
 *
 * [conclusionId] is stable within a conclusion policy. The result envelope copies these fields
 * verbatim and only adds the already-frozen policy id; it must not infer meaning from list order.
 */
data class AnebConclusionItem(
    val conclusionId: String,
    val severity: AnebConclusionSeverity,
    val text: String,
    val basis: List<String>,
) {
    init {
        require(conclusionId.isNotBlank()) { "conclusion_id_blank" }
        require(text.isNotBlank()) { "conclusion_text_blank" }
        require(basis.isNotEmpty() && basis.none(String::isBlank)) { "conclusion_basis_missing" }
    }
}

enum class AnebConclusionSeverity(val wireValue: String) {
    INFO("info"),
    RECOMMENDATION("recommendation"),
    WARNING("warning"),
    FAILURE("failure"),
}

/** Profile v2 behavior-feature vocabulary rendered consistently across all result surfaces. */
object AnebBehaviorFeatureCatalogV1 {
    private val labels = mapOf(
        "uplink_burst" to "上行突发需求",
        "low_latency_start" to "低时延启动需求",
        "stream_continuity" to "流式连续性需求",
        "large_downlink_optional" to "可选大文件下行需求",
        "very_large_uplink_burst" to "超大上行突发需求",
        "large_downlink" to "大带宽下行需求",
        "loaded_latency_sensitive" to "负载时延敏感",
        "full_duplex" to "全双工交互",
        "continuous_small_uplink" to "连续小流量上行",
        "low_latency_response" to "低时延响应需求",
        "barge_in" to "打断响应需求",
        "controlled_server_disconnect_recovery" to "受控服务中断恢复",
        "sustained_downlink" to "持续下行容量",
        "sustained_uplink" to "持续上行容量",
        "loaded_responsiveness" to "负载响应能力",
        "path_stability" to "路径稳定性",
        "constrained_downlink" to "受限下行容量",
        "constrained_uplink" to "受限上行容量",
        "added_application_rtt" to "应用层附加时延",
        "constrained_capacity" to "受限双向容量",
        "single_request_blackout" to "单请求黑洞",
        "post_recovery_stability" to "恢复后稳定性",
        "ip_layer_loss" to "IP 转发层丢包",
        "ip_forwarding_blackout" to "IP 转发中断",
        "low_latency_recovery" to "低时延恢复",
    )

    fun labels(featureIds: List<String>): List<String> = featureIds.distinct().map { id -> labels[id] ?: "未识别特征($id)" }

    fun sentence(featureIds: List<String>, fallbackFeatureIds: List<String>): String {
        val resolved = labels(featureIds.ifEmpty { fallbackFeatureIds })
        return "本次模拟的业务行为：${resolved.joinToString("、")}。"
    }
}

internal fun verdictSeverity(verdict: TokenVerdict): AnebConclusionSeverity = when (verdict) {
    TokenVerdict.PASS -> AnebConclusionSeverity.INFO
    TokenVerdict.FAIL, TokenVerdict.INVALID -> AnebConclusionSeverity.FAILURE
    TokenVerdict.INCONCLUSIVE -> AnebConclusionSeverity.WARNING
}
