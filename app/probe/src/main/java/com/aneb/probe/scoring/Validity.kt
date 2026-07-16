package com.aneb.probe.scoring

/**
 * 场景有效性三态 Gate（KPI 文档 5.3.6 / 5.3.8；红队 R-10 fail-closed 语义）。
 *
 * - [VALID]：全部守卫通过、样本量充足。
 * - [VALID_LOW_CONFIDENCE]：出值但带低置信标（如 seq gap>0 但 ≤1%、样本数不足，R-29）；
 *   AQS 可出分但必须带低置信标注展示（KPI 文档 5.4）。
 * - [INVALID]：抑制 KPI/AQS 聚合输出（KpiValue 置 null、AQS 不出分），
 *   但原始事件全量保留并记 [InvalidReason] 原因码（5.3.8）。
 */
enum class Validity {
    VALID,
    VALID_LOW_CONFIDENCE,
    INVALID,
}

/**
 * 场景判无效的原因码（结果合同字段，R-10：带标样本统计去向必须显式）。
 */
enum class InvalidReason {
    /** seq 缺号/重号 gap 超 token 总数 1%（KPI 文档 5.3.8，红队 R-08 fail-closed） */
    GAP_EXCEEDED,

    /** 测中网络路径变更（默认路由/出口变化，守卫事件时间轴） */
    PATH_CHANGED,

    /** 批化/中间盒缓冲判无效（KPI 文档 5.3.3，link_batching / device_side_batching） */
    BUFFERING_SUSPECT,

    /** 有效性守卫失败：Doze/省电切换、后台限制、热状态迁移等（KPI 文档 5.3.6） */
    GUARD_FAILED,

    /** 流式异常中断/截断：中断点后样本不进 ITL 统计，场景 KPI 置 null（5.3.8，R-10） */
    TRUNCATED,

    /** 服务端时钟可疑（发送空洞 vs 自检读数矛盾，红队 R-25） */
    SERVER_CLOCK_SUSPECT,

    /** 无任何可用样本 */
    NO_DATA,

    /** 监控器自身故障（ConnectivityManager 不可用/路径回调注册失败）：无法证明测中环境稳定，fail-closed（R-01） */
    MONITOR_FAILURE,

    /** 引擎自身异常（场景执行代码抛错，非守卫/路径/监控器语义） */
    ENGINE_ERROR,
}
