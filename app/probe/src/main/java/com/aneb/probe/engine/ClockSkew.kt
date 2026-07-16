package com.aneb.probe.engine

import kotlin.math.abs

/*
 * 双 clock_sync 与 skew 线性插值（C06/R-22，KPI 文档 5.3.2）。
 * 纯 JVM、无 Android 依赖，可直接单测。
 */

/**
 * 一次 clock_sync phase 的收敛结果（Cristian min-RTT 样本）。
 *
 * @param offsetUs 服务端钟 − 客户端钟（微秒）；无有效样本记 null（R-10）
 * @param errUs    ±RTT/2 误差界；offset 为 null 时亦 null
 * @param clientMidUs 该 offset 的客户端时间锚点（最优样本的 (t0+t3)/2，单调 us）
 * @param validSamples 进入 min-RTT 选择的有效样本数（已剔 warmup 与失败）
 */
data class ClockSyncPoint(
    val offsetUs: Long?,
    val errUs: Long?,
    val clientMidUs: Long?,
    val validSamples: Int,
)

/**
 * 场景首尾双 clock_sync 的 offset 轨迹：
 * 场景内依赖 offset 的派生量按首尾两点线性插值取 skew 校正后的 offset（5.3.2）。
 *
 * - 漂移率 driftPpm = Δoffset / Δt × 1e6（客户端时间轴）；|driftPpm| > 100 标 [offsetSuspect]
 *   （R-22：90s 场景内晶振/虚拟化漂移可积累 2–9ms）。
 * - 任一端缺失（offset=null）→ 无法插值：[offsetAtUs] 退化为可用端的常数 offset
 *   （无任何可用端则 null），driftPpm=null 且 [offsetSuspect]=true 保守置疑
 *   （证据缺失 ≠ 隐式健康，§4.6）。
 * - 两锚点客户端时刻差 <1s 时不估漂移率（分母太小放大噪声）：driftPpm=null、不置疑。
 */
class OffsetTrack(
    val start: ClockSyncPoint?,
    val end: ClockSyncPoint?,
) {
    /** 漂移率（ppm，带符号）；不可估时 null。 */
    val driftPpm: Double?

    /** |漂移| > 100ppm，或首尾任一端缺失导致无法核验漂移（保守置疑）。 */
    val offsetSuspect: Boolean

    init {
        val s = start
        val e = end
        if (s?.offsetUs != null && s.clientMidUs != null && e?.offsetUs != null && e.clientMidUs != null) {
            val dtUs = e.clientMidUs - s.clientMidUs
            if (dtUs >= MIN_SPAN_US) {
                val ppm = (e.offsetUs - s.offsetUs).toDouble() / dtUs * 1e6
                driftPpm = ppm
                offsetSuspect = abs(ppm) > SUSPECT_PPM
            } else {
                driftPpm = null
                offsetSuspect = false // 锚点间隔太短，不估也不置疑
            }
        } else {
            driftPpm = null
            // 首尾任一端缺失：无法核验场景内漂移 → offset 派生量置疑（fail-closed 方向）
            offsetSuspect = s?.offsetUs != null || e?.offsetUs != null
        }
    }

    /**
     * 客户端单调时刻 [clientUs] 处的插值 offset（us）。
     * 双端可用：线性插值（允许外插，斜率即漂移率）；单端可用：常数；双端缺失：null。
     */
    fun offsetAtUs(clientUs: Long): Long? {
        val s = start
        val e = end
        val sOk = s?.offsetUs != null && s.clientMidUs != null
        val eOk = e?.offsetUs != null && e.clientMidUs != null
        return when {
            sOk && eOk -> {
                val dt = e!!.clientMidUs!! - s!!.clientMidUs!!
                if (dt == 0L) {
                    s.offsetUs
                } else {
                    val frac = (clientUs - s.clientMidUs!!).toDouble() / dt
                    (s.offsetUs!! + frac * (e.offsetUs!! - s.offsetUs)).toLong()
                }
            }
            sOk -> s!!.offsetUs
            eOk -> e!!.offsetUs
            else -> null
        }
    }

    companion object {
        const val SUSPECT_PPM = 100.0
        const val MIN_SPAN_US = 1_000_000L
    }
}
