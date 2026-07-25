package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeContractRejectionDurabilityTest {
    @Test
    fun `missing receipt persists one invalid zero-business result before returning`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingCapabilityTransport()
        val logs = mutableListOf("REALTIME_V1_START run_id=$RUN_ID variant=quick server=https://aneb.test")
        val stored = mutableListOf<RealtimeDurableResult>()
        var published: RealtimeDurableResult? = null
        val publisher = RealtimeContractFailurePublisher(
            runId = RUN_ID,
            startedAt = 1_000L,
            variant = "quick",
            source = RealtimeResultEnvelopeSource(profile = profile),
            producer = AnebResultProducerContext("aneb-probe-android", "test", "test"),
            device = AnebResultDeviceContext(
                "Huawei", "P40 Pro", "12", 31,
                "com.aneb.probe.codex", "test", 45,
            ),
            network = AnebResultNetworkContext(
                "auto", "wifi", emptyList(), "wlan0",
                true, true, false, false, "off",
            ),
            endedAtEpochMs = { 2_000L },
            radio = { FormalRadioEvidence.notCollected("test_fixture") },
            resultCommitter = DurableResultCommitter(
                store = DurableResultStore { stored += it },
                publish = { published = it },
            ),
        )

        val authorization = RealtimeExecutionContractRunner.authorize(
            runId = RUN_ID,
            serverBase = "https://aneb.test",
            profile = profile,
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
            log = { logs += it },
            failurePublisher = publisher,
        )

        assertNull(authorization)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
        assertEquals(1, stored.size)
        assertSame(stored.single(), published)

        val durable = stored.single()
        val result = durable.result
        assertEquals("receipt_missing", result.evidence.invalidReason)
        assertTrue(result.evidence.sessions.isEmpty())
        assertNull(result.score.totalScore)
        assertNull(result.score.grade)
        assertEquals(TokenVerdict.INVALID, result.score.verdict)
        assertTrue(result.score.metrics.values.all { it.sampleCount == 0 })

        val envelope = Json.parseToJsonElement(durable.envelope.bodyJson).jsonObject
        val raw = envelope.getValue("category_payload").jsonObject
            .getValue("raw_evidence").jsonObject
        assertEquals("receipt_missing", raw.getValue("invalid_reason").jsonPrimitive.content)
        assertTrue(raw.getValue("sessions").jsonArray.isEmpty())
        assertEquals(
            listOf(
                "REALTIME_V1_START",
                "REALTIME_V1_RADIO",
                "REALTIME_V1_DB_WRITE",
                "REALTIME_V1_CONTRACT",
                "REALTIME_V1_END",
            ),
            logs.map { it.substringBefore(' ') },
        )
        assertTrue(logs.all { "run_id=$RUN_ID" in it })
        assertEquals(
            "REALTIME_V1_RADIO run_id=$RUN_ID status=not_collected samples=0",
            logs[1],
        )
        assertEquals("REALTIME_V1_DB_WRITE run_id=$RUN_ID ok=true", logs[2])
        assertTrue(
            logs[3].startsWith(
                "REALTIME_V1_CONTRACT run_id=$RUN_ID status=rejected reason=receipt_missing ",
            ),
        )
        assertEquals(
            "REALTIME_V1_END run_id=$RUN_ID status=contract_rejected",
            logs[4],
        )
    }

    @Test
    fun `post-commit logging failure is marked persisted and never retries insert`() = runBlocking {
        val transport = CountingCapabilityTransport()
        var inserts = 0
        val publisher = failurePublisher(
            DurableResultCommitter(
                store = DurableResultStore<RealtimeDurableResult> { inserts++ },
                publish = {},
            ),
        )

        try {
            RealtimeExecutionContractRunner.authorize(
                runId = RUN_ID,
                serverBase = "https://aneb.test",
                profile = requiredProfile(),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = transport,
                log = { error("log sink failed") },
                failurePublisher = publisher,
            )
            throw AssertionError("expected post-commit logging failure")
        } catch (error: IllegalStateException) {
            assertEquals("realtime_contract_rejection_already_persisted", error.message)
        }

        assertEquals(1, inserts)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    private class CountingCapabilityTransport : ExecutionCapabilityTransport {
        var serverInfoRequests = 0
        var businessRequests = 0

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, """{"version":"aneb-server/test"}""", null)
        }
    }

    private fun requiredProfile() = ScenarioProfile(
        profileId = "ai_realtime_voice_quick",
        version = "1.1.1",
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                "aneb-realtime-simulation-engine", "1.0.0", "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                "aneb-server-capability-receipt", "1.0.0", "2.0.0",
            ),
            requiredPrimitives = listOf(
                ProfileExecutionPrimitive("realtime_sim", "aneb-realtime-session-v1"),
            ),
        ),
    )

    private fun failurePublisher(
        committer: DurableResultCommitter<RealtimeDurableResult>,
    ) = RealtimeContractFailurePublisher(
        runId = RUN_ID,
        startedAt = 1_000L,
        variant = "quick",
        source = RealtimeResultEnvelopeSource(profile = requiredProfile()),
        producer = AnebResultProducerContext("aneb-probe-android", "test", "test"),
        device = AnebResultDeviceContext(
            "Huawei", "P40 Pro", "12", 31,
            "com.aneb.probe.codex", "test", 45,
        ),
        network = AnebResultNetworkContext(
            "auto", "wifi", emptyList(), "wlan0",
            true, true, false, false, "off",
        ),
        endedAtEpochMs = { 2_000L },
        radio = { FormalRadioEvidence.notCollected("test_fixture") },
        resultCommitter = committer,
    )

    private companion object {
        const val RUN_ID = "00000000-0000-7000-8000-000000000191"
        const val PROFILE_SHA =
            "sha256:701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
    }
}
