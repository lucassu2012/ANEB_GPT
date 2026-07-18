package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.room.withTransaction
import com.aneb.probe.BuildConfig
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
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RealtimeSimulationPhase { IDLE, PREPARING, CONNECTING, RECOVERING, CLOCK_SYNC, SPEAKING, WAITING, PLAYING, BARGE_IN, FINALIZING, COMPLETE, FAILED }

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
    val scorePolicyId: String,
    val scoreAnchorPolicyId: String,
    val conclusionPolicyId: String,
    val score: RealtimeScoreResult,
    val evidence: RealtimeRunEvidence,
)

internal suspend fun <T> runRealtimeSessionWithMonitor(
    monitorJob: Job,
    disableMonitor: () -> Unit,
    runSession: suspend () -> T,
): T = try {
    runSession()
} finally {
    disableMonitor()
    withContext(NonCancellable) {
        monitorJob.cancelAndJoin()
    }
}

private class RealtimeResultPersistenceException(cause: Throwable) :
    Exception("realtime_result_persistence_failed", cause)

private data class RealtimeDurableResult(
    val result: RealtimeSimulationResult,
    val envelope: com.aneb.probe.data.ResultEnvelopeEntity,
    val radio: FormalRadioEvidence,
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
    private val resultCommitter = DurableResultCommitter(
        store = DurableResultStore { durable: RealtimeDurableResult ->
            val db = AnebDatabase.get(context)
            db.withTransaction {
                db.realtimeSimulationResultDao().insert(durable.result.toEntity())
                db.resultEnvelopeDao().insert(durable.envelope)
                durable.radio.radioEntities(durable.result.runId).takeIf { it.isNotEmpty() }
                    ?.let { db.radioSampleDao().insertAll(it) }
                durable.radio.eventEntities(durable.result.runId).takeIf { it.isNotEmpty() }
                    ?.let { db.envEventDao().insertAll(it) }
            }
        },
        publish = { durable -> _result.value = durable.result },
    )

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val runId = TestEngine.newRunId()
        val startedAt = System.currentTimeMillis()
        val configuredBase = config.serverBase.trim().trimEnd('/')
        val radioCollector = FormalRadioEvidenceCollector(context).also { it.start(this) }
        _result.value = null
        _telemetry.value = RealtimeSimulationTelemetry(phase = RealtimeSimulationPhase.PREPARING)
        log("REALTIME_V1_START run_id=$runId variant=${config.variant} server=$configuredBase")
        val guard = NetGuard.guardCheck(context)
        var envelopeSource = RealtimeResultEnvelopeSource(profile = null)
        if (!guard.ok) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant,
                "guard_rejected:${guard.reasons.joinToString()}", envelopeSource,
                config.transport, guard, null, radioCollector, log,
            )
            log("REALTIME_V1_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }
        val requestedTransport = when (config.transport) {
            TestEngine.TransportMode.AUTO -> null
            TestEngine.TransportMode.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            TestEngine.TransportMode.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        }
        val initialBound = try {
            requestedTransport?.let { NetGuard.acquireNetwork(context, it) }
        } catch (e: GuardException) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant,
                "bind_failed:${e.javaClass.simpleName}", envelopeSource,
                config.transport, guard, null, radioCollector, log,
            )
            log("REALTIME_V1_END run_id=$runId status=bind_failed")
            return@channelFlow
        }
        val sessionResources = RefreshingSessionResource<BoundNetwork, AnebClient>(
            initialLease = initialBound,
            refreshEnabled = requestedTransport != null,
            isUsable = { it.isUsable },
            acquire = {
                NetGuard.acquireNetwork(context, checkNotNull(requestedTransport))
            },
            release = { it.release() },
            create = { AnebClient(it) },
        )
        try {
            val loaded = RealtimeRuntimeRepository(context).load(config.variant)
            val profile = loaded.profile
            val plan = loaded.plan
            envelopeSource = RealtimeResultEnvelopeSource(
                profile = profile,
                profileHash = loaded.profileHash,
                runtimeArtifactHash = loaded.runtimeArtifactHash,
                profileUri = loaded.profileAssetUri,
                runtimeArtifactUri = loaded.runtimeAssetUri,
            )
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sni, ip) ->
                reach = runCatching { ReachabilityProbe(initialBound).probeDual(sni, ip) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            val wsUrl = measureBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/v1/realtime-sim"
            log("REALTIME_V1_PROFILE id=${profile.profileId} version=${profile.version} model=${plan.modelId}@${plan.modelVersion} sessions=${plan.sessionCount}")
            val evidence = mutableListOf<RealtimeSessionEvidence>()
            var invalidReason: String? = null
            val totalTurns = plan.sessions.sumOf { it.turnCount }.coerceAtLeast(1)
            var completedTurns = 0
            var recoveryStartNanos: Long? = null
            for ((sessionIndex, session) in plan.sessions.withIndex()) {
                val isControlledFault = session.controlledDisconnectAfterTurn != null
                val isRecoveryAttempt = recoveryStartNanos != null && !isControlledFault
                val sessionResource = sessionResources.forSession()
                val client = sessionResource.resource
                if (sessionResource.refreshed) {
                    log(
                        "REALTIME_V1_NETWORK_REFRESH run_id=$runId " +
                            "transport=${config.transport.name.lowercase()} generation=${sessionResource.generation}",
                    )
                }
                val offsetUs = AtomicLong(Long.MIN_VALUE)
                val onTimeWindow = ConcurrentLinkedQueue<Pair<Long, Boolean>>()
                val downlinkWindow = ConcurrentLinkedQueue<Pair<Long, Int>>()
                val loadedRttSamples = Collections.synchronizedList(mutableListOf<Double?>())
                val loadedMonitorEnabled = AtomicBoolean(false)
                val sessionStartProgress = completedTurns.toDouble() / totalTurns
                _telemetry.value = _telemetry.value.copy(
                    phase = if (isRecoveryAttempt) RealtimeSimulationPhase.RECOVERING else RealtimeSimulationPhase.CONNECTING,
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
                val sessionWsUrl = session.controlledDisconnectAfterTurn?.let { turn ->
                    "$wsUrl?controlled_disconnect_after_turn=$turn"
                } ?: wsUrl
                if (session.controlledDisconnectAfterTurn != null) {
                    log(
                        "REALTIME_V1_CONTROLLED_DISCONNECT run_id=$runId session=${session.sessionId} " +
                            "after_turn=${session.controlledDisconnectAfterTurn}",
                    )
                }
                val loadedMonitorJob = launch {
                    while (isActive) {
                        if (!loadedMonitorEnabled.get()) {
                            delay(LOADED_ECHO_IDLE_GAP_MS)
                            continue
                        }
                        val echo = try {
                            client.echo("$measureBase/api/v1/echo")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        val sample = echo?.rttUs?.takeIf { echo.error == null }?.div(1_000.0)
                        loadedRttSamples += sample
                        _telemetry.value = _telemetry.value.copy(
                            liveRttMs = sample,
                            updatedAtNanos = SystemClock.elapsedRealtimeNanos(),
                        )
                        delay(LOADED_ECHO_GAP_MS)
                    }
                }
                val wire = runRealtimeSessionWithMonitor(
                    monitorJob = loadedMonitorJob,
                    disableMonitor = { loadedMonitorEnabled.set(false) },
                ) {
                    RealtimeSimulationWire(client).runSession(
                        sessionWsUrl,
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
                            loadedMonitorEnabled.set(true)
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
                            loadedMonitorEnabled.set(true)
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
                }
                val loadedRttSnapshot = synchronized(loadedRttSamples) { loadedRttSamples.toList() }
                val firstRecoveredAudioNanos = wire.turns
                    .flatMap { it.downlinkFrames }
                    .minOfOrNull { it.arrivalNanos }
                val recoveryMs = if (isRecoveryAttempt && firstRecoveredAudioNanos != null) {
                    recoveryStartNanos?.let { start ->
                        (firstRecoveredAudioNanos - start).coerceAtLeast(0) / 1_000_000.0
                    }
                } else null
                val measured = measureSession(
                    session = session,
                    wire = wire,
                    loadedRttSamplesMs = loadedRttSnapshot,
                    recoveryMs = recoveryMs,
                    reconnectEvents = if (isRecoveryAttempt) 1 else 0,
                    controlledDisconnectExpected = session.controlledDisconnectAfterTurn != null,
                    recoveryStimulusBaselineMs = if (isRecoveryAttempt) {
                        session.turns.firstOrNull()?.let { it.speechMs + it.responseWaitMs }
                    } else {
                        null
                    },
                )
                evidence += measured
                if (isRecoveryAttempt) {
                    log(
                        "REALTIME_V1_RECOVERY run_id=$runId session=${session.sessionId} " +
                            "recovery_ms=${recoveryMs ?: "null"} established=${measured.established}",
                    )
                }
                recoveryStartNanos = nextRealtimeRecoveryStartNanos(
                    controlledPairing = plan.variant == "recovery",
                    currentStartNanos = recoveryStartNanos,
                    isControlledFault = isControlledFault,
                    isRecoveryAttempt = isRecoveryAttempt,
                    unexpectedDisconnect = measured.unexpectedDisconnect,
                    sessionEndNanos = wire.endNanos,
                    recoveryMs = recoveryMs,
                )
                if (wire.ready?.contractVersion != "aneb-realtime-session-v1") invalidReason = "node_contract_mismatch"
                completedTurns += session.turnCount
                _telemetry.value = _telemetry.value.copy(progress = completedTurns.toDouble() / totalTurns)
                log("REALTIME_V1_SESSION_END run_id=$runId session=${session.sessionId} success=${measured.established && !measured.unexpectedDisconnect} error=${measured.error ?: "none"}")
            }
            _telemetry.value = _telemetry.value.copy(phase = RealtimeSimulationPhase.FINALIZING, progress = 0.98)
            val runEvidence = RealtimeRunEvidence(config.variant, evidence, invalidReason)
            val radio = radioCollector.freeze()
            val score = RealtimeSimulationScorer.score(
                runEvidence,
                profile.evaluation.scorePolicyId,
                radio.events,
            )
            val result = RealtimeSimulationResult(
                runId, startedAt, measureBase, profile.profileId, profile.version,
                plan.modelId, plan.modelVersion, plan.modelHash, plan.calibrationStatus,
                plan.variant, profile.evaluation.scorePolicyId, profile.evaluation.scoreAnchorPolicyId,
                profile.evaluation.conclusionPolicyId, score, runEvidence,
            )
            publishResult(
                result = result,
                source = envelopeSource,
                transport = config.transport,
                guard = guard,
                bound = initialBound,
                endedAtEpochMs = System.currentTimeMillis(),
                status = if (score.verdict == TokenVerdict.INVALID) "failed" else "completed",
                radio = radio,
                log = log,
            )
            _telemetry.value = _telemetry.value.copy(
                phase = if (score.verdict == TokenVerdict.INVALID) RealtimeSimulationPhase.FAILED else RealtimeSimulationPhase.COMPLETE,
                progress = 1.0,
            )
            log("REALTIME_V1_RESULT run_id=$runId score=${score.totalScore ?: "null"} grade=${score.grade ?: "null"} verdict=${score.verdict} confidence=${score.confidence}")
            log("REALTIME_V1_END run_id=$runId status=completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RealtimeResultPersistenceException) {
            throw e
        } catch (e: Exception) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant, e.toString(), envelopeSource,
                config.transport, guard, initialBound, radioCollector, log,
            )
            log("REALTIME_V1_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("REALTIME_V1_END run_id=$runId status=error")
        } finally {
            radioCollector.close()
            sessionResources.close()
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

    private fun measureSession(
        session: RealtimeRuntimeSession,
        wire: RealtimeSessionWireResult,
        loadedRttSamplesMs: List<Double?>,
        recoveryMs: Double?,
        reconnectEvents: Int,
        controlledDisconnectExpected: Boolean,
        recoveryStimulusBaselineMs: Double?,
    ): RealtimeSessionEvidence {
        val offset = medianLong(wire.rttSamples.map { it.offsetUs })
        val measuredTurns = session.turns.mapIndexed { turnIndex, turn ->
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
            val responseMs = if (result != null) {
                unique[0]?.arrivalNanos?.let { arrival ->
                    (arrival - result.commitSentNanos).coerceAtLeast(0) / 1_000_000.0
                }
            } else null
            val responseExcessMs = responseMs?.let { (it - turn.responseWaitMs).coerceAtLeast(0.0) }
            val bargeResponse = if (result?.bargeSentNanos != null && result.summaryArrivalNanos != null) {
                (result.summaryArrivalNanos - result.bargeSentNanos).coerceAtLeast(0) / 1_000_000.0
            } else null
            val presentFrames = (0 until expected).map(unique::containsKey)
            val uplinkGoodputKbps = if (result?.uplinkStartNanos != null && result.uplinkEndNanos != null) {
                val seconds = (result.uplinkEndNanos - result.uplinkStartNanos).coerceAtLeast(1) / 1_000_000_000.0
                result.uplinkBytesAccepted * 8.0 / seconds / 1_000.0
            } else null
            val orderedFrames = unique.values.sortedBy { it.arrivalNanos }
            val downlinkGoodputKbps = orderedFrames.takeIf { it.isNotEmpty() }?.let { frames ->
                val durationNanos = if (frames.size == 1) {
                    session.frameMs * 1_000_000L
                } else {
                    (frames.last().arrivalNanos - frames.first().arrivalNanos).coerceAtLeast(0) + session.frameMs * 1_000_000L
                }
                val seconds = durationNanos.coerceAtLeast(1) / 1_000_000_000.0
                frames.sumOf { it.payloadBytes }.toDouble() * 8.0 / seconds / 1_000.0
            }
            val previousSummaryArrival = wire.turns.getOrNull(turnIndex - 1)?.summaryArrivalNanos
            val unplannedOverlap = if (turnIndex == 0) {
                null
            } else {
                result?.uplinkStartNanos?.let { start -> previousSummaryArrival?.let { start < it } }
            }
            RealtimeTurnEvidence(
                responseExcessMs = responseExcessMs,
                expectedFrames = expected,
                uniqueFrames = unique.size,
                onTimeFrames = usable.count { it },
                stallFrames = countLongBadRuns(usable, 3),
                concealFrames = usable.count { !it },
                arrivalVariationMs = variations,
                bargeResponseMs = bargeResponse,
                interrupted = turn.interrupted,
                success = result?.summary?.protocolOk == true && unique.size == expected,
                responseMs = responseMs,
                maxMissingRunFrames = maxBadRun(presentFrames),
                uplinkGoodputKbps = uplinkGoodputKbps,
                downlinkGoodputKbps = downlinkGoodputKbps,
                unplannedOverlap = unplannedOverlap,
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
            loadedRttSamplesMs = loadedRttSamplesMs,
            recoveryMs = recoveryMs,
            reconnectEvents = reconnectEvents,
            controlledDisconnectExpected = controlledDisconnectExpected,
            controlledDisconnectObserved = controlledDisconnectExpected &&
                (wire.error != null || wire.summary?.protocolOk != true),
            recoveryStimulusBaselineMs = recoveryStimulusBaselineMs,
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

    private fun maxBadRun(values: List<Boolean>): Int {
        var run = 0
        var maximum = 0
        values.forEach { good ->
            if (good) {
                run = 0
            } else {
                run++
                maximum = maxOf(maximum, run)
            }
        }
        return maximum
    }

    private suspend fun finishFailed(
        runId: String,
        startedAt: Long,
        server: String,
        variant: String,
        reason: String,
        source: RealtimeResultEnvelopeSource,
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
        radioCollector: FormalRadioEvidenceCollector,
        log: suspend (String) -> Unit,
    ) {
        val evidence = RealtimeRunEvidence(variant, emptyList(), reason)
        val radio = radioCollector.freeze()
        val profile = source.profile
        val scorePolicy = profile?.evaluation?.scorePolicyId?.takeIf(String::isNotBlank)
            ?: if (variant == "recovery") "realtime-recovery-score-v2" else "realtime-interaction-score-v1"
        val result = RealtimeSimulationResult(
            runId, startedAt, server, profile?.profileId ?: "ai_realtime_voice_$variant", profile?.version ?: "unknown",
            profile?.business?.behaviorModelId ?: "unknown",
            profile?.business?.behaviorModelVersion ?: "unknown",
            profile?.business?.behaviorModelHash ?: "unknown",
            profile?.business?.calibrationStatus ?: "unknown",
            variant,
            scorePolicy,
            profile?.evaluation?.scoreAnchorPolicyId?.takeIf(String::isNotBlank) ?: "compliance-anchors-v1",
            profile?.evaluation?.conclusionPolicyId?.takeIf(String::isNotBlank)
                ?: if (variant == "recovery") "realtime-recovery-conclusions-v2" else "realtime-interaction-conclusions-v1",
            RealtimeSimulationScorer.score(evidence, scorePolicy, radio.events),
            evidence,
        )
        publishResult(
            result = result,
            source = source,
            transport = transport,
            guard = guard,
            bound = bound,
            endedAtEpochMs = System.currentTimeMillis(),
            status = "failed",
            radio = radio,
            log = log,
        )
        _telemetry.value = RealtimeSimulationTelemetry(phase = RealtimeSimulationPhase.FAILED)
    }

    private suspend fun publishResult(
        result: RealtimeSimulationResult,
        source: RealtimeResultEnvelopeSource,
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
        endedAtEpochMs: Long,
        status: String,
        radio: FormalRadioEvidence,
        log: suspend (String) -> Unit,
    ) {
        val envelope = RealtimeResultEnvelopeV2.build(
            RealtimeResultEnvelopeInput(
                result = result,
                source = source,
                producer = AnebResultProducerContext(
                    component = "aneb-probe-android",
                    componentVersion = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE,
                ),
                device = AnebResultDeviceContext(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    osRelease = Build.VERSION.RELEASE,
                    apiLevel = Build.VERSION.SDK_INT,
                    appPackage = BuildConfig.APPLICATION_ID,
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                ),
                network = networkContext(transport, guard, bound),
                endedAtEpochMs = endedAtEpochMs,
                status = status,
                radio = radio,
            ),
        )
        try {
            resultCommitter.commit(RealtimeDurableResult(result, envelope, radio))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("REALTIME_V1_DB_WRITE run_id=${result.runId} ok=false error=${e.javaClass.simpleName}")
            throw RealtimeResultPersistenceException(e)
        }
        log("REALTIME_V1_RADIO run_id=${result.runId} status=${radio.collectionStatus} samples=${radio.samples.size}")
        log("REALTIME_V1_DB_WRITE run_id=${result.runId} ok=true")
    }

    private fun RealtimeSimulationResult.toEntity(): RealtimeSimulationResultEntity = RealtimeSimulationResultEntity(
        runId = runId,
        startedAtEpochMs = startedAtEpochMs,
        serverBase = serverBase,
        claimScope = if (variant == "recovery") {
            "controlled_server_disconnect_recovery_to_probe_node"
        } else {
            "application_end_to_end_to_probe_node"
        },
        profileId = profileId,
        profileVersion = profileVersion,
        behaviorModelId = behaviorModelId,
        behaviorModelVersion = behaviorModelVersion,
        behaviorModelHash = behaviorModelHash,
        calibrationStatus = calibrationStatus,
        variant = variant,
        scorePolicyId = scorePolicyId,
        scoreAnchorPolicyId = scoreAnchorPolicyId,
        conclusionPolicyId = conclusionPolicyId,
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
                    put("component_values", buildJsonObject {
                        metric.componentValues.forEach { (name, value) -> put(name, value) }
                    })
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
                    put("loaded_rtt_samples_ms", JsonArray(session.loadedRttSamplesMs.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                    putNullableDouble("recovery_ms", session.recoveryMs)
                    put("reconnect_events", session.reconnectEvents)
                    put("controlled_disconnect_expected", session.controlledDisconnectExpected)
                    put("controlled_disconnect_observed", session.controlledDisconnectObserved)
                    putNullableDouble("recovery_stimulus_baseline_ms", session.recoveryStimulusBaselineMs)
                    put("turns", buildJsonArray {
                        session.turns.forEach { turn ->
                            add(buildJsonObject {
                                putNullableDouble("response_excess_ms", turn.responseExcessMs)
                                putNullableDouble("response_ms", turn.responseMs)
                                put("expected_frames", turn.expectedFrames)
                                put("unique_frames", turn.uniqueFrames)
                                put("on_time_frames", turn.onTimeFrames)
                                put("stall_frames", turn.stallFrames)
                                put("conceal_frames", turn.concealFrames)
                                put("arrival_variation_ms", JsonArray(turn.arrivalVariationMs.map(::JsonPrimitive)))
                                putNullableDouble("barge_response_ms", turn.bargeResponseMs)
                                put("max_missing_run_frames", turn.maxMissingRunFrames?.let(::JsonPrimitive) ?: JsonNull)
                                putNullableDouble("uplink_goodput_kbps", turn.uplinkGoodputKbps)
                                putNullableDouble("downlink_goodput_kbps", turn.downlinkGoodputKbps)
                                put("unplanned_overlap", turn.unplannedOverlap?.let(::JsonPrimitive) ?: JsonNull)
                                put("interrupted", turn.interrupted)
                                put("success", turn.success)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun networkContext(
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
    ): AnebResultNetworkContext {
        val caps = bound?.snapshot?.capabilities
        val activeTransport = bound?.snapshot?.transport
            ?: guard.metadata["active_transports"]?.takeUnless { it == "none" }
        val validated = bound?.let { true }
            ?: guard.metadata["active_validated"]?.toBooleanStrictOrNull()
        val notSuspended = caps?.substringAfter("not_suspended=", "")
            ?.substringBefore(' ')?.toBooleanStrictOrNull()
        val notMetered = caps?.substringAfter("not_metered=", "")
            ?.substringBefore(' ')?.toBooleanStrictOrNull()
        val privateDnsActive = guard.metadata["private_dns_active"]
        val privateDnsMode = when (privateDnsActive) {
            "true" -> guard.metadata["private_dns_server"]?.let { "active:$it" } ?: "active"
            "false" -> "off"
            else -> privateDnsActive
        }
        return AnebResultNetworkContext(
            requestedTransport = transport.name.lowercase(),
            activeTransport = activeTransport,
            capabilities = caps?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            interfaceName = bound?.snapshot?.interfaceName,
            validated = validated,
            notSuspended = notSuspended,
            metered = notMetered?.not(),
            vpnActive = guard.reasons.any { it.startsWith("vpn_active") },
            privateDnsMode = privateDnsMode,
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDouble(key: String, value: Double?) {
        put(key, value?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    private fun medianLong(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private companion object {
        const val PLAYOUT_DEADLINE_MS = 150.0
        const val LOADED_ECHO_GAP_MS = 250L
        const val LOADED_ECHO_IDLE_GAP_MS = 50L
    }
}

internal fun nextRealtimeRecoveryStartNanos(
    controlledPairing: Boolean,
    currentStartNanos: Long?,
    isControlledFault: Boolean,
    isRecoveryAttempt: Boolean,
    unexpectedDisconnect: Boolean,
    sessionEndNanos: Long,
    recoveryMs: Double?,
): Long? = if (controlledPairing) {
    when {
        isControlledFault && unexpectedDisconnect -> sessionEndNanos
        isRecoveryAttempt -> null
        else -> null
    }
} else {
    when {
        unexpectedDisconnect -> currentStartNanos ?: sessionEndNanos
        recoveryMs != null -> null
        else -> currentStartNanos
    }
}
