package com.aneb.probe.prototype

import com.aneb.probe.net.MonotonicNanosClock
import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PrototypeQuickCampaignRunnerTest {
    @Test
    fun quickCampaignRunsBaselineSlowUnstableSequentiallyAndReturnsCampaignSummary() = runBlocking {
        val observedRuns = mutableListOf<PrototypeQuickCampaignRunner.RunPlan>()
        var runInFlight = false
        val runner = PrototypeQuickCampaignRunner(
            runIdFactory = { index -> "run-quick-0$index" },
            executeRun = { plan ->
                assertFalse("Quick runs overlapped", runInFlight)
                runInFlight = true
                yield()
                observedRuns += plan
                runInFlight = false
                PrototypeQuickCampaignRunner.RunResult.completeForTest(
                    runIndex = plan.runIndex,
                    runId = plan.runId,
                    conditionId = plan.conditionId,
                    streamResult = testStreamResult(plan.runIndex),
                )
            },
            waitBetweenRuns = { _ -> },
        )

        val result = runner.run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = "campaign-quick-g2a",
        )

        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            observedRuns.map { it.conditionId },
        )
        assertEquals(listOf(1, 2, 3), observedRuns.map { it.runIndex })
        assertTrue(observedRuns.all { it.endpoint == "http://127.0.0.1:18088/api/v1/prototype/runs" })

        observedRuns.forEach { run ->
            val request = Json.parseToJsonElement(run.requestBody).jsonObject
            assertEquals("prototype-stream-0.1", request.getValue("protocol_version").jsonPrimitive.content)
            assertEquals("campaign-quick-g2a", request.getValue("campaign_id").jsonPrimitive.content)
            assertEquals(run.runId, request.getValue("run_id").jsonPrimitive.content)
            assertEquals("quick", request.getValue("campaign_mode").jsonPrimitive.content)
            assertEquals(run.runIndex, request.getValue("run_index").jsonPrimitive.int)
            assertEquals(
                "streaming_text_reference_v0.1",
                request.getValue("workload_id").jsonPrimitive.content,
            )
            assertEquals("0.1", request.getValue("workload_version").jsonPrimitive.content)
            assertEquals(
                "streaming_text_reference_v0.1",
                request.getValue("profile_id").jsonPrimitive.content,
            )
            assertEquals("0.1", request.getValue("profile_version").jsonPrimitive.content)
            assertEquals(
                "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
                request.getValue("profile_manifest_sha256").jsonPrimitive.content,
            )
            assertEquals(run.conditionId, request.getValue("condition_id").jsonPrimitive.content)
            assertEquals("0.1", request.getValue("condition_version").jsonPrimitive.content)
            assertEquals(12, request.size)
        }

        assertEquals(3, result.runs.size)
        assertEquals(observedRuns.map { it.runId }, result.runs.map { it.runId })
        assertEquals(observedRuns.map { it.conditionId }, result.runs.map { it.conditionId })
        assertEquals("campaign-quick-g2a", result.summary.campaignId)
        assertEquals("quick", result.summary.campaignMode)
        assertEquals(3, result.summary.plannedRuns)
        assertEquals(3, result.summary.attemptedRuns)
        assertEquals(3, result.summary.successfulRuns)
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, result.summary.status)
    }

    @Test
    fun acceptanceCampaignRunsFrozenNinePlanAndReturnsThreeRunConditionSummaries() = runBlocking {
        val observedRuns = mutableListOf<PrototypeQuickCampaignRunner.RunPlan>()
        val cooldowns = mutableListOf<Long>()
        val runner = PrototypeQuickCampaignRunner(
            runIdFactory = { index -> "run-acceptance-${index.toString().padStart(2, '0')}" },
            executeRun = { plan ->
                observedRuns += plan
                PrototypeQuickCampaignRunner.RunResult.completeForTest(
                    runIndex = plan.runIndex,
                    runId = plan.runId,
                    conditionId = plan.conditionId,
                    streamResult = testStreamResult(plan.runIndex),
                )
            },
            waitBetweenRuns =(cooldowns::add),
        )

        val result = runner.run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = "campaign-acceptance-g2c",
            mode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
        )

        assertEquals(
            listOf(
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
            ),
            observedRuns.map { it.conditionId },
        )
        assertEquals((1..9).toList(), observedRuns.map { it.runIndex })
        assertEquals(List(8) { 1_000L }, cooldowns)
        observedRuns.forEach { run ->
            val request = Json.parseToJsonElement(run.requestBody).jsonObject
            assertEquals("acceptance", request.getValue("campaign_mode").jsonPrimitive.content)
            assertEquals(run.runIndex, request.getValue("run_index").jsonPrimitive.int)
            assertEquals(run.conditionId, request.getValue("condition_id").jsonPrimitive.content)
        }

        assertEquals("acceptance", result.summary.campaignMode)
        assertEquals(9, result.summary.plannedRuns)
        assertEquals(9, result.summary.attemptedRuns)
        assertEquals(9, result.summary.successfulRuns)
        assertEquals(0, result.summary.failedRuns)
        assertEquals(0, result.summary.notStartedRuns)
        assertEquals(1.0, result.summary.successRate, 0.0)
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, result.summary.status)
        result.summary.conditionSummaries.forEach { summary ->
            assertEquals(3, summary.plannedRuns)
            assertEquals(3, summary.attemptedRuns)
            assertEquals(3, summary.successfulRuns)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.HIGH, summary.confidence)
        }
    }

    @Test
    fun acceptanceSummaryUsesSuccessfulRunMediansAndFrozenConfidence() {
        fun metrics(
            ttftMs: Double,
            completionMs: Double,
            eventRate: Double,
            stallCount: Int,
            stallDurationMs: Double,
            stallFraction: Double,
        ) = PrototypeQuickCampaignRunner.RunMetrics(
            ttftMs = ttftMs,
            completionMs = completionMs,
            streamSpanMs = completionMs - ttftMs,
            streamEventRateEps = eventRate,
            stallThresholdMs = 500.0,
            stallCount = stallCount,
            stallDurationMs = stallDurationMs,
            stallFraction = stallFraction,
        )

        fun successful(
            conditionId: String,
            metrics: PrototypeQuickCampaignRunner.RunMetrics,
        ) = PrototypeQuickCampaignRunner.SummaryRun(
            conditionId = conditionId,
            status = PrototypeQuickCampaignRunner.RunStatus.COMPLETE,
            taskSuccess = true,
            scoreEligible = true,
            metrics = metrics,
        )

        val runs = listOf(
            successful("baseline_v0.1", metrics(300.0, 4_000.0, 11.0, 0, 300.0, 0.20)),
            successful("slow_v0.1", metrics(900.0, 9_000.0, 7.0, 2, 700.0, 0.45)),
            successful("unstable_v0.1", metrics(550.0, 11_000.0, 7.0, 5, 1_400.0, 0.30)),
            successful("baseline_v0.1", metrics(100.0, 6_000.0, 10.0, 2, 200.0, 0.30)),
            successful("slow_v0.1", metrics(500.0, 15_000.0, 8.0, 4, 500.0, 0.35)),
            successful("unstable_v0.1", metrics(650.0, 7_000.0, 9.0, 3, 1_100.0, 0.50)),
            successful("baseline_v0.1", metrics(200.0, 5_000.0, 12.0, 1, 100.0, 0.10)),
            successful("slow_v0.1", metrics(700.0, 12_000.0, 6.0, 3, 900.0, 0.25)),
            successful("unstable_v0.1", metrics(450.0, 9_000.0, 8.0, 7, 800.0, 0.40)),
        )
        val result = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
            campaignId = "campaign-acceptance-medians",
            mode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
            results = runs,
        )

        val baseline = result.conditionSummaries.single { it.conditionId == "baseline_v0.1" }
        assertEquals(PrototypeQuickCampaignRunner.Confidence.HIGH, baseline.confidence)
        assertEquals(200.0, baseline.medianTtftMs)
        assertEquals(100.0, baseline.minTtftMs)
        assertEquals(300.0, baseline.maxTtftMs)
        assertEquals(5_000.0, baseline.medianCompletionMs)
        assertEquals(4_000.0, baseline.minCompletionMs)
        assertEquals(6_000.0, baseline.maxCompletionMs)
        assertEquals(11.0, baseline.medianStreamEventRateEps)
        assertEquals(1.0, baseline.medianStallCount)
        assertEquals(200.0, baseline.medianStallDurationMs)
        assertEquals(0.20, baseline.medianStallFraction)
        assertEquals(96, baseline.rpi)

        val slow = result.conditionSummaries.single { it.conditionId == "slow_v0.1" }
        assertEquals(700.0, slow.medianTtftMs)
        assertEquals(500.0, slow.minTtftMs)
        assertEquals(900.0, slow.maxTtftMs)
        assertEquals(12_000.0, slow.medianCompletionMs)
        assertEquals(9_000.0, slow.minCompletionMs)
        assertEquals(15_000.0, slow.maxCompletionMs)
        assertEquals(7.0, slow.medianStreamEventRateEps)
        assertEquals(3.0, slow.medianStallCount)
        assertEquals(700.0, slow.medianStallDurationMs)
        assertEquals(0.35, slow.medianStallFraction)

        val unstable = result.conditionSummaries.single { it.conditionId == "unstable_v0.1" }
        assertEquals(550.0, unstable.medianTtftMs)
        assertEquals(450.0, unstable.minTtftMs)
        assertEquals(650.0, unstable.maxTtftMs)
        assertEquals(9_000.0, unstable.medianCompletionMs)
        assertEquals(7_000.0, unstable.minCompletionMs)
        assertEquals(11_000.0, unstable.maxCompletionMs)
        assertEquals(8.0, unstable.medianStreamEventRateEps)
        assertEquals(5.0, unstable.medianStallCount)
        assertEquals(1_100.0, unstable.medianStallDurationMs)
        assertEquals(0.40, unstable.medianStallFraction)
        result.conditionSummaries.forEach { summary ->
            assertEquals(3, summary.plannedRuns)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.HIGH, summary.confidence)
            assertNotNull(summary.rpi)
        }

        val quick = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
            campaignId = "campaign-quick-compat",
            results = runs.take(3),
        )
        assertEquals("quick", quick.campaignMode)
        assertEquals(3, quick.plannedRuns)
        quick.conditionSummaries.forEach { summary ->
            assertEquals(1, summary.plannedRuns)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.LOW, summary.confidence)
        }
    }

    @Test
    fun quickCampaignPublishesRunningAfterClockDomainValidationBeforeEachExecution() = runBlocking {
        val campaignId = "campaign-quick-running-progress"
        val runIds = listOf("run-progress-01", "run-progress-02", "run-progress-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val streams = ArrayDeque(
            conditions.mapIndexed { index, conditionId ->
                completeStream(campaignId, runIds[index], conditionId)
            },
        )
        val timeline = mutableListOf<String>()
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String) =
                streams.removeFirst().also {
                    val request = Json.parseToJsonElement(requestBody).jsonObject
                    timeline += "execute:${request.getValue("condition_id").jsonPrimitive.content}"
                }
        }
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index ->
                timeline += "clock:$index"
                "progress-domain-$index"
            },
            waitBetweenRuns = { _ -> },
            publishProgress = { progress ->
                if (
                    progress is PrototypeCampaignProgress.Running &&
                    progress.live.phase == PrototypeRunLivePhase.CONNECTING
                ) {
                    timeline +=
                        "running:${progress.currentRunRef.runIndex}:" +
                            "${progress.currentRunRef.runId}:" +
                            "${progress.currentRunRef.conditionId}:" +
                            "${progress.processedRuns}/${progress.totalRuns}"
                }
            },
        )

        runner.run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(
            listOf(
                "clock:1",
                "running:1:run-progress-01:baseline_v0.1:0/3",
                "execute:baseline_v0.1",
                "clock:2",
                "running:2:run-progress-02:slow_v0.1:1/3",
                "execute:slow_v0.1",
                "clock:3",
                "running:3:run-progress-03:unstable_v0.1:2/3",
                "execute:unstable_v0.1",
            ),
            timeline,
        )
    }

    @Test
    fun quickCampaignMapsValidatedObservedSnapshotsToLiveRunPhasesAndMetrics() = runBlocking {
        val campaignId = "campaign-quick-live-progress"
        val runIds = listOf("run-live-01", "run-live-02", "run-live-03")
        val conditionIds = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val streams = ArrayDeque(
            conditionIds.mapIndexed { index, conditionId ->
                completeStream(campaignId, runIds[index], conditionId)
            },
        )
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("live progress requires the observed transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                observer.beforeDispatch()
                return streams.removeFirst().also { stream ->
                    stream.events.forEach(observer.onRawEvent)
                }
            }
        }
        val progress = mutableListOf<PrototypeCampaignProgress>()

        PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index -> "live-progress-domain-$index" },
            waitBetweenRuns = { _ -> },
            publishProgress = progress::add,
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        val firstRun = progress.filterIsInstance<PrototypeCampaignProgress.Running>()
            .filter { running -> running.currentRunRef.runIndex == 1 }
        assertEquals(123, firstRun.size)
        assertEquals(PrototypeRunLivePhase.CONNECTING, firstRun[0].live.phase)
        assertEquals(PrototypeRunLivePhase.WAITING_FOR_FIRST_EVENT, firstRun[1].live.phase)
        assertEquals(0, firstRun[1].live.validatedEventCount)
        assertNull(firstRun[1].live.ttftMs)
        assertNull(firstRun[1].live.eventRateEps)
        assertEquals(PrototypeRunLivePhase.STREAMING, firstRun[2].live.phase)
        assertEquals(1, firstRun[2].live.validatedEventCount)
        assertEquals(0.000001, firstRun[2].live.ttftMs)
        assertNull(firstRun[2].live.eventRateEps)
        assertEquals(PrototypeRunLivePhase.STREAMING, firstRun[3].live.phase)
        assertEquals(2, firstRun[3].live.validatedEventCount)
        assertEquals(1_000_000_000.0, firstRun[3].live.eventRateEps)
        assertEquals(PrototypeRunLivePhase.FINALIZING, firstRun.last().live.phase)
        assertEquals(120, firstRun.last().live.validatedEventCount)
        assertEquals(1_000_000_000.0, firstRun.last().live.eventRateEps)
        assertFalse(firstRun.last().live.stallObserved)
    }

    @Test
    fun liveStallUsesStrictFrozenThresholdForAbsoluteAndNominalBoundaries() {
        data class Case(
            val label: String,
            val nominalIntervalMs: Int,
            val gapNanos: Long,
            val expectedStall: Boolean,
        )
        val cases = listOf(
            Case("50ms nominal equality", 50, 500_000_000L, false),
            Case("50ms nominal plus one", 50, 500_000_001L, true),
            Case("200ms nominal equality", 200, 800_000_000L, false),
            Case("200ms nominal plus one", 200, 800_000_001L, true),
        )

        cases.forEach { case ->
            val firstContent = 2_000_000L
            val progress = PrototypeRunLiveSnapshot(
                t0MonotonicNanos = 1_000_000L,
                validatedContentTimestampsNanos =
                    listOf(firstContent, firstContent + case.gapNanos),
                terminalClientMonotonicNanos = null,
            ).toPrototypeRunLiveProgress(case.nominalIntervalMs)

            assertEquals(case.label, case.expectedStall, progress.stallObserved)
            assertEquals(2, progress.validatedEventCount)
            assertEquals(1.0, progress.ttftMs)
            assertEquals(1_000_000_000.0 / case.gapNanos.toDouble(), progress.eventRateEps)
        }
    }

    @Test
    fun quickCampaignPublishesCooldownForTheNextRunBeforeWaiting() = runBlocking {
        val timeline = mutableListOf<String>()
        val runIds = listOf("run-cooldown-01", "run-cooldown-02", "run-cooldown-03")
        val runner = PrototypeQuickCampaignRunner(
            runIdFactory = { index -> runIds[index - 1] },
            executeRun = { plan ->
                timeline += "execute:${plan.runIndex}"
                PrototypeQuickCampaignRunner.RunResult.completeForTest(
                    runIndex = plan.runIndex,
                    runId = plan.runId,
                    conditionId = plan.conditionId,
                    streamResult = testStreamResult(plan.runIndex),
                )
            },
            waitBetweenRuns = { timeline += "wait" },
            publishProgress = { progress ->
                when (progress) {
                    is PrototypeCampaignProgress.Running ->
                        timeline += "running:${progress.currentRunRef.runIndex}:${progress.processedRuns}"

                    is PrototypeCampaignProgress.Cooldown ->
                        timeline +=
                            "cooldown:${progress.nextRunRef.runIndex}:" +
                                "${progress.nextRunRef.runId}:" +
                                "${progress.nextRunRef.conditionId}:" +
                                "${progress.processedRuns}/${progress.totalRuns}"

                    is PrototypeCampaignProgress.Saving ->
                        error("Quick runner must not publish persistence progress")
                }
            },
        )

        runner.run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = "campaign-quick-cooldown-progress",
        )

        assertEquals(
            listOf(
                "running:1:0",
                "execute:1",
                "cooldown:2:run-cooldown-02:slow_v0.1:1/3",
                "wait",
                "running:2:1",
                "execute:2",
                "cooldown:3:run-cooldown-03:unstable_v0.1:2/3",
                "wait",
                "running:3:2",
                "execute:3",
            ),
            timeline,
        )
    }

    @Test
    fun allCompleteQuickConditionsEmitCanonicalEvidenceForEveryRun() = runBlocking {
        val campaignId = "campaign-quick-all-complete-evidence"
        val runIds = listOf("run-all-01", "run-all-02", "run-all-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                conditions.mapIndexed { index, conditionId ->
                    completeStream(campaignId, runIds[index], conditionId)
                },
            ),
        )
        val clockDomainCalls = mutableListOf<Int>()
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, RecordingSteppedClock()),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index ->
                clockDomainCalls += index
                "all-complete-domain-$index"
            },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(listOf(1, 2, 3), clockDomainCalls)
        assertEquals(List(3) { "COMPLETE" }, result.runs.map { it.status.name })
        result.runs.forEachIndexed { runOffset, run ->
            val runIndex = runOffset + 1
            val condition = evidenceCondition(conditions[runOffset])
            val clockDomainId = "all-complete-domain-$runIndex"
            val events = evidenceEvents(run)
            val stream = requireNotNull(run.completedStreamResult)

            assertEquals(122, events.size)
            assertEquals(
                listOf("run_started") + List(120) { "content_event" } + "terminal_event",
                events.map(::eventType),
            )
            events.forEach { event ->
                assertCanonicalEvidenceEnvelope(
                    event = event,
                    campaignId = campaignId,
                    runId = runIds[runOffset],
                    runIndex = runIndex,
                    condition = condition,
                    clockDomainId = clockDomainId,
                )
            }
            assertEquals(
                buildJsonObject {
                    put("t0_monotonic_ns", JsonPrimitive(stream.t0MonotonicNanos))
                },
                events.first().getValue("details"),
            )
            events.subList(1, 121).forEachIndexed { contentOffset, event ->
                val sequence = contentOffset + 1
                assertEquals(
                    buildJsonObject {
                        put("seq", JsonPrimitive(sequence))
                        put(
                            "planned_offset_ms",
                            JsonPrimitive(plannedOffsetMs(condition.id, sequence)),
                        )
                        put("payload_id", JsonPrimitive("ref-${sequence.toString().padStart(4, '0')}"))
                    },
                    event.getValue("details"),
                )
                assertEquals(
                    stream.validatedContentEvents[contentOffset].clientMonotonicNanos,
                    event.getValue("client_monotonic_ns").jsonPrimitive.long,
                )
            }
            val terminal = events.last()
            val expectedTerminalDetails = PrototypeTerminalProjection.project(
                serverDetails = stream.decodedTerminal.envelope.getValue("details").jsonObject,
                androidAdditions = buildJsonObject {
                    put("receipt_version", JsonPrimitive("prototype-terminal-receipt-0.1"))
                    put("events_expected", JsonPrimitive(120))
                    put("events_received", JsonPrimitive(120))
                    put("clock_domain_id", JsonPrimitive(clockDomainId))
                    put("clock_source", JsonPrimitive(ANDROID_CLOCK_SOURCE))
                    put("clock_unit", JsonPrimitive("ns"))
                    put("clock_epoch", JsonPrimitive("device_boot"))
                    put("t0_monotonic_ns", JsonPrimitive(stream.t0MonotonicNanos))
                    put("client_monotonic_ns", JsonPrimitive(stream.terminalClientMonotonicNanos))
                },
            )
            assertEquals(expectedTerminalDetails, terminal.getValue("details"))
            assertEquals(
                stream.terminalClientMonotonicNanos,
                terminal.getValue("client_monotonic_ns").jsonPrimitive.long,
            )
        }
    }

    @Test
    fun baselineCompleteRunExposesFrozenRunMetricsFromAndroidMonotonicSamples() = runBlocking {
        val campaignId = "campaign-quick-baseline-metrics"
        val runIds = listOf("run-metrics-01", "run-metrics-02", "run-metrics-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val baselineT0 = 10_000_000_000_000L
        val t0Values = List(3) { index -> baselineT0 + index * 100_000_000_000L }
        val clockSegments = conditions.mapIndexed { index, conditionId ->
            val condition = evidenceCondition(conditionId)
            val t0 = t0Values[index]
            buildList {
                add(t0)
                repeat(120) { contentIndex ->
                    add(
                        t0 +
                            plannedOffsetMs(conditionId, contentIndex + 1) * 1_000_000L,
                    )
                }
                add(
                    t0 +
                        (plannedOffsetMs(conditionId, 120) + condition.nominalIntervalMs) *
                        1_000_000L,
                )
            }.also { samples ->
                require(samples.size == 122)
                require(samples.zipWithNext().all { (previous, next) -> next > previous })
            }
        }
        val baselineSamples = clockSegments.first()
        require(baselineSamples[0] == baselineT0)
        require(baselineSamples[1] == baselineT0 + 200L * 1_000_000L)
        require(baselineSamples[120] == baselineT0 + 6_150L * 1_000_000L)
        require(baselineSamples[121] == baselineT0 + 6_200L * 1_000_000L)
        val scriptedSamples = ArrayDeque(clockSegments.flatten())
        val clock = object : MonotonicNanosClock {
            override fun now(): Long = scriptedSamples.removeFirstOrNull()
                ?: error("Prototype metrics scripted clock exhausted")
        }
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                conditions.mapIndexed { index, conditionId ->
                    completeStream(campaignId, runIds[index], conditionId)
                },
            ),
        )

        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, clock),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(List(3) { "COMPLETE" }, result.runs.map { it.status.name })
        assertTrue("scripted clock must provide exactly 122 samples per run", scriptedSamples.isEmpty())
        result.runs.forEachIndexed { runIndex, run ->
            val stream = requireNotNull(run.completedStreamResult)
            val segment = clockSegments[runIndex]
            assertEquals(segment[0], stream.t0MonotonicNanos)
            assertEquals(
                segment.subList(1, 121),
                stream.validatedContentEvents.map { it.clientMonotonicNanos },
            )
            assertEquals(segment[121], stream.terminalClientMonotonicNanos)
        }
        val baselineRun = result.runs.first()
        assertRunContract(
            run = baselineRun,
            taskSuccess = true,
            scoreEligible = true,
            eventsExpected = 120,
            eventsReceived = 120,
            failureReason = null,
            terminalReceiptValid = true,
        )
        assertEquals(122, requireNotNull(baselineRun.completedStreamResult).rawEvents.size)

        val expectedMetricTypes = linkedMapOf(
            "ttftMs" to Double::class.javaObjectType,
            "completionMs" to Double::class.javaObjectType,
            "streamSpanMs" to Double::class.javaObjectType,
            "streamEventRateEps" to Double::class.javaObjectType,
            "stallThresholdMs" to Double::class.javaObjectType,
            "stallCount" to Int::class.javaObjectType,
            "stallDurationMs" to Double::class.javaObjectType,
            "stallFraction" to Double::class.javaObjectType,
        )
        expectedMetricTypes.keys.forEach { propertyName ->
            val getterName = "get" + propertyName.replaceFirstChar(Char::uppercaseChar)
            assertTrue(
                "RunResult must not expose temporary top-level metric property $propertyName",
                baselineRun.javaClass.methods.none { method ->
                    method.name == getterName && method.parameterCount == 0
                },
            )
        }
        val metrics = requireNotNull(contractProperty(baselineRun, "metrics"))
        val metricGetters = metrics.javaClass.methods
            .filter { method ->
                method.declaringClass == metrics.javaClass &&
                    method.parameterCount == 0 &&
                    method.name.startsWith("get")
            }
            .associateBy { method ->
                method.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar)
            }
        assertEquals(expectedMetricTypes.keys, metricGetters.keys)
        expectedMetricTypes.forEach { (propertyName, expectedType) ->
            assertEquals(expectedType, metricGetters.getValue(propertyName).returnType)
        }
        assertEquals(200.0, contractProperty(metrics, "ttftMs"))
        assertEquals(6_200.0, contractProperty(metrics, "completionMs"))
        assertEquals(5_950.0, contractProperty(metrics, "streamSpanMs"))
        assertEquals(20.0, contractProperty(metrics, "streamEventRateEps"))
        assertEquals(500.0, contractProperty(metrics, "stallThresholdMs"))
        assertEquals(0, contractProperty(metrics, "stallCount"))
        assertEquals(0.0, contractProperty(metrics, "stallDurationMs"))
        assertEquals(0.0, contractProperty(metrics, "stallFraction"))

        val slowMetrics = requireNotNull(result.runs[1].metrics)
        assertEquals(650.0, slowMetrics.ttftMs)
        assertEquals(15_650.0, slowMetrics.completionMs)
        assertEquals(14_875.0, slowMetrics.streamSpanMs)
        assertEquals(8.0, slowMetrics.streamEventRateEps)
        assertEquals(500.0, slowMetrics.stallThresholdMs)
        assertEquals(0, slowMetrics.stallCount)
        assertEquals(0.0, slowMetrics.stallDurationMs)
        assertEquals(0.0, slowMetrics.stallFraction)

        val unstableMetrics = requireNotNull(result.runs[2].metrics)
        assertEquals(350.0, unstableMetrics.ttftMs)
        assertEquals(10_450.0, unstableMetrics.completionMs)
        assertEquals(10_035.0, unstableMetrics.streamSpanMs)
        assertEquals(
            119_000_000_000.0 / 10_035_000_000.0,
            unstableMetrics.streamEventRateEps,
        )
        assertEquals(500.0, unstableMetrics.stallThresholdMs)
        assertEquals(2, unstableMetrics.stallCount)
        assertEquals(2_300.0, unstableMetrics.stallDurationMs)
        assertEquals(2_300_000_000.0 / 10_035_000_000.0, unstableMetrics.stallFraction)

        assertEquals(campaignId, result.summary.campaignId)
        assertEquals("quick", result.summary.campaignMode)
        assertEquals(3, result.summary.plannedRuns)
        assertEquals(3, result.summary.attemptedRuns)
        assertEquals(3, result.summary.successfulRuns)
        assertEquals(0, result.summary.failedRuns)
        assertEquals(0, result.summary.notStartedRuns)
        assertEquals(1.0, result.summary.successRate, 0.0)
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, result.summary.status)
    }

    @Test
    fun completeQuickCampaignExposesOrderedPerConditionSummaryRows() = runBlocking {
        val campaignId = "campaign-quick-condition-summary"
        val runIds = listOf("run-summary-01", "run-summary-02", "run-summary-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val firstT0 = 14_000_000_000_000L
        val clockSegments = conditions.mapIndexed { index, conditionId ->
            val condition = evidenceCondition(conditionId)
            val t0 = firstT0 + index * 100_000_000_000L
            buildList {
                add(t0)
                repeat(120) { contentIndex ->
                    add(t0 + plannedOffsetMs(conditionId, contentIndex + 1) * 1_000_000L)
                }
                add(
                    t0 +
                        (plannedOffsetMs(conditionId, 120) + condition.nominalIntervalMs) *
                        1_000_000L,
                )
            }.also { samples ->
                require(samples.size == 122)
                require(samples.zipWithNext().all { (previous, next) -> next > previous })
            }
        }
        val scriptedSamples = ArrayDeque(clockSegments.flatten())
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = QueuedRawPostTransport(
                    streams = ArrayDeque(
                        conditions.mapIndexed { index, conditionId ->
                            completeStream(campaignId, runIds[index], conditionId)
                        },
                    ),
                ),
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = scriptedSamples.removeFirstOrNull()
                        ?: error("Prototype condition-summary clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted clock must provide exactly 122 samples per run", scriptedSamples.isEmpty())
        assertEquals(conditions, result.runs.map { run -> run.conditionId })
        result.runs.forEach { run ->
            assertEquals(PrototypeQuickCampaignRunner.RunStatus.COMPLETE, run.status)
            assertTrue(run.taskSuccess)
            assertTrue(run.scoreEligible)
            assertEquals(true, run.terminalReceiptValid)
            val metrics = requireNotNull(run.metrics)
            assertTrue(
                listOf<Any?>(
                    metrics.ttftMs,
                    metrics.completionMs,
                    metrics.streamSpanMs,
                    metrics.streamEventRateEps,
                    metrics.stallThresholdMs,
                    metrics.stallCount,
                    metrics.stallDurationMs,
                    metrics.stallFraction,
                ).all { value -> value != null },
            )
        }
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, result.summary.status)

        val conditionSummaries = contractProperty(result.summary, "conditionSummaries") as? List<*>
            ?: error("conditionSummaries must be a list")
        val expectedRows = listOf(
            "baseline_v0.1" to linkedMapOf(
                "successRate" to 1.0,
                "medianTtftMs" to 200.0,
                "minTtftMs" to 200.0,
                "maxTtftMs" to 200.0,
                "medianCompletionMs" to 6_200.0,
                "minCompletionMs" to 6_200.0,
                "maxCompletionMs" to 6_200.0,
                "medianStreamEventRateEps" to 20.0,
                "medianStallCount" to 0.0,
                "medianStallDurationMs" to 0.0,
                "medianStallFraction" to 0.0,
            ),
            "slow_v0.1" to linkedMapOf(
                "successRate" to 1.0,
                "medianTtftMs" to 650.0,
                "minTtftMs" to 650.0,
                "maxTtftMs" to 650.0,
                "medianCompletionMs" to 15_650.0,
                "minCompletionMs" to 15_650.0,
                "maxCompletionMs" to 15_650.0,
                "medianStreamEventRateEps" to 8.0,
                "medianStallCount" to 0.0,
                "medianStallDurationMs" to 0.0,
                "medianStallFraction" to 0.0,
            ),
            "unstable_v0.1" to linkedMapOf(
                "successRate" to 1.0,
                "medianTtftMs" to 350.0,
                "minTtftMs" to 350.0,
                "maxTtftMs" to 350.0,
                "medianCompletionMs" to 10_450.0,
                "minCompletionMs" to 10_450.0,
                "maxCompletionMs" to 10_450.0,
                "medianStreamEventRateEps" to 119_000_000_000.0 / 10_035_000_000.0,
                "medianStallCount" to 2.0,
                "medianStallDurationMs" to 2_300.0,
                "medianStallFraction" to 2_300_000_000.0 / 10_035_000_000.0,
            ),
        )
        assertEquals(3, conditionSummaries.size)
        assertEquals(
            expectedRows.map { (conditionId, _) -> conditionId },
            conditionSummaries.map { row ->
                contractProperty(requireNotNull(row), "conditionId")
            },
        )
        conditionSummaries.zip(expectedRows).forEach { (rowValue, expected) ->
            val row = requireNotNull(rowValue)
            assertEquals(1, (contractProperty(row, "plannedRuns") as Number).toInt())
            assertEquals(1, (contractProperty(row, "attemptedRuns") as Number).toInt())
            assertEquals(1, (contractProperty(row, "successfulRuns") as Number).toInt())
            assertEquals(0, (contractProperty(row, "failedRuns") as Number).toInt())
            assertEquals(0, (contractProperty(row, "notStartedRuns") as Number).toInt())
            val confidence = contractProperty(row, "confidence")
            assertEquals("LOW", (confidence as? Enum<*>)?.name ?: confidence.toString())
            expected.second.forEach { (propertyName, expectedValue) ->
                assertEquals(
                    expectedValue,
                    (contractProperty(row, propertyName) as Number).toDouble(),
                    0.0,
                )
            }
        }

        conditionSummaries.zip(listOf(100, 48, 62)).forEach { (rowValue, expectedRpi) ->
            val row = requireNotNull(rowValue)
            assertEquals(expectedRpi, (contractProperty(row, "rpi") as Number).toInt())
            assertEquals("rpi-0.1", contractProperty(row, "rpiPolicyId"))
            assertNull(contractProperty(row, "primaryNullReason"))
            assertNull(contractProperty(row, "allNullReasons"))
        }
    }

    @Test
    fun completeQuickCampaignRoundsBinaryExactRpiHalfTieAwayFromZero() = runBlocking {
        val campaignId = "campaign-quick-rpi-half-tie"
        val runIds = listOf("run-rpi-tie-01", "run-rpi-tie-02", "run-rpi-tie-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val nanosPerMillisecond = 1_000_000L
        val baselineT0 = 30_000_000_000_000L
        val slowT0 = baselineT0 + 100_000_000_000L
        val unstableT0 = baselineT0 + 200_000_000_000L
        val baselineSamples = buildList {
            add(baselineT0)
            repeat(120) { index ->
                add(baselineT0 + (8L + index.toLong()) * nanosPerMillisecond)
            }
            add(baselineT0 + 2_048L * nanosPerMillisecond)
        }
        val slowSamples = buildList {
            add(slowT0)
            repeat(119) { index ->
                add(slowT0 + (64L + index.toLong()) * nanosPerMillisecond)
            }
            add(slowT0 + 2_008L * nanosPerMillisecond)
            add(slowT0 + 16_384L * nanosPerMillisecond)
        }
        val unstableCondition = evidenceCondition("unstable_v0.1")
        val unstableSamples = buildList {
            add(unstableT0)
            repeat(120) { index ->
                add(
                    unstableT0 +
                        plannedOffsetMs("unstable_v0.1", index + 1) * nanosPerMillisecond,
                )
            }
            add(
                unstableT0 +
                    (plannedOffsetMs("unstable_v0.1", 120) +
                        unstableCondition.nominalIntervalMs) * nanosPerMillisecond,
            )
        }
        val clockSegments = listOf(baselineSamples, slowSamples, unstableSamples)
        clockSegments.forEach { samples ->
            require(samples.size == 122)
            require(samples.zipWithNext().all { (previous, next) -> next > previous })
        }
        val scriptedSamples = ArrayDeque(clockSegments.flatten())
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = QueuedRawPostTransport(
                    streams = ArrayDeque(
                        conditions.mapIndexed { index, conditionId ->
                            completeStream(campaignId, runIds[index], conditionId)
                        },
                    ),
                ),
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = scriptedSamples.removeFirstOrNull()
                        ?: error("Prototype RPI half-tie clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted clock must provide exactly 122 samples per run", scriptedSamples.isEmpty())
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, result.summary.status)
        assertEquals(
            listOf("COMPLETE", "COMPLETE", "COMPLETE"),
            result.runs.map { run -> run.status.name },
        )
        val baselineMetrics = requireNotNull(result.runs[0].metrics)
        assertEquals(8.0, baselineMetrics.ttftMs)
        assertEquals(2_048.0, baselineMetrics.completionMs)
        val slowMetrics = requireNotNull(result.runs[1].metrics)
        assertEquals(64.0, slowMetrics.ttftMs)
        assertEquals(16_384.0, slowMetrics.completionMs)
        assertEquals(1_944.0, slowMetrics.streamSpanMs)
        assertEquals(1_701.0, slowMetrics.stallDurationMs)
        assertEquals(0.875, slowMetrics.stallFraction)
        val conditionSummaries = result.summary.conditionSummaries
        assertEquals(conditions, conditionSummaries.map { summary -> summary.conditionId })
        assertEquals(
            listOf("rpi-0.1", "rpi-0.1", "rpi-0.1"),
            conditionSummaries.map { summary -> summary.rpiPolicyId },
        )
        val slowRpi: Any? = conditionSummaries[1].rpi
        assertTrue("Slow RPI must be an Int at runtime", slowRpi is Int)
        assertEquals(13, slowRpi)
    }

    @Test
    fun completeMetricsUseAndroidMonotonicTimestampsInsteadOfWireTiming() = runBlocking {
        val campaignId = "campaign-quick-metrics-clock-authority"
        val runIds = listOf("run-clock-01", "run-clock-02", "run-clock-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val baselineT0 = 10_000_000_000_000L
        val baselineContentOffsetsMs = buildList {
            var timestampMs = 230L
            repeat(120) { index ->
                if (index > 0) {
                    timestampMs += if (index == 60) 550L else 50L
                }
                add(timestampMs)
            }
        }
        require(baselineContentOffsetsMs.first() == 230L)
        require(baselineContentOffsetsMs.last() == 6_680L)
        val baselineSamples = buildList {
            add(baselineT0)
            baselineContentOffsetsMs.forEach { offsetMs ->
                add(baselineT0 + offsetMs * 1_000_000L)
            }
            add(baselineT0 + 6_760L * 1_000_000L)
        }
        val clockSegments = buildList {
            add(baselineSamples)
            conditions.drop(1).forEachIndexed { index, conditionId ->
                val t0 = baselineT0 + (index + 1) * 100_000_000_000L
                val condition = evidenceCondition(conditionId)
                add(
                    buildList {
                        add(t0)
                        repeat(120) { contentIndex ->
                            add(
                                t0 +
                                    plannedOffsetMs(conditionId, contentIndex + 1) * 1_000_000L,
                            )
                        }
                        add(
                            t0 +
                                (plannedOffsetMs(conditionId, 120) + condition.nominalIntervalMs) *
                                1_000_000L,
                        )
                    },
                )
            }
        }
        require(clockSegments.all { samples ->
            samples.size == 122 && samples.zipWithNext().all { (previous, next) -> next > previous }
        })
        val scriptedSamples = ArrayDeque(clockSegments.flatten())
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                conditions.mapIndexed { index, conditionId ->
                    completeStream(campaignId, runIds[index], conditionId)
                },
            ),
        )

        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = scriptedSamples.removeFirstOrNull()
                        ?: error("Prototype metrics authority clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(List(3) { "COMPLETE" }, result.runs.map { it.status.name })
        assertTrue("scripted clock must provide exactly 122 samples per run", scriptedSamples.isEmpty())
        val baselineRun = result.runs.first()
        val baselineStream = requireNotNull(baselineRun.completedStreamResult)
        assertEquals(baselineSamples[0], baselineStream.t0MonotonicNanos)
        assertEquals(
            baselineSamples.subList(1, 121),
            baselineStream.validatedContentEvents.map { it.clientMonotonicNanos },
        )
        assertEquals(baselineSamples[121], baselineStream.terminalClientMonotonicNanos)
        assertEquals(
            List(122) { index -> (index + 1) * 1_000L },
            baselineStream.rawEvents.map { event -> event.arrivalNanos },
        )
        val metrics = requireNotNull(baselineRun.metrics)
        assertEquals(230.0, metrics.ttftMs)
        assertEquals(6_760.0, metrics.completionMs)
        assertEquals(6_450.0, metrics.streamSpanMs)
        assertEquals(119_000_000_000.0 / 6_450_000_000.0, metrics.streamEventRateEps)
        assertEquals(500.0, metrics.stallThresholdMs)
        assertEquals(1, metrics.stallCount)
        assertEquals(500.0, metrics.stallDurationMs)
        assertEquals(500.0 / 6_450.0, metrics.stallFraction)
    }

    @Test
    fun baselineStallBoundaryUsesOnlyStrictAdjacentContentGaps() = runBlocking {
        val campaignId = "campaign-quick-metrics-stall-boundary"
        val runIds = listOf("run-boundary-01", "run-boundary-02", "run-boundary-03")
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val baselineT0 = 12_000_000_000_000L
        val baselineContentTimestamps = buildList {
            var timestamp = baselineT0 + 600_000_000L
            repeat(120) { index ->
                if (index > 0) {
                    timestamp += when (index) {
                        40 -> 500_000_000L
                        80 -> 500_000_001L
                        else -> 50_000_000L
                    }
                }
                add(timestamp)
            }
        }
        require(baselineContentTimestamps.first() == baselineT0 + 600_000_000L)
        require(baselineContentTimestamps.last() == baselineT0 + 7_450_000_001L)
        val baselineSamples = buildList {
            add(baselineT0)
            addAll(baselineContentTimestamps)
            add(baselineContentTimestamps.last() + 700_000_000L)
        }
        val clockSegments = buildList {
            add(baselineSamples)
            conditions.drop(1).forEachIndexed { index, conditionId ->
                val t0 = baselineT0 + (index + 1) * 100_000_000_000L
                val condition = evidenceCondition(conditionId)
                add(
                    buildList {
                        add(t0)
                        repeat(120) { contentIndex ->
                            add(
                                t0 +
                                    plannedOffsetMs(conditionId, contentIndex + 1) * 1_000_000L,
                            )
                        }
                        add(
                            t0 +
                                (plannedOffsetMs(conditionId, 120) + condition.nominalIntervalMs) *
                                1_000_000L,
                        )
                    },
                )
            }
        }
        require(clockSegments.all { samples ->
            samples.size == 122 && samples.zipWithNext().all { (previous, next) -> next > previous }
        })
        val scriptedSamples = ArrayDeque(clockSegments.flatten())
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                conditions.mapIndexed { index, conditionId ->
                    completeStream(campaignId, runIds[index], conditionId)
                },
            ),
        )

        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = scriptedSamples.removeFirstOrNull()
                        ?: error("Prototype stall-boundary clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(List(3) { "COMPLETE" }, result.runs.map { it.status.name })
        assertTrue("scripted clock must provide exactly 122 samples per run", scriptedSamples.isEmpty())
        val baselineStream = requireNotNull(result.runs.first().completedStreamResult)
        assertEquals(baselineSamples[0], baselineStream.t0MonotonicNanos)
        assertEquals(
            baselineSamples.subList(1, 121),
            baselineStream.validatedContentEvents.map { event -> event.clientMonotonicNanos },
        )
        assertEquals(baselineSamples[121], baselineStream.terminalClientMonotonicNanos)
        val metrics = requireNotNull(result.runs.first().metrics)
        assertEquals(600.0, metrics.ttftMs)
        assertEquals(8_150.000001, metrics.completionMs)
        assertEquals(6_850.000001, metrics.streamSpanMs)
        assertEquals(119_000_000_000.0 / 6_850_000_001.0, metrics.streamEventRateEps)
        assertEquals(500.0, metrics.stallThresholdMs)
        assertEquals(1, metrics.stallCount)
        assertEquals(450.000001, metrics.stallDurationMs)
        assertEquals(450_000_001.0 / 6_850_000_001.0, metrics.stallFraction)
    }

    @Test
    fun runStartedScheduleHashMustMatchQuickRunPlanBeforeInterruptedEvidence() = runBlocking {
        val campaignId = "campaign-quick-run-start-authority"
        val runId = "run-start-authority-01"
        val baselineSchedule = evidenceCondition("baseline_v0.1").scheduleHash
        val slowSchedule = evidenceCondition("slow_v0.1").scheduleHash
        val baselineMember = "\"schedule_hash\":\"$baselineSchedule\""
        val canonicalBlock = runStartedBlock(campaignId, runId, "baseline_v0.1")
        require(canonicalBlock.split(baselineMember).size - 1 == 1) {
            "canonical Baseline run_started must contain one schedule_hash authority"
        }
        val forgedBlock = canonicalBlock.replace(
            baselineMember,
            "\"schedule_hash\":\"$slowSchedule\"",
        )
        val forgedStream = rawStream(listOf(forgedBlock), truncatedTail = false)
        val forgedRawEvent = forgedStream.events.single()
        var deliveredRawEvent: RawSseEvent? = null
        var postObservedCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("Runner must use the observed Prototype transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                if (++postObservedCalls != 1) {
                    error("Slow/Unstable Quick slot reached the transport")
                }
                observer.beforeDispatch()
                deliveredRawEvent = forgedRawEvent
                observer.onRawEvent(forgedRawEvent)
                return forgedStream
            }
        }
        val outcome = runCatching {
            PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index ->
                    if (index == 1) runId else "unexpected-run-$index"
                },
                waitBetweenRuns = { _ -> },
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )
        }

        assertEquals(1, postObservedCalls)
        assertSame(forgedRawEvent, deliveredRawEvent)
        assertArrayEquals(forgedRawEvent.bytes, requireNotNull(deliveredRawEvent).bytes)
        val error = outcome.exceptionOrNull()
        if (error == null) {
            org.junit.Assert.fail("expected run_started Quick plan authority error was not thrown")
            return@runBlocking
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Prototype run_started authority does not match the Quick run plan",
            error.message,
        )
    }

    @Test
    fun completeRunStartedScheduleHashMustMatchQuickRunPlan() = runBlocking {
        val campaignId = "campaign-quick-complete-start-authority"
        val runIds = listOf(
            "run-complete-start-authority-01",
            "run-complete-start-authority-02",
            "run-complete-start-authority-03",
        )
        val baselineSchedule = evidenceCondition("baseline_v0.1").scheduleHash
        val slowSchedule = evidenceCondition("slow_v0.1").scheduleHash
        val baselineMember = "\"schedule_hash\":\"$baselineSchedule\""
        val canonicalBaseline = completeBaselineStream(campaignId, runIds[0])
        val canonicalStart = canonicalBaseline.events.first()
        val canonicalStartText = canonicalStart.bytes.toString(Charsets.UTF_8)
        require(canonicalBaseline.events.size == 122)
        require(canonicalStartText.split(baselineMember).size - 1 == 1) {
            "canonical complete Baseline run_started must contain one schedule_hash authority"
        }
        val forgedStart = RawSseEvent(
            bytes = canonicalStartText.replace(
                baselineMember,
                "\"schedule_hash\":\"$slowSchedule\"",
            ).toByteArray(Charsets.UTF_8),
            arrivalNanos = canonicalStart.arrivalNanos,
            sameReadBatch = canonicalStart.sameReadBatch,
        )
        require(forgedStart.bytes.size == canonicalStart.bytes.size)
        val forgedBaseline = RawSseStream(
            events = listOf(forgedStart) + canonicalBaseline.events.drop(1),
            readCount = canonicalBaseline.readCount,
            totalBytes = canonicalBaseline.totalBytes,
            truncatedTail = canonicalBaseline.truncatedTail,
            eofNanos = canonicalBaseline.eofNanos,
        )
        require(
            forgedBaseline.events.drop(1).zip(canonicalBaseline.events.drop(1)).all { (actual, expected) ->
                actual === expected
            },
        ) {
            "complete Baseline content and terminal carriers must remain canonical"
        }
        val streams = listOf(
            forgedBaseline,
            completeStream(campaignId, runIds[1], "slow_v0.1"),
            completeStream(campaignId, runIds[2], "unstable_v0.1"),
        )
        var deliveredStart: RawSseEvent? = null
        var postObservedCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("Runner must use the observed Prototype transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                val stream = streams.getOrNull(postObservedCalls)
                    ?: error("Quick runner exceeded the three frozen slots")
                postObservedCalls += 1
                observer.beforeDispatch()
                stream.events.forEachIndexed { index, event ->
                    if (postObservedCalls == 1 && index == 0) {
                        deliveredStart = event
                    }
                    observer.onRawEvent(event)
                }
                return stream
            }
        }
        val outcome = runCatching {
            PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index -> runIds[index - 1] },
                waitBetweenRuns = { _ -> },
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )
        }

        assertSame(forgedStart, deliveredStart)
        assertArrayEquals(forgedStart.bytes, requireNotNull(deliveredStart).bytes)
        val error = outcome.exceptionOrNull()
        if (error == null) {
            org.junit.Assert.fail(
                "expected complete run_started Quick plan authority error was not thrown",
            )
            return@runBlocking
        }
        assertEquals(1, postObservedCalls)
        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Prototype run_started authority does not match the Quick run plan",
            error.message,
        )
    }

    @Test
    fun runStartedManifestMustMatchQuickRunPlanForCompleteAndInterruptedRuns() = runBlocking {
        val forgedManifest = "f".repeat(64)
        require(forgedManifest != PROFILE_MANIFEST_SHA256)
        val canonicalMember = "\"profile_manifest_sha256\":\"$PROFILE_MANIFEST_SHA256\""
        val forgedMember = "\"profile_manifest_sha256\":\"$forgedManifest\""
        val acceptedCases = mutableListOf<String>()

        listOf(
            "complete 122-frame" to true,
            "zero-content clean EOF" to false,
        ).forEach { (caseName, isComplete) ->
            val caseSuffix = if (isComplete) "complete" else "clean-eof"
            val campaignId = "campaign-quick-manifest-$caseSuffix"
            val runIds = listOf(
                "run-manifest-$caseSuffix-01",
                "run-manifest-$caseSuffix-02",
                "run-manifest-$caseSuffix-03",
            )
            val canonicalBaseline = if (isComplete) {
                completeBaselineStream(campaignId, runIds[0])
            } else {
                rawStream(
                    blocks = listOf(runStartedBlock(campaignId, runIds[0], "baseline_v0.1")),
                    truncatedTail = false,
                )
            }
            val canonicalStart = canonicalBaseline.events.first()
            val canonicalStartText = canonicalStart.bytes.toString(Charsets.UTF_8)
            require(canonicalStartText.split(canonicalMember).size - 1 == 1) {
                "canonical Baseline run_started must contain one profile manifest authority"
            }
            val forgedStart = RawSseEvent(
                bytes = canonicalStartText.replace(canonicalMember, forgedMember)
                    .toByteArray(Charsets.UTF_8),
                arrivalNanos = canonicalStart.arrivalNanos,
                sameReadBatch = canonicalStart.sameReadBatch,
            )
            require(forgedStart.bytes.size == canonicalStart.bytes.size)
            val forgedBaseline = RawSseStream(
                events = listOf(forgedStart) + canonicalBaseline.events.drop(1),
                readCount = canonicalBaseline.readCount,
                totalBytes = canonicalBaseline.totalBytes,
                truncatedTail = canonicalBaseline.truncatedTail,
                eofNanos = canonicalBaseline.eofNanos,
            )
            require(
                forgedBaseline.events.drop(1).zip(canonicalBaseline.events.drop(1))
                    .all { (actual, expected) -> actual === expected },
            ) {
                "non-run_started carriers must remain canonical"
            }
            val streams = if (isComplete) {
                listOf(
                    forgedBaseline,
                    completeStream(campaignId, runIds[1], "slow_v0.1"),
                    completeStream(campaignId, runIds[2], "unstable_v0.1"),
                )
            } else {
                listOf(forgedBaseline)
            }
            var deliveredStart: RawSseEvent? = null
            var postObservedCalls = 0
            val transport = object : PrototypeRawPostTransport {
                override suspend fun post(url: String, requestBody: String): RawSseStream =
                    error("Runner must use the observed Prototype transport path")

                override suspend fun postObserved(
                    url: String,
                    requestBody: String,
                    observer: PrototypeRawPostObserver,
                ): RawSseStream {
                    val stream = streams.getOrNull(postObservedCalls)
                        ?: error("$caseName reached an unexpected Quick slot")
                    postObservedCalls += 1
                    observer.beforeDispatch()
                    stream.events.forEachIndexed { index, event ->
                        if (postObservedCalls == 1 && index == 0) {
                            deliveredStart = event
                        }
                        observer.onRawEvent(event)
                    }
                    return stream
                }
            }
            val outcome = runCatching {
                PrototypeQuickCampaignRunner(
                    streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                    runIdFactory = { index -> runIds[index - 1] },
                    waitBetweenRuns = { _ -> },
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }

            assertSame(forgedStart, deliveredStart)
            assertArrayEquals(forgedStart.bytes, requireNotNull(deliveredStart).bytes)
            val error = outcome.exceptionOrNull()
            if (error == null) {
                acceptedCases += caseName
            } else {
                assertEquals(1, postObservedCalls)
                assertTrue(error is IllegalArgumentException)
                assertEquals(
                    "Prototype run_started authority does not match the Quick run plan",
                    error.message,
                )
            }
        }

        if (acceptedCases.isNotEmpty()) {
            org.junit.Assert.fail(
                "expected run_started profile manifest authority error was not thrown for " +
                    acceptedCases.joinToString(),
            )
        }
    }

    @Test
    fun runStartedNominalIntervalMustMatchQuickRunPlanForCompleteAndInterruptedRuns() = runBlocking {
        val canonicalMember = "\"nominal_interval_ms\":50"
        val forgedMember = "\"nominal_interval_ms\":51"
        val acceptedCases = mutableListOf<String>()

        listOf(
            "complete 122-frame" to true,
            "zero-content clean EOF" to false,
        ).forEach { (caseName, isComplete) ->
            val caseSuffix = if (isComplete) "complete" else "clean-eof"
            val campaignId = "campaign-quick-nominal-$caseSuffix"
            val runIds = listOf(
                "run-nominal-$caseSuffix-01",
                "run-nominal-$caseSuffix-02",
                "run-nominal-$caseSuffix-03",
            )
            val canonicalBaseline = if (isComplete) {
                completeBaselineStream(campaignId, runIds[0])
            } else {
                rawStream(
                    blocks = listOf(runStartedBlock(campaignId, runIds[0], "baseline_v0.1")),
                    truncatedTail = false,
                )
            }
            val canonicalStart = canonicalBaseline.events.first()
            val canonicalStartText = canonicalStart.bytes.toString(Charsets.UTF_8)
            require(canonicalStartText.split(canonicalMember).size - 1 == 1) {
                "canonical Baseline run_started must contain one nominal interval authority"
            }
            val forgedStart = RawSseEvent(
                bytes = canonicalStartText.replace(canonicalMember, forgedMember)
                    .toByteArray(Charsets.UTF_8),
                arrivalNanos = canonicalStart.arrivalNanos,
                sameReadBatch = canonicalStart.sameReadBatch,
            )
            require(forgedStart.bytes.size == canonicalStart.bytes.size)
            val forgedBaseline = RawSseStream(
                events = listOf(forgedStart) + canonicalBaseline.events.drop(1),
                readCount = canonicalBaseline.readCount,
                totalBytes = canonicalBaseline.totalBytes,
                truncatedTail = canonicalBaseline.truncatedTail,
                eofNanos = canonicalBaseline.eofNanos,
            )
            require(
                forgedBaseline.events.drop(1).zip(canonicalBaseline.events.drop(1))
                    .all { (actual, expected) -> actual === expected },
            ) {
                "non-run_started carriers must remain canonical"
            }
            val streams = if (isComplete) {
                listOf(
                    forgedBaseline,
                    completeStream(campaignId, runIds[1], "slow_v0.1"),
                    completeStream(campaignId, runIds[2], "unstable_v0.1"),
                )
            } else {
                listOf(forgedBaseline)
            }
            var deliveredStart: RawSseEvent? = null
            var postObservedCalls = 0
            val transport = object : PrototypeRawPostTransport {
                override suspend fun post(url: String, requestBody: String): RawSseStream =
                    error("Runner must use the observed Prototype transport path")

                override suspend fun postObserved(
                    url: String,
                    requestBody: String,
                    observer: PrototypeRawPostObserver,
                ): RawSseStream {
                    val stream = streams.getOrNull(postObservedCalls)
                        ?: error("$caseName reached an unexpected Quick slot")
                    postObservedCalls += 1
                    observer.beforeDispatch()
                    stream.events.forEachIndexed { index, event ->
                        if (postObservedCalls == 1 && index == 0) {
                            deliveredStart = event
                        }
                        observer.onRawEvent(event)
                    }
                    return stream
                }
            }
            val outcome = runCatching {
                PrototypeQuickCampaignRunner(
                    streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                    runIdFactory = { index -> runIds[index - 1] },
                    waitBetweenRuns = { _ -> },
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }

            assertSame(forgedStart, deliveredStart)
            assertArrayEquals(forgedStart.bytes, requireNotNull(deliveredStart).bytes)
            val error = outcome.exceptionOrNull()
            if (error == null) {
                acceptedCases += caseName
            } else {
                assertEquals(1, postObservedCalls)
                assertTrue(error is IllegalArgumentException)
                assertEquals(
                    "Prototype run_started authority does not match the Quick run plan",
                    error.message,
                )
            }
        }

        if (acceptedCases.isNotEmpty()) {
            org.junit.Assert.fail(
                "expected run_started nominal interval authority error was not thrown for " +
                    acceptedCases.joinToString(),
            )
        }
    }

    @Test
    fun runStartedProfileIdentityMustMatchQuickRunPlanForCompleteAndInterruptedRuns() = runBlocking {
        val profileIdMutation =
            "\"profile_id\":\"streaming_text_reference_v0.1\"" to
                "\"profile_id\":\"streaming_text_reference_v0.2\""
        val profileVersionMutation =
            "\"profile_version\":\"0.1\"" to "\"profile_version\":\"0.2\""
        val acceptedCases = mutableListOf<String>()
        val cases = listOf(
            Triple("complete profile_id", true, profileIdMutation),
            Triple("complete profile_version", true, profileVersionMutation),
            Triple("zero-content clean EOF profile_id", false, profileIdMutation),
            Triple("zero-content clean EOF profile_version", false, profileVersionMutation),
        )

        cases.forEachIndexed { caseIndex, (caseName, isComplete, mutation) ->
            val (canonicalMember, forgedMember) = mutation
            val campaignId = "campaign-quick-profile-${caseIndex + 1}"
            val runIds = listOf(
                "run-profile-${caseIndex + 1}-01",
                "run-profile-${caseIndex + 1}-02",
                "run-profile-${caseIndex + 1}-03",
            )
            val canonicalBaseline = if (isComplete) {
                completeBaselineStream(campaignId, runIds[0])
            } else {
                rawStream(
                    blocks = listOf(runStartedBlock(campaignId, runIds[0], "baseline_v0.1")),
                    truncatedTail = false,
                )
            }
            val canonicalStart = canonicalBaseline.events.first()
            val canonicalStartText = canonicalStart.bytes.toString(Charsets.UTF_8)
            require(canonicalStartText.split(canonicalMember).size - 1 == 1) {
                "canonical Baseline run_started must contain one $caseName authority"
            }
            val forgedStart = RawSseEvent(
                bytes = canonicalStartText.replace(canonicalMember, forgedMember)
                    .toByteArray(Charsets.UTF_8),
                arrivalNanos = canonicalStart.arrivalNanos,
                sameReadBatch = canonicalStart.sameReadBatch,
            )
            require(forgedStart.bytes.size == canonicalStart.bytes.size)
            val forgedBaseline = RawSseStream(
                events = listOf(forgedStart) + canonicalBaseline.events.drop(1),
                readCount = canonicalBaseline.readCount,
                totalBytes = canonicalBaseline.totalBytes,
                truncatedTail = canonicalBaseline.truncatedTail,
                eofNanos = canonicalBaseline.eofNanos,
            )
            require(
                forgedBaseline.events.drop(1).zip(canonicalBaseline.events.drop(1))
                    .all { (actual, expected) -> actual === expected },
            ) {
                "non-run_started carriers must remain canonical"
            }
            val streams = if (isComplete) {
                listOf(
                    forgedBaseline,
                    completeStream(campaignId, runIds[1], "slow_v0.1"),
                    completeStream(campaignId, runIds[2], "unstable_v0.1"),
                )
            } else {
                listOf(forgedBaseline)
            }
            var deliveredStart: RawSseEvent? = null
            var postObservedCalls = 0
            val transport = object : PrototypeRawPostTransport {
                override suspend fun post(url: String, requestBody: String): RawSseStream =
                    error("Runner must use the observed Prototype transport path")

                override suspend fun postObserved(
                    url: String,
                    requestBody: String,
                    observer: PrototypeRawPostObserver,
                ): RawSseStream {
                    val stream = streams.getOrNull(postObservedCalls)
                        ?: error("$caseName reached an unexpected Quick slot")
                    postObservedCalls += 1
                    observer.beforeDispatch()
                    stream.events.forEachIndexed { index, event ->
                        if (postObservedCalls == 1 && index == 0) {
                            deliveredStart = event
                        }
                        observer.onRawEvent(event)
                    }
                    return stream
                }
            }
            val outcome = runCatching {
                PrototypeQuickCampaignRunner(
                    streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                    runIdFactory = { index -> runIds[index - 1] },
                    waitBetweenRuns = { _ -> },
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }

            assertSame(forgedStart, deliveredStart)
            assertArrayEquals(forgedStart.bytes, requireNotNull(deliveredStart).bytes)
            val error = outcome.exceptionOrNull()
            if (error == null) {
                acceptedCases += caseName
            } else {
                assertEquals(1, postObservedCalls)
                assertTrue(error is IllegalArgumentException)
                assertEquals(
                    "Prototype run_started authority does not match the Quick run plan",
                    error.message,
                )
            }
        }

        if (acceptedCases.isNotEmpty()) {
            org.junit.Assert.fail(
                "expected run_started profile identity authority error was not thrown for " +
                    acceptedCases.joinToString(),
            )
        }
    }

    @Test
    fun secondQuickRunInterruptionReturnsPartialResultWithRetainedEvidenceAndNotStartedSuffix() =
        runBlocking {
            val campaignId = "campaign-quick-partial"
            val runIds = listOf("run-partial-01", "run-partial-02", "run-partial-03")
            val transport = QueuedRawPostTransport(
                streams = ArrayDeque(
                    listOf(
                        completeBaselineStream(campaignId, runIds[0]),
                        interruptedSlowStream(campaignId, runIds[1]),
                    ),
                ),
            )
            val cooldowns = mutableListOf<Long>()
            val progress = mutableListOf<PrototypeCampaignProgress>()
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index -> runIds[index - 1] },
                clockDomainIdFactory = { index -> "partial-progress-domain-$index" },
                waitBetweenRuns = { cooldowns += it },
                publishProgress = progress::add,
            )

            val result = runner.run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )

            assertEquals(2, transport.postedBodies.size)
            assertEquals(listOf(1L), cooldowns.map { it / 1_000L })
            assertEquals(3, result.runs.size)
            assertEquals(listOf(1, 2, 3), result.runs.map { it.runIndex })
            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                result.runs.map { it.conditionId },
            )
            assertEquals(
                listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            val lifecycleProgress = progress.filter { item ->
                item !is PrototypeCampaignProgress.Running ||
                    item.live.phase == PrototypeRunLivePhase.CONNECTING
            }
            assertEquals(
                listOf(
                    PrototypeCampaignProgress.Running(
                        campaignId,
                        PrototypeCampaignRunRef(1, runIds[0], "baseline_v0.1"),
                        0,
                        3,
                    ),
                    PrototypeCampaignProgress.Cooldown(
                        campaignId,
                        PrototypeCampaignRunRef(2, runIds[1], "slow_v0.1"),
                        1,
                        3,
                    ),
                    PrototypeCampaignProgress.Running(
                        campaignId,
                        PrototypeCampaignRunRef(2, runIds[1], "slow_v0.1"),
                        1,
                        3,
                    ),
                ),
                lifecycleProgress,
            )

            val baselineRun = result.runs[0]
            assertRunContract(
                run = baselineRun,
                taskSuccess = true,
                scoreEligible = true,
                eventsExpected = 120,
                eventsReceived = 120,
                failureReason = null,
                terminalReceiptValid = true,
            )
            val baseline = baselineRun.streamResult
            assertEquals(baseline, contractProperty(baselineRun, "completedStreamResult"))
            assertEquals(122, baseline.rawEvents.size)
            assertEquals(120, baseline.validatedContentEvents.size)
            assertNull(contractProperty(baselineRun, "partialEvidence"))

            val interruptedRun = result.runs[1]
            assertRunContract(
                run = interruptedRun,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 1,
                failureReason = "stream_interrupted",
                terminalReceiptValid = null,
            )
            assertNull(contractProperty(interruptedRun, "completedStreamResult"))
            val partialEvidence = requireNotNull(contractProperty(interruptedRun, "partialEvidence"))
            assertEquals(2, contractList(partialEvidence, "rawEvents").size)
            assertTrue((contractProperty(partialEvidence, "t0MonotonicNanos") as Long) >= 0L)
            val validatedContent = contractList(partialEvidence, "validatedContentEvents")
            assertEquals(1, validatedContent.size)
            assertEquals(
                1,
                contractProperty(requireNotNull(validatedContent.single()), "sequence"),
            )

            val notStartedRun = result.runs[2]
            assertRunContract(
                run = notStartedRun,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 0,
                failureReason = "not_started",
                terminalReceiptValid = null,
            )
            assertNull(contractProperty(notStartedRun, "completedStreamResult"))
            assertNull(contractProperty(notStartedRun, "partialEvidence"))
            assertEquals("PARTIAL", result.summary.status.name)
            assertEquals(3, result.summary.plannedRuns)
            assertEquals(2, result.summary.attemptedRuns)
            assertEquals(1, result.summary.successfulRuns)
            assertEquals(1, contractProperty(result.summary, "failedRuns"))
            assertEquals(1, contractProperty(result.summary, "notStartedRuns"))
            assertEquals(
                1.0 / 3.0,
                contractProperty(result.summary, "successRate") as Double,
                0.0,
            )

            val conditionSummaries = result.summary.conditionSummaries
            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                conditionSummaries.map { summary -> summary.conditionId },
            )
            val baselineSummary = conditionSummaries[0]
            val baselineMetrics = requireNotNull(baselineRun.metrics)
            assertEquals(1, baselineSummary.plannedRuns)
            assertEquals(1, baselineSummary.attemptedRuns)
            assertEquals(1, baselineSummary.successfulRuns)
            assertEquals(0, baselineSummary.failedRuns)
            assertEquals(0, baselineSummary.notStartedRuns)
            assertEquals(1.0, baselineSummary.successRate, 0.0)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.LOW, baselineSummary.confidence)
            assertEquals(baselineMetrics.ttftMs, baselineSummary.medianTtftMs)
            assertEquals(baselineMetrics.ttftMs, baselineSummary.minTtftMs)
            assertEquals(baselineMetrics.ttftMs, baselineSummary.maxTtftMs)
            assertEquals(baselineMetrics.completionMs, baselineSummary.medianCompletionMs)
            assertEquals(baselineMetrics.completionMs, baselineSummary.minCompletionMs)
            assertEquals(baselineMetrics.completionMs, baselineSummary.maxCompletionMs)
            assertEquals(
                baselineMetrics.streamEventRateEps,
                baselineSummary.medianStreamEventRateEps,
            )
            assertEquals(baselineMetrics.stallCount?.toDouble(), baselineSummary.medianStallCount)
            assertEquals(baselineMetrics.stallDurationMs, baselineSummary.medianStallDurationMs)
            assertEquals(baselineMetrics.stallFraction, baselineSummary.medianStallFraction)

            val slowSummary = conditionSummaries[1]
            assertEquals(1, slowSummary.plannedRuns)
            assertEquals(1, slowSummary.attemptedRuns)
            assertEquals(0, slowSummary.successfulRuns)
            assertEquals(1, slowSummary.failedRuns)
            assertEquals(0, slowSummary.notStartedRuns)
            assertEquals(0.0, slowSummary.successRate, 0.0)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, slowSummary.confidence)
            listOf(
                slowSummary.medianTtftMs,
                slowSummary.minTtftMs,
                slowSummary.maxTtftMs,
                slowSummary.medianCompletionMs,
                slowSummary.minCompletionMs,
                slowSummary.maxCompletionMs,
                slowSummary.medianStreamEventRateEps,
                slowSummary.medianStallCount,
                slowSummary.medianStallDurationMs,
                slowSummary.medianStallFraction,
            ).forEach { aggregate -> assertNull(aggregate) }

            val unstableSummary = conditionSummaries[2]
            assertEquals(1, unstableSummary.plannedRuns)
            assertEquals(0, unstableSummary.attemptedRuns)
            assertEquals(0, unstableSummary.successfulRuns)
            assertEquals(0, unstableSummary.failedRuns)
            assertEquals(1, unstableSummary.notStartedRuns)
            assertEquals(0.0, unstableSummary.successRate, 0.0)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, unstableSummary.confidence)
            listOf(
                unstableSummary.medianTtftMs,
                unstableSummary.minTtftMs,
                unstableSummary.maxTtftMs,
                unstableSummary.medianCompletionMs,
                unstableSummary.minCompletionMs,
                unstableSummary.maxCompletionMs,
                unstableSummary.medianStreamEventRateEps,
                unstableSummary.medianStallCount,
                unstableSummary.medianStallDurationMs,
                unstableSummary.medianStallFraction,
            ).forEach { aggregate -> assertNull(aggregate) }

            conditionSummaries.forEach { summary ->
                assertNull(summary.rpi)
                assertEquals("rpi-0.1", summary.rpiPolicyId)
                assertEquals("campaign_incomplete", summary.primaryNullReason)
            }
            assertEquals(listOf("campaign_incomplete"), baselineSummary.allNullReasons)
            val failedOrNotStartedReasons = listOf(
                "campaign_incomplete",
                "no_successful_condition_run",
                "mandatory_metric_missing",
            )
            assertEquals(failedOrNotStartedReasons, slowSummary.allNullReasons)
            assertEquals(failedOrNotStartedReasons, unstableSummary.allNullReasons)
        }

    @Test
    fun oneContentInterruptedRunExposesTtftOnlyPartialMetrics() = runBlocking {
        val campaignId = "campaign-quick-one-content-metrics"
        val runIds = listOf("run-one-content-01", "run-one-content-02", "run-one-content-03")
        val t0 = 10_000L * 1_000_000L
        val contentTimestamp = 10_200L * 1_000_000L
        val failureTimestamp = 19_000L * 1_000_000L
        val clockSamples = ArrayDeque(listOf(t0, contentTimestamp, failureTimestamp))
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(interruptedStream(campaignId, runIds[0], "baseline_v0.1")),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = clockSamples.removeFirstOrNull()
                        ?: error("Prototype one-content metrics clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted interruption clock must be exhausted", clockSamples.isEmpty())
        assertEquals(1, transport.postedBodies.size)
        assertEquals(
            listOf("INTERRUPTED", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL, result.summary.status)
        val interruptedRun = result.runs[0]
        assertRunContract(
            run = interruptedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 1,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        assertNull(interruptedRun.completedStreamResult)
        val partialEvidence = requireNotNull(interruptedRun.partialEvidence)
        assertEquals(t0, partialEvidence.t0MonotonicNanos)
        assertEquals(1, partialEvidence.validatedContentEvents.size)
        assertEquals(1, partialEvidence.validatedContentEvents.single().sequence)
        assertEquals(
            contentTimestamp,
            partialEvidence.validatedContentEvents.single().clientMonotonicNanos,
        )
        assertEquals(failureTimestamp, partialEvidence.interruptionClientMonotonicNanos)
        val projectedEvidence = evidenceEvents(interruptedRun)
        assertEquals(
            listOf("run_started", "content_event", "run_failed"),
            projectedEvidence.map(::eventType),
        )
        assertEquals(
            failureTimestamp,
            projectedEvidence.last().getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        val baselineSummary = result.summary.conditionSummaries.first()
        listOf(
            baselineSummary.medianTtftMs,
            baselineSummary.minTtftMs,
            baselineSummary.maxTtftMs,
            baselineSummary.medianCompletionMs,
            baselineSummary.minCompletionMs,
            baselineSummary.maxCompletionMs,
            baselineSummary.medianStreamEventRateEps,
            baselineSummary.medianStallCount,
            baselineSummary.medianStallDurationMs,
            baselineSummary.medianStallFraction,
        ).forEach { aggregate -> assertNull(aggregate) }
        assertNull(baselineSummary.rpi)

        val metrics = checkNotNull(interruptedRun.metrics) {
            "one-content interrupted run must expose partial RunMetrics"
        }
        assertEquals(200.0, metrics.ttftMs)
        listOf(
            metrics.completionMs,
            metrics.streamSpanMs,
            metrics.streamEventRateEps,
            metrics.stallThresholdMs,
            metrics.stallCount,
            metrics.stallDurationMs,
            metrics.stallFraction,
        ).forEach { metric -> assertNull(metric) }
    }

    @Test
    fun sameTickFirstContentFailsClosedBeforeInterruptedResult() = runBlocking {
        val campaignId = "campaign-quick-same-tick-interruption"
        val runIds = listOf("run-same-tick-01", "run-same-tick-02", "run-same-tick-03")
        val t0 = 30_000L * 1_000_000L
        val failureTimestamp = 31_000L * 1_000_000L
        val clockSamples = ArrayDeque(listOf(t0, t0, failureTimestamp))
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(interruptedStream(campaignId, runIds[0], "baseline_v0.1")),
            ),
        )
        val error = try {
            PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(
                    transport = transport,
                    clock = object : MonotonicNanosClock {
                        override fun now(): Long = clockSamples.removeFirstOrNull()
                            ?: error("Prototype same-tick interruption clock exhausted")
                    },
                ),
                runIdFactory = { index -> runIds[index - 1] },
                waitBetweenRuns = { _ -> },
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )
            null
        } catch (caught: IllegalArgumentException) {
            caught
        }

        assertTrue("scripted interruption clock must be exhausted", clockSamples.isEmpty())
        assertEquals(1, transport.postedBodies.size)
        assertEquals(
            "Prototype partial content evidence timestamps are not strictly ordered",
            error?.message,
        )
    }

    @Test
    fun twoContentInterruptedRunExposesObservedSpanRateAndStallMetrics() = runBlocking {
        val campaignId = "campaign-quick-two-content-metrics"
        val runIds = listOf("run-two-content-01", "run-two-content-02", "run-two-content-03")
        val t0 = 20_000L * 1_000_000L
        val firstContentTimestamp = 20_200L * 1_000_000L
        val secondContentTimestamp = 20_900L * 1_000_000L
        val failureTimestamp = 29_000L * 1_000_000L
        val clockSamples = ArrayDeque(
            listOf(t0, firstContentTimestamp, secondContentTimestamp, failureTimestamp),
        )
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    rawStream(
                        blocks = listOf(
                            runStartedBlock(campaignId, runIds[0], "baseline_v0.1"),
                            contentBlock(
                                campaignId,
                                runIds[0],
                                "baseline_v0.1",
                                sequence = 1,
                            ),
                            contentBlock(
                                campaignId,
                                runIds[0],
                                "baseline_v0.1",
                                sequence = 2,
                            ),
                        ),
                        truncatedTail = false,
                    ),
                ),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = clockSamples.removeFirstOrNull()
                        ?: error("Prototype two-content metrics clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted interruption clock must be exhausted", clockSamples.isEmpty())
        assertEquals(1, transport.postedBodies.size)
        assertEquals(
            listOf("INTERRUPTED", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL, result.summary.status)
        val interruptedRun = result.runs[0]
        assertRunContract(
            run = interruptedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 2,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        assertNull(interruptedRun.completedStreamResult)
        val partialEvidence = requireNotNull(interruptedRun.partialEvidence)
        assertEquals(t0, partialEvidence.t0MonotonicNanos)
        assertEquals(listOf(1, 2), partialEvidence.validatedContentEvents.map { it.sequence })
        assertEquals(
            listOf(firstContentTimestamp, secondContentTimestamp),
            partialEvidence.validatedContentEvents.map { it.clientMonotonicNanos },
        )
        assertEquals(failureTimestamp, partialEvidence.interruptionClientMonotonicNanos)
        val projectedEvidence = evidenceEvents(interruptedRun)
        assertEquals(
            listOf("run_started", "content_event", "content_event", "run_failed"),
            projectedEvidence.map(::eventType),
        )
        assertEquals(
            failureTimestamp,
            projectedEvidence.last().getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        val baselineSummary = result.summary.conditionSummaries.first()
        listOf(
            baselineSummary.medianTtftMs,
            baselineSummary.minTtftMs,
            baselineSummary.maxTtftMs,
            baselineSummary.medianCompletionMs,
            baselineSummary.minCompletionMs,
            baselineSummary.maxCompletionMs,
            baselineSummary.medianStreamEventRateEps,
            baselineSummary.medianStallCount,
            baselineSummary.medianStallDurationMs,
            baselineSummary.medianStallFraction,
        ).forEach { aggregate -> assertNull(aggregate) }
        assertNull(baselineSummary.rpi)

        val metrics = checkNotNull(interruptedRun.metrics) {
            "two-content interrupted run must expose partial RunMetrics"
        }
        assertEquals(200.0, metrics.ttftMs)
        assertNull(metrics.completionMs)
        assertEquals(700.0, metrics.streamSpanMs)
        assertEquals(1_000.0 / 700.0, metrics.streamEventRateEps)
        assertEquals(500.0, metrics.stallThresholdMs)
        assertEquals(1, metrics.stallCount)
        assertEquals(650.0, metrics.stallDurationMs)
        assertEquals(650.0 / 700.0, metrics.stallFraction)
    }

    @Test
    fun partialStallBoundaryRequiresGapStrictlyGreaterThanThreshold() = runBlocking {
        val campaignId = "campaign-quick-partial-stall-boundary"
        val runIds = listOf("run-partial-boundary-01", "run-partial-boundary-02", "run-partial-boundary-03")
        val t0 = 40_000L * 1_000_000L
        val firstContentTimestamp = 40_200L * 1_000_000L
        val secondContentTimestamp = 40_700L * 1_000_000L
        val failureTimestamp = 49_000L * 1_000_000L
        val clockSamples = ArrayDeque(
            listOf(t0, firstContentTimestamp, secondContentTimestamp, failureTimestamp),
        )
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    rawStream(
                        blocks = listOf(
                            runStartedBlock(campaignId, runIds[0], "baseline_v0.1"),
                            contentBlock(
                                campaignId,
                                runIds[0],
                                "baseline_v0.1",
                                sequence = 1,
                            ),
                            contentBlock(
                                campaignId,
                                runIds[0],
                                "baseline_v0.1",
                                sequence = 2,
                            ),
                        ),
                        truncatedTail = false,
                    ),
                ),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = clockSamples.removeFirstOrNull()
                        ?: error("Prototype partial boundary clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted interruption clock must be exhausted", clockSamples.isEmpty())
        assertEquals(1, transport.postedBodies.size)
        assertEquals(
            listOf("INTERRUPTED", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL, result.summary.status)
        val interruptedRun = result.runs[0]
        assertRunContract(
            run = interruptedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 2,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        assertNull(interruptedRun.completedStreamResult)
        val partialEvidence = requireNotNull(interruptedRun.partialEvidence)
        assertEquals(t0, partialEvidence.t0MonotonicNanos)
        assertEquals(listOf(1, 2), partialEvidence.validatedContentEvents.map { it.sequence })
        assertEquals(
            listOf(firstContentTimestamp, secondContentTimestamp),
            partialEvidence.validatedContentEvents.map { it.clientMonotonicNanos },
        )
        assertEquals(failureTimestamp, partialEvidence.interruptionClientMonotonicNanos)
        val projectedEvidence = evidenceEvents(interruptedRun)
        assertEquals(
            listOf("run_started", "content_event", "content_event", "run_failed"),
            projectedEvidence.map(::eventType),
        )
        assertEquals(
            failureTimestamp,
            projectedEvidence.last().getValue("client_monotonic_ns").jsonPrimitive.long,
        )

        val baselineSummary = result.summary.conditionSummaries.first()
        assertEquals(1, baselineSummary.plannedRuns)
        assertEquals(1, baselineSummary.attemptedRuns)
        assertEquals(0, baselineSummary.successfulRuns)
        assertEquals(1, baselineSummary.failedRuns)
        assertEquals(0, baselineSummary.notStartedRuns)
        assertEquals(0.0, baselineSummary.successRate, 0.0)
        assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, baselineSummary.confidence)
        listOf(
            baselineSummary.medianTtftMs,
            baselineSummary.minTtftMs,
            baselineSummary.maxTtftMs,
            baselineSummary.medianCompletionMs,
            baselineSummary.minCompletionMs,
            baselineSummary.maxCompletionMs,
            baselineSummary.medianStreamEventRateEps,
            baselineSummary.medianStallCount,
            baselineSummary.medianStallDurationMs,
            baselineSummary.medianStallFraction,
        ).forEach { aggregate -> assertNull(aggregate) }
        assertNull(baselineSummary.rpi)

        val metrics = requireNotNull(interruptedRun.metrics)
        assertEquals(200.0, metrics.ttftMs)
        assertNull(metrics.completionMs)
        assertEquals(500.0, metrics.streamSpanMs)
        assertEquals(2.0, metrics.streamEventRateEps)
        assertEquals(500.0, metrics.stallThresholdMs)
        assertEquals("gap equal to threshold must not count as stall", 0, metrics.stallCount)
        assertEquals(0.0, metrics.stallDurationMs)
        assertEquals(0.0, metrics.stallFraction)
    }

    @Test
    fun slowTwoContentInterruptionUsesSlowConditionMetadataForPartialStallMetrics() = runBlocking {
        val campaignId = "campaign-quick-slow-partial-metadata"
        val runIds = listOf("run-slow-metadata-01", "run-slow-metadata-02", "run-slow-metadata-03")
        val nanosPerMillisecond = 1_000_000L
        val baselineT0 = 50_000L * nanosPerMillisecond
        val baselineSamples = buildList {
            add(baselineT0)
            repeat(120) { index ->
                add(
                    baselineT0 +
                        plannedOffsetMs("baseline_v0.1", index + 1) * nanosPerMillisecond,
                )
            }
            add(
                baselineT0 +
                    (plannedOffsetMs("baseline_v0.1", 120) + 50L) * nanosPerMillisecond,
            )
        }.also { samples ->
            require(samples.size == 122)
            require(samples.zipWithNext().all { (previous, next) -> next > previous })
        }
        val slowT0 = 70_000L * nanosPerMillisecond
        val firstContentTimestamp = 70_200L * nanosPerMillisecond
        val secondContentTimestamp = 70_900L * nanosPerMillisecond
        val failureTimestamp = 79_000L * nanosPerMillisecond
        val clockSamples = ArrayDeque(
            baselineSamples +
                listOf(slowT0, firstContentTimestamp, secondContentTimestamp, failureTimestamp),
        )
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    completeBaselineStream(campaignId, runIds[0]),
                    rawStream(
                        blocks = listOf(
                            runStartedBlock(campaignId, runIds[1], "slow_v0.1"),
                            contentBlock(
                                campaignId,
                                runIds[1],
                                "slow_v0.1",
                                sequence = 1,
                            ),
                            contentBlock(
                                campaignId,
                                runIds[1],
                                "slow_v0.1",
                                sequence = 2,
                            ),
                        ),
                        truncatedTail = false,
                    ),
                ),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = transport,
                clock = object : MonotonicNanosClock {
                    override fun now(): Long = clockSamples.removeFirstOrNull()
                        ?: error("Prototype partial condition metadata clock exhausted")
                },
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertTrue("scripted campaign clock must be exhausted", clockSamples.isEmpty())
        assertEquals(2, transport.postedBodies.size)
        assertEquals(
            listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL, result.summary.status)
        assertRunContract(
            run = result.runs[0],
            taskSuccess = true,
            scoreEligible = true,
            eventsExpected = 120,
            eventsReceived = 120,
            failureReason = null,
            terminalReceiptValid = true,
        )
        assertTrue(result.runs[0].metrics != null)

        val slowRun = result.runs[1]
        assertRunContract(
            run = slowRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 2,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        assertNull(slowRun.completedStreamResult)
        val partialEvidence = requireNotNull(slowRun.partialEvidence)
        assertEquals(slowT0, partialEvidence.t0MonotonicNanos)
        assertEquals(listOf(1, 2), partialEvidence.validatedContentEvents.map { it.sequence })
        assertEquals(
            listOf(firstContentTimestamp, secondContentTimestamp),
            partialEvidence.validatedContentEvents.map { it.clientMonotonicNanos },
        )
        assertEquals(failureTimestamp, partialEvidence.interruptionClientMonotonicNanos)
        val projectedEvidence = evidenceEvents(slowRun)
        assertEquals(
            listOf("run_started", "content_event", "content_event", "run_failed"),
            projectedEvidence.map(::eventType),
        )
        assertEquals(
            failureTimestamp,
            projectedEvidence.last().getValue("client_monotonic_ns").jsonPrimitive.long,
        )

        val slowSummary = result.summary.conditionSummaries[1]
        assertEquals("slow_v0.1", slowSummary.conditionId)
        assertEquals(1, slowSummary.plannedRuns)
        assertEquals(1, slowSummary.attemptedRuns)
        assertEquals(0, slowSummary.successfulRuns)
        assertEquals(1, slowSummary.failedRuns)
        assertEquals(0, slowSummary.notStartedRuns)
        assertEquals(0.0, slowSummary.successRate, 0.0)
        assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, slowSummary.confidence)
        listOf(
            slowSummary.medianTtftMs,
            slowSummary.minTtftMs,
            slowSummary.maxTtftMs,
            slowSummary.medianCompletionMs,
            slowSummary.minCompletionMs,
            slowSummary.maxCompletionMs,
            slowSummary.medianStreamEventRateEps,
            slowSummary.medianStallCount,
            slowSummary.medianStallDurationMs,
            slowSummary.medianStallFraction,
        ).forEach { aggregate -> assertNull(aggregate) }
        assertNull(slowSummary.rpi)

        val metrics = requireNotNull(slowRun.metrics)
        assertEquals(200.0, metrics.ttftMs)
        assertNull(metrics.completionMs)
        assertEquals(700.0, metrics.streamSpanMs)
        assertEquals(1_000.0 / 700.0, metrics.streamEventRateEps)
        assertEquals(500.0, metrics.stallThresholdMs)
        assertEquals(1, metrics.stallCount)
        assertEquals("Slow nominal interval must determine stall duration", 575.0, metrics.stallDurationMs)
        assertEquals(575.0 / 700.0, metrics.stallFraction)
    }

    @Test
    fun baselineInterruptionReportsNoSuccessfulBaselineRpiReasons() = runBlocking {
        val campaignId = "campaign-quick-baseline-interrupted-rpi"
        val runIds = listOf("run-baseline-stop-01", "run-baseline-stop-02", "run-baseline-stop-03")
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(interruptedStream(campaignId, runIds[0], "baseline_v0.1")),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(1, transport.postedBodies.size)
        assertEquals(
            listOf("INTERRUPTED", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL, result.summary.status)
        val conditionSummaries = result.summary.conditionSummaries
        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            conditionSummaries.map { summary -> summary.conditionId },
        )
        val expectedCounts = listOf(
            listOf(1, 1, 0, 1, 0),
            listOf(1, 0, 0, 0, 1),
            listOf(1, 0, 0, 0, 1),
        )
        val expectedReasons = listOf(
            "campaign_incomplete",
            "no_successful_baseline",
            "no_successful_condition_run",
            "mandatory_metric_missing",
        )
        conditionSummaries.zip(expectedCounts).forEach { (summary, counts) ->
            assertEquals(counts[0], summary.plannedRuns)
            assertEquals(counts[1], summary.attemptedRuns)
            assertEquals(counts[2], summary.successfulRuns)
            assertEquals(counts[3], summary.failedRuns)
            assertEquals(counts[4], summary.notStartedRuns)
            assertEquals(0.0, summary.successRate, 0.0)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, summary.confidence)
            listOf(
                summary.medianTtftMs,
                summary.minTtftMs,
                summary.maxTtftMs,
                summary.medianCompletionMs,
                summary.minCompletionMs,
                summary.maxCompletionMs,
                summary.medianStreamEventRateEps,
                summary.medianStallCount,
                summary.medianStallDurationMs,
                summary.medianStallFraction,
            ).forEach { aggregate -> assertNull(aggregate) }
            assertNull(summary.rpi)
            assertEquals("rpi-0.1", summary.rpiPolicyId)
            assertEquals("campaign_incomplete", summary.primaryNullReason)
            assertEquals(expectedReasons, summary.allNullReasons)
        }
    }

    @Test
    fun ioExceptionAfterCanonicalPrefixReturnsPartialCampaignWithRetainedEvidence() = runBlocking {
        val campaignId = "campaign-quick-io-prefix"
        val runIds = listOf("run-io-01", "run-io-02", "run-io-03")
        val baselineStream = completeBaselineStream(campaignId, runIds[0])
        val slowPrefix = interruptedSlowStream(campaignId, runIds[1])
        val postedBodies = mutableListOf<String>()
        var postObservedCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("Runner must use the observed Prototype transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                postedBodies += requestBody
                return when (++postObservedCalls) {
                    1 -> {
                        observer.beforeDispatch()
                        baselineStream.events.forEach(observer.onRawEvent)
                        baselineStream
                    }

                    2 -> {
                        observer.beforeDispatch()
                        slowPrefix.events.forEach(observer.onRawEvent)
                        throw IOException("forced transport interruption")
                    }

                    else -> error("not_started Quick slot reached the transport")
                }
            }
        }
        val clockDomainCalls = mutableListOf<Int>()
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, RecordingSteppedClock()),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index ->
                clockDomainCalls += index
                "clock-domain-$index"
            },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(2, postObservedCalls)
        assertEquals(2, postedBodies.size)
        assertEquals(listOf(1, 2), clockDomainCalls)
        assertEquals(
            listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )

        val baselineRun = result.runs[0]
        assertRunContract(
            run = baselineRun,
            taskSuccess = true,
            scoreEligible = true,
            eventsExpected = 120,
            eventsReceived = 120,
            failureReason = null,
            terminalReceiptValid = true,
        )
        assertEquals(122, requireNotNull(baselineRun.completedStreamResult).rawEvents.size)

        val interruptedRun = result.runs[1]
        assertRunContract(
            run = interruptedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 1,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        val partial = requireNotNull(interruptedRun.partialEvidence)
        assertEquals(2, partial.rawEvents.size)
        partial.rawEvents.indices.forEach { index ->
            assertSame(slowPrefix.events[index], partial.rawEvents[index])
            assertArrayEquals(slowPrefix.events[index].bytes, partial.rawEvents[index].bytes)
        }
        assertEquals(1, partial.validatedContentEvents.size)
        assertSame(slowPrefix.events[1], partial.validatedContentEvents.single().rawEvent)

        val interruptedEvidence = evidenceEvents(interruptedRun)
        assertEquals(
            listOf("run_started", "content_event", "run_failed"),
            interruptedEvidence.map(::eventType),
        )
        interruptedEvidence.forEach { event ->
            assertCanonicalEvidenceEnvelope(
                event = event,
                campaignId = campaignId,
                runId = runIds[1],
                runIndex = 2,
                condition = evidenceCondition("slow_v0.1"),
                clockDomainId = "clock-domain-2",
            )
        }
        assertEquals(
            buildJsonObject {
                put("t0_monotonic_ns", JsonPrimitive(partial.t0MonotonicNanos))
            },
            interruptedEvidence[0].getValue("details"),
        )
        assertEquals(
            buildJsonObject {
                put("seq", JsonPrimitive(1))
                put("planned_offset_ms", JsonPrimitive(650))
                put("payload_id", JsonPrimitive("ref-0001"))
            },
            interruptedEvidence[1].getValue("details"),
        )
        assertEquals(
            buildJsonObject {
                put("failure_reason", JsonPrimitive("stream_interrupted"))
                put("events_received", JsonPrimitive(1))
            },
            interruptedEvidence[2].getValue("details"),
        )
        assertEquals(
            partial.validatedContentEvents.single().clientMonotonicNanos,
            interruptedEvidence[1].getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        assertEquals(
            partial.interruptionClientMonotonicNanos,
            interruptedEvidence[2].getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        assertTrue(
            partial.interruptionClientMonotonicNanos >
                partial.validatedContentEvents.single().clientMonotonicNanos,
        )

        val notStartedRun = result.runs[2]
        assertRunContract(
            run = notStartedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 0,
            failureReason = "not_started",
            terminalReceiptValid = null,
        )
        assertTrue(evidenceEvents(notStartedRun).isEmpty())
        assertEquals("PARTIAL", result.summary.status.name)
        assertEquals(3, result.summary.plannedRuns)
        assertEquals(2, result.summary.attemptedRuns)
        assertEquals(1, result.summary.successfulRuns)
        assertEquals(1, contractProperty(result.summary, "failedRuns"))
        assertEquals(1, contractProperty(result.summary, "notStartedRuns"))
        assertEquals(1.0 / 3.0, contractProperty(result.summary, "successRate") as Double, 0.0)
    }

    @Test
    fun ioExceptionAfterRunStartedReturnsZeroContentInterruptedEvidence() = runBlocking {
        val campaignId = "campaign-quick-io-zero-content"
        val runIds = listOf("run-zero-01", "run-zero-02", "run-zero-03")
        val baselineStream = completeBaselineStream(campaignId, runIds[0])
        val slowRunStarted = interruptedSlowStream(campaignId, runIds[1]).events.first()
        var postObservedCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("Runner must use the observed Prototype transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                return when (++postObservedCalls) {
                    1 -> {
                        observer.beforeDispatch()
                        baselineStream.events.forEach(observer.onRawEvent)
                        baselineStream
                    }

                    2 -> {
                        observer.beforeDispatch()
                        observer.onRawEvent(slowRunStarted)
                        throw IOException("forced zero-content transport interruption")
                    }

                    else -> error("not_started Quick slot reached the transport")
                }
            }
        }
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, RecordingSteppedClock()),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index -> "clock-domain-$index" },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(2, postObservedCalls)
        assertEquals(
            listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        val interruptedRun = result.runs[1]
        assertRunContract(
            run = interruptedRun,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 0,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        val partial = requireNotNull(interruptedRun.partialEvidence)
        assertEquals(1, partial.rawEvents.size)
        assertSame(slowRunStarted, partial.rawEvents.single())
        assertArrayEquals(slowRunStarted.bytes, partial.rawEvents.single().bytes)
        assertTrue(partial.validatedContentEvents.isEmpty())
        assertTrue(partial.interruptionClientMonotonicNanos > partial.t0MonotonicNanos)

        val interruptedEvidence = evidenceEvents(interruptedRun)
        assertEquals(listOf("run_started", "run_failed"), interruptedEvidence.map(::eventType))
        interruptedEvidence.forEach { event ->
            assertCanonicalEvidenceEnvelope(
                event = event,
                campaignId = campaignId,
                runId = runIds[1],
                runIndex = 2,
                condition = evidenceCondition("slow_v0.1"),
                clockDomainId = "clock-domain-2",
            )
        }
        assertEquals(
            buildJsonObject {
                put("failure_reason", JsonPrimitive("stream_interrupted"))
                put("events_received", JsonPrimitive(0))
            },
            interruptedEvidence.last().getValue("details"),
        )
        assertEquals(
            partial.interruptionClientMonotonicNanos,
            interruptedEvidence.last().getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        assertTrue(evidenceEvents(result.runs[2]).isEmpty())
    }

    @Test
    fun interruptedQuickRunRequiresCanonicalAndroidEvidenceSequence() = runBlocking {
        val campaignId = "campaign-quick-evidence-red"
        val runIds = listOf("run-evidence-01", "run-evidence-02", "run-evidence-03")
        val baselineStream = completeBaselineStream(campaignId, runIds[0])
        val slowStream = interruptedSlowStream(campaignId, runIds[1])
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    baselineStream,
                    slowStream,
                ),
            ),
        )
        val clockDomainCalls = mutableListOf<Int>()
        val evidenceClock = RecordingSteppedClock()
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, evidenceClock),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index ->
                clockDomainCalls += index
                "clock-domain-$index"
            },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(listOf(1, 2), clockDomainCalls)
        val baselineRun = result.runs[0]
        val interruptedRun = result.runs[1]
        val notStartedRun = result.runs[2]
        val baselineEvidence = evidenceEvents(baselineRun)
        val interruptedEvidence = evidenceEvents(interruptedRun)
        val notStartedEvidence = evidenceEvents(notStartedRun)

        assertEquals(122, baselineEvidence.size)
        assertEquals(
            listOf("run_started") + List(120) { "content_event" } + "terminal_event",
            baselineEvidence.map(::eventType),
        )
        assertEquals(
            listOf("run_started", "content_event", "run_failed"),
            interruptedEvidence.map(::eventType),
        )
        assertTrue(notStartedEvidence.isEmpty())

        baselineEvidence.forEach { event ->
            assertCanonicalEvidenceEnvelope(
                event = event,
                campaignId = campaignId,
                runId = runIds[0],
                runIndex = 1,
                condition = evidenceCondition("baseline_v0.1"),
                clockDomainId = "clock-domain-1",
            )
        }
        interruptedEvidence.forEach { event ->
            assertCanonicalEvidenceEnvelope(
                event = event,
                campaignId = campaignId,
                runId = runIds[1],
                runIndex = 2,
                condition = evidenceCondition("slow_v0.1"),
                clockDomainId = "clock-domain-2",
            )
        }

        val completedStream = requireNotNull(baselineRun.completedStreamResult)
        val baselineStart = baselineEvidence.first()
        assertEquals(
            completedStream.t0MonotonicNanos,
            baselineStart.getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        assertEquals(
            buildJsonObject {
                put("t0_monotonic_ns", JsonPrimitive(completedStream.t0MonotonicNanos))
            },
            baselineStart.getValue("details"),
        )
        var previousTimestamp = completedStream.t0MonotonicNanos
        baselineEvidence.subList(1, 121).forEachIndexed { index, event ->
            val sequence = index + 1
            val observed = completedStream.validatedContentEvents[index]
            val timestamp = event.getValue("client_monotonic_ns").jsonPrimitive.long
            assertEquals(observed.clientMonotonicNanos, timestamp)
            assertTrue("content timestamp $sequence did not advance", timestamp > previousTimestamp)
            previousTimestamp = timestamp
            assertEquals(
                buildJsonObject {
                    put("seq", JsonPrimitive(sequence))
                    put("planned_offset_ms", JsonPrimitive(plannedOffsetMs("baseline_v0.1", sequence)))
                    put("payload_id", JsonPrimitive("ref-${sequence.toString().padStart(4, '0')}"))
                },
                event.getValue("details"),
            )
        }
        val baselineTerminal = baselineEvidence.last()
        val terminalTimestamp = baselineTerminal.getValue("client_monotonic_ns").jsonPrimitive.long
        assertEquals(completedStream.terminalClientMonotonicNanos, terminalTimestamp)
        assertTrue("terminal timestamp did not follow content", terminalTimestamp > previousTimestamp)
        val expectedTerminalDetails = PrototypeTerminalProjection.project(
            serverDetails = completedStream.decodedTerminal.envelope.getValue("details").jsonObject,
            androidAdditions = buildJsonObject {
                put("receipt_version", JsonPrimitive("prototype-terminal-receipt-0.1"))
                put("events_expected", JsonPrimitive(120))
                put("events_received", JsonPrimitive(120))
                put("clock_domain_id", JsonPrimitive("clock-domain-1"))
                put("clock_source", JsonPrimitive(ANDROID_CLOCK_SOURCE))
                put("clock_unit", JsonPrimitive("ns"))
                put("clock_epoch", JsonPrimitive("device_boot"))
                put("t0_monotonic_ns", JsonPrimitive(completedStream.t0MonotonicNanos))
                put("client_monotonic_ns", JsonPrimitive(terminalTimestamp))
            },
        )
        assertEquals(24, expectedTerminalDetails.size)
        assertEquals(expectedTerminalDetails, baselineTerminal.getValue("details"))

        val partial = requireNotNull(interruptedRun.partialEvidence)
        val interruptedStart = interruptedEvidence[0]
        val interruptedContent = interruptedEvidence[1]
        val interruptedFailure = interruptedEvidence[2]
        assertEquals(
            partial.t0MonotonicNanos,
            interruptedStart.getValue("client_monotonic_ns").jsonPrimitive.long,
        )
        assertEquals(
            buildJsonObject {
                put("t0_monotonic_ns", JsonPrimitive(partial.t0MonotonicNanos))
            },
            interruptedStart.getValue("details"),
        )
        assertEquals(
            buildJsonObject {
                put("seq", JsonPrimitive(1))
                put("planned_offset_ms", JsonPrimitive(650))
                put("payload_id", JsonPrimitive("ref-0001"))
            },
            interruptedContent.getValue("details"),
        )
        val contentTimestamp = interruptedContent.getValue("client_monotonic_ns").jsonPrimitive.long
        assertEquals(partial.validatedContentEvents.single().clientMonotonicNanos, contentTimestamp)
        val failureTimestamp = interruptedFailure.getValue("client_monotonic_ns").jsonPrimitive.long
        assertEquals(partial.interruptionClientMonotonicNanos, failureTimestamp)
        assertTrue("run_failed timestamp did not follow content", failureTimestamp > contentTimestamp)
        assertEquals(10L, failureTimestamp - contentTimestamp)
        assertEquals(evidenceClock.samples.last(), failureTimestamp)
        assertEquals(
            buildJsonObject {
                put("failure_reason", JsonPrimitive("stream_interrupted"))
                put("events_received", JsonPrimitive(1))
            },
            interruptedFailure.getValue("details"),
        )

        assertEquals(2, partial.rawEvents.size)
        partial.rawEvents.indices.forEach { index ->
            assertSame(slowStream.events[index], partial.rawEvents[index])
            assertArrayEquals(slowStream.events[index].bytes, partial.rawEvents[index].bytes)
        }
        assertEquals(
            listOf("event: run_started", "event: content_event"),
            partial.rawEvents.map { raw -> raw.bytes.toString(Charsets.UTF_8).lineSequence().first() },
        )
    }

    @Test
    fun invalidSequenceReturnsCanonicalFailedRunAndNotStartedSuffix() =
        runBlocking {
            val campaignId = "campaign-quick-invalid-prefix"
            val transport = QueuedRawPostTransport(
                streams = ArrayDeque(
                    listOf(
                        rawStream(
                            blocks = listOf(
                                runStartedBlock(campaignId, "run-invalid-01", "baseline_v0.1"),
                                contentBlock(
                                    campaignId,
                                    "run-invalid-01",
                                    "baseline_v0.1",
                                    sequence = 1,
                                ),
                                contentBlock(
                                    campaignId,
                                    "run-invalid-01",
                                    "baseline_v0.1",
                                    sequence = 3,
                                ),
                            ),
                            truncatedTail = false,
                        ),
                    ),
                ),
            )
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index -> "run-invalid-0$index" },
                waitBetweenRuns = { _ -> },
            )

            val result = runner.run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )

            assertEquals(
                listOf("INVALID_SEQUENCE", "NOT_STARTED", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            val failed = result.runs.first()
            assertRunContract(
                run = failed,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 1,
                failureReason = "invalid_sequence",
                terminalReceiptValid = null,
            )
            assertNull(failed.metrics)
            assertEquals(listOf("run_started", "content_event", "run_failed"), evidenceEvents(failed).map(::eventType))
            assertEquals(
                buildJsonObject {
                    put("failure_reason", JsonPrimitive("invalid_sequence"))
                    put("events_received", JsonPrimitive(1))
                },
                evidenceEvents(failed).last().getValue("details"),
            )
            assertEquals(2, requireNotNull(failed.partialEvidence).rawEvents.size)
            assertEquals("PARTIAL", result.summary.status.name)
            assertEquals(1, result.summary.attemptedRuns)
            assertEquals(0, result.summary.successfulRuns)
            assertEquals(1, contractProperty(result.summary, "failedRuns"))
            assertEquals(2, contractProperty(result.summary, "notStartedRuns"))
            assertEquals(1, transport.postedBodies.size)
        }

    @Test
    fun earlyDoneReturnsCanonicalInvalidSequenceResultInsteadOfDroppingTheCampaign() =
        runBlocking {
            val campaignId = "campaign-quick-early-done"
            val runId = "run-early-done-01"
            val transport = QueuedRawPostTransport(
                streams = ArrayDeque(
                    listOf(
                        rawStream(
                            blocks = listOf(
                                runStartedBlock(campaignId, runId, "baseline_v0.1"),
                                contentBlock(
                                    campaignId,
                                    runId,
                                    "baseline_v0.1",
                                    sequence = 1,
                                ),
                                doneBlock(
                                    campaignId,
                                    runId,
                                    "baseline_v0.1",
                                    runIndex = 1,
                                    campaignMode = PrototypeQuickCampaignRunner.CampaignMode.QUICK,
                                ),
                            ),
                            truncatedTail = false,
                        ),
                    ),
                ),
            )
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index -> if (index == 1) runId else "run-early-done-0$index" },
                waitBetweenRuns = { _ -> },
            )

            val result = runner.run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )

            assertEquals(
                listOf("INVALID_SEQUENCE", "NOT_STARTED", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            val invalid = result.runs.first()
            assertEquals(1, invalid.eventsReceived)
            assertEquals("invalid_sequence", invalid.failureReason)
            assertNull(invalid.metrics)
            assertEquals(
                listOf("run_started", "content_event", "run_failed"),
                evidenceEvents(invalid).map(::eventType),
            )
            assertEquals("PARTIAL", result.summary.status.name)
        }

    @Test
    fun userCancellationReturnsCanonicalCancelledRunAndNotStartedSuffix() = runBlocking {
        val campaignId = "campaign-quick-cancelled-prefix"
        val runId = "run-cancelled-01"
        val prefix = rawStream(
            blocks = listOf(
                runStartedBlock(campaignId, runId, "baseline_v0.1"),
                contentBlock(campaignId, runId, "baseline_v0.1", sequence = 1),
            ),
            truncatedTail = false,
        )
        val cancellation = CancellationException("cancelled by Prototype user")
        val authority = PrototypeUserCancellationAuthority().also { it.request() }
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("observed transport path required")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                postCalls += 1
                observer.beforeDispatch()
                prefix.events.forEach(observer.onRawEvent)
                throw cancellation
            }
        }
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> if (index == 1) runId else "run-cancelled-0$index" },
            waitBetweenRuns = { _ -> },
        )

        val failure = try {
            withContext(authority) {
                runner.run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }
            org.junit.Assert.fail("cancelled campaign result was not emitted")
            error("unreachable")
        } catch (error: PrototypeCampaignCancelledWithResult) {
            error
        }

        val result = failure.result
        assertEquals(listOf("CANCELLED", "NOT_STARTED", "NOT_STARTED"), result.runs.map { it.status.name })
        val cancelled = result.runs.first()
        assertRunContract(
            run = cancelled,
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 1,
            failureReason = "cancelled",
            terminalReceiptValid = null,
        )
        assertEquals(
            listOf("run_started", "content_event", "run_cancelled"),
            evidenceEvents(cancelled).map(::eventType),
        )
        assertNotNull(cancelled.metrics?.ttftMs)
        assertNull(cancelled.metrics?.completionMs)
        assertEquals("CANCELLED", result.summary.status.name)
        assertEquals(1, result.summary.attemptedRuns)
        assertEquals(1, result.summary.failedRuns)
        assertEquals(2, result.summary.notStartedRuns)
        assertEquals(1, postCalls)
    }

    @Test
    fun cancellationWinningBeforeFinalReceiptClaimReturnsCanonicalFullPrefixCancellation() =
        runBlocking {
            val campaignId = "campaign-quick-cancelled-at-final-receipt"
            val runIds = listOf("run-final-cancel-01", "run-final-cancel-02", "run-final-cancel-03")
            val authority = PrototypeUserCancellationAuthority()
            var completionClaims = 0
            val completionAuthority = PrototypeCampaignResultReadyAuthority {
                completionClaims += 1
                assertTrue(authority.request())
                false
            }
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(
                    transport = QueuedRawPostTransport(
                        streams = ArrayDeque(
                            listOf(
                                completeStream(campaignId, runIds[0], "baseline_v0.1"),
                                completeStream(campaignId, runIds[1], "slow_v0.1"),
                                completeStream(campaignId, runIds[2], "unstable_v0.1"),
                            ),
                        ),
                    ),
                    clock = IncrementingClock(),
                ),
                runIdFactory = { index -> runIds[index - 1] },
                waitBetweenRuns = { _ -> },
            )

            val failure = try {
                withContext(authority + completionAuthority) {
                    runner.run(
                        endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                        campaignId = campaignId,
                    )
                }
                org.junit.Assert.fail("final-receipt cancellation result was not emitted")
                error("unreachable")
            } catch (error: PrototypeCampaignCancelledWithResult) {
                error
            }

            assertEquals(1, completionClaims)
            assertEquals(
                listOf("COMPLETE", "COMPLETE", "CANCELLED"),
                failure.result.runs.map { run -> run.status.name },
            )
            val cancelled = failure.result.runs.last()
            assertEquals(120, cancelled.eventsReceived)
            assertEquals("cancelled", cancelled.failureReason)
            assertEquals(
                listOf("run_started") + List(120) { "content_event" } + "run_cancelled",
                evidenceEvents(cancelled).map(::eventType),
            )
            assertNull(cancelled.metrics?.completionMs)
            assertEquals("COMPLETE", failure.result.summary.status.name)
        }

    @Test
    fun duplicateFinalReceiptFailsBeforeCampaignResultReadyClaim() = runBlocking {
        val campaignId = "campaign-quick-duplicate-final-receipt"
        val runIds = listOf("run-duplicate-01", "run-duplicate-02", "run-duplicate-03")
        val finalStream = completeStream(campaignId, runIds[2], "unstable_v0.1")
        val duplicateDone = finalStream.events.last()
        val invalidFinalStream = RawSseStream(
            events = finalStream.events + duplicateDone,
            readCount = finalStream.readCount + 1,
            totalBytes = finalStream.totalBytes + duplicateDone.bytes.size,
            truncatedTail = finalStream.truncatedTail,
            eofNanos = finalStream.eofNanos,
        )
        var completionClaims = 0
        val completionAuthority = PrototypeCampaignResultReadyAuthority {
            completionClaims += 1
            true
        }
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = QueuedRawPostTransport(
                    streams = ArrayDeque(
                        listOf(
                            completeStream(campaignId, runIds[0], "baseline_v0.1"),
                            completeStream(campaignId, runIds[1], "slow_v0.1"),
                            invalidFinalStream,
                        ),
                    ),
                ),
                clock = IncrementingClock(),
            ),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        )

        try {
            withContext(completionAuthority) {
                runner.run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }
            org.junit.Assert.fail("duplicate final receipt was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain exactly one final done event",
                error.message,
            )
        }

        assertEquals(0, completionClaims)
    }

    @Test
    fun userCancellationBeforeDispatchReturnsAllNotStartedCanonicalResult() = runBlocking {
        val campaignId = "campaign-quick-cancelled-before-dispatch"
        val cancellation = CancellationException("cancelled before Prototype dispatch")
        val authority = PrototypeUserCancellationAuthority().also { it.request() }
        var dispatchObserved = false
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("observed transport path required")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                throw cancellation
            }
        }
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> "run-cancelled-before-dispatch-0$index" },
            waitBetweenRuns = { _ -> },
        )

        val failure = try {
            withContext(authority) {
                runner.run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }
            org.junit.Assert.fail("cancelled campaign result was not emitted")
            error("unreachable")
        } catch (error: PrototypeCampaignCancelledWithResult) {
            error
        }

        assertFalse(dispatchObserved)
        val observation = failure.cause as PrototypeRunCancellationObservation
        assertNull(observation.evidence)
        assertSame(cancellation, observation.cause)
        assertEquals(
            listOf("NOT_STARTED", "NOT_STARTED", "NOT_STARTED"),
            failure.result.runs.map { it.status.name },
        )
        assertEquals("PARTIAL", failure.result.summary.status.name)
        assertEquals(0, failure.result.summary.attemptedRuns)
        assertEquals(3, failure.result.summary.notStartedRuns)
    }

    @Test
    fun userCancellationDuringCooldownKeepsCompletedPrefixAndNotStartedSuffix() = runBlocking {
        val campaignId = "campaign-quick-cancelled-during-cooldown"
        val cancellation = CancellationException("cancelled during Prototype cooldown")
        val authority = PrototypeUserCancellationAuthority().also { it.request() }
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(completeBaselineStream(campaignId, "run-cancelled-cooldown-01")),
            ),
        )
        var cooldownCalls = 0
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> "run-cancelled-cooldown-0$index" },
            waitBetweenRuns = {
                cooldownCalls += 1
                throw cancellation
            },
        )

        val failure = try {
            withContext(authority) {
                runner.run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    campaignId = campaignId,
                )
            }
            org.junit.Assert.fail("cancelled campaign result was not emitted")
            error("unreachable")
        } catch (error: PrototypeCampaignCancelledWithResult) {
            error
        }

        assertSame(cancellation, failure.cause)
        assertEquals(1, cooldownCalls)
        assertEquals(
            listOf("COMPLETE", "NOT_STARTED", "NOT_STARTED"),
            failure.result.runs.map { it.status.name },
        )
        assertEquals("PARTIAL", failure.result.summary.status.name)
        assertEquals(1, failure.result.summary.attemptedRuns)
        assertEquals(1, failure.result.summary.successfulRuns)
        assertEquals(2, failure.result.summary.notStartedRuns)
    }

    @Test
    fun outOfOrderTruncatedPrefixReturnsInvalidSequenceAfterCompletedRun() =
        runBlocking {
            val campaignId = "campaign-quick-invalid-truncated-prefix"
            val transport = QueuedRawPostTransport(
                streams = ArrayDeque(
                    listOf(
                        completeBaselineStream(campaignId, "run-invalid-tail-01"),
                        rawStream(
                            blocks = listOf(
                                runStartedBlock(campaignId, "run-invalid-tail-02", "slow_v0.1"),
                                contentBlock(
                                    campaignId,
                                    "run-invalid-tail-02",
                                    "slow_v0.1",
                                    sequence = 2,
                                ),
                                contentBlock(
                                    campaignId,
                                    "run-invalid-tail-02",
                                    "slow_v0.1",
                                    sequence = 1,
                                ),
                            ),
                            truncatedTail = true,
                        ),
                    ),
                ),
            )
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
                runIdFactory = { index -> "run-invalid-tail-0$index" },
                waitBetweenRuns = { _ -> },
            )

            val result = runner.run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                campaignId = campaignId,
            )
            assertEquals(
                listOf("COMPLETE", "INVALID_SEQUENCE", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            assertEquals("invalid_sequence", result.runs[1].failureReason)
            assertNull(result.runs[1].metrics)
            assertEquals(
                listOf("run_started", "run_failed"),
                evidenceEvents(result.runs[1]).map(::eventType),
            )
            assertEquals(2, transport.postedBodies.size)
        }

    @Test
    fun contentBeforeRunStartedReturnsTypedInvalidSequenceCampaignResult() = runBlocking {
        val campaignId = "campaign-quick-content-before-run-started"
        val runId = "run-content-before-start-01"
        val observedEvents = rawStream(
            blocks = listOf(
                contentBlock(campaignId, runId, "baseline_v0.1", sequence = 1),
                runStartedBlock(campaignId, runId, "baseline_v0.1"),
                contentBlock(campaignId, runId, "baseline_v0.1", sequence = 2),
            ),
            truncatedTail = false,
        ).events
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream =
                error("Runner must use the observed Prototype transport path")

            override suspend fun postObserved(
                url: String,
                requestBody: String,
                observer: PrototypeRawPostObserver,
            ): RawSseStream {
                observer.beforeDispatch()
                observedEvents.forEach(observer.onRawEvent)
                throw IOException("forced out-of-order transport interruption")
            }
        }

        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, RecordingSteppedClock()),
            runIdFactory = { index -> "run-content-before-start-0$index" },
            clockDomainIdFactory = { index -> "clock-domain-$index" },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(
            listOf("INVALID_SEQUENCE", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals("invalid_sequence", result.runs.first().failureReason)
        assertEquals(0, result.runs.first().eventsReceived)
        assertEquals(
            listOf("run_started", "run_failed"),
            evidenceEvents(result.runs.first()).map(::eventType),
        )
    }

    @Test
    fun thirdQuickRunInterruptionKeepsCampaignCompleteWithoutNotStartedSlots() = runBlocking {
        val campaignId = "campaign-quick-third-interrupted"
        val runIds = listOf("run-third-01", "run-third-02", "run-third-03")
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    completeStream(campaignId, runIds[0], "baseline_v0.1"),
                    completeStream(campaignId, runIds[1], "slow_v0.1"),
                    interruptedStream(campaignId, runIds[2], "unstable_v0.1"),
                ),
            ),
        )
        val cooldowns = mutableListOf<Long>()
        val runner = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { cooldowns += it },
        )

        val result = runner.run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(3, transport.postedBodies.size)
        assertEquals(listOf(1L, 1L), cooldowns.map { it / 1_000L })
        assertEquals(
            listOf("COMPLETE", "COMPLETE", "INTERRUPTED"),
            result.runs.map { it.status.name },
        )
        assertRunContract(
            run = result.runs[2],
            taskSuccess = false,
            scoreEligible = false,
            eventsExpected = 120,
            eventsReceived = 1,
            failureReason = "stream_interrupted",
            terminalReceiptValid = null,
        )
        assertEquals("COMPLETE", result.summary.status.name)
        assertEquals(3, result.summary.plannedRuns)
        assertEquals(3, result.summary.attemptedRuns)
        assertEquals(2, result.summary.successfulRuns)
        assertEquals(1, contractProperty(result.summary, "failedRuns"))
        assertEquals(0, contractProperty(result.summary, "notStartedRuns"))
        assertEquals(2.0 / 3.0, contractProperty(result.summary, "successRate") as Double, 0.0)

        val conditionSummaries = result.summary.conditionSummaries
        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            conditionSummaries.map { summary -> summary.conditionId },
        )
        conditionSummaries.take(2).forEachIndexed { index, summary ->
            val metrics = requireNotNull(result.runs[index].metrics)
            assertEquals(1, summary.plannedRuns)
            assertEquals(1, summary.attemptedRuns)
            assertEquals(1, summary.successfulRuns)
            assertEquals(0, summary.failedRuns)
            assertEquals(0, summary.notStartedRuns)
            assertEquals(1.0, summary.successRate, 0.0)
            assertEquals(PrototypeQuickCampaignRunner.Confidence.LOW, summary.confidence)
            assertEquals(metrics.ttftMs, summary.medianTtftMs)
            assertEquals(metrics.ttftMs, summary.minTtftMs)
            assertEquals(metrics.ttftMs, summary.maxTtftMs)
            assertEquals(metrics.completionMs, summary.medianCompletionMs)
            assertEquals(metrics.completionMs, summary.minCompletionMs)
            assertEquals(metrics.completionMs, summary.maxCompletionMs)
            assertEquals(metrics.streamEventRateEps, summary.medianStreamEventRateEps)
            assertEquals(metrics.stallCount?.toDouble(), summary.medianStallCount)
            assertEquals(metrics.stallDurationMs, summary.medianStallDurationMs)
            assertEquals(metrics.stallFraction, summary.medianStallFraction)
            requireNotNull(summary.rpi)
            assertEquals("rpi-0.1", summary.rpiPolicyId)
            assertNull(summary.primaryNullReason)
            assertNull(summary.allNullReasons)
        }
        val unstableSummary = conditionSummaries[2]
        assertEquals(1, unstableSummary.plannedRuns)
        assertEquals(1, unstableSummary.attemptedRuns)
        assertEquals(0, unstableSummary.successfulRuns)
        assertEquals(1, unstableSummary.failedRuns)
        assertEquals(0, unstableSummary.notStartedRuns)
        assertEquals(0.0, unstableSummary.successRate, 0.0)
        assertEquals(PrototypeQuickCampaignRunner.Confidence.NONE, unstableSummary.confidence)
        listOf(
            unstableSummary.medianTtftMs,
            unstableSummary.minTtftMs,
            unstableSummary.maxTtftMs,
            unstableSummary.medianCompletionMs,
            unstableSummary.minCompletionMs,
            unstableSummary.maxCompletionMs,
            unstableSummary.medianStreamEventRateEps,
            unstableSummary.medianStallCount,
            unstableSummary.medianStallDurationMs,
            unstableSummary.medianStallFraction,
        ).forEach { aggregate -> assertNull(aggregate) }
        assertNull(unstableSummary.rpi)
        assertEquals("rpi-0.1", unstableSummary.rpiPolicyId)
        assertEquals("no_successful_condition_run", unstableSummary.primaryNullReason)
        assertEquals(
            listOf("no_successful_condition_run", "mandatory_metric_missing"),
            unstableSummary.allNullReasons,
        )
    }

    @Test
    fun canonicalTruncatedPrefixAlsoReturnsPartialWithoutMaskingValidation() = runBlocking {
        val campaignId = "campaign-quick-valid-truncated-prefix"
        val runIds = listOf("run-valid-tail-01", "run-valid-tail-02", "run-valid-tail-03")
        val transport = QueuedRawPostTransport(
            streams = ArrayDeque(
                listOf(
                    completeBaselineStream(campaignId, runIds[0]),
                    interruptedStream(
                        campaignId = campaignId,
                        runId = runIds[1],
                        conditionId = "slow_v0.1",
                        truncatedTail = true,
                    ),
                ),
            ),
        )
        val result = PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(transport, IncrementingClock()),
            runIdFactory = { index -> runIds[index - 1] },
            waitBetweenRuns = { _ -> },
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            campaignId = campaignId,
        )

        assertEquals(
            listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
            result.runs.map { it.status.name },
        )
        assertEquals("PARTIAL", result.summary.status.name)
        assertEquals(2, contractList(requireNotNull(result.runs[1].partialEvidence), "rawEvents").size)
        assertEquals(2, transport.postedBodies.size)
    }

    private fun testStreamResult(runIndex: Int): PrototypeRunStreamResult =
        PrototypeRunStreamResult(
            rawEvents = emptyList(),
            decodedTerminal = PrototypeSseTerminalDecoder.DecodedDoneFrame(
                eventName = "done",
                envelope = buildJsonObject {
                    put("event_type", JsonPrimitive("terminal_event"))
                    put("details", buildJsonObject {})
                },
            ),
            t0MonotonicNanos = runIndex.toLong(),
            validatedContentEvents = emptyList(),
            terminalClientMonotonicNanos = runIndex.toLong(),
        )

    private fun evidenceEvents(run: PrototypeQuickCampaignRunner.RunResult): List<JsonObject> {
        val getter = run.javaClass.methods.singleOrNull { method ->
            method.name == "getEvidenceEvents" && method.parameterCount == 0
        }
        assertTrue("RunResult must expose evidenceEvents", getter != null)
        return (getter!!.invoke(run) as List<*>).map { event -> event as JsonObject }
    }

    private fun eventType(event: JsonObject): String =
        event.getValue("event_type").jsonPrimitive.content

    private fun assertCanonicalEvidenceEnvelope(
        event: JsonObject,
        campaignId: String,
        runId: String,
        runIndex: Int,
        condition: EvidenceCondition,
        clockDomainId: String,
    ) {
        // This locks the canonical Android output, not a protocol-wide closed envelope claim.
        assertEquals(CANONICAL_EVENT_KEYS, event.keys)
        assertEquals("aneb-prototype-evidence-0.1", event.getValue("schema_version").jsonPrimitive.content)
        assertEquals(campaignId, event.getValue("campaign_id").jsonPrimitive.content)
        assertEquals(runId, event.getValue("run_id").jsonPrimitive.content)
        assertEquals("quick", event.getValue("campaign_mode").jsonPrimitive.content)
        assertEquals(runIndex, event.getValue("run_index").jsonPrimitive.int)
        assertEquals(condition.id, event.getValue("condition_id").jsonPrimitive.content)
        assertEquals("0.1", event.getValue("condition_version").jsonPrimitive.content)
        assertEquals(condition.nominalIntervalMs, event.getValue("nominal_interval_ms").jsonPrimitive.int)
        assertEquals(PROFILE_MANIFEST_SHA256, event.getValue("profile_manifest_sha256").jsonPrimitive.content)
        assertEquals(condition.scheduleHash, event.getValue("schedule_hash").jsonPrimitive.content)
        assertTrue(event.getValue("client_monotonic_ns").jsonPrimitive.long >= 0L)
        assertEquals(ANDROID_CLOCK_SOURCE, event.getValue("clock_source").jsonPrimitive.content)
        assertEquals("ns", event.getValue("clock_unit").jsonPrimitive.content)
        assertEquals("device_boot", event.getValue("clock_epoch").jsonPrimitive.content)
        assertEquals(clockDomainId, event.getValue("clock_domain_id").jsonPrimitive.content)
        assertTrue(event.getValue("clock_domain_id").jsonPrimitive.content.isNotBlank())
        assertEquals("android", event.getValue("source").jsonPrimitive.content)
        assertTrue(event.getValue("details") is JsonObject)
    }

    private companion object {
        private const val PROFILE_MANIFEST_SHA256 =
            "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"
        private const val ANDROID_CLOCK_SOURCE =
            "android.os.SystemClock.elapsedRealtimeNanos"
        private val CANONICAL_EVENT_KEYS = setOf(
            "schema_version",
            "campaign_id",
            "run_id",
            "campaign_mode",
            "run_index",
            "condition_id",
            "condition_version",
            "nominal_interval_ms",
            "profile_manifest_sha256",
            "schedule_hash",
            "event_type",
            "client_monotonic_ns",
            "clock_source",
            "clock_unit",
            "clock_epoch",
            "clock_domain_id",
            "source",
            "details",
        )
    }

    private fun assertRunContract(
        run: PrototypeQuickCampaignRunner.RunResult,
        taskSuccess: Boolean,
        scoreEligible: Boolean,
        eventsExpected: Int,
        eventsReceived: Int,
        failureReason: String?,
        terminalReceiptValid: Boolean?,
    ) {
        assertEquals(taskSuccess, contractProperty(run, "taskSuccess"))
        assertEquals(scoreEligible, contractProperty(run, "scoreEligible"))
        assertEquals(eventsExpected, contractProperty(run, "eventsExpected"))
        assertEquals(eventsReceived, contractProperty(run, "eventsReceived"))
        assertEquals(failureReason, contractProperty(run, "failureReason"))
        assertEquals(terminalReceiptValid, contractProperty(run, "terminalReceiptValid"))
    }

    private fun contractList(target: Any, propertyName: String): List<*> =
        contractProperty(target, propertyName) as List<*>

    private fun contractProperty(target: Any, propertyName: String): Any? {
        val getterName = "get" + propertyName.replaceFirstChar(Char::uppercaseChar)
        val getter = target.javaClass.methods.singleOrNull { method ->
            method.name == getterName && method.parameterCount == 0
        } ?: error("missing partial campaign contract property: $propertyName")
        return getter.invoke(target)
    }
}
