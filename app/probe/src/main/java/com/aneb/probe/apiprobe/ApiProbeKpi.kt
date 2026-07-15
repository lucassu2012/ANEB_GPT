package com.aneb.probe.apiprobe

import com.aneb.probe.scoring.KpiCalculator

/**
 * 真实 API 探针 KPI 计算（纯函数，无 Android 依赖）。
 *
 * **claim scope＝`application_end_to_end_to_llm_api`**：端到端 TTFT/ITL 含 DNS、TLS、
 * （可能的）用户代理、服务商排队与模型推理时间，**无服务端时刻表可剥离**——与仿真
 * 节点 KPI（T1/T2 系列，服务端注入时延与 pacing 误差可校正）是两个不可互换的口径。
 * 因此本结果**绝不进 AQS**（设计文档 §9 风险表："结果单独归类不进 AQS"），仅作对照列。
 *
 * 统计口径与仿真侧保持可比的部分：
 *  - ITL 样本 = 相邻 token delta 到达间隔，剔除 sameReadBatch 伪 0 间隔（R-04 合帧
 *    组内只保留组首间隔）与恰为 0 的间隔（5.1 同款）；
 *  - 分位数 = 最近秩（复用 [KpiCalculator.percentileOrNull]，口径单一事实来源）；
 *  - 失败/无样本一律 null，绝不 0（R-10）。
 */
object ApiProbeKpi {

    /** 数值全部可空：无 token 到达时 ttft/itl/total 均 null（R-10）。 */
    data class Kpis(
        /** 请求发起 → 首个 token delta 到达（ms）；无 token 记 null */
        val ttftMs: Double?,
        /** ITL 中位数（ms）；样本 <1 记 null */
        val itlMedianMs: Double?,
        /** ITL P95（ms）；样本 <1 记 null */
        val itlP95Ms: Double?,
        /** 进入分位数的 ITL 样本数（已剔 sameReadBatch/0 值） */
        val itlSampleCount: Int,
        /** token delta 到达事件总数 */
        val tokenEventCount: Int,
        /** 请求发起 → 流 EOF 的总时长（ms）；EOF 缺失记 null */
        val totalMs: Double?,
        /** 全部 delta 的文本字符总数 */
        val totalTextChars: Int,
    )

    /**
     * @param requestStartNanos 请求发起时刻（elapsedRealtimeNanos，call.enqueue 前打戳）
     * @param arrivals          适配器解析出的 token delta 到达序列
     * @param eofNanos          流 EOF 时刻；失败/未读完记 null
     */
    fun compute(
        requestStartNanos: Long,
        arrivals: List<LlmTokenArrival>,
        eofNanos: Long?,
    ): Kpis {
        val ttftMs = arrivals.firstOrNull()?.let { (it.arrivalNanos - requestStartNanos) / 1e6 }

        val itlSamples = ArrayList<Double>(arrivals.size)
        for (i in 1 until arrivals.size) {
            val cur = arrivals[i]
            if (cur.sameReadBatch) continue // R-04：合帧伪 0 间隔剔除
            val dtMs = (cur.arrivalNanos - arrivals[i - 1].arrivalNanos) / 1e6
            if (dtMs == 0.0) continue // 0 值样本不入分位数（5.1 同款）
            itlSamples.add(dtMs)
        }

        return Kpis(
            ttftMs = ttftMs,
            itlMedianMs = KpiCalculator.percentileOrNull(itlSamples, 0.50),
            itlP95Ms = KpiCalculator.percentileOrNull(itlSamples, 0.95),
            itlSampleCount = itlSamples.size,
            tokenEventCount = arrivals.size,
            totalMs = eofNanos?.let { (it - requestStartNanos) / 1e6 },
            totalTextChars = arrivals.sumOf { it.textChars },
        )
    }
}
