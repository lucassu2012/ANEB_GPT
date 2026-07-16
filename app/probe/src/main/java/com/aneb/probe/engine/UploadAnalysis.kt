package com.aneb.probe.engine

/**
 * 上行慢启动爬坡段估计（U1 双口径，KPI 文档 5.1）。纯 JVM、无 Android 依赖。
 *
 * 输入是服务端 /upload 响应返回的**权威逐块到达序列**（R-07：服务端视角，
 * 非客户端 writeTo 本地写序列）。
 *
 * 估计口径（实现内定义，随版本冻结、待阶段一标定修订）：
 * - 需 ≥ [MIN_CHUNKS] 块（64KB/块 → ≥1MB 上传才可估；S1 2KB / S2 512KB 输出 null）；
 * - 稳态速率 = 后半段字节数 / 后半段耗时；
 * - 慢启动终点 = 首个"滑动窗口（[WINDOW] 块）瞬时速率 ≥ 稳态 × [STEADY_FRACTION]"的块；
 * - 找不到终点或终点在首块 → null（无可剥离的爬坡段，剔慢启动口径退化为 null，
 *   KpiCalculator 对 null 慢启动的语义：excl 口径不出值——绝不猜）。
 */
object UploadAnalysis {

    const val MIN_CHUNKS = 16
    const val WINDOW = 4
    const val STEADY_FRACTION = 0.5

    /**
     * @param chunkArrivalUs 服务端逐块到达时刻（单调 us，升序），每块 [chunkBytes] 字节
     *   （末块可能不足，忽略该误差——估计目标是量级而非字节级精确）
     * @param recvStartUs 服务端开始收 body 时刻
     * @return (slowStartUs, slowStartBytes)；不可估返回 null
     */
    fun estimateSlowStart(
        chunkArrivalUs: List<Long>,
        recvStartUs: Long,
        chunkBytes: Long,
    ): Pair<Long, Long>? {
        val n = chunkArrivalUs.size
        if (n < MIN_CHUNKS) return null

        // 稳态速率：后半段（bytes/us）
        val halfIdx = n / 2
        val steadyDurUs = chunkArrivalUs[n - 1] - chunkArrivalUs[halfIdx - 1]
        if (steadyDurUs <= 0) return null
        val steadyRate = (n - halfIdx) * chunkBytes.toDouble() / steadyDurUs

        // 从头找首个达到稳态一半速率的窗口
        for (i in 0..n - WINDOW) {
            val t0 = if (i == 0) recvStartUs else chunkArrivalUs[i - 1]
            val durUs = chunkArrivalUs[i + WINDOW - 1] - t0
            if (durUs <= 0) continue
            val rate = WINDOW * chunkBytes.toDouble() / durUs
            if (rate >= steadyRate * STEADY_FRACTION) {
                if (i == 0) return null // 一开始就达稳态：无爬坡段可剥离
                val slowStartUs = chunkArrivalUs[i - 1] - recvStartUs
                if (slowStartUs <= 0) return null
                return slowStartUs to i * chunkBytes
            }
        }
        return null
    }
}
