package com.aneb.probe.ui

import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.data.VoiceResultRepository
import com.aneb.probe.engine.ResultReporter
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.scoring.AqsScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceFormatTest {

    private fun simDoneSample(
        mouthEarProxyMs: Double? = 180.0,
        m7MaxFrameGapMs: Double? = 35.0,
        lowConfidence: Boolean = false,
    ) = VoiceRunner.Sample(
        phase = VoiceRunner.Phase.Done,
        rttMs = 30.0,
        jitterMs = 4.0,
        upFrameJitterMs = 5.0,
        downFrameJitterMs = 6.0,
        mouthEarBudgetMs = 96.0,
        framesSent = 150,
        framesRecv = 800,
        progress = 1f,
        ttfbP50Ms = 110.0,
        ttfbP95Ms = 165.0,
        downNetJitterMs = 4.5,
        mouthEarProxyMs = mouthEarProxyMs,
        turnSwitchP50Ms = 140.0,
        bargeStopMaxMs = 95.0,
        turnsOk = 8,
        caliber = VoiceRunner.SIM_CALIBER,
        lowConfidence = lowConfidence,
        m7MaxFrameGapMs = m7MaxFrameGapMs,
        voiceNearZeroArrivalRatio = 0.025,
    )

    @Test
    fun `scoreSample ignores non-Done phases`() {
        val nonDone = listOf(
            VoiceRunner.Phase.Ping,
            VoiceRunner.Phase.Uplink,
            VoiceRunner.Phase.Downlink,
            VoiceRunner.Phase.Handshake,
            VoiceRunner.Phase.Turns,
        )
        nonDone.forEach { phase ->
            assertNull(VoiceFormat.scoreSample(simDoneSample().copy(phase = phase)))
        }
    }

    @Test
    fun `scoreSample scores v2 server-sim and matches scoreEntity after toEntity round-trip`() {
        val sample = simDoneSample()
        val fromSample = VoiceFormat.scoreSample(sample)!!
        assertEquals(AqsScorer.AQS_VERSION_VOICE_SIM_V02, fromSample.aqsVersion)
        assertNotNull(fromSample.score)
        assertFalse(fromSample.vetoApplied)
        assertTrue(fromSample.score!! in 0.0..100.0)

        val entity = VoiceResultRepository.toEntity(sample, tsEpochMs = 1_784_000_000_000L)!!
        val fromEntity = VoiceFormat.scoreEntity(entity)
        assertEquals(fromSample.aqsVersion, fromEntity.aqsVersion)
        assertEquals(fromSample.score!!, fromEntity.score!!, 1e-9)
        assertEquals(fromSample.vetoApplied, fromEntity.vetoApplied)
        assertEquals(fromSample.subScores, fromEntity.subScores)
    }

    @Test
    fun `scoreSample applies M1 veto above 400ms and stays null when required KPI is missing`() {
        val vetoed = VoiceFormat.scoreSample(simDoneSample(mouthEarProxyMs = 450.0))!!
        assertTrue(vetoed.vetoApplied)
        assertNotNull(vetoed.score)
        assertTrue(vetoed.score!! <= AqsScorer.T4_VETO_CAP)
        assertTrue(VoiceFormat.verdictFor(vetoed).contains("400ms"))

        val missingM7 = VoiceFormat.scoreSample(simDoneSample(m7MaxFrameGapMs = null))!!
        assertNull(missingM7.score)
        assertNotNull(missingM7.notComputableReason)
        assertTrue(VoiceFormat.verdictFor(missingM7).contains("诚实缺席"))
    }

    @Test
    fun `nearZeroRatioDisplay distinguishes null from zero and uses Locale ROOT`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("—", VoiceFormat.nearZeroRatioDisplay(null))
            assertEquals("0.0%", VoiceFormat.nearZeroRatioDisplay(0.0))
            assertEquals("12.5%", VoiceFormat.nearZeroRatioDisplay(0.125))
            assertEquals("—", VoiceFormat.msOrDash(null))
            assertEquals("12.3", VoiceFormat.msOrDash(12.34, decimals = 1))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `buildCsv preserves all 20 columns with empty cells for nulls and Locale ROOT`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val allNull = VoiceResultEntity(
                id = 1L,
                tsEpochMs = 1_784_000_000_000L,
                caliber = null,
                lowConfidence = false,
                rttMs = null,
                jitterMs = null,
                upFrameJitterMs = null,
                downFrameJitterMs = null,
                mouthEarBudgetMs = null,
                framesSent = null,
                framesRecv = null,
                ttfbP50Ms = null,
                ttfbP95Ms = null,
                downNetJitterMs = null,
                mouthEarProxyMs = null,
                turnSwitchP50Ms = null,
                bargeStopMaxMs = null,
                turnsOk = null,
                m7MaxFrameGapMs = null,
                voiceNearZeroArrivalRatio = null,
            )
            val populated = VoiceResultEntity(
                id = 2L,
                tsEpochMs = 1_784_000_060_000L,
                caliber = "custom,\"v2\"",
                lowConfidence = true,
                rttMs = 25.5,
                jitterMs = 2.25,
                upFrameJitterMs = 4.1,
                downFrameJitterMs = 5.2,
                mouthEarBudgetMs = 90.7,
                framesSent = 150,
                framesRecv = 800,
                ttfbP50Ms = 101.5,
                ttfbP95Ms = 150.25,
                downNetJitterMs = 3.75,
                mouthEarProxyMs = 178.5,
                turnSwitchP50Ms = 120.0,
                bargeStopMaxMs = 88.0,
                turnsOk = 8,
                m7MaxFrameGapMs = 42.5,
                voiceNearZeroArrivalRatio = 0.0,
            )

            val csv = VoiceFormat.buildCsv(listOf(allNull, populated))
            val lines = csv.trimEnd('\n').split('\n')
            assertEquals(3, lines.size)
            assertEquals(VoiceFormat.CSV_HEADER, lines[0])
            assertEquals(20, lines[0].split(',').size)

            // All 17 nullable columns stay empty (never "0")
            assertEquals("1,1784000000000,,false,,,,,,,,,,,,,,,,", lines[1])

            // Quoted caliber with comma and embedded quotes + dot decimal under German locale
            assertTrue(lines[2].startsWith("2,1784000060000,\"custom,\"\"v2\"\"\",true,25.5000,2.2500,"))
            assertTrue(lines[2].endsWith(",8,42.5000,0.0000"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `buildJson emits distinct claim_scope and preserves JsonNull for missing metrics`() {
        assertNotEquals(ResultReporter.CLAIM_SCOPE, VoiceFormat.CLAIM_SCOPE)
        val rowNull = VoiceResultEntity(
            id = 7L,
            tsEpochMs = 1_784_000_000_000L,
            caliber = null,
            lowConfidence = false,
            rttMs = 31.2,
            jitterMs = null,
            upFrameJitterMs = null,
            downFrameJitterMs = null,
            mouthEarBudgetMs = null,
            framesSent = 200,
            framesRecv = 200,
            ttfbP50Ms = null,
            ttfbP95Ms = null,
            downNetJitterMs = null,
            mouthEarProxyMs = null,
            turnSwitchP50Ms = null,
            bargeStopMaxMs = null,
            turnsOk = null,
            m7MaxFrameGapMs = null,
            voiceNearZeroArrivalRatio = null,
        )
        val jsonText = VoiceFormat.buildJson(listOf(rowNull))
        val doc = Json.parseToJsonElement(jsonText).jsonObject

        assertEquals(VoiceFormat.CLAIM_SCOPE, doc.getValue("claim_scope").jsonPrimitive.content)
        assertEquals(VoiceFormat.EXPORT_SCHEMA_VERSION, doc.getValue("schema_version").jsonPrimitive.content)
        assertEquals(1, doc.getValue("count").jsonPrimitive.int)

        val record = doc.getValue("records").jsonArray.single().jsonObject
        assertEquals(7L, record.getValue("id").jsonPrimitive.long)
        assertEquals(1_784_000_000_000L, record.getValue("ts_epoch_ms").jsonPrimitive.long)
        assertEquals(JsonNull, record.getValue("caliber"))
        assertFalse(record.getValue("low_confidence").jsonPrimitive.boolean)
        assertEquals(31.2, record.getValue("rtt_ms").jsonPrimitive.double, 1e-9)
        assertEquals(JsonNull, record.getValue("jitter_ms"))
        assertEquals(JsonNull, record.getValue("mouth_ear_proxy_ms"))
        assertEquals(JsonNull, record.getValue("m7_max_frame_gap_ms"))
        assertEquals(JsonNull, record.getValue("voice_near_zero_arrival_ratio"))
        assertEquals(JsonNull, record.getValue("turns_ok"))
    }
}
