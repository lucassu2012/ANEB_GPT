package com.aneb.probe.engine

import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.data.VoiceResultRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [VoiceSummary.select] 24h 窗口与字段保真测试（特别是 mouthEarProxyP50Ms <- mouthEarProxyMs 而非 mouthEarBudgetMs）
 * 以及 [VoiceResultRepository.toEntity] Done 过滤与 R-10 null 保真测试。
 */
class VoiceSummaryTest {

    private val now = 1_783_950_000_000L

    private fun row(
        tsEpochMs: Long = now - 60_000L,
        caliber: String? = VoiceRunner.SIM_CALIBER,
        lowConfidence: Boolean = false,
        mouthEarProxyMs: Double? = 412.0,
        mouthEarBudgetMs: Double? = 999.0,
        turnsOk: Int? = 12,
        m7MaxFrameGapMs: Double? = 180.5,
    ) = VoiceResultEntity(
        tsEpochMs = tsEpochMs,
        caliber = caliber,
        lowConfidence = lowConfidence,
        rttMs = 55.0,
        jitterMs = 4.0,
        upFrameJitterMs = 6.0,
        downFrameJitterMs = null,
        mouthEarBudgetMs = mouthEarBudgetMs,
        framesSent = 100,
        framesRecv = 100,
        ttfbP50Ms = 210.0,
        ttfbP95Ms = 300.0,
        downNetJitterMs = 5.5,
        mouthEarProxyMs = mouthEarProxyMs,
        turnSwitchP50Ms = 800.0,
        bargeStopMaxMs = 120.0,
        turnsOk = turnsOk,
        m7MaxFrameGapMs = m7MaxFrameGapMs,
    )

    @Test
    fun `empty candidates produce null voice summary`() {
        assertNull(VoiceSummary.select(emptyList(), now))
    }

    @Test
    fun `in-window row preserves fields and maps mouthEarProxyMs instead of mouthEarBudgetMs`() {
        val v = VoiceSummary.select(listOf(row()), now)!!
        assertEquals(VoiceRunner.SIM_CALIBER, v.caliber)
        assertEquals(180.5, v.m7MaxFrameGapMs!!, 0.0)
        assertEquals(412.0, v.mouthEarProxyP50Ms!!, 0.0)
        assertEquals(false, v.lowConfidence)
        assertEquals(12, v.turnsOk)
        assertEquals(now - 60_000L, v.tsEpochMs)
    }

    @Test
    fun `v1 paced-proxy row preserves nullable fields without inventing zeros`() {
        val v = VoiceSummary.select(
            listOf(row(caliber = null, mouthEarProxyMs = null, turnsOk = null, m7MaxFrameGapMs = null)),
            now,
        )!!
        assertNull(v.caliber)
        assertNull(v.mouthEarProxyP50Ms)
        assertNull(v.turnsOk)
        assertNull(v.m7MaxFrameGapMs)
    }

    @Test
    fun `expired rows beyond 24h are excluded while exact boundary is retained`() {
        assertNull(VoiceSummary.select(listOf(row(tsEpochMs = now - VoiceSummary.MAX_AGE_MS - 1L)), now))
        val boundary = VoiceSummary.select(listOf(row(tsEpochMs = now - VoiceSummary.MAX_AGE_MS)), now)
        assertEquals(now - VoiceSummary.MAX_AGE_MS, boundary!!.tsEpochMs)
    }

    @Test
    fun `future timestamp rows are excluded`() {
        assertNull(VoiceSummary.select(listOf(row(tsEpochMs = now + 1L)), now))
    }

    @Test
    fun `repository toEntity ignores non-Done phases and preserves nulls on Done`() {
        val pingSample = VoiceRunner.Sample(
            phase = VoiceRunner.Phase.Ping,
            rttMs = 30.0,
            jitterMs = 2.0,
            upFrameJitterMs = null,
            downFrameJitterMs = null,
            mouthEarBudgetMs = null,
            framesSent = 0,
            framesRecv = 0,
            progress = 0.1f,
        )
        assertNull(VoiceResultRepository.toEntity(pingSample, now))

        val doneWithNulls = VoiceRunner.Sample(
            phase = VoiceRunner.Phase.Done,
            rttMs = null,
            jitterMs = null,
            upFrameJitterMs = null,
            downFrameJitterMs = null,
            mouthEarBudgetMs = null,
            framesSent = 0,
            framesRecv = 0,
            progress = 1.0f,
        )
        val entity = VoiceResultRepository.toEntity(doneWithNulls, now)!!
        assertNull(entity.rttMs)
        assertNull(entity.jitterMs)
        assertNull(entity.mouthEarBudgetMs)
        assertNull(entity.m7MaxFrameGapMs)
        assertNull(entity.voiceNearZeroArrivalRatio)
    }
}
