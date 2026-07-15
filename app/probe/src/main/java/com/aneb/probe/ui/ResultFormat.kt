package com.aneb.probe.ui

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.engine.KpiGrading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 结果页/导出的纯展示层逻辑（P1-C07）。纯 JVM、无 Android/Compose 依赖，可单测。
 *
 * 设计取舍（D-02 数据质量>功能>UI）：
 * - 所有值/分级直接来自落库实体（TestEngine 写入口径），本层只做排版与展平，
 *   绝不重算 KPI/分级/AQS——防展示层与测量层口径漂移；
 * - null 值一律显示 "—" / CSV 空串，绝不显示 0（R-10 失败样本语义）;
 * - lowConfidence 必须显式标注（KPI 文档 5.4 展示边界）;
 * - 数值格式化一律固定 Locale.ROOT——逗号小数区域（如德语）默认 locale 会输出
 *   "12,34"，污染 CSV 列结构与展示口径（C07 评审修复）。
 */
object ResultFormat {

    /** claim scope 固定声明（KPI 文档声明边界 + 5.4 AQS 表述边界），结果页页脚原文展示 */
    const val CLAIM_SCOPE_TEXT: String =
        "测量对象为终端至指定仿真节点的应用层路径，非无线层/运营商全网结论"

    const val AQS_DISCLAIMER_TEXT: String =
        "AQS 为实验性应用层综合体验分（门限实验性），不构成 MOS/无线层评级/SLA 结论"

    /** 低置信标注文案（uiautomator 断言锚点，勿改） */
    const val LOW_CONFIDENCE_LABEL: String = "低置信 low_confidence"

    // ---- AQS 分级（KPI 文档 5.4：≥85 优 / 70–85 良 / 55–70 可 / <55 差） ----

    fun aqsGrade(score: Double): String = when {
        score >= 85.0 -> KpiGrading.EXCELLENT
        score >= 70.0 -> KpiGrading.GOOD
        score >= 55.0 -> KpiGrading.FAIR
        else -> KpiGrading.POOR
    }

    /** 分级中文标签（优/良/可/差）；null 分级（值缺失/无门限）→ "—" */
    fun gradeLabel(grade: String?): String = when (grade) {
        KpiGrading.EXCELLENT -> "优"
        KpiGrading.GOOD -> "良"
        KpiGrading.FAIR -> "可"
        KpiGrading.POOR -> "差"
        else -> "—"
    }

    /** 批化标注固定尾注（R-05 红线的展示锚点，uiautomator/单测断言用，勿改） */
    const val BUFFERING_NOTE: String = "标注不改有效性判定"

    /**
     * 场景批化标注行（P1-C08 接线）：`buffering=0.xxx attribution=xxx（标注不改有效性判定）`。
     * 未检测（bufferingScore=null，如流失败/无残差样本）→ null 不显示（R-10）。
     */
    fun bufferingLabel(s: ScenarioResultEntity): String? {
        val score = s.bufferingScore ?: return null
        return String.format(
            Locale.ROOT,
            "buffering=%.3f attribution=%s（%s）",
            score,
            s.bufferingAttribution ?: "none",
            BUFFERING_NOTE,
        )
    }

    /**
     * AQS v0.2 并列展示行（阶段2 C03 接线）：
     * - 行 0：`AQS v0.2 = 82.3（良）` 或 `AQS v0.2 不可计算：<reason>`（+低置信标注）；
     * - 行 1：所用 continuity 数据的 C1/C2 值、时间与来源 run（可追溯标注）。
     * 无 v0.2 分支（run 期无可用 continuity 数据）→ null，只显 v0.1（语义不变）。
     */
    fun aqsV02Lines(run: TestRun): List<String>? {
        val srcRunId = run.aqsV02ContinuityRunId ?: return null
        val score = run.aqsV02Score
        val head = if (score != null) {
            val lowConf = if (run.aqsV02LowConfidence == true) "　⚠ $LOW_CONFIDENCE_LABEL" else ""
            val veto = if (run.aqsV02VetoApplied == true) "　T4 否决封顶" else ""
            String.format(Locale.ROOT, "AQS v0.2 = %.1f（%s）%s%s", score, gradeLabel(aqsGrade(score)), veto, lowConf)
        } else {
            "AQS v0.2 不可计算：${run.aqsV02NotComputableReason ?: "unknown"}"
        }
        val c1 = run.aqsV02C1DropRate?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "—"
        val c2 = run.aqsV02C2RecoveryMs?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—"
        val at = run.aqsV02ContinuityStartedAtEpochMs?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(it))
        } ?: "—"
        return listOf(
            head,
            "continuity 数据: C1=$c1 C2=$c2 @$at run=${srcRunId.take(8)}",
        )
    }

    // ---- KPI 行模型 ----

    data class KpiRow(
        val id: String,
        val label: String,
        val value: Double?,
        val unit: String,
        /** KpiGrading 四级串；值 null 或双口径并列项/T5 无门限 → null */
        val grade: String?,
        val lowConfidence: Boolean,
    )

    /** 值格式化：null → "—"；比率 ×100 显示百分数 */
    fun formatValue(row: KpiRow): String {
        val v = row.value ?: return "—"
        return if (row.unit == "ratio") {
            String.format(Locale.ROOT, "%.2f%%", v * 100)
        } else {
            String.format(Locale.ROOT, "%.1f %s", v, row.unit)
        }
    }

    /**
     * 单场景全量 KPI 行（含双口径并列项：T2 剔/含 coalesced、T3 剔/含 resume、
     * U1 含/剔慢启动——KPI 文档 5.1 双口径都要展示）。
     * 并列口径行不给分级（进 AQS/门限的只有主口径，防止并列口径被误读为评级结论）。
     */
    fun kpiRows(s: ScenarioResultEntity): List<KpiRow> {
        val lowConf = s.lowConfidenceKpis.split(',').filter { it.isNotBlank() }.toSet()
        fun row(id: String, label: String, value: Double?, unit: String, graded: Boolean) = KpiRow(
            id = id, label = label, value = value, unit = unit,
            grade = if (graded) KpiGrading.grade(id, value) else null,
            lowConfidence = id in lowConf,
        )
        return listOf(
            row("T1", "TTFT", s.t1TtftMs, "ms", graded = true),
            row("T2", "ITL P95（剔 coalesced，主口径）", s.t2ItlP95Ms, "ms", graded = true),
            KpiRow(
                "T2_incl_coalesced", "ITL P95（含 coalesced，并列口径）",
                s.t2ItlP95InclCoalescedMs, "ms", grade = null,
                lowConfidence = "T2_incl_coalesced" in lowConf,
            ),
            row("T3", "stall 率（剔 resume，主口径）", s.t3StallRate, "ratio", graded = true),
            KpiRow(
                "T3_incl_resume", "stall 率（含 resume，并列口径）",
                s.t3StallRateInclResume, "ratio", grade = null,
                lowConfidence = "T3_incl_resume" in lowConf,
            ),
            row("T4", "严重卡顿率", s.t4SevereStallRate, "ratio", graded = true),
            KpiRow("T5", "恢复时延 P95（不进 AQS）", s.t5ResumeP95Ms, "ms", grade = null, lowConfidence = "T5" in lowConf),
            row("N1", "RTT P50", s.n1RttP50Ms, "ms", graded = true),
            row("N2", "抖动", s.n2JitterMs, "ms", graded = true),
            row("U1", "上行吞吐（含慢启动，主口径）", s.u1GoodputMbps, "Mbps", graded = true),
            KpiRow(
                "U1_excl_slow_start", "上行吞吐（剔慢启动，并列口径）",
                s.u1GoodputExclSlowStartMbps, "Mbps", grade = null,
                lowConfidence = "U1_excl_slow_start" in lowConf,
            ),
            row("U2", "工具循环 P95", s.u2ToolLoopP95Ms, "ms", graded = true),
        )
    }

    // ---- run 级 KPI 表（AQS 输入映射视图，AqsInputMapper 合同的展示镜像） ----

    data class RunKpiRow(
        val row: KpiRow,
        /** 来源场景（AQS_INPUT_MAP 合同：N←S1 / T,U2←S2 / U1←S3） */
        val source: String,
    )

    /**
     * run 级 KPI 表：按 AqsInputMapper 映射合同取来源场景，取证模式多遍取
     * **有效遍（非 null）中位数**（5.3.6）。展示层聚合与 AqsInputMapper.medianKpi
     * 同口径（偶数取上中位=percentile 0.5 的最近秩法）。
     */
    fun runKpiRows(scenarios: List<ScenarioResultEntity>): List<RunKpiRow> {
        val byId = scenarios.groupBy { it.profileId }
        val s1 = byId["s1_chat"].orEmpty()
        val s2 = byId["s2_coding_agent"].orEmpty()
        val s3 = byId["s3_multimodal"].orEmpty()

        fun agg(
            src: List<ScenarioResultEntity>, srcLabel: String, id: String, label: String,
            unit: String, graded: Boolean, pick: (ScenarioResultEntity) -> Double?,
        ): RunKpiRow {
            // 与 AqsInputMapper.medianKpi 同源同口径（调用 scoring 既有公共 API，不重实现）
            val median = com.aneb.probe.scoring.KpiCalculator.percentileOrNull(src.mapNotNull(pick), 0.50)
            val lowConf = src.any { s ->
                pick(s) != null && s.lowConfidenceKpis.split(',').contains(id)
            }
            return RunKpiRow(
                KpiRow(id, label, median, unit, if (graded) KpiGrading.grade(id, median) else null, lowConf),
                srcLabel,
            )
        }
        return listOf(
            agg(s2, "S2", "T1", "TTFT", "ms", true) { it.t1TtftMs },
            agg(s2, "S2", "T2", "ITL P95（剔 coalesced）", "ms", true) { it.t2ItlP95Ms },
            agg(s2, "S2", "T2_incl_coalesced", "ITL P95（含 coalesced）", "ms", false) { it.t2ItlP95InclCoalescedMs },
            agg(s2, "S2", "T3", "stall 率（剔 resume）", "ratio", true) { it.t3StallRate },
            agg(s2, "S2", "T3_incl_resume", "stall 率（含 resume）", "ratio", false) { it.t3StallRateInclResume },
            agg(s2, "S2", "T4", "严重卡顿率", "ratio", true) { it.t4SevereStallRate },
            agg(s1, "S1", "N1", "RTT P50", "ms", true) { it.n1RttP50Ms },
            agg(s1, "S1", "N2", "抖动", "ms", true) { it.n2JitterMs },
            agg(s3, "S3", "U1", "上行吞吐（含慢启动）", "Mbps", true) { it.u1GoodputMbps },
            agg(s3, "S3", "U1_excl_slow_start", "上行吞吐（剔慢启动）", "Mbps", false) { it.u1GoodputExclSlowStartMbps },
            agg(s2, "S2", "U2", "工具循环 P95", "ms", true) { it.u2ToolLoopP95Ms },
        )
    }

    // ---- CSV 导出（场景×KPI 展平） ----

    const val CSV_HEADER: String =
        "run_id,mode,kpi_set,aqs_version,profile_id,profile_version,repeat_index,order_index," +
            "validity,invalid_reasons,offset_drift_ppm,offset_suspect,kpi_id,value,unit,grade,low_confidence"

    /** RFC4180 风格最小转义：含逗号/引号/换行的字段加引号，引号翻倍 */
    fun csvEscape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    /**
     * CSV（场景×KPI 展平）：每场景 12 个 KPI 行（含双口径并列项）。
     * null 值 → 空串（绝不 0）；比率保持原始比率值（不 ×100，机器口径）。
     */
    fun buildCsv(run: TestRun, scenarios: List<ScenarioResultEntity>): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        for (s in scenarios) {
            for (row in kpiRows(s)) {
                val cells = listOf(
                    run.runId, run.mode, run.kpiSet, run.aqsVersion,
                    s.profileId, s.profileVersion, s.repeatIndex.toString(), s.orderIndex.toString(),
                    s.validity, s.invalidReasons,
                    s.offsetDriftPpm?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "",
                    s.offsetSuspect.toString(),
                    row.id,
                    row.value?.toString() ?: "",
                    row.unit,
                    row.grade ?: "",
                    row.lowConfidence.toString(),
                )
                sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
            }
        }
        return sb.toString()
    }
}
