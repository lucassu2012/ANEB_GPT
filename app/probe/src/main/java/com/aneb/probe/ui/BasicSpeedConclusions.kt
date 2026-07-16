package com.aneb.probe.ui

import com.aneb.probe.engine.BasicSpeedResult
import java.util.Locale

/**
 * 基本网络测速的用户结论策略。阈值是 ANEB 产品策略，不宣称为运营商 SLA 或行业标准；
 * policyId 随结果展示，后续调整必须升版本并记录决策。
 */
object BasicSpeedConclusions {
    const val POLICY_ID = "network-basic-conclusions-v1"

    enum class Tone { GOOD, CAUTION, POOR, NEUTRAL }

    data class Item(
        val title: String,
        val body: String,
        val tone: Tone,
    )

    fun build(result: BasicSpeedResult): List<Item> {
        val completion = when (result.status) {
            "completed" -> Item(
                "基础测速已完成",
                "下载、上传、时延与应用层请求失败率均已完成采样。",
                Tone.GOOD,
            )
            "partial" -> Item(
                "仅完成部分测速",
                "下载或上传方向缺少有效结果；未测到的指标保持为空，不按 0 处理。",
                Tone.CAUTION,
            )
            else -> Item(
                "基础测速未完成",
                "本次状态为 ${friendlyStatus(result.status)}，不能形成完整网络能力结论。",
                Tone.POOR,
            )
        }
        return listOf(completion, bottleneck(result)) + useCases(result)
    }

    private fun bottleneck(result: BasicSpeedResult): Item {
        val candidates = buildList {
            result.downloadMbps?.let { add(Weakness("下载带宽", throughputSeverity(it, 5.0, 25.0), "${one(it)} Mbps")) }
            result.uploadMbps?.let { add(Weakness("上传带宽", throughputSeverity(it, 2.0, 10.0), "${one(it)} Mbps")) }
            result.pingMs?.let { add(Weakness("交互时延", latencySeverity(it), "${one(it)} ms")) }
            result.jitterMs?.let { add(Weakness("时延抖动", jitterSeverity(it), "${one(it)} ms")) }
            result.requestLossRate?.let { add(Weakness("应用层请求失败", lossSeverity(it), percent(it))) }
        }
        val weakest = candidates.maxByOrNull { it.severity }
            ?: return Item("主要短板未知", "关键指标不足，无法识别主要网络短板。", Tone.NEUTRAL)
        return if (weakest.severity == 0) {
            Item("未见明显基础短板", "本策略覆盖的下载、上传、时延、抖动和请求失败指标均处于良好区间。", Tone.GOOD)
        } else {
            Item(
                "主要短板：${weakest.name}",
                "本次测得 ${weakest.value}，它是当前策略下最需要优先改善的指标。",
                if (weakest.severity >= 2) Tone.POOR else Tone.CAUTION,
            )
        }
    }

    private fun useCases(result: BasicSpeedResult): List<Item> = listOf(
        useCase(
            title = "4K 视频",
            known = result.downloadMbps != null && result.requestLossRate != null,
            suitable = result.downloadMbps?.let { it >= 25.0 } == true &&
                result.requestLossRate?.let { it <= 0.02 } == true,
            pass = "下载与请求稳定性满足本策略的流畅观看条件。",
            fail = "下载需至少 25 Mbps，且应用层请求失败率需不高于 2%。",
        ),
        useCase(
            title = "视频会议",
            known = listOf(result.downloadMbps, result.uploadMbps, result.pingMs, result.jitterMs, result.requestLossRate).all { it != null },
            suitable = result.downloadMbps?.let { it >= 5.0 } == true &&
                result.uploadMbps?.let { it >= 5.0 } == true &&
                result.pingMs?.let { it <= 100.0 } == true &&
                result.jitterMs?.let { it <= 30.0 } == true &&
                result.requestLossRate?.let { it <= 0.02 } == true,
            pass = "上下行、时延、抖动与请求稳定性满足本策略的会议条件。",
            fail = "至少一项未满足：上下行 5 Mbps、时延 100 ms、抖动 30 ms、请求失败率 2%。",
        ),
        useCase(
            title = "大文件上传",
            known = result.uploadMbps != null,
            suitable = result.uploadMbps?.let { it >= 10.0 } == true,
            pass = "上传达到 10 Mbps，可进行常规大文件上传。",
            fail = "上传低于 10 Mbps，大文件上传等待会较明显。",
        ),
    )

    private fun useCase(title: String, known: Boolean, suitable: Boolean, pass: String, fail: String): Item = when {
        !known -> Item("$title：证据不足", "缺少必要指标，本次不判断是否适合。", Tone.NEUTRAL)
        suitable -> Item("$title：适合", pass, Tone.GOOD)
        else -> Item("$title：受限", fail, Tone.CAUTION)
    }

    private data class Weakness(val name: String, val severity: Int, val value: String)

    private fun throughputSeverity(value: Double, poorBelow: Double, goodAt: Double) = when {
        value < poorBelow -> 2
        value < goodAt -> 1
        else -> 0
    }

    private fun latencySeverity(value: Double) = when {
        value > 100.0 -> 2
        value > 60.0 -> 1
        else -> 0
    }

    private fun jitterSeverity(value: Double) = when {
        value > 80.0 -> 2
        value > 30.0 -> 1
        else -> 0
    }

    private fun lossSeverity(value: Double) = when {
        value > 0.02 -> 2
        value > 0.005 -> 1
        else -> 0
    }

    private fun friendlyStatus(status: String): String = when {
        status.startsWith("guard_rejected") -> "环境守卫拒测"
        status.startsWith("bind_failed") -> "网络绑定失败"
        status.startsWith("error") -> "执行异常"
        else -> "未正常完成"
    }

    private fun one(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun percent(value: Double) = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
}
