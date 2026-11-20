package com.aneb.probe.engineering

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile4VoiceCorrelationContractTest {

    private fun validArgs(conditionId: String = "R") = mapOf(
        "feature_release" to Profile4VoiceCorrelationContract.FEATURE_RELEASE,
        "live_authorized" to "true",
        "condition_id" to conditionId,
        "run_id" to "voice-sim-$conditionId-01",
        "expected_phone_ipv4" to "192.168.137.113",
        "fixed_remote_ipv4" to Profile4VoiceCorrelationContract.MIW2_HOST,
        "card_sha256" to "a".repeat(64),
        "source_commit" to "b".repeat(40),
        "target_apk_sha256" to "c".repeat(64),
        "test_apk_sha256" to "d".repeat(64),
    )

    private fun sampleDone(
        mouthEarProxyMs: Double? = 185.0,
        downNetJitterMs: Double? = 4.5,
        m7MaxFrameGapMs: Double? = 38.0,
        lowConfidence: Boolean = false,
    ) = VoiceRunner.Sample(
        phase = VoiceRunner.Phase.Done,
        rttMs = 42.0,
        jitterMs = 2.5,
        upFrameJitterMs = 5.5,
        downFrameJitterMs = null,
        mouthEarBudgetMs = null,
        framesSent = 150,
        framesRecv = 750,
        progress = 1f,
        ttfbP50Ms = 115.0,
        ttfbP95Ms = 172.0,
        downNetJitterMs = downNetJitterMs,
        mouthEarProxyMs = mouthEarProxyMs,
        turnSwitchP50Ms = 148.0,
        bargeStopMaxMs = 92.0,
        turnsOk = 8,
        caliber = VoiceRunner.SIM_CALIBER,
        lowConfidence = lowConfidence,
        m7MaxFrameGapMs = m7MaxFrameGapMs,
        voiceNearZeroArrivalRatio = 0.015,
    )

    @Test
    fun `parse accepts R B1 B2 and pins miw2 host`() {
        for (cond in listOf("R", "B1", "B2")) {
            val spec = Profile4VoiceCorrelationContract.parse(validArgs(cond))
            assertEquals(cond, spec.conditionId)
            assertEquals(Profile4VoiceCorrelationContract.MIW2_HOST, spec.remoteIp)
            assertEquals("192.168.137.113", spec.phoneIp)
        }
        val withoutRemote = validArgs("B1") - "fixed_remote_ipv4"
        assertEquals(
            Profile4VoiceCorrelationContract.MIW2_HOST,
            Profile4VoiceCorrelationContract.parse(withoutRemote).remoteIp,
        )
    }

    @Test
    fun `parse rejects arbitrary url non-miw2 remote bad condition and bad ipv4`() {
        val urlErr = assertThrows(IllegalArgumentException::class.java) {
            Profile4VoiceCorrelationContract.parse(validArgs() + ("url" to "https://evil.example/"))
        }
        assertTrue(urlErr.message!!.contains("ARBITRARY_URL_FORBIDDEN"))

        val remoteErr = assertThrows(IllegalArgumentException::class.java) {
            Profile4VoiceCorrelationContract.parse(validArgs() + ("fixed_remote_ipv4" to "1.1.1.1"))
        }
        assertTrue(remoteErr.message!!.contains("MIW2_HOST_PIN_MISMATCH"))

        val condErr = assertThrows(IllegalArgumentException::class.java) {
            Profile4VoiceCorrelationContract.parse(validArgs() + ("condition_id" to "B3"))
        }
        assertTrue(condErr.message!!.contains("CONDITION_INVALID"))

        val ipErr = assertThrows(IllegalArgumentException::class.java) {
            Profile4VoiceCorrelationContract.parse(validArgs() + ("expected_phone_ipv4" to "999.1.1.1"))
        }
        assertTrue(ipErr.message!!.contains("IPV4_REQUIRED"))
    }

    @Test
    fun `deadline constant exceeds 100s inside 115s ttl window`() {
        assertEquals(105_000_000_000L, Profile4VoiceCorrelationContract.PHASE_DEADLINE_NANOS)
        assertEquals("https://120.77.174.207:8443", Profile4VoiceCorrelationContract.MIW2_BASE_URL)
    }

    @Test
    fun `buildRawPayloadJson preserves VoiceFormat export AQS_v02 veto and R-10 nulls`() {
        val spec = Profile4VoiceCorrelationContract.parse(validArgs("B2"))
        val rawStr = Profile4VoiceCorrelationContract.buildRawPayloadJson(
            spec = spec,
            startedNanos = 1_000_000_000L,
            finishedNanos = 46_250_000_000L,
            deadlineNanos = 106_000_000_000L,
            tsEpochMs = 1_795_000_000_000L,
            deviceModel = "ELS-AN00",
            androidApi = 31,
            packageName = "com.aneb.probe.codex",
            versionCode = 20L,
            versionName = "0.2.0-codex",
            targetApkSha256 = "c".repeat(64),
            testApkSha256 = "d".repeat(64),
            guardMetadata = mapOf("active_transports" to "WIFI"),
            guardReasons = emptyList(),
            networkHandle = "12345",
            networkId = "101",
            interfaceName = "wlan0",
            localAddresses = listOf("192.168.137.113"),
            postNetwork = buildJsonObject { put("interface", "wlan0") },
            phaseEvents = listOf(Profile4VoiceCorrelationContract.PhaseEvent("VOICE_SIM_START", 1_000_000_000L)),
            cleanup = buildJsonObject { put("network_release_requested", true) },
            termination = "VOICE_SIM_DONE",
            failure = null,
            pathInvalidReason = null,
            doneSample = sampleDone(mouthEarProxyMs = 450.0),
        )
        val obj = Json.parseToJsonElement(rawStr).jsonObject
        assertEquals(Profile4VoiceCorrelationContract.RAW_FORMAT, obj["format"]?.jsonPrimitive?.content)
        assertEquals("B2", obj["condition_id"]?.jsonPrimitive?.content)
        assertEquals(45250.0, obj["duration_ms"]?.jsonPrimitive?.double ?: -1.0, 1e-3)

        val export = obj["voice_export"]!!.jsonObject
        assertEquals("voice-export-1.0", export["schema_version"]?.jsonPrimitive?.content)
        val rec = export["records"]!!.jsonArray[0].jsonObject
        assertEquals(JsonNull, rec["down_frame_jitter_ms"])
        assertEquals(8, rec["turns_ok"]?.jsonPrimitive?.int)
        assertEquals(450.0, rec["mouth_ear_proxy_ms"]?.jsonPrimitive?.double ?: -1.0, 1e-6)

        val aqs = obj["aqs_v02"]!!.jsonObject
        assertEquals(AqsScorer.AQS_VERSION_VOICE_SIM_V02, aqs["aqs_version"]?.jsonPrimitive?.content)
        assertTrue(aqs["veto_applied"]!!.jsonPrimitive.boolean)
        val score = aqs["score"]!!.jsonPrimitive.double
        assertTrue(score <= AqsScorer.T4_VETO_CAP)
        assertEquals(9, aqs["sub_scores"]!!.jsonObject.size)

        // R-10 KPI_MISSING propagation when downNetJitterMs is null
        val missingStr = Profile4VoiceCorrelationContract.buildRawPayloadJson(
            spec = spec,
            startedNanos = 1L,
            finishedNanos = 2L,
            deadlineNanos = 3L,
            tsEpochMs = 1L,
            deviceModel = "ELS-AN00",
            androidApi = 31,
            packageName = null,
            versionCode = null,
            versionName = null,
            targetApkSha256 = null,
            testApkSha256 = null,
            guardMetadata = emptyMap(),
            guardReasons = emptyList(),
            networkHandle = null,
            networkId = null,
            interfaceName = null,
            localAddresses = emptyList(),
            postNetwork = null,
            phaseEvents = emptyList(),
            cleanup = buildJsonObject {},
            termination = "VOICE_SIM_DONE",
            failure = null,
            pathInvalidReason = null,
            doneSample = sampleDone(downNetJitterMs = null),
        )
        val missingObj = Json.parseToJsonElement(missingStr).jsonObject
        val missingAqs = missingObj["aqs_v02"]!!.jsonObject
        assertEquals(JsonNull, missingAqs["score"])
        assertFalse(missingAqs["veto_applied"]!!.jsonPrimitive.boolean)
        assertNotNull(missingAqs["not_computable_reason"])
        assertEquals("KPI_MISSING:M2", missingAqs["not_computable_reason"]?.jsonPrimitive?.content)
    }
}
