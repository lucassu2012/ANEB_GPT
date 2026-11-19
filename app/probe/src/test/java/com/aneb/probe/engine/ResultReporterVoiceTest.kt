package com.aneb.probe.engine

import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.AqsScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class ResultReporterVoiceTest {

    private fun testRun() = TestRun(
        runId = "run-voice-wire-1",
        startedAtEpochMs = 1_784_000_000_000L,
        serverBase = "https://120.79.148.0:8443",
        mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal",
        transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2",
        aqsVersion = "aqs-v0.1",
        profileVersions = "s1_chat@0.2.0;s2_coding_agent@0.2.0;s3_multimodal@0.2.0",
        schemaVersion = "1.0",
        profileSource = "server",
        appVersionName = "0.3.0",
        appVersionCode = 1L,
        guardMetadata = "private_dns_active=false",
        aqsScore = 86.0,
        aqsLowConfidence = false,
        aqsVetoApplied = false,
        aqsNotComputableReason = null,
        status = "completed",
        reportStatus = null,
    )

    private fun aqs() = AqsScorer.AqsResult(
        aqsVersion = "aqs-v0.1",
        kpiSetVersion = "agent-qoe-kpi-v0.2",
        score = 86.0,
        subScores = mapOf("T1" to 90.0),
        vetoApplied = false,
        lowConfidence = false,
        notComputableReason = null,
    )

    @Test
    fun `omits run voice block when voice summary is null`() {
        val body = ResultReporter.build(testRun(), emptyList(), aqs())
        val runObj = Json.parseToJsonElement(body).jsonObject.getValue("run").jsonObject
        assertFalse(runObj.containsKey("voice"))
    }

    @Test
    fun `serializes run voice block with all six keys and preserves nulls on v1 rows`() {
        val simVoice = VoiceSummary.Voice(
            caliber = VoiceRunner.SIM_CALIBER,
            m7MaxFrameGapMs = 122.5,
            mouthEarProxyP50Ms = 205.0,
            lowConfidence = true,
            turnsOk = 8,
            tsEpochMs = 1_783_999_000_000L,
        )
        val simDoc = Json.parseToJsonElement(
            ResultReporter.build(testRun(), emptyList(), aqs(), voice = simVoice),
        ).jsonObject
        assertEquals(ResultReporter.CLAIM_SCOPE, simDoc.getValue("claim_scope").jsonPrimitive.content)
        val voiceObj = simDoc.getValue("run").jsonObject.getValue("voice").jsonObject
        assertEquals(
            setOf(
                "caliber",
                "m7_max_frame_gap_ms",
                "mouth_ear_proxy_p50_ms",
                "low_confidence",
                "turns_ok",
                "ts_epoch_ms",
            ),
            voiceObj.keys,
        )
        assertEquals(VoiceRunner.SIM_CALIBER, voiceObj.getValue("caliber").jsonPrimitive.content)
        assertEquals(122.5, voiceObj.getValue("m7_max_frame_gap_ms").jsonPrimitive.double, 1e-9)
        assertEquals(205.0, voiceObj.getValue("mouth_ear_proxy_p50_ms").jsonPrimitive.double, 1e-9)
        assertTrue(voiceObj.getValue("low_confidence").jsonPrimitive.boolean)
        assertEquals(8, voiceObj.getValue("turns_ok").jsonPrimitive.int)
        assertEquals(1_783_999_000_000L, voiceObj.getValue("ts_epoch_ms").jsonPrimitive.long)

        // v1 paced-proxy row keeps nullable keys as JSON null (never 0)
        val v1Voice = VoiceSummary.Voice(
            caliber = null,
            m7MaxFrameGapMs = null,
            mouthEarProxyP50Ms = null,
            lowConfidence = false,
            turnsOk = null,
            tsEpochMs = 1_783_999_500_000L,
        )
        val v1Obj = Json.parseToJsonElement(
            ResultReporter.build(testRun(), emptyList(), aqs(), voice = v1Voice),
        ).jsonObject.getValue("run").jsonObject.getValue("voice").jsonObject
        assertEquals(JsonNull, v1Obj.getValue("caliber"))
        assertEquals(JsonNull, v1Obj.getValue("m7_max_frame_gap_ms"))
        assertEquals(JsonNull, v1Obj.getValue("mouth_ear_proxy_p50_ms"))
        assertFalse(v1Obj.getValue("low_confidence").jsonPrimitive.boolean)
        assertEquals(JsonNull, v1Obj.getValue("turns_ok"))
        assertEquals(1_783_999_500_000L, v1Obj.getValue("ts_epoch_ms").jsonPrimitive.long)
    }

    @Test
    fun `production TestEngine and MainActivity wire VoiceSummary and VoiceScreen cleanly`() {
        val testEngine = source("engine/TestEngine.kt")
        assertTrue(testEngine.contains("VoiceSummary.select("))
        assertTrue(testEngine.contains("db.voiceResultDao().recent(1)"))
        assertTrue(testEngine.contains("ResultReporter.build(runEntity, scenarioReports, aqsResult, voice = voiceSummary)"))

        val activity = source("ui/MainActivity.kt")
        assertTrue(activity.contains("data object Voice : Screen"))
        assertTrue(activity.contains("VoiceResultRepository(db.voiceResultDao())"))
        assertTrue(activity.contains("VoiceFormat::buildJson"))
        assertTrue(activity.contains("VoiceFormat::buildCsv"))
        assertTrue(activity.contains("nonPrototypeRunning || prototypePresentation.quickRunning"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }
}
