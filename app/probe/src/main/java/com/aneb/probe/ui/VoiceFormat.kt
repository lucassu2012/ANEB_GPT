package com.aneb.probe.ui

import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.scoring.KpiValue
import com.aneb.probe.ui.theme.Grade
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * Profile 4 实时语音测量（[VoiceRunner] / [VoiceResultEntity]）的纯 JVM 展示、评分与离线导出格式化层。
 *
 * 严格遵守 R-10 与 ANEB 声明边界：
 * - 所有可空指标原样透传：UI 一律展示 "—"，CSV 一律输出空串，JSON 一律输出 `null`，绝不以 0 顶替；
 * - 语音评分不在 Room 持久化，展示侧通过 [AqsScorer.scoreVoice] / [AqsScorer.scoreVoiceSim] 现算；
 * - 数值格式化固定 [Locale.ROOT]，防逗号小数区域污染 CSV 与展示口径；
 * - 独立观测口径，不混入 Token AQS 或宣称无线层/MOS/运营商全网结论。
 */
object VoiceFormat {

    /** 语音导出独立 claim_scope（与 Token 上报体 [com.aneb.probe.engine.ResultReporter.CLAIM_SCOPE] 显式区分）。 */
    const val CLAIM_SCOPE: String = "application_end_to_end_to_probe_node_voice_observation"

    const val EXPORT_SCHEMA_VERSION: String = "voice-export-1.0"

    const val CLAIM_SCOPE_TEXT: String =
        "测量对象为终端至指定仿真节点的实时语音应用层模拟路径，观测口径独立出分，非真实音频链路/MOS/无线层或运营商全网结论"

    const val CSV_HEADER: String =
        "id,ts_epoch_ms,caliber,low_confidence,rtt_ms,jitter_ms," +
            "up_frame_jitter_ms,down_frame_jitter_ms,mouth_ear_budget_ms," +
            "frames_sent,frames_recv,ttfb_p50_ms,ttfb_p95_ms,down_net_jitter_ms," +
            "mouth_ear_proxy_ms,turn_switch_p50_ms,barge_stop_max_ms,turns_ok," +
            "m7_max_frame_gap_ms,voice_near_zero_arrival_ratio"

    fun caliberLabel(caliber: String?): String = when (caliber) {
        VoiceRunner.SIM_CALIBER -> "server-sim"
        null -> "paced-proxy"
        else -> caliber
    }

    fun msOrDash(value: Double?, decimals: Int = 1): String {
        val v = value?.takeIf { it.isFinite() } ?: return "—"
        return String.format(Locale.ROOT, "%.${decimals}f", v)
    }

    /**
     * 近零到达占比（[0,1]）展示：null → "—"（R-10：0.0% 为实测零，"—" 为缺测，必须可区分）。
     */
    fun nearZeroRatioDisplay(ratio: Double?): String {
        val v = ratio?.takeIf { it.isFinite() } ?: return "—"
        return String.format(Locale.ROOT, "%.1f%%", v * 100.0)
    }

    /** 口到耳主值：v2 实测代理优先，缺席时退 v1 网络预算；返回 `(数值, 是否实测代理)`。 */
    fun primaryMouthEar(proxyMs: Double?, budgetMs: Double?): Pair<Double?, Boolean> =
        if (proxyMs != null) proxyMs to true else budgetMs to false

    /** 仅对 [VoiceRunner.Phase.Done] 样本现算独立语音体验分；未完成相位返回 null。 */
    fun scoreSample(sample: VoiceRunner.Sample): AqsScorer.AqsResult? {
        if (sample.phase != VoiceRunner.Phase.Done) return null
        return scoreFields(
            caliber = sample.caliber,
            lowConfidence = sample.lowConfidence,
            rttMs = sample.rttMs,
            jitterMs = sample.jitterMs,
            upFrameJitterMs = sample.upFrameJitterMs,
            downFrameJitterMs = sample.downFrameJitterMs,
            mouthEarBudgetMs = sample.mouthEarBudgetMs,
            framesSent = sample.framesSent,
            framesRecv = sample.framesRecv,
            ttfbP50Ms = sample.ttfbP50Ms,
            downNetJitterMs = sample.downNetJitterMs,
            mouthEarProxyMs = sample.mouthEarProxyMs,
            turnSwitchP50Ms = sample.turnSwitchP50Ms,
            bargeStopMaxMs = sample.bargeStopMaxMs,
            turnsOk = sample.turnsOk,
            m7MaxFrameGapMs = sample.m7MaxFrameGapMs,
        )
    }

    /** 对已落库的 [VoiceResultEntity] 现算独立语音体验分（用于详情复核；不写回库）。 */
    fun scoreEntity(entity: VoiceResultEntity): AqsScorer.AqsResult = scoreFields(
        caliber = entity.caliber,
        lowConfidence = entity.lowConfidence,
        rttMs = entity.rttMs,
        jitterMs = entity.jitterMs,
        upFrameJitterMs = entity.upFrameJitterMs,
        downFrameJitterMs = entity.downFrameJitterMs,
        mouthEarBudgetMs = entity.mouthEarBudgetMs,
        framesSent = entity.framesSent ?: 0,
        framesRecv = entity.framesRecv ?: 0,
        ttfbP50Ms = entity.ttfbP50Ms,
        downNetJitterMs = entity.downNetJitterMs,
        mouthEarProxyMs = entity.mouthEarProxyMs,
        turnSwitchP50Ms = entity.turnSwitchP50Ms,
        bargeStopMaxMs = entity.bargeStopMaxMs,
        turnsOk = entity.turnsOk ?: 0,
        m7MaxFrameGapMs = entity.m7MaxFrameGapMs,
    )

    private fun scoreFields(
        caliber: String?,
        lowConfidence: Boolean,
        rttMs: Double?,
        jitterMs: Double?,
        upFrameJitterMs: Double?,
        downFrameJitterMs: Double?,
        mouthEarBudgetMs: Double?,
        framesSent: Int,
        framesRecv: Int,
        ttfbP50Ms: Double?,
        downNetJitterMs: Double?,
        mouthEarProxyMs: Double?,
        turnSwitchP50Ms: Double?,
        bargeStopMaxMs: Double?,
        turnsOk: Int,
        m7MaxFrameGapMs: Double?,
    ): AqsScorer.AqsResult = if (caliber == VoiceRunner.SIM_CALIBER) {
        AqsScorer.scoreVoiceSim(
            n1RttMs = KpiValue(rttMs, "ms", 11, lowConfidence = lowConfidence),
            n2JitterMs = KpiValue(jitterMs, "ms", 11, lowConfidence = false),
            m1MouthEarProxyMs = KpiValue(mouthEarProxyMs, "ms", turnsOk, lowConfidence = lowConfidence),
            m2DownNetJitterMs = KpiValue(downNetJitterMs, "ms", framesRecv, lowConfidence = false),
            m3UpFrameJitterMs = KpiValue(upFrameJitterMs, "ms", framesSent, lowConfidence = lowConfidence),
            m4TtfbMs = KpiValue(ttfbP50Ms, "ms", turnsOk, lowConfidence = false),
            m5TurnSwitchMs = KpiValue(turnSwitchP50Ms, "ms", (turnsOk - 1).coerceAtLeast(0), lowConfidence = false),
            m6BargeStopMs = KpiValue(bargeStopMaxMs, "ms", 2, lowConfidence = false),
            m7MaxFrameGapMs = KpiValue(m7MaxFrameGapMs, "ms", framesRecv, lowConfidence = lowConfidence),
        )
    } else {
        AqsScorer.scoreVoice(
            n1RttMs = KpiValue(rttMs, "ms", 11, lowConfidence = false),
            n2JitterMs = KpiValue(jitterMs, "ms", 11, lowConfidence = false),
            m1BudgetMs = KpiValue(mouthEarBudgetMs, "ms", 1, lowConfidence = false),
            m2DownFrameJitterMs = KpiValue(downFrameJitterMs, "ms", framesRecv, lowConfidence = false),
            m3UpFrameJitterMs = KpiValue(upFrameJitterMs, "ms", framesSent, lowConfidence = false),
            m7MaxFrameGapMs = KpiValue(m7MaxFrameGapMs, "ms", framesRecv, lowConfidence = false),
        )
    }

    fun verdictFor(result: AqsScorer.AqsResult): String {
        val score = result.score
            ?: return "语音分不可计算（${result.notComputableReason ?: "指标缺失"}）——诚实缺席，不以 0 顶替。"
        if (result.vetoApplied) {
            return "口到耳超 ${AqsScorer.M1_VETO_THRESHOLD_MS.toInt()}ms 红线——实时对话自然度不可用，分数封顶。"
        }
        return when (Grade.fromAqsScore(score)) {
            Grade.Excellent -> "语音双工承载优秀：口到耳时延低、帧流平稳，适合实时 AI 对话。"
            Grade.Good -> "语音双工承载良好：实时对话可用，帧抖动或时延略有余量收紧。"
            Grade.Fair -> "语音双工承载一般：对话可能出现可感延迟或顿挫。"
            Grade.Poor -> "语音双工承载不足：口到耳或帧间静默超预算，实时语音体验受损。"
        }
    }

    /**
     * 离线 CSV 导出（Room `voice_result` 全 20 列逐行保真）：
     * null 字段输出空串（绝不填 0），浮点使用 [Locale.ROOT]。
     */
    fun buildCsv(records: List<VoiceResultEntity>): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        for (r in records) {
            val cells = listOf(
                r.id.toString(),
                r.tsEpochMs.toString(),
                r.caliber ?: "",
                r.lowConfidence.toString(),
                r.rttMs.csvDouble(),
                r.jitterMs.csvDouble(),
                r.upFrameJitterMs.csvDouble(),
                r.downFrameJitterMs.csvDouble(),
                r.mouthEarBudgetMs.csvDouble(),
                r.framesSent?.toString() ?: "",
                r.framesRecv?.toString() ?: "",
                r.ttfbP50Ms.csvDouble(),
                r.ttfbP95Ms.csvDouble(),
                r.downNetJitterMs.csvDouble(),
                r.mouthEarProxyMs.csvDouble(),
                r.turnSwitchP50Ms.csvDouble(),
                r.bargeStopMaxMs.csvDouble(),
                r.turnsOk?.toString() ?: "",
                r.m7MaxFrameGapMs.csvDouble(),
                r.voiceNearZeroArrivalRatio.csvDouble(),
            )
            sb.append(cells.joinToString(",") { ResultFormat.csvEscape(it) }).append('\n')
        }
        return sb.toString()
    }

    /**
     * 离线 JSON 导出：带显式 [CLAIM_SCOPE] 与免责声明，所有 17 个可空字段按 Room 原值输出
     * JSON `null` 或实测值，绝不补 0。
     */
    fun buildJson(records: List<VoiceResultEntity>): String = buildJsonObject {
        put("claim_scope", CLAIM_SCOPE)
        put("schema_version", EXPORT_SCHEMA_VERSION)
        put("disclaimer", CLAIM_SCOPE_TEXT)
        put("count", records.size)
        putJsonArray("records") {
            for (r in records) {
                add(
                    buildJsonObject {
                        put("id", r.id)
                        put("ts_epoch_ms", r.tsEpochMs)
                        putNullableString("caliber", r.caliber)
                        put("low_confidence", r.lowConfidence)
                        putNullableDouble("rtt_ms", r.rttMs)
                        putNullableDouble("jitter_ms", r.jitterMs)
                        putNullableDouble("up_frame_jitter_ms", r.upFrameJitterMs)
                        putNullableDouble("down_frame_jitter_ms", r.downFrameJitterMs)
                        putNullableDouble("mouth_ear_budget_ms", r.mouthEarBudgetMs)
                        putNullableInt("frames_sent", r.framesSent)
                        putNullableInt("frames_recv", r.framesRecv)
                        putNullableDouble("ttfb_p50_ms", r.ttfbP50Ms)
                        putNullableDouble("ttfb_p95_ms", r.ttfbP95Ms)
                        putNullableDouble("down_net_jitter_ms", r.downNetJitterMs)
                        putNullableDouble("mouth_ear_proxy_ms", r.mouthEarProxyMs)
                        putNullableDouble("turn_switch_p50_ms", r.turnSwitchP50Ms)
                        putNullableDouble("barge_stop_max_ms", r.bargeStopMaxMs)
                        putNullableInt("turns_ok", r.turnsOk)
                        putNullableDouble("m7_max_frame_gap_ms", r.m7MaxFrameGapMs)
                        putNullableDouble("voice_near_zero_arrival_ratio", r.voiceNearZeroArrivalRatio)
                    },
                )
            }
        }
    }.toString()

    private fun Double?.csvDouble(): String {
        val v = this?.takeIf { it.isFinite() } ?: return ""
        return String.format(Locale.ROOT, "%.4f", v)
    }

    private fun JsonObjectBuilder.putNullableString(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun JsonObjectBuilder.putNullableDouble(key: String, value: Double?) {
        val finite = value?.takeIf { it.isFinite() }
        if (finite == null) put(key, JsonNull) else put(key, finite)
    }

    private fun JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}
