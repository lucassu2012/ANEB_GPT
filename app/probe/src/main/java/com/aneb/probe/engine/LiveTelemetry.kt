package com.aneb.probe.engine

import com.aneb.probe.radio.RadioSample
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * 实时分层遥测——**只读观测通道**，与测量记录口径解耦，不参与测量（R-16）。
 *
 * 语义边界（红线）：
 *  - 本类型与 [derive] 只是把引擎「已记录的最新状态」节流投影给 UI，绝不改动任何
 *    时间戳 / 落库 / 日志 KEY，也不定义新的测量口径。ITL/RTT 波形与既有 KPI 同源
 *    （[ScenarioKpi.correctedItlSamplesMs] / 首个 clock_sync echo），只是「节流暴露」。
 *  - 全部字段可空：未测到＝null（R-10，绝不以 0 顶替）。计数/波形无数据＝0/空列表。
 *  - [stallCount] / [tokenRatePerSec] 为**粗粒度实时指示**，仅供 UI 动效，不等价于
 *    KpiCalculator 的三态 stall 判定（后者含 coalesced/resume 剔除与分级严重度）。
 *
 * 分两层呈现：网络层（承载质量）+ AI 业务层（token 生成质量）+ 全局进度。
 */
data class LiveTelemetry(
    // ---------------- 网络层 ----------------
    /** 往返时延中位数（ms）；同源首个 clock_sync 的有效 echo 样本（N1 口径）。无样本＝null */
    val rttMs: Double? = null,
    /** 抖动（ms）＝相邻 RTT 差绝对值中位数（粗口径，供波形）；样本 <2 个＝null */
    val jitterMs: Double? = null,
    /** 最近 1Hz 无线样本的服务小区 ssRsrp/rsrp（dBm）；无小区/权限缺失＝null */
    val rsrp: Int? = null,
    /** 最近 1Hz 无线样本的 ssSinr/rssnr（dB）；无小区/权限缺失＝null */
    val sinr: Int? = null,
    /** 设备报告制式标签；仅多源一致时确定，冲突/缺证据显式标注；降级样本＝null */
    val rat: String? = null,
    /** 最近一次 upload_burst goodput（Mbps，终点=2xx 头，与 U1 同终点）；无上传＝null */
    val upMbps: Double? = null,

    // ---------------- AI 业务层 ----------------
    /** 最近完成流的 TTFT（ms，已剥服务端 dwell）；不可算/未出流＝null */
    val ttftMs: Double? = null,
    /** 最近 [ITL_WINDOW] 个校正 ITL（ms）滑窗，供实时波形；无样本＝空列表 */
    val itlRecentMs: List<Double> = emptyList(),
    /** 上述滑窗的中位数（ms）；空＝null */
    val itlMedianMs: Double? = null,
    /** 粗粒度实时 stall 计数（校正 ITL > [LIVE_STALL_MS] 的样本数）；非 KPI stall */
    val stallCount: Int = 0,
    /** 累计已接收 token 事件数 */
    val tokensReceived: Int = 0,
    /** 粗粒度 token 速率（token/s）＝累计 token / 累计到达跨度；不可算＝null */
    val tokenRatePerSec: Double? = null,
    /**
     * 最近 [LiveStreamWindow.DEFAULT_WINDOW_NANOS] 内的 SSE 事件边界到达速率（event/s）。
     * 这是实时动画用的应用层代理量，不等于模型计费 token；非流式阶段或窗口不足＝null。
     */
    val streamArrivalRatePerSec: Double? = null,
    /** 当前仿真 profile 声明的目标发送速率（event/s）；仅用于仪表量程，不参与 KPI/AQS。 */
    val streamTargetRatePerSec: Double? = null,
    /** 当前是否正在读取 token_stream；用于 UI 区分“尚未测量”和“速率为 0”。 */
    val streamActive: Boolean = false,

    // ---------------- 进度 ----------------
    /** 当前场景+阶段标识（如 profileId#round）；未开始＝null */
    val phase: String? = null,
    /** 全局完成度 0..1（已完成场景 / 总场景数） */
    val fraction: Double = 0.0,
    /** 边测边合成的粗 AQS（run 收尾时才有）；未合成＝null */
    val aqsRunning: Double? = null,
) {
    companion object {
        /** ITL 实时波形滑窗长度（与 KPI 近端 ~40 同量级） */
        const val ITL_WINDOW = 40

        /** 粗粒度实时 stall 阈值（ms）；仅 UI 指示用，非 KPI 分级 stall 口径 */
        const val LIVE_STALL_MS = 500.0

        /** 复位态（run 未开始 / 结束 / 取消） */
        val EMPTY = LiveTelemetry()

        /**
         * 纯函数：既有样本快照 → 分层遥测（JVM 可单测，无 Android 依赖）。
         * 输入 [TelemetrySnapshot] 全部来自引擎已记录的只读投影；本函数不读时钟、
         * 不触碰任何测量状态，transport（AUTO/WIFI/CELLULAR）不参与派生。
         */
        fun derive(s: TelemetrySnapshot): LiveTelemetry {
            // ---- 网络层：RTT / 抖动（同源首个 clock_sync 有效样本）----
            val rttMs = median(s.rttSamplesMs)
            val jitterMs = if (s.rttSamplesMs.size >= 2) {
                val diffs = s.rttSamplesMs.zipWithNext { a, b -> kotlin.math.abs(b - a) }
                median(diffs)
            } else {
                null
            }

            // ---- 网络层：无线（最近 1Hz 样本；降级样本字段自身即 null）----
            val radio = s.latestRadio
            val rat = radio?.let(::deviceReportedRat)

            // ---- AI 业务层：ITL 滑窗 / stall / token 速率 ----
            val window = if (s.itlAllMs.size > ITL_WINDOW) s.itlAllMs.takeLast(ITL_WINDOW) else s.itlAllMs
            val itlMedian = median(window)
            val stall = s.itlAllMs.count { it > LIVE_STALL_MS }
            val rate = s.tokenElapsedSec?.takeIf { it > 0.0 }?.let { s.tokensReceived / it }

            return LiveTelemetry(
                rttMs = rttMs,
                jitterMs = jitterMs,
                rsrp = radio?.rsrp,
                sinr = radio?.sinr,
                rat = rat,
                upMbps = s.latestUpMbps,
                ttftMs = s.ttftMs,
                itlRecentMs = window,
                itlMedianMs = itlMedian,
                stallCount = stall,
                tokensReceived = s.tokensReceived,
                tokenRatePerSec = rate,
                streamArrivalRatePerSec = s.streamArrivalRatePerSec,
                streamTargetRatePerSec = s.streamTargetRatePerSec,
                streamActive = s.streamActive,
                phase = s.phase,
                fraction = s.fraction.coerceIn(0.0, 1.0),
                aqsRunning = s.aqsRunning,
            )
        }

        private fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val sorted = xs.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }

        /**
         * R-15 展示口径：dataNetworkType、display override、registered CellInfo 分列判断。
         * 公开 API 无法可靠证明 NSA/SA，故 UI 不再把 NR 小区或 5G 图标直接翻译成 SA/NSA。
         */
        private fun deviceReportedRat(r: RadioSample): String? = when {
            r.networkType == "NR" && r.rat == "NR" && r.nrState == "connected" -> "设备报告 NR"
            r.networkType == "LTE" && r.rat == "LTE" && r.nrState == "none" -> "设备报告 LTE"
            r.networkType == "LTE" && r.overrideType in NR_DISPLAY_OVERRIDES -> "LTE / 5G 图标不一致"
            r.networkType in setOf("3G", "2G") -> "设备报告 ${r.networkType}"
            r.networkType in setOf("NR", "LTE") && r.rat == r.networkType -> "制式证据不足"
            r.networkType in setOf("NR", "LTE") || r.rat != null -> "制式证据不一致"
            else -> null
        }

        private val NR_DISPLAY_OVERRIDES = setOf("nr_nsa", "nr_nsa_mmwave", "nr_advanced")
    }
}

/**
 * [LiveTelemetry.derive] 的纯输入：引擎既有记录的只读投影。
 *
 * 由引擎在既有记录点（场景边界 / 流完成后的 KPI 计算处，均在 run 主协程、非热路径）
 * 追加式填充，采样协程按 ~100ms 读一致快照。[latestRadio] 由采样协程从既有 radioBuf
 * 尾部只读注入（不消费队列）。字段语义与 [LiveTelemetry] 对应字段同源。
 */
data class TelemetrySnapshot(
    /** 最近 1Hz 无线样本（radioBuf 尾部只读投影）；无＝null */
    val latestRadio: RadioSample? = null,
    /** 当前场景首个 clock_sync 的有效 echo RTT（ms）；无＝空列表 */
    val rttSamplesMs: List<Double> = emptyList(),
    /** 最近一次 upload goodput（Mbps）；无＝null */
    val latestUpMbps: Double? = null,
    /** 最近完成流的 TTFT（ms）；无＝null */
    val ttftMs: Double? = null,
    /** 累计校正 ITL（ms，与 [ScenarioKpi.correctedItlSamplesMs] 同口径）；无＝空列表 */
    val itlAllMs: List<Double> = emptyList(),
    /** 累计已接收 token 事件数 */
    val tokensReceived: Int = 0,
    /** 累计 token 到达跨度（秒，速率分母）；不可算＝null */
    val tokenElapsedSec: Double? = null,
    /** 最近 1 秒 SSE 事件边界到达速率（event/s，实时展示代理量）；不可算＝null。 */
    val streamArrivalRatePerSec: Double? = null,
    /** 当前 token_stream 的 profile 目标速率（event/s）；非流式阶段＝null。 */
    val streamTargetRatePerSec: Double? = null,
    /** 当前是否处于 token_stream 读循环。 */
    val streamActive: Boolean = false,
    /** 当前场景+阶段标识；未开始＝null */
    val phase: String? = null,
    /** 全局完成度 0..1 */
    val fraction: Double = 0.0,
    /** 粗 AQS；未合成＝null */
    val aqsRunning: Double? = null,
) {
    companion object {
        val NONE = TelemetrySnapshot()
    }
}

/**
 * 引擎 → 采样协程的线程安全只读投影源（观测通道，不参与测量）。
 *
 * 引擎在既有记录点用 [update] 追加式覆盖 AI/网络/进度字段（run 主协程，非 SSE 读线程／
 * 计时回调，R-16）；采样协程用 [read] 注入最近无线样本并取一致快照。CAS 无锁，读多写少。
 * [latestRadio] 恒由 [read] 参数注入，[update] 的变换不应触碰它。
 */
class TelemetrySource {
    private val ref = AtomicReference(TelemetrySnapshot.NONE)

    /** 追加式覆盖投影（CAS 重试）；[transform] 须为无副作用纯变换。 */
    fun update(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
        while (true) {
            val cur = ref.get()
            if (ref.compareAndSet(cur, transform(cur))) return
        }
    }

    /** 取一致快照并注入采样协程持有的最近无线样本（不写队列、不改测量状态）。 */
    fun read(latestRadio: RadioSample?): TelemetrySnapshot = ref.get().copy(latestRadio = latestRadio)

    /** run 结束/取消复位。 */
    fun reset() = ref.set(TelemetrySnapshot.NONE)
}

/**
 * token_stream 的只读实时观察合同。
 *
 * [onEventArrival] 在 SSE 读线程的边界切分点调用，因此实现只能做常数时间、无阻塞记录；
 * 禁止解析、落库、日志、Flow 发射或 UI 回调。采样/平滑由独立遥测协程完成，避免反压测量热路径。
 */
interface LiveStreamObserver {
    fun onStreamStarted(targetRatePerSec: Double, startedAtNanos: Long)
    fun onEventArrival(arrivalNanos: Long)
    fun onStreamFinished()
}

/**
 * 无锁固定容量滑窗：SSE 读线程只写到达戳，遥测协程按需读取最近窗口的事件速率。
 *
 * 计数对象是完整 SSE event 边界（含协议 prelude/summary），所以输出必须标为“流式到达速率/代理量”，
 * 不能标为计费 Token。容量覆盖 1 秒内远高于当前 profile 的事件数，溢出时仅保留最新样本。
 */
class LiveStreamWindow(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    capacity: Int = DEFAULT_CAPACITY,
) : LiveStreamObserver {
    init {
        require(windowNanos > 0L) { "windowNanos must be positive" }
        require(capacity > 0) { "capacity must be positive" }
    }

    data class Snapshot(
        val active: Boolean,
        val arrivalRatePerSec: Double?,
        val targetRatePerSec: Double?,
    )

    private val capacity = capacity
    private val arrivals = AtomicLongArray(capacity)
    private val written = AtomicLong(0L)

    @Volatile private var active = false
    @Volatile private var startedAtNanos = 0L
    @Volatile private var targetRatePerSec: Double? = null

    override fun onStreamStarted(targetRatePerSec: Double, startedAtNanos: Long) {
        active = false
        written.set(0L)
        this.startedAtNanos = startedAtNanos
        this.targetRatePerSec = targetRatePerSec.takeIf { it > 0.0 }
        active = true
    }

    override fun onEventArrival(arrivalNanos: Long) {
        if (!active) return
        // 单流单读线程写入：先发布槽位内容，再推进 written，采样线程不会读到尚未写入的新槽位。
        val sequence = written.get()
        arrivals.set((sequence % capacity).toInt(), arrivalNanos)
        written.lazySet(sequence + 1L)
    }

    override fun onStreamFinished() {
        active = false
    }

    fun snapshot(nowNanos: Long): Snapshot {
        val isActive = active
        val target = targetRatePerSec
        if (!isActive) return Snapshot(active = false, arrivalRatePerSec = null, targetRatePerSec = target)

        val elapsed = nowNanos - startedAtNanos
        if (elapsed < MIN_SAMPLE_NANOS) {
            return Snapshot(active = true, arrivalRatePerSec = null, targetRatePerSec = target)
        }

        val lowerBound = maxOf(startedAtNanos, nowNanos - windowNanos)
        val sampleCount = minOf(written.get(), capacity.toLong()).toInt()
        var inWindow = 0
        for (i in 0 until sampleCount) {
            val stamp = arrivals.get(i)
            if (stamp in lowerBound..nowNanos) inWindow++
        }
        val denominatorNanos = minOf(windowNanos, elapsed).coerceAtLeast(MIN_SAMPLE_NANOS)
        val rate = inWindow * 1_000_000_000.0 / denominatorNanos
        return Snapshot(active = true, arrivalRatePerSec = rate, targetRatePerSec = target)
    }

    fun reset() {
        active = false
        written.set(0L)
        startedAtNanos = 0L
        targetRatePerSec = null
    }

    companion object {
        const val DEFAULT_WINDOW_NANOS: Long = 1_000_000_000L
        const val MIN_SAMPLE_NANOS: Long = 250_000_000L
        const val DEFAULT_CAPACITY: Int = 2_048
    }
}
