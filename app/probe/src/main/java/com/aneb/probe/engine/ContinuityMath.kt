package com.aneb.probe.engine

/**
 * 阶段 2 C 组连续性实验的纯计算逻辑（纯 JVM、无 Android 依赖，可直接单测）。
 *
 * - 重连退避：指数退避 500ms 起（500/1000/2000/4000/8000），最多 5 次（任务书口径）；
 * - C1 会话中断率 = 实验内异常断开段数 / 流式段总数（KPI 文档 5.1，跨段聚合）；
 * - C2 恢复时间样本取中位数进评分（多样本聚合口径与场景 KPI 的"取中位数"惯例一致）；
 * - 失败语义 R-10：无样本/分母为 0 一律 null，绝不记 0 或哨兵值。
 */
object ContinuityMath {

    const val DEFAULT_MAX_ATTEMPTS = 5
    const val DEFAULT_BACKOFF_BASE_MS = 500L

    /**
     * 第 [attempt]（1 起）次重连前的退避时长：base × 2^(attempt-1)。
     * attempt 越界（<1）按 1 处理（防御，不抛）。
     */
    fun backoffDelayMs(attempt: Int, baseMs: Long = DEFAULT_BACKOFF_BASE_MS): Long {
        val a = if (attempt < 1) 1 else attempt
        return baseMs shl (a - 1)
    }

    /** C1 会话中断率；总段数为 0 时 null（R-10：无分母不出值）。 */
    fun c1Rate(abnormalDisconnects: Int, segmentsTotal: Int): Double? =
        if (segmentsTotal <= 0) null else abnormalDisconnects.toDouble() / segmentsTotal

    /** 中位数（偶数取中间两数均值）；空集 null（R-10）。 */
    fun medianMs(samples: List<Double>): Double? {
        if (samples.isEmpty()) return null
        val s = samples.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * C3 阶梯探测单档结果。
     *
     * @param idleS 空闲时长（秒）
     * @param connNew 探测请求是否新建了连接（EventListener 有 connectStart 打点即新建）；
     *   探测彻底失败（无 timing）记 null
     * @param echoMs 探测 echo 端到端耗时（ms）；失败 null（R-10）
     * @param error 失败原因；成功 null
     */
    data class C3Probe(
        val idleS: Int,
        val connNew: Boolean?,
        val echoMs: Double?,
        val error: String?,
    )

    /** C3 阶梯序列化（Room 单列存储）："idle_s:conn_new:echo_ms:error;..."。 */
    fun c3LadderCsv(probes: List<C3Probe>): String =
        probes.joinToString(";") { p ->
            val ms = p.echoMs?.let { "%.2f".format(it) } ?: "null"
            val err = p.error?.replace(' ', '_')?.replace(';', ',')?.replace(':', '~') ?: "none"
            "${p.idleS}:${p.connNew ?: "null"}:$ms:$err"
        }
}
