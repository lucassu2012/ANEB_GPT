package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.roundToLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BehaviorTraceRunnerTest {

    private enum class StreamFault { NONE, TRUNCATED, HTTP_500 }

    private lateinit var server: LoopbackV2Server

    @Before
    fun setUp() {
        server = LoopbackV2Server()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun testProfile(minSamples: Int = 1): ScenarioProfile {
        val modelHash = "sha256:test-model-hash"
        val required = listOf(
            "TOK-B01", "TOK-B02", "TOK-B05", "TOK-B07", "TOK-B09", "TOK-B10",
            "TOK-B11", "TOK-B14", "TOK-N03", "TOK-N04", "TOK-N05", "TOK-N06",
        )
        val measurements = listOf(
            metric("TOK-B01", "gte", 0.99, minSamples = minSamples, required = true),
            metric("TOK-B02", "lte", 5000.0, minSamples = minSamples, required = true),
            metric("TOK-B05", "lte", 200.0, minSamples = minSamples, required = true),
            metric("TOK-B07", "gte", 0.95, minSamples = minSamples, required = true),
            metric("TOK-B09", "lte", 0.02, minSamples = minSamples, required = true),
            metric("TOK-B10", "eq", 0.0, minSamples = minSamples, required = true),
            metric("TOK-B11", "gte", 0.99, minSamples = minSamples, required = true),
            metric("TOK-B14", "lte", 0.05, minSamples = minSamples, required = true),
            metric("TOK-N03", "lte", 200.0, minSamples = minSamples, required = true),
            metric("TOK-N04", "lte", 100.0, minSamples = minSamples, required = true),
            metric("TOK-N05", "lte", 0.02, minSamples = minSamples, required = true),
            metric("TOK-N06", "gte", 0.1, minSamples = minSamples, required = true),
            metric("TOK-N07", "gte", 0.1, minSamples = minSamples, required = false),
            metric("TOK-B12", "lte", 5000.0, minSamples = minSamples, required = false),
        )
        return ScenarioProfile(
            profileId = "token_test_v2",
            version = "1.0.0-test",
            contractVersion = ScenarioProfile.CONTRACT_V2,
            modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
            executionTarget = "aneb_probe_simulator",
            claimScope = "application_end_to_end_to_probe_node",
            business = ProfileBusiness(
                categoryId = "token_multimodal",
                label = "Test Multimodal",
                behaviorFeatureIds = listOf("uplink_burst", "stream_continuity"),
                behaviorModelId = "test-model",
                behaviorModelVersion = "0.1.0",
                behaviorModelHash = modelHash,
                calibrationStatus = "hypothesis",
            ),
            measurements = measurements,
            livePresentation = ProfileLivePresentation(
                primaryMetricId = "SIM_TPS_LIVE",
                secondaryMetricIds = listOf("RTT_LIVE"),
                windowMs = 1000,
                uiRefreshMs = 250,
                staleAfterMs = 1500,
                missingBehavior = "show_unavailable_never_zero",
            ),
            evaluation = ProfileEvaluation(
                targetSetId = "targets-v1",
                scorePolicyId = "score-v1",
                conclusionPolicyId = "conclusions-v1",
                requiredMetricIds = required,
                guardrailMetricIds = listOf("TOK-B10"),
                groupWeights = mapOf(
                    "task_completion" to 0.25,
                    "interaction" to 0.30,
                    "multimodal_transfer" to 0.25,
                    "network_stability" to 0.15,
                    "efficiency" to 0.05,
                ),
                missingRequiredMetric = "score_null",
                invalidRun = "retain_raw_suppress_score",
            ),
            trace = ProfileTrace(
                contractVersion = BehaviorTracePlan.CONTRACT_VERSION,
                seed = 42L,
                prng = "pcg32-v1",
            ),
            phases = listOf(
                ProfilePhase(
                    type = ProfilePhase.TYPE_BEHAVIOR_TRACE,
                    modelId = "test-model",
                    modelVersion = "0.1.0",
                    modelHash = modelHash,
                    seed = 42L,
                ),
            ),
        )
    }

    private fun metric(id: String, op: String, value: Double, minSamples: Int, required: Boolean) = ProfileMeasurement(
        metricId = id,
        label = id,
        measurementLevel = "derived",
        formulaId = "$id-v1",
        requiredForScore = required,
        minimumSampleCount = minSamples,
        qualityTarget = ProfileQualityTarget(operator = op, value = value),
    )

    private fun twoTaskTraceJsonl(): String {
        val lines = mutableListOf<String>()
        var idx = 0
        fun add(taskId: String, kind: String, rep: Int, offsetMs: Double, type: String, extra: String = "") {
            val suffix = if (extra.isNotEmpty()) ",$extra" else ""
            lines += """{"trace_contract_version":"aneb-behavior-trace-v1","model_id":"test-model","model_version":"0.1.0","seed":42,"event_index":${idx++},"business_type":"token_multimodal","task_id":"$taskId","workload_kind":"$kind","repetition":$rep,"event_type":"$type","planned_offset_ms":$offsetMs$suffix}"""
        }

        // Task 1: 4KB upload, 10ms processing, 4 tokens over 30ms, 16KB artifact
        add("task-0001", "text", 0, 0.0, "user_action")
        add("task-0001", "text", 0, 0.0, "upload_chunk_plan", """"chunk_index":0,"bytes":4096""")
        add("task-0001", "text", 0, 1.0, "upload_complete_plan", """"bytes":4096,"chunks":1""")
        add("task-0001", "text", 0, 11.0, "processing_end_plan", """"duration_ms":10.0""")
        add("task-0001", "text", 0, 11.0, "token_emit_plan", """"seq":0,"planned_interval_ms":0.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0001", "text", 0, 21.0, "token_emit_plan", """"seq":1,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0001", "text", 0, 31.0, "token_emit_plan", """"seq":2,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0001", "text", 0, 41.0, "token_emit_plan", """"seq":3,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0001", "text", 0, 41.0, "artifact_complete_plan", """"bytes":16384""")
        add("task-0001", "text", 0, 41.0, "task_complete_plan", """"output_tokens":4""")

        // Task 2: 8KB upload, 15ms processing, 4 tokens over 30ms, 32KB artifact
        add("task-0002", "image", 0, 50.0, "user_action")
        add("task-0002", "image", 0, 50.0, "upload_chunk_plan", """"chunk_index":0,"bytes":4096""")
        add("task-0002", "image", 0, 51.0, "upload_chunk_plan", """"chunk_index":1,"bytes":4096""")
        add("task-0002", "image", 0, 52.0, "upload_complete_plan", """"bytes":8192,"chunks":2""")
        add("task-0002", "image", 0, 67.0, "processing_end_plan", """"duration_ms":15.0""")
        add("task-0002", "image", 0, 67.0, "token_emit_plan", """"seq":0,"planned_interval_ms":0.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0002", "image", 0, 77.0, "token_emit_plan", """"seq":1,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0002", "image", 0, 87.0, "token_emit_plan", """"seq":2,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0002", "image", 0, 97.0, "token_emit_plan", """"seq":3,"planned_interval_ms":10.0,"interval_state":"NORMAL","token_bytes":64""")
        add("task-0002", "image", 0, 97.0, "artifact_complete_plan", """"bytes":32768""")
        add("task-0002", "image", 0, 97.0, "task_complete_plan", """"output_tokens":4""")

        return lines.joinToString("\n")
    }

    @Test
    fun `executes genuine 2-task v2 trace end to end over loopback HTTP and SSE`() = runBlocking {
        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        val profile = testProfile(minSamples = 1)
        val plan = BehaviorTracePlan.parse(twoTaskTraceJsonl(), profile)
        val client = AnebClient.createForLoopbackTest(clock)
        val runner = BehaviorTraceRunner(client, clock)

        val logs = runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = plan,
                runId = "v2-e2e-run-1",
                echoSamples = 4,
            ),
        ).toList()

        val result = runner.result.value
        assertNotNull(result)
        result!!
        assertTrue("expected computable=true, reasons=${result.suppressionReasons}", result.computable)
        assertEquals("completed", result.status)
        assertEquals(2, result.tasksExecuted)
        assertEquals(2, result.tasksSucceeded)
        assertEquals(1.0, result.metrics["TOK-B01"]!!, 1e-9)
        assertEquals(1.0, result.metrics["TOK-B11"]!!, 1e-9)
        assertEquals(0.0, result.metrics["TOK-N05"]!!, 1e-9)
        assertTrue(result.metrics["TOK-B02"]!! > 0.0)
        assertTrue(result.metrics["TOK-B04"]!! > 0.0)
        assertTrue(result.metrics["TOK-N03"]!! > 0.0)
        assertTrue(result.metrics["TOK-N06"]!! > 0.0)
        assertTrue(result.metrics["TOK-N07"]!! > 0.0)
        assertTrue(result.actualTotalDurationMs > 0.0)
        assertTrue(result.computable)
        assertNotNull(result.score)

        assertTrue(server.requests.any { it.startsWith("POST /api/v1/echo") })
        assertTrue(server.requests.any { it.startsWith("POST /api/v1/upload?run=v2-e2e-run-1") })
        assertTrue(server.requests.any { it.contains("/api/v1/stream?") && it.contains("tokens=4") && it.contains("ttft_inject_us=10000") && it.contains("rate_tps=") })
        assertTrue(server.requests.any { it.contains("/api/v1/download?bytes=16384") })
        assertTrue(server.requests.any { it.contains("/api/v1/download?bytes=32768") })
        assertTrue(logs.any { it.startsWith("V2_RUN_END") && it.contains("status=completed") })
    }

    @Test
    fun `truncated stream and HTTP 500 fail closed per R-10 and suppress score`() = runBlocking {
        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        // 1. Truncated stream retains partial tokens, drops TOK-B01 & TOK-B11 below 1.0, and sets score == null
        server.streamFault = StreamFault.TRUNCATED
        val profile = testProfile(minSamples = 1)
        val plan = BehaviorTracePlan.parse(twoTaskTraceJsonl(), profile)
        val runnerTrunc = BehaviorTraceRunner(
            AnebClient.createForLoopbackTest(clock),
            clock,
        )
        runnerTrunc.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = plan,
                runId = "v2-trunc-1",
                echoSamples = 3,
                maxTasks = 1,
            ),
        ).toList()
        val truncResult = runnerTrunc.result.value!!
        assertFalse(truncResult.computable)
        assertNull(truncResult.score)
        assertTrue(truncResult.tasks.single().truncatedEarly)
        assertEquals(2, truncResult.tasks.single().receivedUniqueTokens)
        assertEquals(0.0, truncResult.metrics["TOK-B01"]!!, 1e-9)
        assertEquals(0.5, truncResult.metrics["TOK-B11"]!!, 1e-9)

        // 2. HTTP 500 stream never fabricates 0 for missing stream metrics and suppresses score
        server.streamFault = StreamFault.HTTP_500
        val runner500 = BehaviorTraceRunner(
            AnebClient.createForLoopbackTest(clock),
            clock,
        )
        runner500.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = plan,
                runId = "v2-500-1",
                echoSamples = 3,
                maxTasks = 1,
            ),
        ).toList()
        val errResult = runner500.result.value!!
        assertFalse(errResult.computable)
        assertNull(errResult.score)
        assertNull("TOK-B05 must remain null when stream returns 500", errResult.metrics["TOK-B05"])
        assertNull("TOK-B11 must remain null when stream returns 500", errResult.metrics["TOK-B11"])
        assertTrue(errResult.missingRequiredMetrics.contains("TOK-B11"))
        assertTrue(errResult.metrics["TOK-N05"]!! > 0.0)
    }

    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("cannot find $relFromRepoRoot from user.dir")
    }

    private fun scaleTimingForFastLoopbackTest(
        plan: BehaviorTracePlan,
        scale: Double = 0.005,
    ): BehaviorTracePlan = plan.copy(
        totalPlannedSpanMs = plan.totalPlannedSpanMs * scale,
        tasks = plan.tasks.map { task ->
            task.copy(
                uploadChunkCadenceMs = task.uploadChunkCadenceMs * scale,
                processingDelayMs = task.processingDelayMs * scale,
                tokenPlans = task.tokenPlans.map { tp ->
                    tp.copy(
                        plannedOffsetMs = tp.plannedOffsetMs * scale,
                        plannedIntervalMs = tp.plannedIntervalMs * scale,
                    )
                },
                plannedDurationMs = task.plannedDurationMs * scale,
                interTaskGapMs = task.interTaskGapMs * scale,
            )
        },
    )

    @Test
    fun `loads and executes exported 20-task token_multimodal_standard profile end to end with computable score`() = runBlocking {
        val profileJson = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        val traceJsonl = repoFile("profiles/v2/token_multimodal_standard/golden_trace.jsonl").readText(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileJson)
        assertTrue(ProfileCapability.assess(profile).executable)

        val scaledPlan = scaleTimingForFastLoopbackTest(BehaviorTracePlan.parse(traceJsonl, profile), scale = 0.005)
        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        val runner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)

        runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = scaledPlan,
                runId = "v2-real-20task-e2e",
                echoSamples = null,
            ),
        ).toList()

        val result = runner.result.value
        assertNotNull(result)
        result!!
        assertTrue("expected computable=true, reasons=${result.suppressionReasons}", result.computable)
        assertEquals("completed", result.status)
        assertTrue("belowSampleFloor=${result.belowSampleFloorMetrics}", result.belowSampleFloorMetrics.isEmpty())
        assertEquals(20, result.tasks.size)
        assertNotNull(result.score)
        assertTrue("score=${result.score}", result.score!! in 50.0..100.0)
        assertEquals(1.0, result.metrics["TOK-B01"]!!, 1e-9)
        assertTrue("TOK-B07=${result.metrics["TOK-B07"]}", result.metrics["TOK-B07"]!! >= 0.95)
        assertTrue("TOK-B08=${result.metrics["TOK-B08"]}", result.metrics["TOK-B08"]!! <= 100.0)
        assertEquals(0.0, result.metrics["TOK-B10"]!!, 1e-9)
    }

    @Test
    fun `real profile enforces R-10 sample floors fail-closed when maxTasks=1 and echoSamples=5`() = runBlocking {
        val profileJson = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        val traceJsonl = repoFile("profiles/v2/token_multimodal_standard/golden_trace.jsonl").readText(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileJson)
        val scaledPlan = scaleTimingForFastLoopbackTest(BehaviorTracePlan.parse(traceJsonl, profile), scale = 0.005)

        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        val runner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)

        runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = scaledPlan,
                runId = "v2-sample-floor-fail",
                echoSamples = 5,
                maxTasks = 1,
            ),
        ).toList()

        val result = runner.result.value!!
        assertFalse(result.computable)
        assertNull(result.score)
        assertEquals("completed_unscored", result.status)
        assertTrue(result.belowSampleFloorMetrics.containsAll(listOf("TOK-B01", "TOK-N03", "TOK-N04")))
    }

    @Test
    fun `penalizes genuine mid-stream network stall on TOK-B08 TOK-B09 and trips TOK-B10 guardrail`() = runBlocking {
        val profileJson = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        val traceJsonl = repoFile("profiles/v2/token_multimodal_standard/golden_trace.jsonl").readText(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileJson)
        val scaledPlan = scaleTimingForFastLoopbackTest(BehaviorTracePlan.parse(traceJsonl, profile), scale = 0.005)

        // Inject >5% periodic stalls (>=150ms) plus one >1000ms severe stall across task-0001 (320 tokens)
        server.streamStallAtStreamIndex = 0
        server.streamStallDelayProvider = { seq ->
            when {
                seq == 150 -> 1200L
                seq > 0 && seq % 12 == 0 -> 150L
                else -> 0L
            }
        }

        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        val runner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)

        runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = scaledPlan,
                runId = "v2-stall-guardrail",
                echoSamples = null,
                maxTasks = 1,
            ),
        ).toList()

        val result = runner.result.value!!
        assertTrue("TOK-B08=${result.metrics["TOK-B08"]}", result.metrics["TOK-B08"]!! > 100.0)
        assertTrue("TOK-B09=${result.metrics["TOK-B09"]}", result.metrics["TOK-B09"]!! > 0.0)
        assertTrue("TOK-B10=${result.metrics["TOK-B10"]}", result.metrics["TOK-B10"]!! > 0.0)
        assertTrue(result.guardrailViolated)
        assertFalse(result.computable)
        assertNull(result.score)
        assertTrue(result.suppressionReasons.contains("guardrail_violated"))
    }

    @Test
    fun `scores TOK-B12 by artifact deadline TOK-B02 and TOK-N06 per workload and fails closed on unknown policy or eq mismatch`() = runBlocking {
        val baseProfile = testProfile(minSamples = 1)
        val v2WorkloadProfile = baseProfile.copy(
            measurements = baseProfile.measurements.map { m ->
                when (m.metricId) {
                    "TOK-B02" -> m.copy(
                        qualityTarget = ProfileQualityTarget(
                            operator = "lte_by_workload",
                            values = mapOf("text" to 1000.0, "document_5mib" to 6000.0, "image" to 10000.0),
                        ),
                    )
                    "TOK-N06" -> m.copy(
                        qualityTarget = ProfileQualityTarget(
                            operator = "gte_by_workload",
                            values = mapOf("text" to 1.0, "document" to 10.0, "image" to 12.0),
                        ),
                    )
                    "TOK-N07" -> m.copy(qualityTarget = ProfileQualityTarget(operator = "gte", value = 25.0))
                    "TOK-B12" -> m.copy(
                        qualityTarget = ProfileQualityTarget(
                            operator = "deadline_by_artifact_size",
                            policyId = "artifact-deadline-v1",
                        ),
                    )
                    else -> m
                }
            },
        )
        val plan = BehaviorTracePlan.parse(twoTaskTraceJsonl(), v2WorkloadProfile)
        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }

        // 1. Fast run baseline
        val fastRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        fastRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = v2WorkloadProfile,
                tracePlan = plan,
                runId = "v2-b12-fast",
                echoSamples = 4,
            ),
        ).toList()
        val fastRes = fastRunner.result.value!!
        assertNotNull(fastRes.score)

        // 2. Slow artifact download triggers genuine TOK-B12 penalty (deadline ~1005ms for 16KB at 25Mbps)
        server.downloadDelayMs = 2500L
        val slowRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        slowRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = v2WorkloadProfile,
                tracePlan = plan,
                runId = "v2-b12-slow",
                echoSamples = 4,
            ),
        ).toList()
        server.downloadDelayMs = 0L
        val slowRes = slowRunner.result.value!!
        assertTrue("TOK-B12=${slowRes.metrics["TOK-B12"]}", slowRes.metrics["TOK-B12"]!! >= 2400.0)
        assertNotNull(slowRes.score)
        assertTrue("slow score=${slowRes.score} must be < fast score=${fastRes.score}", slowRes.score!! < fastRes.score!!)

        // 3. Unknown TOK-B12 policyId fails closed (score == null)
        val badPolicyProfile = v2WorkloadProfile.copy(
            measurements = v2WorkloadProfile.measurements.map { m ->
                if (m.metricId == "TOK-B12") {
                    m.copy(qualityTarget = m.qualityTarget?.copy(policyId = "unknown-policy"))
                } else m
            },
        )
        val badPolicyRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        badPolicyRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = badPolicyProfile,
                tracePlan = plan,
                runId = "v2-b12-bad-policy",
                echoSamples = 4,
            ),
        ).toList()
        assertNull(badPolicyRunner.result.value!!.score)

        // 4. Mixed-workload TOK-B02 alias fallback ("document_5mib") passes 2000ms document upload (even though > text 1000ms)
        val docAndTextJsonl = twoTaskTraceJsonl().replace("\"workload_kind\":\"image\"", "\"workload_kind\":\"document\"")
        val docPlan = BehaviorTracePlan.parse(docAndTextJsonl, v2WorkloadProfile)
        server.resetCounters()
        server.uploadDelayByRequestIndex[1] = 2000L // 2nd upload ("document") takes 2000ms (<= 6000ms, > text 1000ms)
        val docRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        docRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = v2WorkloadProfile,
                tracePlan = docPlan,
                runId = "v2-mixed-doc",
                echoSamples = 4,
            ),
        ).toList()
        server.uploadDelayByRequestIndex.clear()
        val docRes = docRunner.result.value!!
        assertNotNull(docRes.score)
        assertTrue("doc upload latency=${docRes.tasks[1].uploadLatencyMs}", (docRes.tasks[1].uploadLatencyMs ?: 0.0) >= 1900.0)

        // 4.5 Mixed-workload TOK-N06: "text" at ~1.09 Mbps (>= text:1.0) and "image" at ~1.01 Mbps (>= text:1.0, < image:12.0) drops composite score
        server.resetCounters()
        server.uploadDelayByRequestIndex[0] = 30L // 4096 B / 30 ms ~= 1.09 Mbps (>= text:1.0)
        server.uploadDelayByRequestIndex[1] = 65L // 8192 B / 65 ms ~= 1.01 Mbps (>= text:1.0, < image:12.0)
        val slowImgRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        slowImgRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = v2WorkloadProfile,
                tracePlan = plan,
                runId = "v2-mixed-img-slow-n06",
                echoSamples = 4,
            ),
        ).toList()
        server.uploadDelayByRequestIndex.clear()
        val slowImgRes = slowImgRunner.result.value!!
        assertNotNull(slowImgRes.score)
        assertTrue(
            "slow image goodput score=${slowImgRes.score} must be < fast score=${fastRes.score}",
            slowImgRes.score!! < fastRes.score!!,
        )

        // 5. Unknown workload kind absent from target.values fails closed (score == null)
        val unknownWorkloadJsonl = twoTaskTraceJsonl().replace("\"workload_kind\":\"image\"", "\"workload_kind\":\"audio_3d\"")
        val unknownPlan = BehaviorTracePlan.parse(unknownWorkloadJsonl, v2WorkloadProfile)
        val unknownWorkloadRunner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        unknownWorkloadRunner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = v2WorkloadProfile,
                tracePlan = unknownPlan,
                runId = "v2-unknown-workload",
                echoSamples = 4,
            ),
        ).toList()
        assertNull(unknownWorkloadRunner.result.value!!.score)

        // 6. Direct scoreAgainstTarget checks for exact "eq" and unknown operator
        assertEquals(0.0, BehaviorTraceRunner.scoreAgainstTarget(0.5, ProfileQualityTarget(operator = "eq", value = 1.0))!!, 1e-9)
        assertEquals(100.0, BehaviorTraceRunner.scoreAgainstTarget(1.0, ProfileQualityTarget(operator = "eq", value = 1.0))!!, 1e-9)
        assertNull(BehaviorTraceRunner.scoreAgainstTarget(1.0, ProfileQualityTarget(operator = "unknown_operator", value = 1.0)))
    }

    @Test
    fun `uploadTraceStream cancellation aborts immediately resets result and BehaviorTracePlan rejects interleaved task_id`() = runBlocking {
        val origin = System.nanoTime() - 1_000_000_000L
        val clock = MonotonicNanosClock { System.nanoTime() - origin }
        val client = AnebClient.createForLoopbackTest(clock)

        // 1. Cancellation during 5_000ms chunkCadenceMs aborts in < 500ms
        val t0 = System.nanoTime()
        val job = launch {
            client.uploadTraceStream(
                url = "${server.baseUrl}/api/v1/upload?run=cancel-test",
                totalBytes = 4096L,
                chunkSizes = listOf(1024, 1024, 1024, 1024),
                chunkCadenceMs = 5_000L,
            )
        }
        delay(80L)
        job.cancel()
        job.join()
        val cancelElapsedMs = (System.nanoTime() - t0) / 1e6
        assertTrue("cancellation took ${cancelElapsedMs}ms (expected < 500ms)", cancelElapsedMs < 500.0)

        // 2. Starting a second runner.run clears _result.value = null immediately
        val profile = testProfile(minSamples = 1)
        val plan = BehaviorTracePlan.parse(twoTaskTraceJsonl(), profile)
        val runner = BehaviorTraceRunner(AnebClient.createForLoopbackTest(clock), clock)
        runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = plan,
                runId = "v2-reset-1",
                echoSamples = 3,
                maxTasks = 1,
            ),
        ).toList()
        assertNotNull(runner.result.value)

        // Observe inside collection of a second run before completion
        var seenResetToNull = false
        runner.run(
            BehaviorTraceRunner.Config(
                serverBase = server.baseUrl,
                profile = profile,
                tracePlan = plan,
                runId = "v2-reset-2",
                echoSamples = 3,
                maxTasks = 1,
            ),
        ).first {
            seenResetToNull = runner.result.value == null
            true
        }
        assertTrue("_result.value must be cleared to null at start of run()", seenResetToNull)

        // 3. Interleaved task_id sequence rejected by BehaviorTracePlan.parse
        val interleaved = listOf(
            """{"trace_contract_version":"aneb-behavior-trace-v1","model_id":"test-model","model_version":"0.1.0","seed":42,"event_index":0,"task_id":"task-0001","workload_kind":"text","event_type":"user_action","planned_offset_ms":0.0}""",
            """{"trace_contract_version":"aneb-behavior-trace-v1","model_id":"test-model","model_version":"0.1.0","seed":42,"event_index":1,"task_id":"task-0002","workload_kind":"text","event_type":"user_action","planned_offset_ms":10.0}""",
            """{"trace_contract_version":"aneb-behavior-trace-v1","model_id":"test-model","model_version":"0.1.0","seed":42,"event_index":2,"task_id":"task-0001","workload_kind":"text","event_type":"task_complete_plan","planned_offset_ms":20.0}""",
        ).joinToString("\n")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BehaviorTracePlan.parse(interleaved, profile)
        }
        assertTrue(ex.message?.contains("interleaved_task_id") == true)
    }

    private class LoopbackV2Server : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<String>()
        private val streamCounter = AtomicInteger(0)
        private val uploadCounter = AtomicInteger(0)
        @Volatile var streamFault = StreamFault.NONE
        @Volatile var streamStallAtStreamIndex: Int? = null
        @Volatile var streamStallDelayProvider: ((Int) -> Long)? = null
        @Volatile var downloadDelayMs: Long = 0L
        val uploadDelayByRequestIndex = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        @Volatile private var closed = false
        val baseUrl = "http://127.0.0.1:${listener.localPort}"

        fun resetCounters() {
            streamCounter.set(0)
            uploadCounter.set(0)
        }

        private val acceptThread = thread(isDaemon = true, name = "v2-loopback-accept") {
            while (!closed) {
                val socket = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true, name = "v2-loopback-conn") { handle(socket) }
            }
        }

        private fun handle(socket: java.net.Socket) {
            try {
                socket.use { s ->
                    s.soTimeout = 30_000
                    val input = BufferedInputStream(s.getInputStream())
                    val reqLine = readLineAscii(input) ?: return
                    var contentLength = 0
                    while (true) {
                        val line = readLineAscii(input) ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        var left = contentLength
                        val buf = ByteArray(8192)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, left))
                            if (n <= 0) break
                            left -= n
                        }
                    }
                    val parts = reqLine.split(' ')
                    val method = parts.getOrElse(0) { "" }
                    val target = parts.getOrElse(1) { "" }
                    requests.add("$method $target")
                    val out = s.getOutputStream()

                    when {
                        target.startsWith("/api/v1/echo") -> {
                            Thread.sleep(2)
                            val body = """{"t1_us":1000,"t2_us":1500,"observed":"127.0.0.1:12345"}""".toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nX-Aneb-Proto: HTTP/1.1;via=tcp\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.write(body)
                            out.flush()
                        }
                        target.startsWith("/api/v1/upload") -> {
                            val upIdx = uploadCounter.getAndIncrement()
                            val delayMs = uploadDelayByRequestIndex[upIdx] ?: 0L
                            if (delayMs > 0L) Thread.sleep(delayMs)
                            val body = """{"bytes":$contentLength,"recv_start_us":1000,"recv_end_us":3000,"chunk_us":[1000,3000],"observed":"127.0.0.1:12345"}""".toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nX-Aneb-Proto: HTTP/1.1;via=tcp\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.write(body)
                            out.flush()
                        }
                        target.startsWith("/api/v1/stream") -> {
                            if (streamFault == StreamFault.HTTP_500) {
                                val err = "sim_500".toByteArray(Charsets.UTF_8)
                                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: ${err.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                out.write(err)
                                out.flush()
                                return
                            }
                            val streamIdx = streamCounter.getAndIncrement()
                            val tokens = Regex("[?&]tokens=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull() ?: 4
                            val rateTps = Regex("[?&]rate_tps=([0-9.]+)").find(target)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.1, 100_000.0) ?: 50.0
                            val ttftInjectUs = Regex("[?&]ttft_inject_us=(\\d+)").find(target)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0L, 10_000_000L) ?: 0L
                            val startUs = 1_000L
                            val startNs = System.nanoTime()

                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Aneb-Proto: HTTP/1.1;via=tcp\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.write(": prelude {\"srv_ts_us\":$startUs,\"ttft_inject_us\":$ttftInjectUs}\n\n".toByteArray(Charsets.UTF_8))
                            out.flush()

                            val payloadB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 'A'.code.toByte() })
                            val emitCount = if (streamFault == StreamFault.TRUNCATED) minOf(2, tokens) else tokens
                            val stallStream = streamStallAtStreamIndex == null || streamStallAtStreamIndex == streamIdx
                            for (seq in 0 until emitCount) {
                                val relUs = ttftInjectUs + (seq * 1_000_000.0 / rateTps).roundToLong()
                                val targetNs = startNs + relUs * 1_000L
                                val waitNs = targetNs - System.nanoTime()
                                if (waitNs > 0L) {
                                    if (waitNs > 2_000_000L) {
                                        Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                                    } else {
                                        LockSupport.parkNanos(waitNs)
                                    }
                                }
                                val schedUs = startUs + relUs
                                val preFlushUs = startUs + ((System.nanoTime() - startNs) / 1_000L).coerceAtLeast(0L)
                                val stallMs = if (stallStream) (streamStallDelayProvider?.invoke(seq) ?: 0L) else 0L
                                if (stallMs > 0L) {
                                    // Stamp pre_flush_us before sleeping to simulate a genuine mid-stream network stall
                                    Thread.sleep(stallMs)
                                }
                                val ev = "event: token\ndata: {\"seq\":$seq,\"sched_us\":$schedUs,\"pre_flush_us\":$preFlushUs,\"payload\":\"$payloadB64\"}\n\n"
                                out.write(ev.toByteArray(Charsets.UTF_8))
                                out.flush()
                            }
                            if (streamFault == StreamFault.NONE) {
                                out.write("event: summary\ndata: {\"sent\":$tokens}\n\n".toByteArray(Charsets.UTF_8))
                                out.flush()
                            }
                        }
                        target.startsWith("/api/v1/download") -> {
                            val dDelay = downloadDelayMs
                            if (dDelay > 0L) Thread.sleep(dDelay)
                            val bytes = Regex("[?&]bytes=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull() ?: 4096
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $bytes\r\nX-Aneb-Proto: HTTP/1.1;via=tcp\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.write(ByteArray(bytes) { 'D'.code.toByte() })
                            out.flush()
                        }
                        else -> {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore socket errors during test teardown.
            }
        }

        private fun readLineAscii(input: BufferedInputStream): String? {
            val baos = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (baos.size() == 0) null else baos.toString(Charsets.US_ASCII.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) baos.write(b)
            }
            return baos.toString(Charsets.US_ASCII.name())
        }

        override fun close() {
            closed = true
            runCatching { listener.close() }
            acceptThread.join(3_000L)
        }
    }
}
