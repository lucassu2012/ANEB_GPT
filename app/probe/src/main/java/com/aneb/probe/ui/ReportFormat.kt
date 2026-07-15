package com.aneb.probe.ui

import com.aneb.probe.scoring.ReportAnalyzer
import com.aneb.probe.scoring.ReportAnalyzer.Method
import com.aneb.probe.scoring.ReportAnalyzer.ReportAnalysis
import java.util.Locale

/**
 * 敏感度报告导出/展示的**纯格式化层**（analysis layer ③ 的呈现出口）。纯 JVM、无
 * Android/Compose 依赖，可单测。只把 [ReportAnalyzer.ReportAnalysis] 排版成 Markdown / JSON，
 * 绝不重算任何量（口径单一事实来源在 ReportAnalyzer；R-10：null 显 "—" 绝不 0）。
 *
 * 诚实红线透传：token 投影恒带"派生/估算,非直接测量"标；样本不足时输出引导文案；
 * claim scope 页脚原文附上（不外推无线层/运营商全网/SLA/MOS）。
 */
object ReportFormat {

    fun methodLabel(method: Method): String = when (method) {
        Method.GROUPED -> "分组均值对比"
        Method.CORRELATION -> "跨 run 相关性/线性回归"
        Method.INSUFFICIENT -> "样本不足（如实降级）"
    }

    /** 样本不足时的界面引导文案（与分析层结论一致，供 ReportScreen 空态展示）。 */
    fun insufficientGuidance(a: ReportAnalysis): String =
        "当前有效 run n=${a.validRunCount}，不同网络条件=${a.distinctConditionCount}" +
            "（需 ≥${ReportAnalyzer.MIN_DISTINCT_CONDITIONS}）。" +
            "请在不同网络条件下（如 WiFi 与蜂窝、强/弱信号、不同时段）多次测试后再生成报告。"

    private fun f(v: Double): String {
        val abs = kotlin.math.abs(v)
        return when {
            abs >= 1000.0 -> String.format(Locale.ROOT, "%.0f", v)
            abs >= 10.0 -> String.format(Locale.ROOT, "%.1f", v)
            abs >= 1.0 -> String.format(Locale.ROOT, "%.2f", v)
            else -> String.format(Locale.ROOT, "%.3f", v)
        }
    }

    private fun fn(v: Double?): String = v?.let { f(it) } ?: "—"

    private val METRIC_CN = mapOf(
        "ttft" to "首字延迟 TTFT", "itl" to "ITL P95", "stall" to "stall 率",
        "up" to "有效上行吞吐", "aqs" to "AQS",
    )
    private val DRIVER_CN = mapOf(
        "rtt" to "RTT", "loss" to "丢包率", "jitter" to "抖动", "rsrp" to "RSRP",
    )

    fun driverLabel(id: String): String = DRIVER_CN[id] ?: id
    fun metricLabel(id: String): String = METRIC_CN[id] ?: id

    /** 单条敏感度发现的一行摘要（供 UI 列表；含方向/量级/低置信标）。 */
    fun findingLine(fd: ReportAnalyzer.SensitivityFinding): String {
        val pct = fd.pctDelta?.let { (if (it >= 0) "+" else "") + f(it) + "%" } ?: "相对值不可算"
        val abs = (if (fd.absDelta >= 0) "+" else "") + f(fd.absDelta)
        val low = if (fd.lowConfidence) "（低置信）" else ""
        return "${driverLabel(fd.driver)} ${f(fd.driverFrom)}→${f(fd.driverTo)} 时，" +
            "${metricLabel(fd.metric)} $pct（$abs，n=${fd.n}）$low"
    }

    // ---------------------------------------------------------------------
    // Markdown 导出
    // ---------------------------------------------------------------------

    fun buildMarkdown(a: ReportAnalysis): String = buildString {
        appendLine("# ANEB 分层测试敏感度报告")
        appendLine()
        appendLine("- 分析器: ${a.analyzerVersion}")
        appendLine("- 方法: ${methodLabel(a.method)}")
        appendLine("- 有效 run 数: ${a.validRunCount}")
        appendLine("- 不同网络条件数: ${a.distinctConditionCount}")
        appendLine()

        appendLine("## 结论")
        appendLine()
        a.conclusions.forEachIndexed { i, c -> appendLine("${i + 1}. $c") }
        appendLine()

        if (a.method == Method.INSUFFICIENT || a.sensitivity.isEmpty()) {
            appendLine("## 敏感度")
            appendLine()
            appendLine("> ${insufficientGuidance(a)}")
            appendLine()
        } else {
            appendLine("## 敏感度发现")
            appendLine()
            appendLine("| 自变量 | 因变量 | 自变量 从→到 | 因变量 从→到 | 绝对Δ | 相对Δ | n | 置信 |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            for (fd in a.sensitivity) {
                val pct = fd.pctDelta?.let { (if (it >= 0) "+" else "") + f(it) + "%" } ?: "—"
                appendLine(
                    "| ${driverLabel(fd.driver)} | ${metricLabel(fd.metric)} | " +
                        "${f(fd.driverFrom)}→${f(fd.driverTo)} | ${f(fd.metricFrom)}→${f(fd.metricTo)} | " +
                        "${if (fd.absDelta >= 0) "+" else ""}${f(fd.absDelta)} | $pct | ${fd.n} | " +
                        "${if (fd.lowConfidence) "低" else "正常"} |",
                )
            }
            appendLine()
        }

        val p = a.tokenProjection
        appendLine("## token 消耗投影（${p.marker}）")
        appendLine()
        appendLine("- 丢包增量: ${fn(p.lossPctDelta)}%")
        appendLine("- 每 token TPOT 拉长: ${fn(p.tpotElongationMsLow)}–${fn(p.tpotElongationMsHigh)} ms")
        appendLine("- 会话中断率: ${p.sessionDropRate?.let { f(it * 100) + "%" } ?: "—"}")
        appendLine("- 每会话上行重发上下文: ${fn(p.uplinkResendTokensLow)}–${fn(p.uplinkResendTokensHigh)} token")
        appendLine("- 说明: ${p.note}")
        appendLine()
        appendLine("### 文献锚点（口径不同，仅供对照）")
        appendLine()
        for (anchor in p.literatureAnchors) {
            appendLine("- **${anchor.name}**: ${anchor.statement}（来源: ${anchor.source}）")
        }
        appendLine()

        appendLine("---")
        appendLine()
        appendLine("_${a.claimScopeNote}_")
    }

    // ---------------------------------------------------------------------
    // JSON 导出（手工构造，稳定字段序；null 显式 null 不顶 0）
    // ---------------------------------------------------------------------

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun jStr(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
    private fun jNum(v: Double?): String = if (v == null) "null" else {
        // 有限值直连；非有限（防御）转 null
        if (v.isFinite()) v.toString() else "null"
    }

    fun buildJson(a: ReportAnalysis): String = buildString {
        append("{")
        append("\"analyzerVersion\":${jStr(a.analyzerVersion)},")
        append("\"method\":${jStr(a.method.name)},")
        append("\"validRunCount\":${a.validRunCount},")
        append("\"distinctConditionCount\":${a.distinctConditionCount},")
        append("\"claimScope\":${jStr(a.claimScope)},")
        append("\"claimScopeNote\":${jStr(a.claimScopeNote)},")

        append("\"conclusions\":[")
        append(a.conclusions.joinToString(",") { jStr(it) })
        append("],")

        append("\"sensitivity\":[")
        append(
            a.sensitivity.joinToString(",") { fd ->
                "{" +
                    "\"driver\":${jStr(fd.driver)}," +
                    "\"metric\":${jStr(fd.metric)}," +
                    "\"driverFrom\":${jNum(fd.driverFrom)}," +
                    "\"driverTo\":${jNum(fd.driverTo)}," +
                    "\"metricFrom\":${jNum(fd.metricFrom)}," +
                    "\"metricTo\":${jNum(fd.metricTo)}," +
                    "\"absDelta\":${jNum(fd.absDelta)}," +
                    "\"pctDelta\":${jNum(fd.pctDelta)}," +
                    "\"slopePerUnit\":${jNum(fd.slopePerUnit)}," +
                    "\"pearson\":${jNum(fd.pearson)}," +
                    "\"n\":${fd.n}," +
                    "\"lowConfidence\":${fd.lowConfidence}" +
                    "}"
            },
        )
        append("],")

        val p = a.tokenProjection
        append("\"tokenProjection\":{")
        append("\"estimate\":${p.estimate},")
        append("\"marker\":${jStr(p.marker)},")
        append("\"lossPctDelta\":${jNum(p.lossPctDelta)},")
        append("\"tpotElongationMsLow\":${jNum(p.tpotElongationMsLow)},")
        append("\"tpotElongationMsHigh\":${jNum(p.tpotElongationMsHigh)},")
        append("\"sessionDropRate\":${jNum(p.sessionDropRate)},")
        append("\"uplinkResendTokensLow\":${jNum(p.uplinkResendTokensLow)},")
        append("\"uplinkResendTokensHigh\":${jNum(p.uplinkResendTokensHigh)},")
        append("\"note\":${jStr(p.note)},")
        append("\"literatureAnchors\":[")
        append(
            p.literatureAnchors.joinToString(",") { anchor ->
                "{\"name\":${jStr(anchor.name)},\"statement\":${jStr(anchor.statement)},\"source\":${jStr(anchor.source)}}"
            },
        )
        append("]},")

        append("\"trends\":{")
        append("\"orderedRunIds\":[${a.trends.orderedRunIds.joinToString(",") { jStr(it) }}],")
        append("\"series\":[")
        append(
            a.trends.series.joinToString(",") { s ->
                "{\"metric\":${jStr(s.metric)},\"values\":[${s.values.joinToString(",") { jNum(it) }}]}"
            },
        )
        append("]}")
        append("}")
    }
}
