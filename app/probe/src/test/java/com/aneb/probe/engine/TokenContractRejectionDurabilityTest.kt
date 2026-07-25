package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimArrival
import com.aneb.probe.net.TokenSimPrelude
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenContractRejectionDurabilityTest {
    @Test
    fun `contract rejection is persisted before a cancelled log can abort the flow`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingTransport()
        var stored: TokenDurableResult? = null
        val failurePublisher = failurePublisher(
            profile = profile,
            committer = DurableResultCommitter(
                store = DurableResultStore<TokenDurableResult> { stored = it },
                publish = {},
            ),
        )

        try {
            TokenExecutionContractRunner.authorize(
                runId = RUN_ID,
                serverBase = "https://aneb.test",
                profile = profile,
                profileCanonicalSha256 = PROFILE_SHA,
                transport = transport,
                log = { throw CancellationException("collector stopped") },
                failurePublisher = failurePublisher,
            )
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation remains observable, but it must not outrun terminal persistence.
        }

        assertEquals("receipt_missing", requireNotNull(stored).result.evidence.invalidReason)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `a post-commit logging failure never retries the terminal insert`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingTransport()
        var insertCount = 0
        val failurePublisher = failurePublisher(
            profile = profile,
            committer = DurableResultCommitter(
                store = DurableResultStore<TokenDurableResult> { insertCount++ },
                publish = {},
            ),
        )

        try {
            TokenExecutionContractRunner.authorize(
                runId = RUN_ID,
                serverBase = "https://aneb.test",
                profile = profile,
                profileCanonicalSha256 = PROFILE_SHA,
                transport = transport,
                log = { error("log sink failed") },
                failurePublisher = failurePublisher,
            )
            throw AssertionError("expected post-commit logging failure")
        } catch (error: IllegalStateException) {
            assertEquals("token_contract_rejection_already_persisted", error.message)
        }

        assertEquals(1, insertCount)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `the fourth post-commit log failure remains marked already persisted`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingTransport()
        var insertCount = 0
        var logCount = 0
        val failurePublisher = failurePublisher(
            profile = profile,
            committer = DurableResultCommitter(
                store = DurableResultStore<TokenDurableResult> { insertCount++ },
                publish = {},
            ),
        )

        try {
            TokenExecutionContractRunner.authorize(
                runId = RUN_ID,
                serverBase = ACTUAL_MEASURE_BASE,
                profile = profile,
                profileCanonicalSha256 = PROFILE_SHA,
                transport = transport,
                log = {
                    logCount++
                    if (logCount == 4) error("final log sink failed")
                },
                failurePublisher = failurePublisher,
            )
            throw AssertionError("expected fourth post-commit logging failure")
        } catch (error: IllegalStateException) {
            assertEquals("token_contract_rejection_already_persisted", error.message)
        }

        assertEquals(4, logCount)
        assertEquals(1, insertCount)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `contract rejection persists the actual measured endpoint`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingTransport()
        var stored: TokenDurableResult? = null
        val failurePublisher = failurePublisher(
            profile = profile,
            committer = DurableResultCommitter(
                store = DurableResultStore<TokenDurableResult> { stored = it },
                publish = {},
            ),
        )

        val traffic = TokenExecutionContractRunner.authorize(
            runId = RUN_ID,
            serverBase = ACTUAL_MEASURE_BASE,
            profile = profile,
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
            log = {},
            failurePublisher = failurePublisher,
        )

        assertNull(traffic)
        assertEquals(ACTUAL_MEASURE_BASE, requireNotNull(stored).result.serverBase)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `missing receipt catch durably preserves machine reason before business traffic`() = runBlocking {
        val profile = requiredProfile()
        val transport = CountingTransport()
        val logs = mutableListOf(
            "TOKEN_V2_START run_id=$RUN_ID variant=quick server=https://aneb.test",
        )
        var stored: TokenDurableResult? = null
        var published: TokenDurableResult? = null
        val committer = DurableResultCommitter(
            store = DurableResultStore<TokenDurableResult> { stored = it },
            publish = { published = it },
        )
        val failurePublisher = TokenContractFailurePublisher(
            runId = RUN_ID,
            startedAt = 1_000L,
            variant = "quick",
            source = TokenResultEnvelopeSource(profile = profile),
            producer = AnebResultProducerContext("aneb-probe-android", "test", "test"),
            device = AnebResultDeviceContext(
                "Huawei", "P40 Pro", "12", 31,
                "com.aneb.probe.codex", "test", 44,
            ),
            network = AnebResultNetworkContext(
                "auto", "wifi", emptyList(), "wlan0",
                true, true, false, false, "off",
            ),
            endedAtEpochMs = { 2_000L },
            radio = { FormalRadioEvidence.notCollected("test_fixture") },
            resultCommitter = committer,
        )

        val traffic = TokenExecutionContractRunner.authorize(
            runId = RUN_ID,
            serverBase = "https://aneb.test",
            profile = profile,
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
            log = { logs += it },
            failurePublisher = failurePublisher,
        )
        assertNull(traffic)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
        assertEquals(
            listOf(
                "TOKEN_V2_START",
                "TOKEN_V2_RADIO",
                "TOKEN_V2_DB_WRITE",
                "TOKEN_V2_CONTRACT",
                "TOKEN_V2_END",
            ),
            logs.map { it.substringBefore(' ') },
        )
        assertTrue(logs.all { "run_id=$RUN_ID" in it })
        assertEquals(
            "TOKEN_V2_RADIO run_id=$RUN_ID status=not_collected samples=0",
            logs[1],
        )
        assertEquals(
            "TOKEN_V2_DB_WRITE run_id=$RUN_ID ok=true",
            logs[2],
        )
        assertTrue(
            logs[3].startsWith(
                "TOKEN_V2_CONTRACT run_id=$RUN_ID status=rejected reason=receipt_missing ",
            ),
        )
        assertEquals(
            "TOKEN_V2_END run_id=$RUN_ID status=contract_rejected",
            logs[4],
        )

        val durable = requireNotNull(stored)
        assertEquals(durable, published)
        val result = durable.result
        assertEquals("receipt_missing", result.evidence.invalidReason)
        assertTrue(result.evidence.tasks.isEmpty())
        assertTrue(result.evidence.rttSamplesMs.isEmpty())
        assertNull(result.score.totalScore)
        assertNull(result.score.grade)
        assertEquals(TokenVerdict.INVALID, result.score.verdict)
        assertEquals(TokenConfidence.INVALID, result.score.confidence)
        assertEquals("receipt_missing", result.score.capReason)
        assertEquals("receipt_missing", result.score.notComputableReason)
        assertTrue(result.score.metrics.isEmpty())
        assertTrue(result.score.groupScores.isEmpty())

        val row = tokenSimulationResultEntity(result)
        assertNull(row.totalScore)
        assertNull(row.grade)
        assertEquals("INVALID", row.verdict)
        assertEquals("INVALID", row.confidence)
        assertEquals("receipt_missing", row.capReason)
        assertEquals(emptySet<String>(), Json.parseToJsonElement(row.metricsJson).jsonObject.keys)
        assertTrue(
            Json.parseToJsonElement(row.evidenceJson).jsonObject
                .getValue("tasks").jsonArray.isEmpty(),
        )

        val root = Json.parseToJsonElement(durable.envelope.bodyJson).jsonObject
        assertEquals(
            "receipt_missing",
            root.getValue("run").jsonObject.getValue("invalid_reason_codes")
                .jsonArray.single().jsonPrimitive.content,
        )
        val score = root.getValue("evaluation").jsonObject.getValue("score").jsonObject
        assertEquals(JsonNull, score.getValue("value"))
        assertEquals(JsonNull, score.getValue("grade"))
        assertEquals("invalid", score.getValue("verdict").jsonPrimitive.content)
        assertEquals("invalid", score.getValue("confidence").jsonPrimitive.content)
        assertEquals("receipt_missing", score.getValue("cap_reason").jsonPrimitive.content)
        assertEquals(
            "receipt_missing",
            score.getValue("not_computable_reason").jsonPrimitive.content,
        )
        assertTrue(
            root.getValue("category_payload").jsonObject.getValue("raw_evidence")
                .jsonObject.getValue("tasks").jsonArray.isEmpty(),
        )
    }

    private class CountingTransport : TokenExecutionTransport {
        var serverInfoRequests = 0
        var echoRequests = 0
        var tokenSimRequests = 0
        var downloadRequests = 0
        val businessRequests: Int get() = echoRequests + tokenSimRequests + downloadRequests

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, """{"version":"aneb-server/test"}""", null)
        }

        override suspend fun echo(url: String): AnebClient.EchoResult {
            echoRequests++
            error("business request must not run")
        }

        override suspend fun tokenSim(
            url: String,
            plan: TokenSimTaskPlan,
            uploadChunkBytes: Int,
            uploadChunkCadenceMs: Double,
            onUploadBytes: (Long, Long) -> Unit,
            onPrelude: (TokenSimPrelude, Long) -> Unit,
            onToken: (TokenSimArrival) -> Unit,
        ): TokenSimTaskResult {
            tokenSimRequests++
            error("business request must not run")
        }

        override suspend fun downloadThroughput(
            url: String,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult {
            downloadRequests++
            error("business request must not run")
        }
    }

    private fun failurePublisher(
        profile: ScenarioProfile,
        committer: DurableResultCommitter<TokenDurableResult>,
    ) = TokenContractFailurePublisher(
        runId = RUN_ID,
        startedAt = 1_000L,
        variant = "quick",
        source = TokenResultEnvelopeSource(profile = profile),
        producer = AnebResultProducerContext("aneb-probe-android", "test", "test"),
        device = AnebResultDeviceContext(
            "Huawei", "P40 Pro", "12", 31,
            "com.aneb.probe.codex", "test", 44,
        ),
        network = AnebResultNetworkContext(
            "auto", "wifi", emptyList(), "wlan0",
            true, true, false, false, "off",
        ),
        endedAtEpochMs = { 2_000L },
        radio = { FormalRadioEvidence.notCollected("test_fixture") },
        resultCommitter = committer,
    )

    private fun requiredProfile() = ScenarioProfile(
        profileId = "token_multimodal_quick",
        version = "1.2.1",
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                "aneb-token-simulation-engine", "1.0.0", "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                "aneb-server-capability-receipt", "1.0.0", "2.0.0",
            ),
            requiredPrimitives = listOf(
                ProfileExecutionPrimitive("echo", "aneb-echo-v1"),
                ProfileExecutionPrimitive("token_sim", "aneb-token-task-v1"),
                ProfileExecutionPrimitive("download", "aneb-download-v1"),
            ),
        ),
    )

    private companion object {
        const val RUN_ID = "00000000-0000-7000-8000-000000000182"
        const val ACTUAL_MEASURE_BASE = "https://203.0.113.10:8443"
        const val PROFILE_SHA =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
