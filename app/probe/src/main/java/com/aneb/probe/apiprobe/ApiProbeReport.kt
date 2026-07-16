package com.aneb.probe.apiprobe

import com.aneb.probe.data.ApiProbeResultEntity
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 真实 API 探针结果导出体构造（纯 JVM，无 Android 依赖）。
 *
 * 单独归类合同（与 /results 上报体**不同构、不混排**）：
 *  - 顶层 claim_scope const 锁定 `application_end_to_end_to_llm_api`；
 *  - `excluded_from_aqs: true` 显式声明不进 AQS（设计文档 §9 风险表）；
 *  - 只入本地导出；若阶段 3 需要上报，须先扩展服务端合同（TODO 见 ApiProbe KDoc）。
 *
 * **隐私红线**：所有自由文本字段（error/protocolError/guardMetadata）写入前再过一次
 * [ApiKeyRedactor]（defense-in-depth；入库时已过一次）。JVM 单测锚定输出不含 key。
 */
object ApiProbeReport {

    const val CLAIM_SCOPE = "application_end_to_end_to_llm_api"
    const val SCHEMA_VERSION = "apiprobe-1.0"

    /**
     * @param apiKey 当前配置的 key（仅用于出口兜底替换，绝不写入输出）；null=不替换
     */
    fun buildJson(results: List<ApiProbeResultEntity>, apiKey: String?): String = buildJsonObject {
        put("claim_scope", CLAIM_SCOPE)
        put("schema_version", SCHEMA_VERSION)
        put("excluded_from_aqs", true)
        put(
            "scope_note",
            "end-to-end to real LLM API via system default network (user proxy included); " +
                "NOT comparable to probe-node KPIs; no radio/RAN attribution claims",
        )
        putJsonArray("results") {
            for (r in results) {
                add(
                    buildJsonObject {
                        put("started_at_epoch_ms", r.startedAtEpochMs)
                        put("provider", r.provider)
                        put("protocol", r.protocolId)
                        put("base_url", ApiKeyRedactor.redact(r.baseUrl, apiKey))
                        put("model", r.model)
                        put("http_code", r.httpCode)
                        put("error", ApiKeyRedactor.redact(r.error, apiKey))
                        put("kpi", buildJsonObject {
                            put("ttft_ms", r.ttftMs)
                            put("itl_median_ms", r.itlMedianMs)
                            put("itl_p95_ms", r.itlP95Ms)
                            put("itl_sample_count", r.itlSampleCount)
                            put("token_event_count", r.tokenEventCount)
                            put("total_ms", r.totalMs)
                            put("total_text_chars", r.totalTextChars)
                        })
                        put("usage", buildJsonObject {
                            put("input_tokens", r.inputTokens)
                            put("output_tokens", r.outputTokens)
                            put("stop_reason", r.stopReason)
                        })
                        put("parse_errors", r.parseErrors)
                        put("protocol_error", ApiKeyRedactor.redact(r.protocolError, apiKey))
                        put("env", buildJsonObject {
                            put("proxy_detected", r.proxyDetected)
                            put("vpn_detected", r.vpnDetected)
                            put("guard_metadata", ApiKeyRedactor.redact(r.guardMetadata, apiKey))
                        })
                        put("read_count", r.readCount)
                        put("total_bytes", r.totalBytes)
                    }
                )
            }
        }
    }.toString()
}
