package com.aneb.probe.ui

import com.aneb.probe.engine.KpiGrading
import java.util.Locale

/**
 * 单次 ANEB 测试的用户可读结论。纯展示层：只翻译已落库的 run/scenario 事实，
 * 不重算 KPI/AQS，也不把仿真事件数冒充真实模型计费 Token。
 */
object OutcomeConclusions {
    enum class Evidence { MEASURED, ESTIMATED, UNAVAILABLE }

    data class Item(
        val title: String,
        val body: String,
        val evidence: Evidence,
    )

    data class Input(
        val runStatus: String?,
        val score: Double?,
        val codingValidity: String?,
        val codingInvalidReasons: String?,
        val ttftMs: Double?,
        val ttftGrade: String?,
        val stallRate: Double?,
        val stallGrade: String?,
        val uploadMbps: Double?,
        val uploadGrade: String?,
        /** C1 会话中断率；来自 24h 内独立连续性实验，缺失时绝不估 Token 增量。 */
        val sessionDropRate: Double?,
    )

    fun build(input: Input): List<Item> = listOf(
        taskOutcome(input),
        primaryImpact(input),
        tokenImpact(input),
    )

    private fun taskOutcome(input: Input): Item {
        val codingInvalid = input.codingValidity.equals("invalid", ignoreCase = true)
        if (codingInvalid) {
            val reason = failureReason(input.codingInvalidReasons)
            return Item("编码任务未完成", reason, Evidence.MEASURED)
        }
        if (input.runStatus != null && input.runStatus != "completed") {
            return Item(
                "测试未完成",
                "测试状态为${friendlyStatus(input.runStatus)}，本次不能形成完整的 AI 任务结论。",
                Evidence.MEASURED,
            )
        }
        if (input.score == null) {
            return Item(
                "证据不完整",
                "测试流程已结束，但关键指标不足，未生成 AI 体验分。",
                Evidence.MEASURED,
            )
        }
        val measured = buildList {
            input.ttftMs?.let { add("首字响应 ${seconds(it)} 秒") }
            input.stallRate?.let { add("卡顿率 ${percent(it)}") }
        }.joinToString("，")
        return Item(
            "编码任务已完成",
            if (measured.isEmpty()) "编码 Agent 场景完成，关键体验指标见下方专业数据。" else "$measured。",
            Evidence.MEASURED,
        )
    }

    private fun primaryImpact(input: Input): Item {
        return when {
            isWeak(input.stallGrade) && input.stallRate != null -> Item(
                "主要影响：输出卡顿",
                "${percent(input.stallRate)} 的流式间隔进入卡顿判定，长回答可能出现明显停顿。",
                Evidence.MEASURED,
            )
            isWeak(input.ttftGrade) && input.ttftMs != null -> Item(
                "主要影响：启动等待",
                "本次首字响应 ${seconds(input.ttftMs)} 秒，开始生成前的等待是主要短板。",
                Evidence.MEASURED,
            )
            isWeak(input.uploadGrade) && input.uploadMbps != null -> Item(
                "主要影响：文件上传",
                "上行吞吐 ${oneDecimal(input.uploadMbps)} Mbps，大文件或多模态任务会受上传阶段限制。",
                Evidence.MEASURED,
            )
            else -> Item(
                "主要影响：未见明显短板",
                "已采到的首字、卡顿与上传指标没有落入“可/差”分档。",
                Evidence.MEASURED,
            )
        }
    }

    private fun tokenImpact(input: Input): Item {
        val dropRate = input.sessionDropRate?.takeIf { it in 0.0..1.0 }
            ?: return Item(
                "Token 增量未计算",
                "本次没有可用的会话中断率或前后 usage 对照，不能给出 Token 增加百分比。",
                Evidence.UNAVAILABLE,
            )
        return Item(
            "输入 Token 影响",
            "会话中断率为 ${percent(dropRate)}；若每次中断都需完整重发上下文，输入 Token 可能约增加 ${percent(dropRate)}。这是派生估算，不是计费实测。",
            Evidence.ESTIMATED,
        )
    }

    private fun failureReason(reasons: String?): String {
        val reasonSet = reasons.orEmpty().split(',').map { it.trim().uppercase(Locale.ROOT) }.toSet()
        return when {
            "TRUNCATED" in reasonSet || "GAP_EXCEEDED" in reasonSet ->
                "流式应用路径发生中断或事件缺失，导致编码 Agent 场景未完成。"
            "PATH_CHANGED" in reasonSet ->
                "测试中网络路径发生切换，编码场景证据失效；请在网络稳定后重测。"
            "GUARD_FAILED" in reasonSet || "MONITOR_FAILURE" in reasonSet ->
                "设备或网络环境在测量中发生变化，安全守卫中止了有效性判定。"
            "ENGINE_ERROR" in reasonSet ->
                "探针执行异常导致编码场景未完成；这不是网络质量结论。"
            else -> "编码 Agent 场景未采到完整有效数据，不能判定为成功。"
        }
    }

    private fun friendlyStatus(status: String): String = when {
        status.startsWith("aborted") -> "中途中止"
        status.startsWith("guard_rejected") -> "环境守卫拒测"
        status.startsWith("bind_failed") -> "网络绑定失败"
        status.startsWith("profiles_unavailable") -> "测试配置不可用"
        status.startsWith("error") -> "执行异常"
        else -> "未正常完成"
    }

    private fun isWeak(grade: String?): Boolean =
        grade == KpiGrading.FAIR || grade == KpiGrading.POOR

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
    private fun seconds(ms: Double): String = String.format(Locale.ROOT, "%.2f", ms / 1_000.0)
    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
