package com.aneb.probe.ui

import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.BasicSpeedResult
import com.aneb.probe.engine.LoadedResponsiveness
import com.aneb.probe.engine.LoadedResponsivenessReport
import com.aneb.probe.engine.LoadedResponsivenessSummary
import com.aneb.probe.ui.theme.Grade
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 热门应用与 AI 体验校准评估器（纯展示投影层，JVM 可单测，绝不改写既有 AQS / KPI 评分口径）。
 *
 * 阈值来源于 `docs/research/ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md` 与
 * `docs/research/PROFILE4_VOICE_CORRELATION_REPORT.md` 在 P40 Pro 受控 R/B 实测中标定的拐点：
 * - 抖音 1080P 短视频（10 卡 / 20 阶段）：起播悬崖 `(1.0, 1.5] Mbps`，稳态连播门限 `(1.5, 2.0] Mbps`，
 *   `>= 4.0 Mbps` 在 `200ms + 5%` 损伤下仍零中段卡顿；
 * - 豆包 AI 对话流（`com.larus.nova`）：流式饿死悬崖 `(0.256, 1.0] Mbps`，低于 256 kbps + 高丢包时
 *   卡死在加载中超 26 秒；有 Token 实测时优先使用实测 TTFT/ITL/卡顿率/AQS；
 * - 豆包 / AI 实时语音（Profile 4，秩相关 ρ = +1.000）：口到耳 `M1' <= 400 ms` 流畅，超过 400 ms 触否决线；
 *   且外场 Wi-Fi vs 5G 实测证明高带宽/低 Ping 无法替代实时语音实测。
 */
object BusinessExperienceEvaluator {

    const val CALIBRATION_CLAIM_SCOPE =
        "基于 ANEB 实机校准拐点与本机网络实测推断，非第三方商业 App 内部遥测。"

    enum class Tone { EXCELLENT, GOOD, CAUTION, POOR, NEUTRAL }

    data class Verdict(
        val id: String,
        val title: String,
        val badge: String,
        val tone: Tone,
        val headline: String,
        val detail: String,
        val calibrationBasis: String,
    )

    fun evaluateDouyin(basic: BasicSpeedResult?): Verdict =
        evaluateDouyinMetrics(
            downMbps = basic?.downloadMbps,
            rttMs = basic?.pingMs,
            lossRate = basic?.requestLossRate,
        )

    fun evaluateDouyin(entity: BasicSpeedResultEntity?): Verdict =
        evaluateDouyinMetrics(
            downMbps = entity?.downloadMbps,
            rttMs = entity?.pingMs,
            lossRate = entity?.requestLossRate,
        )

    fun evaluateDouyinMetrics(
        downMbps: Double?,
        rttMs: Double?,
        lossRate: Double?,
    ): Verdict {
        val basis = "校准基线：P40 Pro 抖音 10 卡实测（1.0M 封面冻结 6–9s · 1.5M 中途卡顿 7.8s · ≥2.0M 稳态流畅 · ≥4.0M 抗弱网解耦）"
        val dl = downMbps?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Verdict(
                id = "douyin",
                title = "抖音 1080P 短视频",
                badge = "待实测",
                tone = Tone.NEUTRAL,
                headline = "尚未完成基础下行测速",
                detail = "运行「基础 / 抖音评估」后，将按实测下行吞吐、时延与请求失败率判定刷播流畅度。",
                calibrationBasis = basis,
            )
        val loss = lossRate?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val rtt = rttMs?.takeIf { it.isFinite() && it >= 0.0 }
        val metrics = buildList {
            add("下行 ${one(dl)} Mbps")
            rtt?.let { add("Ping ${one(it)} ms") }
            lossRate?.takeIf { it.isFinite() }?.let { add("请求失败 ${pct(it)}") }
        }.joinToString(" · ")

        return when {
            dl >= 4.0 && loss <= 0.05 && (rtt == null || rtt <= 220.0) -> Verdict(
                id = "douyin",
                title = "抖音 1080P 短视频",
                badge = "流畅秒开",
                tone = Tone.EXCELLENT,
                headline = "1080P 刷播秒开，抗弱网余量充足",
                detail = "$metrics。超过 4.0 Mbps 解耦余量线，即使轻中度网络波动也能稳定连播不转圈。",
                calibrationBasis = basis,
            )
            dl >= 2.0 && loss <= 0.03 && (rtt == null || rtt <= 180.0) -> Verdict(
                id = "douyin",
                title = "抖音 1080P 短视频",
                badge = "流畅播放",
                tone = Tone.GOOD,
                headline = "满足 1080P 稳态流畅刷播条件",
                detail = "$metrics。高于 2.0 Mbps 连续播放门限，正常上下滑切换与播中不易卡顿。",
                calibrationBasis = basis,
            )
            dl >= 1.5 -> Verdict(
                id = "douyin",
                title = "抖音 1080P 短视频",
                badge = "临界易卡",
                tone = Tone.CAUTION,
                headline = "首帧通常能打开，但播中容易转圈缓冲",
                detail = "$metrics。处于 1.5–2.0 Mbps 缓冲临界区或伴随较高时延/丢包，实测易出现数秒中段停顿。",
                calibrationBasis = basis,
            )
            else -> Verdict(
                id = "douyin",
                title = "抖音 1080P 短视频",
                badge = "严重受限",
                tone = Tone.POOR,
                headline = "首帧慢，极易卡在封面转圈",
                detail = "$metrics。低于 1.5 Mbps 起播悬崖，实测 1.0 Mbps 封面冻结可达 6–9 秒。",
                calibrationBasis = basis,
            )
        }
    }

    fun evaluateDoubaoChat(
        tokenRun: TestRun?,
        scenarios: List<ScenarioResultEntity> = emptyList(),
        basic: BasicSpeedResultEntity? = null,
    ): Verdict {
        val basis = "校准基线：ANEB Token 流式实测（TTFT/ITL/卡顿）+ 豆包实测饿死悬崖（≤256 kbps 易卡死超 26s · ≥1.0M 正常完成）"
        val score = tokenRun?.aqsScore?.takeIf { it.isFinite() }
        if (tokenRun != null && score != null) {
            val rows = ResultFormat.runKpiRows(scenarios).associateBy { it.row.id }
            val ttftMs = rows["T1"]?.row?.value
            val itlMs = rows["T2"]?.row?.value
            val stallRate = rows["T3"]?.row?.value
            val metricSummary = buildList {
                add("AI 体验分 ${score.roundToInt()}")
                ttftMs?.let { add("首字 ${String.format(Locale.ROOT, "%.2fs", it / 1000.0)}") }
                itlMs?.let { add("间隔 ${itlMs.roundToInt()}ms") }
                stallRate?.let { add("卡顿 ${pct(it)}") }
            }.joinToString(" · ")
            val grade = Grade.fromAqsScore(score)
            return when {
                score >= 85.0 && (stallRate == null || stallRate <= 0.02) -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "跟手流畅",
                    tone = Tone.EXCELLENT,
                    headline = "文字对话与代码生成都很跟手",
                    detail = "$metricSummary（${grade.labelFriendly}）。首字快且流式吐字连续，适合豆包/Kimi/DeepSeek 长回答。",
                    calibrationBasis = basis,
                )
                score >= 70.0 -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "顺畅可用",
                    tone = Tone.GOOD,
                    headline = "日常 AI 对话顺畅，偶尔轻微等待",
                    detail = "$metricSummary（${grade.labelFriendly}）。可以稳定完成豆包与编码 Agent 多轮输出。",
                    calibrationBasis = basis,
                )
                score >= 50.0 -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "偏慢易顿",
                    tone = Tone.CAUTION,
                    headline = "首字偏慢或长回答中段有停顿",
                    detail = "$metricSummary（${grade.labelFriendly}）。建议切换更稳定 Wi-Fi/5G 后再用多模态或编码助手。",
                    calibrationBasis = basis,
                )
                else -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "容易卡死",
                    tone = Tone.POOR,
                    headline = "极易卡在「思考中 / ...」或中途断流",
                    detail = "$metricSummary（${grade.labelFriendly}）。当前路径难以支撑稳定流式 AI 对话。",
                    calibrationBasis = basis,
                )
            }
        }

        // Fallback when only basic speed exists (clearly distinguished from full Token run).
        val dl = basic?.downloadMbps?.takeIf { it.isFinite() && it >= 0.0 }
        if (dl != null) {
            val loss = basic.requestLossRate?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val rtt = basic.pingMs?.takeIf { it.isFinite() && it >= 0.0 }
            val metrics = buildList {
                add("下行 ${one(dl)} Mbps")
                rtt?.let { add("Ping ${one(it)} ms") }
                basic.requestLossRate?.takeIf { it.isFinite() }?.let { add("请求失败 ${pct(it)}") }
            }.joinToString(" · ")
            return when {
                dl >= 1.0 && loss <= 0.02 && (rtt == null || rtt <= 120.0) -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "基础可达",
                    tone = Tone.GOOD,
                    headline = "基础带宽高于豆包流式悬崖，建议补跑 AI 对话测速",
                    detail = "$metrics。高于 1.0 Mbps 实测稳定线；再跑一次「AI 对话/编码」可拿到真实首字 TTFT 与吐字卡顿率。",
                    calibrationBasis = basis,
                )
                dl > 0.3 -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "弱网临界",
                    tone = Tone.CAUTION,
                    headline = "处于豆包流式临界区，长回答容易变慢",
                    detail = "$metrics。弱网抖动或丢包会引起 QUIC 重传放大，建议补跑完整 Token 流式体验测试。",
                    calibrationBasis = basis,
                )
                else -> Verdict(
                    id = "doubao_chat",
                    title = "豆包 / AI 对话流",
                    badge = "极易饿死",
                    tone = Tone.POOR,
                    headline = "低于 256 kbps 实测饿死悬崖，可能卡死无吐字",
                    detail = "$metrics。P40 Pro 实测 256 kbps + 高丢包下豆包等待超 26 秒零字符吐出。",
                    calibrationBasis = basis,
                )
            }
        }

        return Verdict(
            id = "doubao_chat",
            title = "豆包 / AI 对话流",
            badge = "待实测",
            tone = Tone.NEUTRAL,
            headline = "尚未完成 AI 对话流测速",
            detail = "点击运行「AI 对话/编码」获取首字响应 TTFT、Token 间隔与长回答卡顿率。",
            calibrationBasis = basis,
        )
    }

    fun evaluateVoice(voice: VoiceResultEntity?): Verdict {
        val basis = "校准基线：Profile 4 双工测量与豆包实时语音通话实测秩相关 ρ = +1.000（口到耳 ≤400ms 流畅线）"
        if (voice == null) {
            return Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "待实测",
                tone = Tone.NEUTRAL,
                headline = "尚未执行 Profile 4 实时语音测量",
                detail = "实测证明高带宽/低 Ping 不能替代语音质量（曾现 Wi-Fi 44 Mbps 语音翻车而 5G 顺畅），请直接测一次实时语音。",
                calibrationBasis = basis,
            )
        }
        val (mouthEar, isProxy) = VoiceFormat.primaryMouthEar(voice.mouthEarProxyMs, voice.mouthEarBudgetMs)
        val m7GapMs = voice.m7MaxFrameGapMs?.takeIf { it.isFinite() }
        val caliber = VoiceFormat.caliberLabel(voice.caliber)
        val mouthText = mouthEar?.takeIf { it.isFinite() }?.let { "${it.roundToInt()} ms" } ?: "—"
        val labelKind = if (isProxy) "实测代理口到耳" else "预算口到耳"
        val m7Text = m7GapMs?.let { " · 最大静默 ${it.roundToInt()} ms" }.orEmpty()
        val lowConfText = if (voice.lowConfidence) " · 低置信样本" else ""

        if (mouthEar == null || !mouthEar.isFinite()) {
            return Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "证据不足",
                tone = Tone.NEUTRAL,
                headline = "上次语音测速未产生有效口到耳读数",
                detail = "口径 $caliber$m7Text$lowConfText。建议在稳定网络下重测 Profile 4。",
                calibrationBasis = basis,
            )
        }
        return when {
            mouthEar <= 260.0 && (m7GapMs == null || m7GapMs <= 120.0) && !voice.lowConfidence -> Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "自然对话",
                tone = Tone.EXCELLENT,
                headline = "口到耳响应迅速，支持自然打断与实时语音",
                detail = "$labelKind $mouthText$m7Text（$caliber）。远优于 400 ms 流畅门槛。",
                calibrationBasis = basis,
            )
            mouthEar <= 400.0 && (m7GapMs == null || m7GapMs <= 250.0) -> Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "基本流畅",
                tone = Tone.GOOD,
                headline = "可正常进行豆包语音对话，偶有轻度迟滞",
                detail = "$labelKind $mouthText$m7Text$lowConfText（$caliber）。在 400 ms 交互阈值内。",
                calibrationBasis = basis,
            )
            mouthEar <= 650.0 -> Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "延迟偏高",
                tone = Tone.CAUTION,
                headline = "超过 400 ms 交互否决线，对话容易抢话/停顿",
                detail = "$labelKind $mouthText$m7Text$lowConfText（$caliber）。可对比切换 Wi-Fi 与 5G 蜂窝网络。",
                calibrationBasis = basis,
            )
            else -> Verdict(
                id = "doubao_voice",
                title = "豆包 / AI 实时语音",
                badge = "易卡断",
                tone = Tone.POOR,
                headline = "口到耳严重超时，实时 AI 语音难以流畅通话",
                detail = "$labelKind $mouthText$m7Text$lowConfText（$caliber）。网络双向抖动或排队严重。",
                calibrationBasis = basis,
            )
        }
    }

    fun evaluateLoadedResponsiveness(summary: LoadedResponsivenessSummary?): Verdict {
        val basis = "测量口径：ANEB LoadedResponsiveness v2（空闲 RTT P50 vs 满载下载/上传 RTT P95 增量，不进综合分）"
        if (summary == null || summary.idleP50Ms == null) {
            return Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "待实测",
                tone = Tone.NEUTRAL,
                headline = "尚未获得有效负载响应证据",
                detail = "运行「抗负载响应」可测出边下载/边上传文件时，交互延迟会暴涨多少毫秒。",
                calibrationBasis = basis,
            )
        }
        val maxDelta = listOfNotNull(summary.downloadDeltaMs, summary.uploadDeltaMs)
            .filter { it.isFinite() }
            .maxOrNull()
        val detail = "空闲 ${one(summary.idleP50Ms)} ms · 下载增量 ${deltaText(summary.downloadDeltaMs)} · 上传增量 ${deltaText(summary.uploadDeltaMs)}"
        return when {
            maxDelta == null -> Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "部分样本",
                tone = Tone.CAUTION,
                headline = "仅部分方向获得负载响应样本",
                detail = detail,
                calibrationBasis = basis,
            )
            maxDelta <= 40.0 -> Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "缓冲控制优秀",
                tone = Tone.EXCELLENT,
                headline = "满载下载/上传时延迟几乎不膨胀",
                detail = "$detail。边传大文件边用 AI 语音或对话也不易卡顿。",
                calibrationBasis = basis,
            )
            maxDelta <= 120.0 -> Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "轻度膨胀",
                tone = Tone.GOOD,
                headline = "高负载下延迟有可控抬升",
                detail = "$detail。普通 AI 文本对话可用，同时上传大图时语音偶有迟滞。",
                calibrationBasis = basis,
            )
            maxDelta <= 250.0 -> Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "明显排队",
                tone = Tone.CAUTION,
                headline = "满载传输存在明显 Bufferbloat 排队膨胀",
                detail = "$detail。后台下载或上传多模态文件时，AI 响应会明显变慢。",
                calibrationBasis = basis,
            )
            else -> Verdict(
                id = "loaded_responsiveness",
                title = "抗负载响应（边传边聊 AI）",
                badge = "严重膨胀",
                tone = Tone.POOR,
                headline = "满载期间延迟暴涨，并发交互极易卡顿",
                detail = "$detail。路由器或链路队列积压严重，建议避免并发大流量上传。",
                calibrationBasis = basis,
            )
        }
    }

    fun evaluateLoadedResponsiveness(report: LoadedResponsivenessReport?): Verdict =
        evaluateLoadedResponsiveness(report?.let(LoadedResponsiveness::summarize))

    private fun one(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    private fun pct(v: Double): String = String.format(Locale.ROOT, "%.1f%%", v * 100.0)
    private fun deltaText(v: Double?): String =
        v?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%+.0f ms", it) } ?: "不可用"
}
