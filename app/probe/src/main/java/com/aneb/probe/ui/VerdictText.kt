package com.aneb.probe.ui

import com.aneb.probe.ui.theme.Grade

/**
 * 结论文案生成器——普通用户结果页永远先读的"一句人话"（设计稿 §02 结论文案系统）。
 *
 * 纯 JVM、无 Android/Compose 依赖，可单测。输入是**已落库的展示态**（AQS 分数 + 各 KPI
 * 分级 + 低置信/否决/不可计算标志），本层绝不重算 KPI/AQS/门限——只把测量层结论翻译成
 * 人话（D-02：展示层不产生新口径）。
 *
 * 逻辑（照设计稿四档 + 点名最拖后腿 KPI）：
 * - 不可计算（score=null）：给"没测出有效结果 + 建议重测"话术，绝不编造分档；
 * - 四档（优 ≥85 / 良 70–85 / 可 55–70 / 差 <55，门限锚点在 [Grade.fromAqsScore]）：
 *   每档一句主干，良/可/差再点名最拖后腿的 KPI（把抽象分数落到具体体验）；
 * - 低置信：句尾追加"证据不完整仅供参考"，不改主干判断；
 * - T4 一票否决：明确点出"严重卡顿"这一封顶主因（优先于普通点名）。
 */
object VerdictText {

    /**
     * 结论生成输入（全部取自 TestRun / ScenarioResultEntity 落库字段的展示投影）。
     *
     * @param score run 级 AQS 分数；null = 不可计算（早退/证据缺失）
     * @param lowConfidence AQS 低置信（valid_low_confidence）
     * @param vetoApplied T4 严重卡顿一票否决（封顶 54）
     * @param notComputableReason score=null 时的不可计算原因码（英文机器码，本层翻译成人话）
     * @param kpiGrades 各 KPI 分级（id→Grade?，null=该项缺失不参与点名）；用于选最拖后腿项
     */
    data class Input(
        val score: Double?,
        val lowConfidence: Boolean = false,
        val vetoApplied: Boolean = false,
        val notComputableReason: String? = null,
        val kpiGrades: Map<String, Grade?> = emptyMap(),
    )

    /** 生成一句结论文案（永不返回空串；不可计算亦给可读话术）。 */
    fun generate(input: Input): String {
        // 不可计算：绝不套四档主干（无分数即无体验结论），给重测引导
        if (input.score == null) {
            return "这次没能测出有效结果$REASON_SEP${friendlyReason(input.notComputableReason)}，" +
                "建议在信号稳定时重测一次。"
        }

        val grade = Grade.fromAqsScore(input.score)
        val caveat = if (input.lowConfidence) LOW_CONF_CAVEAT else ""

        // T4 否决优先：严重卡顿是封顶主因，压过普通点名
        if (input.vetoApplied) {
            return "体验较差——出现严重卡顿，AI 助手容易中途卡死或断开，建议换个网络环境再用。$caveat"
        }

        val weakest = weakestKpi(input.kpiGrades)
        val core = when (grade) {
            Grade.Excellent ->
                "你的网络很适合 AI 助手——响应快、几乎不卡顿，编码和长对话都能跟上。"
            Grade.Good ->
                "日常够用——对话流畅，" + (weakest?.let { "${weaknessClause(it)}，大文件或长任务偶尔要多等一会儿。" }
                    ?: "大文件上传偶尔要多等一会儿。")
            Grade.Fair ->
                "能用但会卡——" + (weakest?.let { "${weaknessClause(it)}，" } ?: "") +
                    "长回答可能一顿一顿地出。"
            Grade.Poor ->
                "体验较差——连接不稳，" + (weakest?.let { "${weaknessClause(it)}，" } ?: "") +
                    "AI 助手常中断。"
        }
        return core + caveat
    }

    // ------------------------------------------------------------------
    // 内部：最拖后腿 KPI 选取 + 人话映射
    // ------------------------------------------------------------------

    /** 分级严重度（越大越差）：差 3 / 可 2 / 良 1 / 优 0；null 不参与。 */
    private fun severity(grade: Grade): Int = when (grade) {
        Grade.Poor -> 3
        Grade.Fair -> 2
        Grade.Good -> 1
        Grade.Excellent -> 0
    }

    /**
     * 选最拖后腿的 KPI：取严重度最高者；仅当其严重度 ≥ 可（Fair）才返回（优/良项不值得点名）。
     * 平手时按 [KPI_MENTION_ORDER] 的体验相关度优先（首字/卡顿 > 上传/工具循环 > 网络层）。
     */
    private fun weakestKpi(kpiGrades: Map<String, Grade?>): String? {
        var bestId: String? = null
        var bestSev = -1
        var bestRank = Int.MAX_VALUE
        for ((id, grade) in kpiGrades) {
            if (grade == null) continue
            val sev = severity(grade)
            val rank = KPI_MENTION_ORDER.indexOf(id).let { if (it < 0) Int.MAX_VALUE - 1 else it }
            if (sev > bestSev || (sev == bestSev && rank < bestRank)) {
                bestSev = sev
                bestRank = rank
                bestId = id
            }
        }
        // 只有真的偏弱（可/差）才点名；全是优/良则不点（主干已表达"够用"）
        return if (bestSev >= severity(Grade.Fair)) bestId else null
    }

    /** KPI id → 一句体验层弱点描述（点名用；未知 id 兜底为泛化"网络表现"）。 */
    private fun weaknessClause(kpiId: String): String = when (kpiId) {
        "T1" -> "首字响应偏慢"
        "T2" -> "输出节奏不太稳"
        "T3", "T4" -> "偶有卡顿"
        "N1" -> "网络延迟偏高"
        "N2" -> "网络抖动明显"
        "U1" -> "上传速度偏慢"
        "U2" -> "工具调用往返偏慢"
        else -> "网络表现有短板"
    }

    /** 不可计算原因码 → 人话（未知码兜底为通用表述，绝不暴露英文机器码给普通用户）。 */
    private fun friendlyReason(reason: String?): String = when {
        reason == null -> "（测试提前结束）"
        reason.contains("guard", ignoreCase = true) -> "（测试环境不稳定，被安全守卫中止）"
        reason.contains("bind", ignoreCase = true) -> "（网络绑定失败）"
        reason.contains("missing", ignoreCase = true) ||
            reason.contains("kpi", ignoreCase = true) -> "（关键指标未采到）"
        reason.contains("abort", ignoreCase = true) -> "（测试中途中断）"
        else -> "（测试未正常完成）"
    }

    /** 点名平手时的体验相关度优先序（越靠前越优先点名）。 */
    private val KPI_MENTION_ORDER = listOf("T3", "T4", "T1", "T2", "U1", "U2", "N1", "N2")

    private const val REASON_SEP = ""
    private const val LOW_CONF_CAVEAT = "（本次证据不完整，分数仅供参考）"
}
