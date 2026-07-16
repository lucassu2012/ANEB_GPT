package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.RealtimeSimulationResultEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.net.RealtimeDownlinkFrame
import com.aneb.probe.net.RealtimeRttSample
import com.aneb.probe.net.RealtimeSessionWirePlan
import com.aneb.probe.net.RealtimeSessionWireResult
import com.aneb.probe.net.RealtimeSimulationWire
import com.aneb.probe.net.RealtimeTurnWirePlan
import com.aneb.probe.net.RealtimeWireCallbacks
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RealtimeSimulationPhase { IDLE, PREPARING, CONNECTING, CLOCK_SYNC, SPEAKING, WAITING, PLAYING, BARGE_IN, FINALIZING, COMPLETE, FAILED }

data class RealtimeSimulationTelemetry(
    val phase: RealtimeSimulationPhase = RealtimeSimulationPhase.IDLE,
    val sessionIndex: Int = 0,
    val sessionCount: Int = 0,
    val turnIndex: Int = 0,
    val turnCount: Int = 0,
    val liveOnTimeRatio: Double? = null,
    val liveHeadroomMs: Double? = null,
    val liveRttMs: Double? = null,
    val liveUpKbps: Double? = null,
    val liveDownKbps: Double? = null,
    val lastResponseMs: Double? = null,
    val progress: Double = 0.0,
    val waveform: List<Double> = emptyList(),
    val updatedAtNanos: Long? = null,
)

data class RealtimeSimulationResult(
    val runId: String,
    val startedAtEpochMs: Long,
    val serverBase: String,
    val profileId: String,
    val profileVersion: String,
    val behaviorModelId: String,
    val behaviorModelVersion: String,
    val behaviorModelHash: String,
    val calibrationStatus: String,
    val variant: String,
    val score: RealtimeScoreResult,
    val evidence: RealtimeRunEvidence,
)

class RealtimeSimulationEngine(private val context: Context) {
    data class Config(
        val serverBase: String,
        val variant: String = "quick",
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
    )

    private val _telemetry = MutableStateFlow(RealtimeSimulationTelemetry())
    val telemetry: StateFlow<RealtimeSimulationTelemetry> = _telemetry.asStateFlow()
    private val _result = MutableStateFlow<RealtimeSimulationResult?>(null)
    val result: StateFlow<RealtimeSimulationResult?> = _result.asStateFlow()

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val runId = TestEngine.newRunId()
        val startedAt = System.currentTimeMillis()
        val configuredBase = config.serverBase.trim().trimEnd('/')
        _result.value = null
        _telemetry.value = RealtimeSimulationTelemetry(phase = RealtimeSimulationPhase.PREPARING)
        log("REALTIME_V1_START run_id=$runId variant=${config.variant} server=$configuredBase")
        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            finishFailed(runId, startedAt, configuredBase, config.variant, "guard_rejected:${guard.reasons.joinToString()}", log)
            log("REALTIME_V1_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }
        var bound: BoundNetwork? = null
        try {
            bound = when (config.transport) {
                TestEngine.TransportMode.AUTO -> null
                TestEngine.TransportMode.WIFI -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)
                TestEngine.TransportMode.CELLULAR -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: GuardException) {
            finishFailed(runId, startedAt, configuredBase, config.variant, "bind_failed:${e.javaClass.simpleName}", log)
            log("REALTIME_V1_END run_id=$runId status=bind_failed")
            return@channelFlow
        }
        try {
            val loaded = RealtimeRuntimeRepository(context).load(config.variant)
            val profile = loaded.profile
            val plan = loaded.plan
            val client = AnebClient(bound)
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sni, ip) ->
                reach = runCatching { ReachabilityProbe(bound).probeDual(sni, ip) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            val wsUrl = measureBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/v1/realtime-sim"
            log("REALTIME_V1_PROFILE id=${profile.profileId} version=${profile.version} model=${plan.modelId}@${plan.modelVersion} sessions=${plan.sessionCount}")
            val evidence = mutableListOf<RealtimeSessionEvidence>()
            var invalidReason: String? = null
            val totalTurns = plan.sessions.sumOf { it.turnCount }.coerceAtLeast(1)
            var completedTurns = 0
            for ((sessionIndex, session) in plan.sessions.withIndex()) {
                val offsetUs = AtomicLong(Long.MIN_VALUE)
                val onTimeWindow = ConcurrentLinkedQueue<Pair<Long, Boolean>>()
                val downlinkWindow = ConcurrentLinkedQueue<Pair<Long, Int>>()
                val sessionStartProgress = completedTurns.toDouble() / totalTurns
                _telemetry.value = _telemetry.value.copy(
                    phase = RealtimeSimulationPhase.CONNECTING,
                    sessionIndex = sessionIndex + 1,
                    sessionCount = plan.sessionCount,
                    turnIndex = 0,
                    turnCount = session.turnCount,
                    liveOnTimeRatio = null,
                    liveHeadroomMs = null,
                    liveRttMs = null,
                    liveUpKbps = null,
                    liveDownKbps = null,
                    progress = sessionStartProgress,
                )
                log("REALTIME_V1_SESSION_START run_id=$runId session=${session.sessionId} turns=${session.turnCount}")
                val wirePlan = RealtimeSessionWirePlan(
                    sessionId = session.sessionId,
                    seed = plan.seed,
                    setupMs = session.setupMs,
                    frameMs = session.frameMs,
                    turns = session.turns.map { turn ->
                        RealtimeTurnWirePlan(
                            turnId = turn.turnId,
                            turnIndex = turn.turnIndex,
                            startAfterPreviousMs = turn.startAfterPreviousMs,
                            uplinkFrames = turn.uplinkFrames,
                            uplinkFrameBytes = turn.uplinkFrameBytes,
                            responseWaitMs = turn.responseWaitMs,
                            plannedDownlinkFrames = turn.plannedDownlinkFrames,
                            downlinkFrameBytes = turn.downlinkFrameBytes,
                            interrupted = turn.interrupted,
                            bargeInAfterFrames = turn.bargeInAfterFrames,
                            expectedStopWithinMs = turn.expectedStopWithinMs,
                        )
                    },
                )
                val uplinkStarted = ConcurrentHashMap<Int, Long>()
                val commitByTurn = ConcurrentHashMap<Int, Long>()
                val firstDownlinkTurns = ConcurrentHashMap.newKeySet<Int>()
                val wire = RealtimeSimulationWire(client).runSession(
                    wsUrl,
                    wirePlan,
                    session.startAfterPreviousMs,
                    RealtimeWireCallbacks(
                        onClockSync = { samples ->
                            offsetUs.set(medianLong(samples.map { it.offsetUs }) ?: Long.MIN_VALUE)
                            _telemetry.value = _telemetry.value.copy(
                                phase = RealtimeSimulationPhase.CLOCK_SYNC,
                                liveRttMs = median(samples.map { it.rttUs / 1_000.0 }),
                            )
                        },
                        onUplink = { turn, bytes, now ->
                            val started = uplinkStarted.putIfAbsent(turn, now) ?: now
                            val seconds = (now - started).coerceAtLeast(1) / 1_000_000_000.0
                            _telemetry.value = _telemetry.value.copy(
                                phase = RealtimeSimulationPhase.SPEAKING,
                                turnIndex = turn + 1,
                                liveUpKbps = bytes * 8.0 / seconds / 1_000.0,
                                updatedAtNanos = now,
                            )
                        },
                        onTurnCommitted = { turn, now ->
                            commitByTurn[turn] = now
                            _telemetry.value = _telemetry.value.copy(
                                phase = RealtimeSimulationPhase.WAITING,
                                turnIndex = turn + 1,
                                updatedAtNanos = now,
                            )
                        },
                        onDownlink = { frame ->
                            val responseMs = if (firstDownlinkTurns.add(frame.turnIndex)) {
                                commitByTurn[frame.turnIndex]?.let { (frame.arrivalNanos - it).coerceAtLeast(0) / 1_000_000.0 }
                            } else null
                            updateDownlinkTelemetry(frame, offsetUs.get(), onTimeWindow, downlinkWindow, responseMs)
                        },
                        onBargeIn = { turn, now ->
                            _telemetry.value = _telemetry.value.copy(
                                phase = RealtimeSimulationPhase.BARGE_IN,
                                turnIndex = turn + 1,
                                updatedAtNanos = now,
                            )
                        },
                    ),
                )
                val measured = measureSession(session, wire)
                evidence += measured
                if (wire.ready?.contractVersion != "aneb-realtime-session-v1") invalidReason = "node_contract_mismatch"
                completedTurns += session.turnCount
                _telemetry.value = _telemetry.value.copy(progress = completedTurns.toDouble() / totalTurns)
                log("REALTIME_V1_SESSION_END run_id=$runId session=${session.sessionId} success=${measured.established && !measured.unexpectedDisconnect} error=${measured.error ?: "none"}")
            }
            _telemetry.value = _telemetry.value.copy(phase = RealtimeSimulationPhase.FINALIZING, progress = 0.98)
            val runEvidence = RealtimeRunEvidence(config.variant, evidence, invalidReason)
            val score = RealtimeSimulationScorer.score(runEvidence)
            val result = RealtimeSimulationResult(
                runId, startedAt, measureBase, profile.profileId, profile.version,
                plan.modelId, plan.modelVersion, plan.modelHash, plan.calibrationStatus,
                plan.variant, score, runEvidence,
            )
            publishResult(result, log)
            _telemetry.value = _telemetry.value.copy(
                phase = if (score.verdict == TokenVerdict.INVALID) RealtimeSimulationPhase.FAILED else RealtimeSimulationPhase.COMPLETE,
                progress = 1.0,
            )
            log("REALTIME_V1_RESULT run_id=$runId score=${score.totalScore ?: "null"} grade=${score.grade ?: "null"} verdict=${score.verdict} confidence=${score.confidence}")
            log("REALTIME_V1_END run_id=$runId status=completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishFailed(runId, startedAt, configuredBase, config.variant, e.toString(), log)
            log("REALTIME_V1_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("REALTIME_V1_END run_id=$runId status=error")
        } finally {
            bound?.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun updateDownlinkTelemetry(
        frame: RealtimeDownlinkFrame,
        offsetUs: Long,
        onTimeWindow: ConcurrentLinkedQueue<Pair<Long, Boolean>>,
        downlinkWindow: ConcurrentLinkedQueue<Pair<Long, Int>>,
        responseMs: Double?,
    ) {
        val now = frame.arrivalNanos
        val cutoff = now - 2_000_000_000L
        val latenessMs = if (offsetUs == Long.MIN_VALUE) null else
            ((now / 1_000.0) - (frame.schedUs - offsetUs)) / 1_000.0
        latenessMs?.let { onTimeWindow.add(now to (it <= PLAYOUT_DEADLINE_MS)) }
        downlinkWindow.add(now to frame.payloadBytes)
        while (onTimeWindow.peek()?.first?.let { it < cutoff } == true) onTimeWindow.poll()
        while (downlinkWindow.peek()?.first?.let { it < cutoff } == true) downlinkWindow.poll()
        val ratio = onTimeWindow.takeIf { it.isNotEmpty() }?.let { values -> values.count { it.second }.toDouble() / values.size }
        val kbps = downlinkWindow.sumOf { it.second } * 8.0 / 2.0 / 1_000.0
        val wave = ratio?.let { (_telemetry.value.waveform + it).takeLast(64) } ?: _telemetry.value.waveform
        _telemetry.value = _telemetry.value.copy(
            phase = RealtimeSimulationPhase.PLAYING,
            turnIndex = frame.turnIndex + 1,
            liveOnTimeRatio = ratio,
            liveHeadroomMs = latenessMs?.let { PLAYOUT_DEADLINE_MS - it },
            liveDownKbps = kbps,
            lastResponseMs = responseMs ?: _telemetry.value.lastResponseMs,
            waveform = wave,
            updatedAtNanos = now,
        )
    }

    private fun measureSession(session: RealtimeRuntimeSession, wire: RealtimeSessionWireResult): RealtimeSessionEvidence {
        val offset = medianLong(wire.rttSamples.map { it.offsetUs })
        val measuredTurns = session.turns.map { turn ->
            val result = wire.turns.firstOrNull { it.plan.turnId == turn.turnId }
            val expected = if (turn.interrupted) turn.bargeInAfterFrames ?: turn.downlinkFramesBeforeStop else turn.plannedDownlinkFrames
            val unique = result?.downlinkFrames.orEmpty().filter { it.seq in 0 until expected }.associateBy { it.seq }
            val latenessBySeq = if (offset == null) emptyMap() else unique.mapValues { (_, frame) ->
                ((frame.arrivalNanos / 1_000.0) - (frame.schedUs - offset)) / 1_000.0
            }
            val usable = (0 until expected).map { seq -> latenessBySeq[seq]?.let { it <= PLAYOUT_DEADLINE_MS } == true }
            val variations = unique.keys.sorted().zipWithNext().mapNotNull { (a, b) ->
                if (b != a + 1) null else
                    (unique.getValue(b).arrivalNanos - unique.getValue(a).arrivalNanos) / 1_000_000.0 - session.frameMs
            }
            val firstLateness = latenessBySeq[0]?.coerceAtLeast(0.0)
            val bargeResponse = if (result?.bargeSentNanos != null && result.summaryArrivalNanos != null) {
                (result.summaryArrivalNanos - result.bargeSentNanos).coerceAtLeast(0) / 1_000_000.0
            } else null
            RealtimeTurnEvidence(
                responseExcessMs = firstLateness,
                expectedFrames = expected,
                uniqueFrames = unique.size,
                onTimeFrames = usable.count { it },
                stallFrames = countLongBadRuns(usable, 3),
                concealFrames = usable.count { !it },
                arrivalVariationMs = variations,
                bargeResponseMs = bargeResponse,
                interrupted = turn.interrupted,
                success = result?.summary?.protocolOk == true && unique.size == expected,
            )
        }
        return RealtimeSessionEvidence(
            established = wire.ready != null,
            setupMs = if (wire.readyArrivalNanos != null) (wire.readyArrivalNanos - wire.connectStartNanos).coerceAtLeast(0) / 1_000_000.0 else null,
            handshakeMs = if (wire.openNanos != null) (wire.openNanos - wire.connectStartNanos).coerceAtLeast(0) / 1_000_000.0 else null,
            rttSamplesMs = wire.rttSamples.map { it.rttUs / 1_000.0 },
            turns = measuredTurns,
            unexpectedDisconnect = wire.error != null || wire.summary?.protocolOk != true,
            error = wire.error,
        )
    }

    private fun countLongBadRuns(values: List<Boolean>, minimum: Int): Int {
        var run = 0
        var total = 0
        values.forEach { good ->
            if (good) {
                if (run >= minimum) total += run
                run = 0
            } else run++
        }
        if (run >= minimum) total += run
        return total
    }

    private suspend fun finishFailed(
        runId: String,
        startedAt: Long,
        server: String,
        variant: String,
        reason: String,
        log: suspend (String) -> Unit,
    ) {
        val evidence = RealtimeRunEvidence(variant, emptyList(), reason)
        publishResult(RealtimeSimulationResult(
            runId, startedAt, server, "ai_realtime_voice_$variant", "unknown",
            "unknown", "unknown", "unknown", "unknown", variant,
            RealtimeSimulationScorer.score(evidence), evidence,
        ), log)
        _telemetry.value = RealtimeSimulationTelemetry(phase = RealtimeSimulationPhase.FAILED)
    }

    private suspend fun publishResult(result: RealtimeSimulationResult, log: suspend (String) -> Unit) {
        _result.value = result
        val write = runCatching {
            AnebDatabase.get(context).realtimeSimulationResultDao().insert(result.toEntity())
        }
        log(
            if (write.isSuccess) {
                "REALTIME_V1_DB_WRITE run_id=${result.runId} ok=true"
            } else {
                "REALTIME_V1_DB_WRITE run_id=${result.runId} ok=false error=${write.exceptionOrNull()?.javaClass?.simpleName}"
            },
        )
    }

    private fun RealtimeSimulationResult.toEntity(): RealtimeSimulationResultEntity = RealtimeSimulationResultEntity(
        runId = runId,
        startedAtEpochMs = startedAtEpochMs,
        serverBase = serverBase,
        claimScope = "application_end_to_end_to_probe_node",
        profileId = profileId,
        profileVersion = profileVersion,
        behaviorModelId = behaviorModelId,
        behaviorModelVersion = behaviorModelVersion,
        behaviorModelHash = behaviorModelHash,
        calibrationStatus = calibrationStatus,
        variant = variant,
        scorePolicyId = "realtime-interaction-score-v1",
        scoreAnchorPolicyId = "compliance-anchors-v1",
        conclusionPolicyId = "realtime-interaction-conclusions-v1",
        totalScore = score.totalScore,
        grade = score.grade,
        verdict = score.verdict.name,
        confidence = score.confidence.name,
        capReason = score.capReason,
        metricsJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            score.metrics.forEach { (id, metric) ->
                put(id, buildJsonObject {
                    putNullableDouble("value", metric.value)
                    putNullableDouble("compliance_ratio", metric.complianceRatio)
                    put("sample_count", metric.sampleCount)
                    put("minimum_sample_count", metric.minimumSampleCount)
                    put("target_compliance_ratio", metric.targetComplianceRatio)
                    putNullableDouble("score", metric.score)
                })
            }
        }),
        conclusionsJson = Json.encodeToString(
            JsonArray.serializer(),
            JsonArray(score.conclusions.map(::JsonPrimitive)),
        ),
        evidenceJson = Json.encodeToString(JsonObject.serializer(), evidence.toJson()),
    )

    private fun RealtimeRunEvidence.toJson(): JsonObject = buildJsonObject {
        put("contract_version", "aneb-realtime-run-evidence-v1")
        put("variant", variant)
        put("invalid_reason", invalidReason?.let(::JsonPrimitive) ?: JsonNull)
        put("sessions", buildJsonArray {
            sessions.forEach { session ->
                add(buildJsonObject {
                    put("established", session.established)
                    putNullableDouble("setup_ms", session.setupMs)
                    putNullableDouble("handshake_ms", session.handshakeMs)
                    put("unexpected_disconnect", session.unexpectedDisconnect)
                    put("error", session.error?.let(::JsonPrimitive) ?: JsonNull)
                    put("rtt_samples_ms", JsonArray(session.rttSamplesMs.map(::JsonPrimitive)))
                    put("turns", buildJsonArray {
                        session.turns.forEach { turn ->
                            add(buildJsonObject {
                                putNullableDouble("response_excess_ms", turn.responseExcessMs)
                                put("expected_frames", turn.expectedFrames)
                                put("unique_frames", turn.uniqueFrames)
                                put("on_time_frames", turn.onTimeFrames)
                                put("stall_frames", turn.stallFrames)
                                put("conceal_frames", turn.concealFrames)
                                put("arrival_variation_ms", JsonArray(turn.arrivalVariationMs.map(::JsonPrimitive)))
                                putNullableDouble("barge_response_ms", turn.bargeResponseMs)
                                put("interrupted", turn.interrupted)
                                put("success", turn.success)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDouble(key: String, value: Double?) {
        put(key, value?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    private fun medianLong(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private companion object {
        const val PLAYOUT_DEADLINE_MS = 150.0
    }
}
