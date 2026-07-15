package com.aneb.probe.engine

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.AqsScorer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 结果上报体构造（P1 范围 8，设计文档 §7 上报口径）。纯 JVM、无 Android 依赖。
 *
 * 合同字段（server/handlers_results.go 校验）：claim_scope（const 锁定）、kpi_set、
 * aqs_version、profile_versions、schema_version 顶层必填。正文＝TestRun 摘要 +
 * 各场景 KPI（值+分级+三态+原因码）+ ITL 对数分桶直方图；目标 <200KB。
 */
object ResultReporter {

    const val CLAIM_SCOPE = "application_end_to_end_to_probe_node"
    const val SCHEMA_VERSION = "1.0"

    /** 上报体大小软上限（bytes）；超限由调用方打 REPORT_SIZE_WARN 日志 */
    const val MAX_REPORT_BYTES = 200_000

    fun build(
        run: TestRun,
        scenarios: List<Pair<ScenarioResultEntity, ItlHistogram>>,
        aqs: AqsScorer.AqsResult,
    ): String = buildJsonObject {
        // ---- 合同字段（顶层，const/枚举锁定） ----
        put("claim_scope", CLAIM_SCOPE)
        put("kpi_set", run.kpiSet)
        put("aqs_version", run.aqsVersion)
        put("profile_versions", run.profileVersions)
        put("schema_version", SCHEMA_VERSION)

        // ---- TestRun 摘要 ----
        put("run", buildJsonObject {
            put("run_id", run.runId)
            put("started_at_epoch_ms", run.startedAtEpochMs)
            put("mode", run.mode)
            put("scenario_order", run.scenarioOrder)
            put("transport", run.transport)
            put("profile_source", run.profileSource)
            put("app_version_name", run.appVersionName)
            put("app_version_code", run.appVersionCode)
            put("guard_metadata", run.guardMetadata)
            put("status", run.status)
            put("aqs", buildJsonObject {
                put("score", aqs.score)
                put("low_confidence", aqs.lowConfidence)
                put("veto_applied", aqs.vetoApplied)
                put("not_computable_reason", aqs.notComputableReason)
                put("input_mapping", AqsInputMapper.MAPPING_DESCRIPTION)
                put("sub_scores", buildJsonObject {
                    aqs.subScores.forEach { (k, v) -> put(k, v) }
                })
            })
        })

        // ---- 各场景 KPI + ITL 直方图 ----
        putJsonArray("scenarios") {
            for ((s, hist) in scenarios) add(scenarioJson(s, hist))
        }
    }.toString()

    private fun scenarioJson(s: ScenarioResultEntity, hist: ItlHistogram): JsonObject = buildJsonObject {
        put("profile_id", s.profileId)
        put("profile_version", s.profileVersion)
        put("repeat_index", s.repeatIndex)
        put("order_index", s.orderIndex)
        put("validity", s.validity)
        put("invalid_reasons", s.invalidReasons)
        put("kpi", buildJsonObject {
            put("t1_ttft_ms", s.t1TtftMs); put("t1_grade", s.t1Grade)
            put("t2_itl_p95_ms", s.t2ItlP95Ms); put("t2_grade", s.t2Grade)
            put("t2_itl_p95_incl_coalesced_ms", s.t2ItlP95InclCoalescedMs)
            put("t3_stall_rate", s.t3StallRate); put("t3_grade", s.t3Grade)
            put("t3_stall_rate_incl_resume", s.t3StallRateInclResume)
            put("t4_severe_stall_rate", s.t4SevereStallRate); put("t4_grade", s.t4Grade)
            put("t5_resume_p95_ms", s.t5ResumeP95Ms)
            put("n1_rtt_p50_ms", s.n1RttP50Ms); put("n1_grade", s.n1Grade)
            put("n2_jitter_ms", s.n2JitterMs); put("n2_grade", s.n2Grade)
            put("u1_goodput_mbps", s.u1GoodputMbps); put("u1_grade", s.u1Grade)
            put("u1_goodput_excl_slow_start_mbps", s.u1GoodputExclSlowStartMbps)
            put("u2_tool_loop_p95_ms", s.u2ToolLoopP95Ms); put("u2_grade", s.u2Grade)
            put("seq_gap_count", s.seqGapCount)
            put("seq_dup_count", s.seqDupCount)
        })
        put("clock", buildJsonObject {
            put("offset_start_us", s.offsetStartUs)
            put("offset_end_us", s.offsetEndUs)
            put("drift_ppm", s.offsetDriftPpm)
            put("offset_suspect", s.offsetSuspect)
        })
        put("network_snapshot", buildJsonObject {
            put("transport", s.netTransport)
            put("capabilities", s.netCapabilities)
            put("interface", s.netInterfaceName)
            put("server_observed_addr", s.serverObservedAddr)
        })
        put("parse", buildJsonObject {
            put("parse_dur_us", s.parseDurUsTotal)
            put("per_event_parse_us", s.perEventParseUs)
        })
        // P1-C08 遗留接线：批化标注（additive 扩展——server/handlers_results.go 的
        // validateResultContract 只校验必填字段、不拒新增字段，已读码确认）。
        // R-05：score/attribution 仅为标注与取证证据，服务端/下游不得据此改判 validity。
        put("buffering", buildJsonObject {
            put("score", s.bufferingScore)
            put("attribution", s.bufferingAttribution)
            put("sample_count", s.bufferingSampleCount)
            put("sawtooth_ratio", s.bufferingSawtoothRatio)
            put("near_zero_arrival_ratio", s.bufferingNearZeroRatio)
            put("lag1_autocorrelation", s.bufferingLag1Autocorr)
            put("batch_count", s.bufferingBatchCount)
            put("best_grid_us", s.bufferingBestGridUs)
            put("jank_overlap_ratio", s.bufferingJankOverlapRatio)
        })
        // ITL 对数分桶直方图（R-27 合同：桶界 = 对数网格 ∪ T2/T3/T4 门限锚点）
        put("itl_histogram", buildJsonObject {
            put("buckets_version", ItlHistogram.BUCKETS_VERSION)
            putJsonArray("edges_ms") { hist.edgesMs.forEach { add(it) } }
            putJsonArray("counts") { hist.counts.forEach { add(it) } }
            put("total", hist.total)
        })
    }
}
