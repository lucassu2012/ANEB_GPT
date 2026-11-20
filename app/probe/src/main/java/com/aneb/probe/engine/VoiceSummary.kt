package com.aneb.probe.engine

import com.aneb.probe.data.VoiceResultEntity

/**
 * run 级 voice 摘要接线（大脑 2026-08-22 裁定「一次 schema 变更两族字段」的 voice 半；
 * 挂接走 [AqsV02Gate]/D-26 同款先例——窗口选择 + 纯映射，DAO/AqsScorer 均不改）。
 *
 * 挂接语义（勘察定案）：voice_result **无 runId 外键、与 run 零关联**（Entities KDoc 明言
 * 加列须走决策——不加）。run 收尾时取 [MAX_AGE_MS]（24h）窗内最近一条 Done 行（表只存
 * Done 样本），additive 写 `run.voice` 块 + 溯源 tsEpochMs（跨纪元以它为准，D-513——
 * id 会随纪元重计）。
 *
 * 边界（大脑裁定 2026-08-22，scripts/README「语音双通道边界」同文）：wire 摘要**只供
 * 战役报告链并入与横幅计数**；语音判读（T65 式锚点判读、逐轮明细）的权威通道仍是设备库
 * voice_result 全表——本摘要不得作判读源。
 *
 * 字段最小集（v3 普查：20 列中 scripts/ 读者仅 caliber+lowConfidence，零读者列不上
 * wire——D-276 反模式在选型期堵死）：caliber / m7_max_frame_gap_ms /
 * mouth_ear_proxy_p50_ms（M1' hero）/ low_confidence / turns_ok ＋ 溯源 ts_epoch_ms。
 *
 * 纯函数、无 Android 依赖，可 JVM 单测（AqsV02Gate 同款）。
 */
object VoiceSummary {

    /** voice 摘要的最大可用年龄（24h；[AqsV02Gate.CONTINUITY_MAX_AGE_MS] 同款语义——超龄的语音证据与本次 run 环境不可比） */
    const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * `run.voice` 的载荷：六键恒在块内，各值按 voice_result 实体语义**独立可空**
     * （v1 行 caliber/turnsOk/proxy 恒 null 而 lowConfidence 恒 false——都是合法状态，
     * R-10 原样透传，无块级 cross-field 假不变量）。
     */
    data class Voice(
        /** 口径标注（实体原样）：null=v1 paced-proxy */
        val caliber: String?,
        /** M7 最长帧间静默（max 非分位，D-390 §5.6）：null=被挂接行早于 M7 落地 */
        val m7MaxFrameGapMs: Double?,
        /** M1' 口到耳实测代理 P50（hero）：v1 行恒 null */
        val mouthEarProxyP50Ms: Double?,
        val lowConfidence: Boolean,
        /** protocol_ok 轮数：v1 行恒 null */
        val turnsOk: Int?,
        /** 溯源：被挂接行的落库时刻（跨纪元唯一凭据，D-513） */
        val tsEpochMs: Long,
    )

    /**
     * 从候选（`recent(1)`，tsEpochMs DESC）中选窗口内（含边界；未来时刻的脏数据剔除，
     * [AqsV02Gate.select] 同款判据形状）最新一条；无 → null（run 不出 voice 块——
     * 块缺席=窗内无 Done 行或旧生产者）。
     *
     * 映射保真警戒：mouthEarProxyP50Ms ← 实体 **mouthEarProxyMs（M1' PROXY）**，
     * 不是 mouthEarBudgetMs（M1 DERIVED，v1 口径）——一字之差两个口径，
     * VoiceSummaryTest 逐字段钉住。
     */
    fun select(candidates: List<VoiceResultEntity>, nowEpochMs: Long): Voice? =
        candidates
            .filter { nowEpochMs - it.tsEpochMs in 0..MAX_AGE_MS }
            .maxByOrNull { it.tsEpochMs }
            ?.let {
                Voice(
                    caliber = it.caliber,
                    m7MaxFrameGapMs = it.m7MaxFrameGapMs,
                    mouthEarProxyP50Ms = it.mouthEarProxyMs,
                    lowConfidence = it.lowConfidence,
                    turnsOk = it.turnsOk,
                    tsEpochMs = it.tsEpochMs,
                )
            }
}
