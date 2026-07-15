package com.aneb.probe.engine

/**
 * ITL 对数分桶直方图（结果上报体，R-27 预留合同）。纯 JVM、无 Android 依赖。
 *
 * 桶界（ms）= 对数网格 {1,2,4,…,8192} ∪ {100,200,400,1000}（T2/T3/T4 全部门限锚点），
 * 保证服务端复算 stall 率与本地精确值一致（server/handlers_results.go 顶部注释同款约定）。
 * 桶 i 覆盖 [edge[i-1], edge[i])；首桶 (-∞, edge[0])（负残差样本落此，5.3.4 不 clamp）；
 * 尾桶 [edge[last], +∞)。counts.size = edges.size + 1。
 */
class ItlHistogram private constructor(
    val edgesMs: List<Double>,
    val counts: IntArray,
) {
    companion object {
        val EDGES_MS: List<Double> = buildList {
            var v = 1.0
            while (v <= 8192.0) {
                add(v)
                v *= 2
            }
            addAll(listOf(100.0, 200.0, 400.0, 1000.0))
        }.distinct().sorted()

        const val BUCKETS_VERSION = "log2-1..8192+thresholds-v1"

        fun of(samplesMs: List<Double>): ItlHistogram {
            val counts = IntArray(EDGES_MS.size + 1)
            for (s in samplesMs) {
                var idx = EDGES_MS.indexOfFirst { s < it }
                if (idx == -1) idx = EDGES_MS.size
                counts[idx]++
            }
            return ItlHistogram(EDGES_MS, counts)
        }
    }

    val total: Int get() = counts.sum()
}
