package com.aneb.probe.engine

import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.net.TokenEvent
import com.aneb.probe.radio.RadioSample
import com.aneb.probe.scoring.RadioSummary
import com.aneb.probe.scoring.ResidualSample

/**
 * BufferingDetector 接线层（P1-C08 遗留接线，phase1 账本 pending 项）：
 * 场景内各 token_stream 结束后，把原始 token 事件变换为**残差域样本**
 * （残差 = 到达间隔 − 服务端实际发出间隔，逐 seq 对齐，KPI 5.3.3/5.3.4），
 * 并从 run 期监控缓冲提取 R1 无线摘要与 app_jank 事件供归因联动。
 *
 * ## R-05 红线（与 BufferingDetector 同款硬约束）
 * 本层只做数据变换与联动取材，产出的 buffering_score/attribution 由 TestEngine
 * 落库为**标注列**——绝不参与 validity 判定、绝不抑制任何 KPI。
 *
 * ## 变换口径
 * - 发出间隔用 **preFlushUs（服务端实际 flush 前时刻）**：残差域剥离的是服务端
 *   真实发出节奏（含 profile 内生突发/停顿），锯齿才是链路缓冲的因果证据；
 * - **逐流内配对**：与 [ScenarioKpi.joinStreams] 同款跨流边界禁配对语义——流 k>0
 *   的首 token 与上一流尾 token 的"间隔"内含 tool_loop/think_pause 整段时长，
 *   不是传输行为；本层按流独立配对天然规避（seq 平移 base=Σ前序 expectedTokens
 *   保证场景内样本 seq 唯一）；
 * - **不剔 sameReadBatch / 不剔 pause 后样本**：近零到达间隔与 pause 吸收正是
 *   检测器的输入特征（残差域已剥离服务端停顿），过滤会破坏形态（不丢样本红线）；
 * - 双端时间戳不齐（preFlushUs 缺失 -1 / seq 缺失）的对自然跳过（R-10 无值不造值）。
 *
 * 纯函数、无 Android 依赖，可 JVM 单测。
 */
object BufferingWiring {

    /**
     * 多 token_stream → 残差样本序列。
     *
     * 每流内：按首见 seq 去重（与 [ScenarioKpi.correctedItlSamplesMs] 同款
     * putIfAbsent 语义），相邻 present seq 对 (s, s+1) 且双方 preFlushUs 有效时产出
     * 一个 [ResidualSample]（挂在后者：seq=base+s+1，arrivalUs=后者到达时刻 µs）。
     */
    fun residualSamples(streams: List<ScenarioKpi.StreamTokens>): List<ResidualSample> {
        val out = ArrayList<ResidualSample>()
        var base = 0L
        for (st in streams) {
            val bySeq = HashMap<Long, TokenEvent>()
            for (e in st.events) bySeq.putIfAbsent(e.seq, e)
            for (s in bySeq.keys.sorted()) {
                val a = bySeq[s] ?: continue
                val b = bySeq[s + 1] ?: continue
                if (a.preFlushUs < 0 || b.preFlushUs < 0) continue
                val arrivalIntervalUs = (b.arrivalNanos - a.arrivalNanos) / 1_000L
                out.add(
                    ResidualSample(
                        seq = base + b.seq,
                        arrivalUs = b.arrivalNanos / 1_000L,
                        arrivalIntervalUs = arrivalIntervalUs,
                        // 残差 = 到达间隔 − 发出间隔（含负值不 clamp，5.3.4）
                        residualUs = arrivalIntervalUs - (b.preFlushUs - a.preFlushUs),
                    )
                )
            }
            base += st.expectedTokens
        }
        return out
    }

    /** 单流的 retrans 共变量取材（P3-C05）：summary 透出的 retrans_total + 事件数。 */
    data class StreamRetrans(
        /** SseStreamResult.summaryRetransTotal；无数据（非 Linux 服务端/h3/无 summary）null */
        val retransTotal: Long?,
        /** 该流收到的 token 事件数 */
        val eventCount: Int,
    )

    /**
     * P3-C05 retrans 共变量：场景级 retransRate = 连接累计重传段数 / token 事件数，
     * 传给 [com.aneb.probe.scoring.BufferingDetector.analyze] 的可选参数。
     *
     * 口径说明：tcpi_total_retrans 是**连接生命周期累计值**。TestEngine 每场景开跑前
     * evictConnections()（设计 §5），场景内多个 token_stream 正常复用同一条连接，
     * 后一流的 retrans_total 已含前一流的重传——因此分子取各流的 **max**（同连接下
     * max=连接累计真值；若中途换连接则低估，宁可回退 MIDDLEBOX 现状也不高估重传，
     * 保守方向），分母取**带数据流**的事件数之和（与分子同一观测范围）。
     *
     * 无任何流带 retrans 数据或分母为 0 → null（无共变量数据，检测器行为与现状
     * 完全一致，零回归合同；R-10 无值不造值）。
     */
    fun retransRate(streams: List<StreamRetrans>): Double? {
        val withData = streams.filter { it.retransTotal != null }
        if (withData.isEmpty()) return null
        val events = withData.sumOf { it.eventCount }
        if (events <= 0) return null
        val retrans = withData.maxOf { it.retransTotal!! }
        return retrans.toDouble() / events
    }

    /**
     * 场景窗口内的 R1 无线摘要（rsrp/sinr 中位数）；窗口内无样本或全无信号值 → null
     * （检测器把 null 当"无无线信息"，不会因此偏向任何归因分支）。
     * [endNanos] 为 null（场景异常未收口）时窗口右开。
     */
    fun radioSummary(samples: Iterable<RadioSample>, startNanos: Long, endNanos: Long?): RadioSummary? {
        val inWindow = samples.filter { it.tsNanos >= startNanos && (endNanos == null || it.tsNanos <= endNanos) }
        val rsrp = medianOrNull(inWindow.mapNotNull { it.rsrp?.toDouble() })
        val sinr = medianOrNull(inWindow.mapNotNull { it.sinr?.toDouble() })
        if (rsrp == null && sinr == null) return null
        return RadioSummary(rsrpMedianDbm = rsrp, sinrMedianDb = sinr)
    }

    /**
     * 场景窗口内的 app_jank 事件时刻（µs）。EnvEvent.tsNanos 与 token arrivalNanos
     * 同为 elapsedRealtimeNanos 单调轴（设计文档 §7），换算 µs 后与
     * [ResidualSample.arrivalUs] 同基准，可直接进重叠比对（R-12/R-16）。
     */
    fun jankEventsUs(events: Iterable<EnvEvent>, startNanos: Long, endNanos: Long?): List<Long> =
        events.filter { ev ->
            ev.type == EnvEventType.APP_JANK &&
                ev.tsNanos >= startNanos && (endNanos == null || ev.tsNanos <= endNanos)
        }.map { it.tsNanos / 1_000L }

    /** 上中位数（与 BufferingDetector.medianOrNull 同口径，偶数取 sorted[n/2]）。 */
    private fun medianOrNull(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        return v.sorted()[v.size / 2]
    }
}
