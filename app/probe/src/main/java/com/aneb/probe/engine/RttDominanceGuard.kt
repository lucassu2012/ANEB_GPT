package com.aneb.probe.engine

/**
 * RTT 主导度自检（spec/PROFILE2_THROUGHPUT_PROBE_SPEC.md §8.3.3，T47 批②，D-468/D-469）。
 * 判据：`ratio = windowActualMs / rttRefMs >= RTT_DOMINANCE_MIN`，且窗口时长/字节数不低于
 * 各自下限（三条件交集，AND 不是 OR）——用于判断一次固定时长窗口传输是否"结构上不属于
 * 时延主导"。纯 JVM、无 Android 依赖。
 */
object RttDominanceGuard {

    const val RTT_DOMINANCE_MIN = 15.0
    const val ABS_FLOOR_MS = 300.0
    const val MIN_BYTES_FLOOR = 100L * 1024L // 100KB

    data class DominanceVerdict(
        val ok: Boolean,
        val ratio: Double?,
    )

    fun evaluate(windowActualMs: Double, rttRefMs: Double?, bytesTransferred: Long): DominanceVerdict {
        if (!windowActualMs.isFinite() || windowActualMs <= 0.0 ||
            rttRefMs == null || !rttRefMs.isFinite() || rttRefMs <= 0.0 ||
            bytesTransferred < 0L
        ) {
            return DominanceVerdict(ok = false, ratio = null)
        }
        val ratio = windowActualMs / rttRefMs
        if (!ratio.isFinite()) {
            return DominanceVerdict(ok = false, ratio = null)
        }
        val ok = ratio >= RTT_DOMINANCE_MIN &&
            windowActualMs >= ABS_FLOOR_MS &&
            bytesTransferred >= MIN_BYTES_FLOOR
        return DominanceVerdict(ok, ratio)
    }
}
