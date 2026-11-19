package com.aneb.probe.data

import com.aneb.probe.engine.VoiceRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * Profile 4 实时语音测量编排与 Room 持久化入口（零侵入现有 Compose 导航与 AnebTestMode）。
 *
 * - 严格遵守 R-10：仅 [VoiceRunner.Phase.Done] 样本入库，17 个可空指标/口径字段原样透传（绝不填 0），
 *   综合评分不在库内存储，由展示/复核侧通过 [com.aneb.probe.scoring.AqsScorer] 现算。
 * - 支持与供体 MainActivity 对齐的 `runSim` 优先、协议/端点不支持时自动降级 `run`（paced-proxy）执行链。
 */
class VoiceResultRepository(
    private val dao: VoiceResultDao,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    data class RunOutcome(
        val finalSample: VoiceRunner.Sample?,
        val persistedRowId: Long?,
        val fellBackToPacedProxy: Boolean,
    )

    suspend fun persistIfDone(
        sample: VoiceRunner.Sample,
        tsEpochMs: Long = nowEpochMs(),
    ): Long? {
        val entity = toEntity(sample, tsEpochMs) ?: return null
        return dao.insert(entity)
    }

    suspend fun recent(limit: Int): List<VoiceResultEntity> = dao.recent(limit)

    suspend fun runWithFallbackAndPersist(
        runner: VoiceRunner,
        serverUrl: String,
        onSample: ((VoiceRunner.Sample) -> Unit)? = null,
    ): RunOutcome {
        var lastSample: VoiceRunner.Sample? = null
        var fellBack = false
        try {
            runner.runSim(serverUrl).collect { sample ->
                lastSample = sample
                onSample?.invoke(sample)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fellBack = true
            lastSample = null
            runner.run(serverUrl).collect { sample ->
                lastSample = sample
                onSample?.invoke(sample)
            }
        }
        val persistedId = lastSample?.let { persistIfDone(it) }
        return RunOutcome(
            finalSample = lastSample,
            persistedRowId = persistedId,
            fellBackToPacedProxy = fellBack,
        )
    }

    companion object {
        fun toEntity(sample: VoiceRunner.Sample, tsEpochMs: Long): VoiceResultEntity? {
            if (sample.phase != VoiceRunner.Phase.Done) return null
            return VoiceResultEntity(
                tsEpochMs = tsEpochMs,
                caliber = sample.caliber,
                lowConfidence = sample.lowConfidence,
                rttMs = sample.rttMs,
                jitterMs = sample.jitterMs,
                upFrameJitterMs = sample.upFrameJitterMs,
                downFrameJitterMs = sample.downFrameJitterMs,
                mouthEarBudgetMs = sample.mouthEarBudgetMs,
                framesSent = sample.framesSent,
                framesRecv = sample.framesRecv,
                ttfbP50Ms = sample.ttfbP50Ms,
                ttfbP95Ms = sample.ttfbP95Ms,
                downNetJitterMs = sample.downNetJitterMs,
                mouthEarProxyMs = sample.mouthEarProxyMs,
                turnSwitchP50Ms = sample.turnSwitchP50Ms,
                bargeStopMaxMs = sample.bargeStopMaxMs,
                turnsOk = sample.turnsOk,
                m7MaxFrameGapMs = sample.m7MaxFrameGapMs,
                voiceNearZeroArrivalRatio = sample.voiceNearZeroArrivalRatio,
            )
        }
    }
}
