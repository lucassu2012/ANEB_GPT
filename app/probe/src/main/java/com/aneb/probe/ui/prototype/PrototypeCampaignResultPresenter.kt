package com.aneb.probe.ui.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import java.util.Locale

internal data class PrototypeCampaignResultPresentation(
    val campaignId: String,
    val status: String,
    val campaignMode: String,
    val attemptedRuns: String,
    val successfulRuns: String,
    val failedRuns: String,
    val notStartedRuns: String,
    val integrity: String,
    val evidenceBadge: String,
    val confidenceExplanation: String,
    val rpiLabel: String,
    val disclosure: String,
    val conditions: List<PrototypeConditionResultPresentation>,
    val blockingError: PrototypeCampaignBlockingErrorPresentation? = null,
    val publicationNodeUrl: String = "",
)

internal data class PrototypeCampaignBlockingErrorPresentation(
    val code: String,
    val title: String,
    val cause: String,
    val action: String,
    val detail: String,
    val evidenceRetained: Boolean,
)

internal data class PrototypeConditionResultPresentation(
    val conditionId: String,
    val title: String,
    val ttft: String,
    val completion: String,
    val eventRate: String,
    val stallCount: String,
    val stallDuration: String,
    val successRate: String,
    val rpi: String,
    val confidence: String,
    val metricNullReason: String?,
    val rpiNullReasons: List<String>,
)

internal object PrototypeCampaignResultPresenter {
    fun present(
        stored: PrototypeCampaignRoomRepository.StoredCampaign,
    ): PrototypeCampaignResultPresentation {
        val summary = stored.summary
        val mode = PrototypeQuickCampaignRunner.CampaignMode.entries.singleOrNull { candidate ->
            candidate.wireValue == summary.campaignMode
        } ?: throw IllegalArgumentException("unsupported Prototype campaign mode")
        val campaignModeLabel = when (mode) {
            PrototypeQuickCampaignRunner.CampaignMode.QUICK -> "Quick"
            PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE -> "Acceptance"
        }
        val expectedRunConditions = List(mode.runsPerCondition) { CONDITION_IDS }.flatten()
        require(summary.conditionSummaries.map { it.conditionId } == CONDITION_IDS)
        require(stored.runs.map { it.conditionId } == expectedRunConditions)
        val invalidSequenceRun = stored.runs.firstOrNull { run ->
            run.status == PrototypeQuickCampaignRunner.RunStatus.INVALID_SEQUENCE
        }
        val interruptedRun = stored.runs.firstOrNull { run ->
            run.status == PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED
        }

        return PrototypeCampaignResultPresentation(
            campaignId = stored.campaignId,
            publicationNodeUrl = stored.nodeBaseUrl,
            status = when (summary.status) {
                PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE -> "已完成"
                PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL -> "部分完成"
                PrototypeQuickCampaignRunner.CampaignStatus.CANCELLED -> "已取消"
            },
            campaignMode = campaignModeLabel,
            attemptedRuns = summary.attemptedRuns.toString(),
            successfulRuns = summary.successfulRuns.toString(),
            failedRuns = summary.failedRuns.toString(),
            notStartedRuns = summary.notStartedRuns.toString(),
            integrity = "本地测试结果已保存 · 证据包尚未验证",
            evidenceBadge = "应用层合成条件",
            confidenceExplanation =
                "Confidence 表示本轮 $campaignModeLabel 的证据完整度，" +
                    "不是行业或网络质量的统计置信区间。",
            rpiLabel = "相对原型指标 RPI（同一轮合成条件对比，不是网络绝对评分）",
            disclosure = DISCLOSURE,
            conditions = summary.conditionSummaries.mapIndexed { index, condition ->
                val failedRun = stored.runs.firstOrNull { run ->
                    run.conditionId == condition.conditionId && run.failureReason != null
                }
                PrototypeConditionResultPresentation(
                    conditionId = condition.conditionId,
                    title = CONDITION_TITLES[index],
                    ttft = metric(condition.medianTtftMs, " ms"),
                    completion = metric(condition.medianCompletionMs, " ms"),
                    eventRate = metric(condition.medianStreamEventRateEps, " events/s"),
                    stallCount = metric(condition.medianStallCount),
                    stallDuration = metric(condition.medianStallDurationMs, " ms"),
                    successRate = metric(condition.successRate * 100.0, "%"),
                    rpi = condition.rpi?.toString() ?: MISSING_VALUE,
                    confidence = condition.confidence.name,
                    metricNullReason = failedRun?.failureReason.takeIf {
                        condition.medianTtftMs == null ||
                            condition.medianCompletionMs == null ||
                            condition.medianStreamEventRateEps == null ||
                            condition.medianStallCount == null ||
                            condition.medianStallDurationMs == null
                    },
                    rpiNullReasons = condition.allNullReasons
                        ?: listOfNotNull(condition.primaryNullReason),
                )
            },
            blockingError = invalidSequenceRun?.let { run ->
                PrototypeCampaignBlockingErrorPresentation(
                    code = "P009_INVALID_SEQUENCE",
                    title = "事件顺序无效",
                    cause = "内容事件存在缺失、重复或乱序。",
                    action = "证据已保留，请报告此实现缺陷。",
                    detail = "Run ${run.runIndex} · ${run.conditionId} · ${run.runId} · " +
                        "${run.eventsReceived}/120 events retained",
                    evidenceRetained = true,
                )
            } ?: interruptedRun?.let { run ->
                PrototypeCampaignBlockingErrorPresentation(
                    code = "P008_STREAM_INTERRUPTED",
                    title = "数据流中断",
                    cause = "收到有效结束回执前，数据流已中断。",
                    action = "部分证据已保留。仅发布证据可重试发布；需要重新测量时，检查节点连接后开始新一轮测试。",
                    detail = "Run ${run.runIndex} · ${run.conditionId} · ${run.runId} · " +
                        "${run.eventsReceived}/120 events retained",
                    evidenceRetained = true,
                )
            },
        )
    }

    private fun metric(value: Double?, suffix: String = ""): String = value?.let {
        String.format(Locale.ROOT, "%.6f", it).trimEnd('0').trimEnd('.') + suffix
    } ?: MISSING_VALUE

    private val CONDITION_IDS = listOf(
        "baseline_v0.1",
        "slow_v0.1",
        "unstable_v0.1",
    )
    private val CONDITION_TITLES = listOf("基线 Baseline", "慢速 Slow", "不稳定 Unstable")
    private const val MISSING_VALUE = "—"
    private const val DISCLOSURE =
        "读数说明：TTFT 是请求发出到首个有效流事件的等待时间，不是模型推理时延；Completion 是请求发出到有效结束回执的耗时。" +
            "Stall 是相邻事件间隔超过冻结阈值的停顿，停顿时长按超出该条件正常间隔的部分累计；不包括首事件等待和结束回执等待。" +
            "Success rate 是成功次数占本条件计划次数的比例，耗时与停顿汇总取成功测量的中位数。事件速率是 events/s，不是 tokens/s 或带宽。— 表示无可用值，不是 0。\n\n" +
        "此分数将确定性应用层合成条件与同一轮 Baseline 基线比较。" +
            "它不是正式 ANEB 行业评分，也不代表第三方 AI 应用的" +
            "网络需求。这些结果来自本机" +
            "探针的应用层合成测量，不测量或代表 IP 丢包、RAN、核心网、运营商、公网、" +
            "真实第三方 AI 应用、模型推理、AQS、MOS、网络质量、SLA 或等级。"
}
