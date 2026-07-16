package com.aneb.probe.scoring

import kotlin.math.ceil

/*
 * KPI 计算引擎（agent-qoe-kpi v0.1 口径，KPI 文档第五部分 5.1/5.3.4/5.3.8）。
 * 纯 JVM 逻辑，无 Android 依赖；输入为简单 data class（不依赖 Room 实体），
 * 由上层（TestEngine，后续接线）负责从 Room 实体映射。
 */

/**
 * 单个 SSE token 事件样本（对应 5.3.4 的 seq + 双服务端时间戳设计）。
 *
 * @param seq 服务端内嵌序号（join 键，5.3.8 强制按 seq join、禁位置配对）
 * @param srvSchedUs 服务端期望发出时刻（profile 时刻表，进程锚点单调 us）；缺失记 null
 * @param srvPreFlushUs 服务端实际 flush 前时刻（进程锚点单调 us）；缺失记 null
 * @param arrivalNanos 客户端到达时刻（elapsedRealtimeNanos）；失败/未到达记 null，绝不记 0（R-10）
 * @param sameReadBatch 该 token 与前一 token 在同一次 socket read 中到达（合帧标记，5.1 T2 口径）
 */
data class TokenSample(
    val seq: Long,
    val srvSchedUs: Long?,
    val srvPreFlushUs: Long?,
    val arrivalNanos: Long?,
    val sameReadBatch: Boolean = false,
)

/**
 * echo RTT 样本（N1/N2，KPI 文档 5.1：每场景开始前采样 ≥20 次，前 2–3 个预热丢弃）。
 *
 * @param rttNanos 应用层往返时延；失败/超时记 null（R-10）
 * @param warmup 预热标记（5.3.2：前 2–3 个请求丢弃预热），warmup 样本不进统计
 */
data class EchoSample(
    val rttNanos: Long?,
    val warmup: Boolean = false,
)

/**
 * 一次大 prompt 上传结果（U1，KPI 文档 5.1）。
 * 计时终点 = 收到服务端 2xx 响应头（2xx 口径：非 2xx 即失败样本，记 null 不记 0）。
 *
 * @param bytes 上传字节数
 * @param durationNanos 总耗时（发出首字节 → 收到 2xx 响应头）；失败记 null
 * @param http2xx 是否收到 2xx（false 即失败样本，不进 U1 统计）
 * @param slowStartNanos 慢启动爬坡段耗时（可选，由传输层采样估计）；缺失则"剔除慢启动"口径为 null
 * @param slowStartBytes 慢启动爬坡段内传输的字节数（与 [slowStartNanos] 配套）
 */
data class UploadResult(
    val bytes: Long,
    val durationNanos: Long?,
    val http2xx: Boolean,
    val slowStartNanos: Long? = null,
    val slowStartBytes: Long? = null,
)

/**
 * 一轮工具循环样本（U2，KPI 文档 5.1：上行 8KB → 服务端处理固定 200ms → 下行 2KB）。
 *
 * @param totalNanos 该轮端到端总耗时；失败记 null（R-10）
 * @param serverProcNanos 服务端已知处理时长（默认 200ms，由服务端随响应返回）
 */
data class ToolLoopSample(
    val totalNanos: Long?,
    val serverProcNanos: Long,
)

/**
 * 一次流式阶段的 TTFT 样本（T1：请求最后一字节发出 → 首 token 事件首字节到达，
 * 已减去服务端已知注入时延；由采集侧算好后传入）。失败/超时记 null（R-10）。
 */
data class TtftSample(
    val ttftMs: Double?,
)

/**
 * 单个 KPI 的输出值（结果合同：值 nullable + 样本量 + 低置信标，R-10/R-29）。
 *
 * @param value KPI 值；失败/无有效样本一律 null，绝不记 0（R-10）
 * @param unit 单位（"ms" / "Mbps" / "ratio"）
 * @param sampleCount 进入统计的有效样本数
 * @param lowConfidence 样本数低于口径最小值时为 true（出值但带标，R-29）
 */
data class KpiValue(
    val value: Double?,
    val unit: String,
    val sampleCount: Int,
    val lowConfidence: Boolean,
) {
    companion object {
        fun empty(unit: String): KpiValue = KpiValue(null, unit, 0, lowConfidence = false)
    }
}

/**
 * KpiCalculator 的输入集合（一个场景的全部原始样本 + 守卫结论）。
 *
 * @param tokenSamples 流式阶段 token 事件（可乱序传入，内部按 seq join）
 * @param pauseSeqs profile 可预测的停顿位置集合：token seq ∈ pauseSeqs 表示
 *   "该 token 是 pause（思考停顿/簇间静默）后的首 token"，其前置间隔计入 T5
 *   resume_latency 并从 T2/T3/T4 样本集剔除（KPI 文档 5.1 T5；红队 R-09）
 * @param echoSamples N1/N2 输入
 * @param uploadResults U1 输入
 * @param toolLoopSamples U2 输入
 * @param ttftSamples T1 输入
 * @param streamTruncated 流式阶段异常中断（5.3.8：判 INVALID(TRUNCATED)，中断样本不进统计）
 * @param externalInvalidReasons 外部守卫判定的无效原因（GUARD_FAILED/PATH_CHANGED/BUFFERING_SUSPECT 等，
 *   由测中持续监控守卫给出，本引擎只透传合并）
 */
data class KpiInput(
    val tokenSamples: List<TokenSample> = emptyList(),
    val pauseSeqs: Set<Long> = emptySet(),
    val echoSamples: List<EchoSample> = emptyList(),
    val uploadResults: List<UploadResult> = emptyList(),
    val toolLoopSamples: List<ToolLoopSample> = emptyList(),
    val ttftSamples: List<TtftSample> = emptyList(),
    val streamTruncated: Boolean = false,
    val externalInvalidReasons: List<InvalidReason> = emptyList(),
)

/**
 * 一个场景的 KPI 计算结果（结果合同）。
 *
 * INVALID 时所有 KpiValue.value 置 null（fail-closed 抑制聚合输出，5.3.8/R-10），
 * 但 gap 计数等诊断字段保留。
 */
data class KpiResult(
    val validity: Validity,
    val invalidReasons: List<InvalidReason>,
    /** seq 缺号数（min..max 连续区间内缺失的序号个数） */
    val seqMissingCount: Int,
    /** seq 重号数 */
    val seqDupCount: Int,
    /** gap 总数 = 缺号 + 重号 */
    val seqGapCount: Int,
    /** 期望 token 总数（max−min+1；无样本时 0） */
    val expectedTokenCount: Int,
    /** T1 TTFT 场景内中位数（最近秩 P50），ms */
    val t1TtftMs: KpiValue,
    /** T2 ITL P95 主口径：合帧组内只保留组首间隔（剔除 sameReadBatch 伪 0 间隔），ms */
    val t2ItlP95Ms: KpiValue,
    /** T2 ITL P95 并列口径：含 coalesced（5.1 要求并列报告），ms */
    val t2ItlP95InclCoalescedMs: KpiValue,
    /** T3 stall 率主口径：不含 resume（T5 已剔除），ratio 0..1 */
    val t3StallRate: KpiValue,
    /** T3 stall 率并列口径：含 resume_latency（5.1 要求两口径输出），ratio 0..1 */
    val t3StallRateInclResume: KpiValue,
    /** T4 严重卡顿率（校正 ITL >1s 占比，样本集同 T3 主口径），ratio 0..1 */
    val t4SevereStallRate: KpiValue,
    /** T5 resume_latency P95（pause 后首 token 的原始到达间隔），ms；不进 AQS */
    val t5ResumeP95Ms: KpiValue,
    /** T5 明细（供分布/分桶展示），ms */
    val t5ResumeLatenciesMs: List<Double>,
    /** N1 echo RTT P50（丢弃 warmup），ms */
    val n1RttP50Ms: KpiValue,
    /** N2 抖动 = RTT P95 − P50，ms */
    val n2JitterMs: KpiValue,
    /** U1 goodput 主口径：含慢启动（2xx 口径，总字节/总耗时），Mbps */
    val u1GoodputMbps: KpiValue,
    /** U1 并列口径：剔除慢启动爬坡，Mbps；慢启动信息缺失时 null */
    val u1GoodputExclSlowStartMbps: KpiValue,
    /** U2 工具循环时延 P95（轮次耗时 − 服务端 proc），ms */
    val u2ToolLoopP95Ms: KpiValue,
)

/**
 * KPI 计算引擎。
 *
 * ## 采用的精确公式（KDoc 合同，按 KPI 文档 5.1 表 + 5.3.4 + 红队 R-09 缓解措施）
 *
 * **配对**：强制按 seq join（禁位置配对，5.3.8）。仅当 seq k 与 k+1 均存在、
 * 且两端 arrivalNanos / srvPreFlushUs / srvSchedUs 均非 null 时构成一个 ITL 配对；
 * 跨缺号的间隔不构成配对（缺号已计 gap）。重号保留首见样本、重复计 gap。
 *
 * **校正 ITL（T2/T3/T4 共用样本域）**：
 * ```
 * arrivalMs  = (arrival[k+1] − arrival[k]) / 1e6        // 客户端到达间隔
 * flushMs    = (preFlush[k+1] − preFlush[k]) / 1e3      // 服务端实际发出间隔
 * schedMs    = (sched[k+1] − sched[k]) / 1e3            // profile 名义间隔
 * residualMs = arrivalMs − flushMs                      // 网络贡献残差（5.3.4，逐 seq 对齐差）
 * correctedItlMs = residualMs + schedMs                 // 校正 ITL：剥离服务端调度误差、保留名义节奏
 * ```
 * 即"若服务端严格按时刻表 flush，客户端会观测到的 ITL"。服务端 GC/定时器漂移由
 * residual 剥离；profile 设计内停顿通过 [KpiInput.pauseSeqs] 归入 T5 剔除（R-09：
 * 纯本地回环 + S2 profile 金样本下 T3 必须为 0）。负残差不 clamp、原值参与统计（5.3.4）。
 *
 * - **T2 主口径**：correctedItlMs 的最近秩 P95，样本集剔除 sameReadBatch 伪 0 间隔
 *   （合帧组内只保留组首间隔）与 pause 间隔；到达间隔恰为 0 的样本不入分位数（5.1）。
 * - **T2 并列口径**：同上但含 coalesced（含 0 值）。
 * - **T3 主口径**：correctedItlMs > 200ms 的占比，样本集同 T2 主口径（不含 resume）。
 * - **T3 并列口径**：含 resume 间隔（5.1"输出含/不含 resume_latency 两口径"）。
 * - **T4**：correctedItlMs > 1000ms 的占比，样本集同 T3 主口径。
 * - **T5**：pause 后首 token 的**原始到达间隔**（arrivalMs，不剥离——C-DRX/RRC 唤醒税
 *   即体现在原始间隔里），输出明细 + 最近秩 P95；不进 AQS。
 *
 * **分位数方法**：一律最近秩法 nearest-rank（rank = ceil(p×n)，1-indexed），
 * 随结果合同声明（R-29：8 样本的 P95 = 最大值，靠 lowConfidence 标暴露而非掩盖）。
 *
 * **失败语义（R-10）**：失败/超时样本时延一律 null、绝不 0，也绝不参与统计；
 * 无任何有效样本的 KPI 输出 value=null（绝不 0）。样本数低于口径最小值
 * （U2<8、echo<10、U1<3、TTFT<3、ITL 配对<100）时出值但带 lowConfidence（R-29）。
 *
 * **有效性 Gate**：gap > token 总数 1% → INVALID(GAP_EXCEEDED)；streamTruncated →
 * INVALID(TRUNCATED)；外部守卫原因透传 INVALID；gap>0 或任一 KPI lowConfidence →
 * VALID_LOW_CONFIDENCE；INVALID 时全部 KpiValue.value 置 null（fail-closed）。
 */
object KpiCalculator {

    const val KPI_SET_VERSION: String = "agent-qoe-kpi-v0.1"

    /** stall 判定线（ms），KPI 文档 5.1 T3（Eloquent 定义） */
    const val STALL_THRESHOLD_MS: Double = 200.0

    /** 严重卡顿判定线（ms），KPI 文档 5.1 T4 */
    const val SEVERE_STALL_THRESHOLD_MS: Double = 1000.0

    /** gap 判无效阈值（占期望 token 总数比例），KPI 文档 5.3.8 */
    const val GAP_INVALID_RATIO: Double = 0.01

    // 各 KPI 口径最小样本量（低于即 lowConfidence，R-29）
    const val MIN_ECHO_SAMPLES: Int = 10
    const val MIN_TOOL_LOOP_SAMPLES: Int = 8
    const val MIN_UPLOAD_SAMPLES: Int = 3
    const val MIN_TTFT_SAMPLES: Int = 3
    const val MIN_ITL_SAMPLES: Int = 100

    fun calculate(input: KpiInput): KpiResult {
        // ---- seq join 与 gap 统计（5.3.8：强制 seq join，禁位置配对）----
        val bySeq = HashMap<Long, TokenSample>()
        var dupCount = 0
        for (s in input.tokenSamples) {
            if (bySeq.putIfAbsent(s.seq, s) != null) dupCount++
        }
        val presentSeqs = bySeq.keys.sorted()
        val expectedCount: Int
        val missingCount: Int
        if (presentSeqs.isEmpty()) {
            expectedCount = 0
            missingCount = 0
        } else {
            expectedCount = (presentSeqs.last() - presentSeqs.first() + 1).toInt()
            missingCount = expectedCount - presentSeqs.size
        }
        val gapCount = missingCount + dupCount

        // ---- ITL 配对（仅相邻 seq，双端时间戳齐备）----
        class ItlPair(
            val arrivalMs: Double,
            val correctedMs: Double,
            val coalesced: Boolean,
            val isResume: Boolean,
        )

        val pairs = ArrayList<ItlPair>()
        for (seq in presentSeqs) {
            val a = bySeq[seq] ?: continue
            val b = bySeq[seq + 1] ?: continue
            val aArr = a.arrivalNanos ?: continue
            val bArr = b.arrivalNanos ?: continue
            val aFlush = a.srvPreFlushUs ?: continue
            val bFlush = b.srvPreFlushUs ?: continue
            val aSched = a.srvSchedUs ?: continue
            val bSched = b.srvSchedUs ?: continue
            val arrivalMs = (bArr - aArr) / 1e6
            val flushMs = (bFlush - aFlush) / 1e3
            val schedMs = (bSched - aSched) / 1e3
            val residualMs = arrivalMs - flushMs // 负残差不 clamp（5.3.4）
            pairs.add(
                ItlPair(
                    arrivalMs = arrivalMs,
                    correctedMs = residualMs + schedMs,
                    coalesced = b.sameReadBatch,
                    isResume = b.seq in input.pauseSeqs,
                )
            )
        }

        // T2/T3/T4 主样本集：剔除 coalesced 伪 0 间隔与 resume 间隔
        val primaryPairs = pairs.filter { !it.coalesced && !it.isResume && it.arrivalMs != 0.0 }
        val primaryCorrected = primaryPairs.map { it.correctedMs }
        // T2 并列口径：含 coalesced（含 0 值），仍剔除 resume
        val inclCoalescedCorrected = pairs.filter { !it.isResume }.map { it.correctedMs }
        // T3 并列口径：含 resume，剔除 coalesced 伪 0
        val inclResumeCorrected = pairs.filter { !it.coalesced && it.arrivalMs != 0.0 }.map { it.correctedMs }
        // T5：resume 间隔的原始到达间隔
        val resumeLatencies = pairs.filter { it.isResume }.map { it.arrivalMs }

        val itlLowConf = primaryCorrected.size < MIN_ITL_SAMPLES

        val t2 = KpiValue(
            value = percentileOrNull(primaryCorrected, 0.95),
            unit = "ms",
            sampleCount = primaryCorrected.size,
            lowConfidence = itlLowConf && primaryCorrected.isNotEmpty(),
        )
        val t2Incl = KpiValue(
            value = percentileOrNull(inclCoalescedCorrected, 0.95),
            unit = "ms",
            sampleCount = inclCoalescedCorrected.size,
            lowConfidence = inclCoalescedCorrected.size < MIN_ITL_SAMPLES && inclCoalescedCorrected.isNotEmpty(),
        )
        val t3 = rateKpi(primaryCorrected, STALL_THRESHOLD_MS, itlLowConf)
        val t3Incl = rateKpi(
            inclResumeCorrected, STALL_THRESHOLD_MS,
            inclResumeCorrected.size < MIN_ITL_SAMPLES,
        )
        val t4 = rateKpi(primaryCorrected, SEVERE_STALL_THRESHOLD_MS, itlLowConf)
        val t5 = KpiValue(
            value = percentileOrNull(resumeLatencies, 0.95),
            unit = "ms",
            sampleCount = resumeLatencies.size,
            lowConfidence = false, // T5 不设门限、不进 AQS，仅分布呈现
        )

        // ---- T1 TTFT：场景内中位数（最近秩 P50），失败样本 null 剔除 ----
        val ttftValid = input.ttftSamples.mapNotNull { it.ttftMs }
        val t1 = KpiValue(
            value = percentileOrNull(ttftValid, 0.50),
            unit = "ms",
            sampleCount = ttftValid.size,
            lowConfidence = ttftValid.size < MIN_TTFT_SAMPLES && ttftValid.isNotEmpty(),
        )

        // ---- N1/N2：echo RTT，丢弃 warmup 与失败样本 ----
        val rtts = input.echoSamples.filter { !it.warmup }.mapNotNull { it.rttNanos }.map { it / 1e6 }
        val echoLowConf = rtts.size < MIN_ECHO_SAMPLES && rtts.isNotEmpty()
        val n1Value = percentileOrNull(rtts, 0.50)
        val n2Value = if (n1Value == null) null else percentileOrNull(rtts, 0.95)!! - n1Value
        val n1 = KpiValue(n1Value, "ms", rtts.size, echoLowConf)
        val n2 = KpiValue(n2Value, "ms", rtts.size, echoLowConf)

        // ---- U1：2xx 口径 goodput，含/剔除慢启动双值，跨上传取中位数 ----
        val validUploads = input.uploadResults.filter {
            it.http2xx && it.durationNanos != null && it.durationNanos > 0
        }
        val u1Incl = validUploads.map { goodputMbps(it.bytes, it.durationNanos!!) }
        val u1ExclList = validUploads.mapNotNull { up ->
            val ssNs = up.slowStartNanos ?: return@mapNotNull null
            val ssBytes = up.slowStartBytes ?: return@mapNotNull null
            val remainNs = up.durationNanos!! - ssNs
            val remainBytes = up.bytes - ssBytes
            if (remainNs <= 0 || remainBytes <= 0) null else goodputMbps(remainBytes, remainNs)
        }
        val u1LowConf = u1Incl.size < MIN_UPLOAD_SAMPLES && u1Incl.isNotEmpty()
        val u1 = KpiValue(percentileOrNull(u1Incl, 0.50), "Mbps", u1Incl.size, u1LowConf)
        val u1Excl = KpiValue(
            percentileOrNull(u1ExclList, 0.50), "Mbps", u1ExclList.size,
            u1ExclList.size < MIN_UPLOAD_SAMPLES && u1ExclList.isNotEmpty(),
        )

        // ---- U2：轮次耗时 − 服务端 proc，P95（最近秩；n=8 时即最大值，R-29 靠低置信标暴露）----
        val loopMs = input.toolLoopSamples.mapNotNull { s ->
            val total = s.totalNanos ?: return@mapNotNull null // 失败样本 null，绝不 0（R-10）
            (total - s.serverProcNanos) / 1e6
        }
        val u2LowConf = loopMs.size < MIN_TOOL_LOOP_SAMPLES && loopMs.isNotEmpty()
        val u2 = KpiValue(percentileOrNull(loopMs, 0.95), "ms", loopMs.size, u2LowConf)

        // ---- 有效性 Gate（fail-closed，5.3.8/R-10）----
        val invalidReasons = ArrayList<InvalidReason>()
        invalidReasons.addAll(input.externalInvalidReasons)
        if (input.streamTruncated) invalidReasons.add(InvalidReason.TRUNCATED)
        if (expectedCount > 0 && gapCount.toDouble() / expectedCount > GAP_INVALID_RATIO) {
            invalidReasons.add(InvalidReason.GAP_EXCEEDED)
        }
        val noData = input.tokenSamples.isEmpty() && input.echoSamples.isEmpty() &&
            input.uploadResults.isEmpty() && input.toolLoopSamples.isEmpty() &&
            input.ttftSamples.isEmpty()
        if (noData) invalidReasons.add(InvalidReason.NO_DATA)

        val allKpis = listOf(t1, t2, t2Incl, t3, t3Incl, t4, n1, n2, u1, u1Excl, u2)
        val validity = when {
            invalidReasons.isNotEmpty() -> Validity.INVALID
            gapCount > 0 || allKpis.any { it.lowConfidence } -> Validity.VALID_LOW_CONFIDENCE
            else -> Validity.VALID
        }

        // INVALID：抑制 KPI 值输出（value 置 null），但样本计数/诊断字段保留（5.3.8）
        fun gate(v: KpiValue): KpiValue =
            if (validity == Validity.INVALID) v.copy(value = null) else v

        return KpiResult(
            validity = validity,
            invalidReasons = invalidReasons,
            seqMissingCount = missingCount,
            seqDupCount = dupCount,
            seqGapCount = gapCount,
            expectedTokenCount = expectedCount,
            t1TtftMs = gate(t1),
            t2ItlP95Ms = gate(t2),
            t2ItlP95InclCoalescedMs = gate(t2Incl),
            t3StallRate = gate(t3),
            t3StallRateInclResume = gate(t3Incl),
            t4SevereStallRate = gate(t4),
            t5ResumeP95Ms = gate(t5),
            t5ResumeLatenciesMs = if (validity == Validity.INVALID) emptyList() else resumeLatencies,
            n1RttP50Ms = gate(n1),
            n2JitterMs = gate(n2),
            u1GoodputMbps = gate(u1),
            u1GoodputExclSlowStartMbps = gate(u1Excl),
            u2ToolLoopP95Ms = gate(u2),
        )
    }

    /** 超阈值占比 KPI（T3/T4） */
    private fun rateKpi(samples: List<Double>, thresholdMs: Double, lowConf: Boolean): KpiValue {
        if (samples.isEmpty()) return KpiValue.empty("ratio")
        val rate = samples.count { it > thresholdMs }.toDouble() / samples.size
        return KpiValue(rate, "ratio", samples.size, lowConf)
    }

    private fun goodputMbps(bytes: Long, durationNanos: Long): Double =
        bytes * 8.0 / (durationNanos / 1e9) / 1e6

    /**
     * 最近秩分位数（nearest-rank：rank = ceil(p×n)，1-indexed）。
     * 空集返回 null（绝不 0，R-10）。
     */
    fun percentileOrNull(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
