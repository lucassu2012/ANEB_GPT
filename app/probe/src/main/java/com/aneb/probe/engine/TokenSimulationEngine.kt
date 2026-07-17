package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.room.withTransaction
import com.aneb.probe.BuildConfig
import com.aneb.probe.net.AnebClient
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.TokenSimulationResultEntity
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
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

enum class TokenSimulationPhase { IDLE, PREPARING, LATENCY, UPLOADING, PROCESSING, STREAMING, DOWNLOADING, FINALIZING, COMPLETE, FAILED }

data class TokenSimulationTelemetry(
    val phase: TokenSimulationPhase = TokenSimulationPhase.IDLE,
    val workloadKind: String? = null,
    val taskIndex: Int = 0,
    val taskCount: Int = 0,
    val liveTokenPerSecond: Double? = null,
    val liveRttMs: Double? = null,
    val liveUploadMbps: Double? = null,
    val liveDownloadMbps: Double? = null,
    val liveOnTimeRatio: Double? = null,
    val progress: Double = 0.0,
    val tokenHistory: List<Double> = emptyList(),
    val updatedAtNanos: Long? = null,
)

data class TokenSimulationResult(
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
    val score: TokenScoreResult,
    val evidence: TokenRunEvidence,
)

private class TokenResultPersistenceException(cause: Throwable) :
    IllegalStateException("token_result_persistence_failed", cause)

private data class TokenDurableResult(
    val result: TokenSimulationResult,
    val envelope: com.aneb.probe.data.ResultEnvelopeEntity,
    val radio: FormalRadioEvidence,
)

/** Executes the hash-bound Profile v2 Token plan and produces an independent score. */
class TokenSimulationEngine(private val context: Context) {
    data class Config(
        val serverBase: String,
        val variant: String = "quick",
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
    )

    private val _telemetry = MutableStateFlow(TokenSimulationTelemetry())
    val telemetry: StateFlow<TokenSimulationTelemetry> = _telemetry.asStateFlow()

    private val _result = MutableStateFlow<TokenSimulationResult?>(null)
    val result: StateFlow<TokenSimulationResult?> = _result.asStateFlow()
    private val resultCommitter = DurableResultCommitter(
        store = DurableResultStore { durable: TokenDurableResult ->
            val db = AnebDatabase.get(context)
            db.withTransaction {
                db.tokenSimulationResultDao().insert(durable.result.toEntity())
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
        _telemetry.value = TokenSimulationTelemetry(phase = TokenSimulationPhase.PREPARING)
        log("TOKEN_V2_START run_id=$runId variant=${config.variant} server=$configuredBase")

        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant,
                "guard_rejected:${guard.reasons.joinToString(",")}",
                TokenResultEnvelopeSource(profile = null), config.transport, guard, null, radioCollector, log,
            )
            log("TOKEN_V2_END run_id=$runId status=guard_rejected")
            return@channelFlow
        }

        var bound: BoundNetwork? = null
        var envelopeSource = TokenResultEnvelopeSource(profile = null)
        try {
            bound = when (config.transport) {
                TestEngine.TransportMode.AUTO -> null
                TestEngine.TransportMode.WIFI -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)
                TestEngine.TransportMode.CELLULAR -> NetGuard.acquireNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: GuardException) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant,
                "bind_failed:${e.javaClass.simpleName}", envelopeSource, config.transport, guard, null, radioCollector, log,
            )
            log("TOKEN_V2_END run_id=$runId status=bind_failed")
            return@channelFlow
        }

        val loadedRttSamples = Collections.synchronizedList(mutableListOf<Double?>())
        val loadedMonitorEnabled = AtomicBoolean(false)
        var loadedMonitorJob: Job? = null
        try {
            val loaded = TokenRuntimeRepository(context).load(config.variant)
            val profile = loaded.profile
            val plan = loaded.plan
            envelopeSource = TokenResultEnvelopeSource(
                profile = profile,
                profileHash = loaded.profileHash,
                runtimeArtifactHash = loaded.runtimeArtifactHash,
                profileUri = loaded.profileAssetUri,
                runtimeArtifactUri = loaded.runtimeAssetUri,
            )
            val client = AnebClient(bound)
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sniBase, ipBase) ->
                reach = runCatching { ReachabilityProbe(bound).probeDual(sniBase, ipBase) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            if (config.variant == "stress") {
                loadedMonitorJob = launch {
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
            }
            log(
                "TOKEN_V2_PROFILE id=${profile.profileId} version=${profile.version} model=${plan.modelId}@${plan.modelVersion} " +
                    "hash=${plan.modelHash} calibration=${plan.calibrationStatus} tasks=${plan.taskCount}"
            )

            _telemetry.value = TokenSimulationTelemetry(
                phase = TokenSimulationPhase.LATENCY,
                taskCount = plan.taskCount,
                progress = 0.01,
            )
            val echoResults = mutableListOf<AnebClient.EchoResult>()
            repeat(ECHO_SAMPLES) { index ->
                val echo = client.echo("$measureBase/api/v1/echo")
                echoResults += echo
                val validRtt = echoResults.mapNotNull { sample -> sample.rttUs?.takeIf { sample.error == null }?.div(1_000.0) }
                _telemetry.value = _telemetry.value.copy(
                    liveRttMs = median(validRtt),
                    progress = 0.01 + 0.09 * (index + 1) / ECHO_SAMPLES,
                    updatedAtNanos = SystemClock.elapsedRealtimeNanos(),
                )
                if (index + 1 < ECHO_SAMPLES) delay(ECHO_GAP_MS)
            }
            val offsetUs = medianLong(echoResults.mapNotNull { it.offsetUs?.takeIf { _ -> it.error == null } })
            val rttSamples = echoResults.map { it.rttUs?.takeIf { _ -> it.error == null }?.div(1_000.0) }
            val taskEvidence = mutableListOf<TokenTaskEvidence>()
            var invalidReason: String? = if (offsetUs == null) "clock_sync_unavailable" else null

            for ((taskIndex, task) in plan.tasks.withIndex()) {
                if (task.startAfterPreviousMs > 0) delay(task.startAfterPreviousMs.toLong())
                val taskStartProgress = 0.10 + 0.84 * taskIndex / plan.taskCount
                val taskEndProgress = 0.10 + 0.84 * (taskIndex + 1) / plan.taskCount
                val uploadStart = AtomicLong(-1L)
                val tokenWindow = ConcurrentLinkedQueue<Long>()
                val onTimeWindow = ConcurrentLinkedQueue<Pair<Long, Boolean>>()
                _telemetry.value = _telemetry.value.copy(
                    phase = TokenSimulationPhase.UPLOADING,
                    workloadKind = task.workloadKind,
                    taskIndex = taskIndex + 1,
                    liveTokenPerSecond = null,
                    liveUploadMbps = null,
                    liveDownloadMbps = null,
                    liveOnTimeRatio = null,
                    progress = taskStartProgress,
                    updatedAtNanos = SystemClock.elapsedRealtimeNanos(),
                )
                log("TOKEN_V2_TASK_START run_id=$runId task=${task.taskId} kind=${task.workloadKind} bytes=${task.upload.payloadBytes}")
                val wirePlan = TokenSimTaskPlan(
                    taskId = task.taskId,
                    workloadKind = task.workloadKind,
                    seed = plan.seed,
                    processingMs = task.processingMs,
                    uploadPayloadBytes = task.upload.payloadBytes,
                    tokenIntervalsMs = task.tokenStream.intervalsMs,
                    tokenSizesBytes = task.tokenStream.sizesBytes,
                )
                loadedMonitorEnabled.set(config.variant == "stress")
                val taskResult = client.tokenSim(
                    url = "$measureBase/api/v1/token-sim",
                    plan = wirePlan,
                    uploadChunkBytes = task.upload.chunkBytes,
                    uploadChunkCadenceMs = task.upload.chunkCadenceMs,
                    onUploadBytes = { totalBytes, now ->
                        val started = uploadStart.updateAndGet { old -> if (old < 0L) now else old }
                        val elapsed = (now - started).coerceAtLeast(1L) / 1_000_000_000.0
                        _telemetry.value = _telemetry.value.copy(
                            liveUploadMbps = totalBytes * 8.0 / elapsed / 1_000_000.0,
                            progress = taskStartProgress + (taskEndProgress - taskStartProgress) * 0.18 * totalBytes / task.upload.payloadBytes,
                            updatedAtNanos = now,
                        )
                    },
                    onPrelude = { _, arrival ->
                        loadedMonitorEnabled.set(false)
                        _telemetry.value = _telemetry.value.copy(
                            phase = TokenSimulationPhase.PROCESSING,
                            liveUploadMbps = null,
                            progress = taskStartProgress + (taskEndProgress - taskStartProgress) * 0.20,
                            updatedAtNanos = arrival,
                        )
                    },
                    onToken = { arrival ->
                        val now = arrival.arrivalNanos
                        tokenWindow.add(now)
                        val cutoff = now - LIVE_WINDOW_NANOS
                        while (tokenWindow.peek()?.let { it < cutoff } == true) tokenWindow.poll()
                        val onTime = offsetUs?.let { offset ->
                            val pathLatenessUs = now / 1_000L - (arrival.event.schedUs - offset)
                            pathLatenessUs <= 200_000L
                        }
                        if (onTime != null) onTimeWindow.add(now to onTime)
                        while (onTimeWindow.peek()?.first?.let { it < cutoff } == true) onTimeWindow.poll()
                        val onTimeRatio = onTimeWindow.takeIf { it.isNotEmpty() }?.let { window ->
                            window.count { it.second }.toDouble() / window.size
                        }
                        val history = (_telemetry.value.tokenHistory + tokenWindow.size.toDouble()).takeLast(48)
                        _telemetry.value = _telemetry.value.copy(
                            phase = TokenSimulationPhase.STREAMING,
                            liveTokenPerSecond = tokenWindow.size.toDouble(),
                            liveOnTimeRatio = onTimeRatio,
                            tokenHistory = history,
                            progress = taskStartProgress + (taskEndProgress - taskStartProgress) *
                                (0.20 + 0.65 * (arrival.event.seq + 1) / task.tokenStream.intervalsMs.size),
                            updatedAtNanos = now,
                        )
                    },
                )
                loadedMonitorEnabled.set(false)
                if (taskResult.prelude?.let { it.taskId != task.taskId || it.contractVersion != "aneb-token-task-v1" } == true) {
                    invalidReason = "node_contract_or_task_identity_mismatch"
                }
                var downloadMbps: Double? = null
                var downloadDurationMs: Double? = null
                var downloadFailed = false
                if (task.responseArtifactBytes > 0L && taskResult.completed) {
                    _telemetry.value = _telemetry.value.copy(
                        phase = TokenSimulationPhase.DOWNLOADING,
                        liveTokenPerSecond = null,
                        liveDownloadMbps = null,
                        progress = taskStartProgress + (taskEndProgress - taskStartProgress) * 0.86,
                    )
                    val downloadStart = AtomicLong(-1L)
                    val downloadedBytes = AtomicLong(0L)
                    loadedMonitorEnabled.set(config.variant == "stress")
                    val download = client.downloadThroughput(
                        "$measureBase/api/v1/download?bytes=${task.responseArtifactBytes}&chunk_kb=256",
                    ) { bytes, now ->
                        val started = downloadStart.updateAndGet { old -> if (old < 0L) now else old }
                        val elapsed = (now - started).coerceAtLeast(1L) / 1_000_000_000.0
                        val total = downloadedBytes.addAndGet(bytes.toLong())
                        _telemetry.value = _telemetry.value.copy(
                            liveDownloadMbps = total * 8.0 / elapsed / 1_000_000.0,
                            updatedAtNanos = now,
                        )
                    }
                    loadedMonitorEnabled.set(false)
                    downloadMbps = if (download.error == null && download.endNanos != null) {
                        val seconds = (download.endNanos - download.startNanos).coerceAtLeast(1L) / 1_000_000_000.0
                        downloadDurationMs = seconds * 1_000.0
                        download.totalBytes * 8.0 / seconds / 1_000_000.0
                    } else null
                    downloadFailed = download.error != null || download.totalBytes != task.responseArtifactBytes
                }
                val measured = measureTask(task, taskResult, offsetUs, downloadMbps, downloadDurationMs, downloadFailed)
                taskEvidence += measured
                _telemetry.value = _telemetry.value.copy(
                    liveTokenPerSecond = null,
                    liveUploadMbps = null,
                    liveDownloadMbps = null,
                    progress = taskEndProgress,
                )
                log(
                    "TOKEN_V2_TASK_END run_id=$runId task=${task.taskId} success=${measured.success} " +
                        "tokens=${measured.uniqueTokens}/${measured.expectedTokens} up_mbps=${measured.uploadGoodputMbps ?: "null"} " +
                        "error=${measured.error?.replace(' ', '_') ?: "none"}"
                )
            }

            _telemetry.value = _telemetry.value.copy(
                phase = TokenSimulationPhase.FINALIZING,
                workloadKind = null,
                liveTokenPerSecond = null,
                liveUploadMbps = null,
                liveDownloadMbps = null,
                progress = 0.96,
            )
            loadedMonitorEnabled.set(false)
            loadedMonitorJob?.cancelAndJoin()
            loadedMonitorJob = null
            val loadedRttSnapshot = synchronized(loadedRttSamples) { loadedRttSamples.toList() }
            val evidence = TokenRunEvidence(
                variant = config.variant,
                tasks = taskEvidence,
                rttSamplesMs = rttSamples,
                invalidReason = invalidReason,
                loadedRttSamplesMs = loadedRttSnapshot,
            )
            val score = score(evidence)
            val endedAt = System.currentTimeMillis()
            val result = TokenSimulationResult(
                runId, startedAt, measureBase, profile.profileId, profile.version,
                plan.modelId, plan.modelVersion, plan.modelHash, plan.calibrationStatus,
                plan.variant, profile.evaluation.scorePolicyId, profile.evaluation.scoreAnchorPolicyId,
                profile.evaluation.conclusionPolicyId, score, evidence,
            )
            publishResult(
                result = result,
                source = envelopeSource,
                transport = config.transport,
                guard = guard,
                bound = bound,
                endedAtEpochMs = endedAt,
                status = if (score.verdict == TokenVerdict.INVALID) "failed" else "completed",
                radioCollector = radioCollector,
                log = log,
            )
            _telemetry.value = _telemetry.value.copy(
                phase = if (score.verdict == TokenVerdict.INVALID) TokenSimulationPhase.FAILED else TokenSimulationPhase.COMPLETE,
                progress = 1.0,
            )
            log(
                "TOKEN_V2_RESULT run_id=$runId score=${score.totalScore ?: "null"} grade=${score.grade ?: "null"} " +
                    "verdict=${score.verdict} confidence=${score.confidence}"
            )
            log("TOKEN_V2_END run_id=$runId status=completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenResultPersistenceException) {
            throw e
        } catch (e: Exception) {
            finishFailed(
                runId, startedAt, configuredBase, config.variant, e.toString(),
                envelopeSource, config.transport, guard, bound, radioCollector, log,
            )
            log("TOKEN_V2_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("TOKEN_V2_END run_id=$runId status=error")
        } finally {
            withContext(NonCancellable) {
                loadedMonitorEnabled.set(false)
                loadedMonitorJob?.cancelAndJoin()
                radioCollector.close()
                bound?.release()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun measureTask(
        task: TokenRuntimeTask,
        result: TokenSimTaskResult,
        offsetUs: Long?,
        downloadMbps: Double?,
        downloadDurationMs: Double?,
        downloadFailed: Boolean,
    ): TokenTaskEvidence {
        val validArrivals = result.arrivals.filter { it.event.seq in task.tokenStream.intervalsMs.indices }
        val bySeq = validArrivals.groupBy { it.event.seq }
        val unique = bySeq.mapValues { it.value.first() }
        val duplicateCount = validArrivals.size - unique.size
        val correctedServerUs = unique.mapValues { (seq, arrival) ->
            val timerLate = result.summary?.timerLateUs?.getOrNull(seq) ?: 0L
            arrival.event.schedUs + timerLate
        }
        val lateness = if (offsetUs == null) emptyList() else unique.mapNotNull { (seq, arrival) ->
            val serverUs = correctedServerUs[seq] ?: return@mapNotNull null
            (arrival.arrivalNanos / 1_000.0) - (serverUs - offsetUs)
        }.map { it / 1_000.0 }
        val residual = unique.keys.sorted().zipWithNext().mapNotNull { (previous, current) ->
            if (current != previous + 1) return@mapNotNull null
            val previousArrival = unique.getValue(previous).arrivalNanos
            val currentArrival = unique.getValue(current).arrivalNanos
            val previousServer = correctedServerUs.getValue(previous)
            val currentServer = correctedServerUs.getValue(current)
            ((currentArrival - previousArrival) / 1_000_000.0) - ((currentServer - previousServer) / 1_000.0)
        }
        val mappedRecvEndNanos = if (offsetUs == null) null else result.prelude?.let { (it.uploadRecvEndUs - offsetUs) * 1_000L }
        val clickToNode = mappedRecvEndNanos?.let { (it - result.requestStartNanos) / 1_000_000.0 }?.takeIf { it >= 0.0 }
        val first = unique[0]
        val firstCorrectedServerUs = correctedServerUs[0]
        val ttftExcess = (if (offsetUs == null || first == null || firstCorrectedServerUs == null) null else {
            ((first.arrivalNanos / 1_000.0) - (firstCorrectedServerUs - offsetUs)) / 1_000.0
        })?.takeIf { it >= 0.0 }
        val uploadDurationSeconds = mappedRecvEndNanos?.let { recv ->
            result.uploadWriteStartNanos?.let { start -> (recv - start).coerceAtLeast(1L) / 1_000_000_000.0 }
        }
        val uploadMbps = uploadDurationSeconds?.let { task.upload.payloadBytes * 8.0 / it / 1_000_000.0 }
        val expected = task.tokenStream.intervalsMs.size
        val streamComplete = result.completed && unique.size == expected && unique.keys.all { it in 0 until expected }
        val artifactComplete = task.responseArtifactBytes == 0L || (!downloadFailed && downloadMbps != null)
        val requestCount = 1 + if (task.responseArtifactBytes > 0L && result.completed) 1 else 0
        val failedCount = (if (result.error == null) 0 else 1) + (if (downloadFailed) 1 else 0)
        return TokenTaskEvidence(
            workloadKind = task.workloadKind,
            uploadBytes = task.upload.payloadBytes,
            responseArtifactBytes = task.responseArtifactBytes,
            success = streamComplete && artifactComplete,
            networkFailure = result.error != null || downloadFailed,
            error = result.error ?: if (downloadFailed) "artifact_download_failed" else null,
            clickToNodeReceiveMs = clickToNode,
            ttftExcessMs = ttftExcess,
            uploadGoodputMbps = uploadMbps,
            downloadGoodputMbps = downloadMbps,
            expectedTokens = expected,
            uniqueTokens = unique.size,
            duplicateTokens = duplicateCount,
            tokenLatenessMs = lateness,
            itlResidualMs = residual,
            requestCount = requestCount,
            failedRequestCount = failedCount,
            artifactDownloadDurationMs = downloadDurationMs,
        )
    }

    private suspend fun finishFailed(
        runId: String,
        startedAt: Long,
        server: String,
        variant: String,
        reason: String,
        source: TokenResultEnvelopeSource,
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
        radioCollector: FormalRadioEvidenceCollector,
        log: suspend (String) -> Unit,
    ) {
        val evidence = TokenRunEvidence(variant, emptyList(), emptyList(), reason)
        val policies = policies(variant)
        val profile = source.profile
        val result = TokenSimulationResult(
            runId, startedAt, server, profile?.profileId ?: "token_multimodal_$variant", profile?.version ?: "unknown",
            profile?.business?.behaviorModelId ?: "unknown",
            profile?.business?.behaviorModelVersion ?: "unknown",
            profile?.business?.behaviorModelHash ?: "unknown",
            profile?.business?.calibrationStatus ?: "unknown",
            variant,
            profile?.evaluation?.scorePolicyId?.takeIf { it.isNotBlank() } ?: policies.first,
            profile?.evaluation?.scoreAnchorPolicyId?.takeIf { it.isNotBlank() } ?: policies.second,
            profile?.evaluation?.conclusionPolicyId?.takeIf { it.isNotBlank() } ?: policies.third,
            score(evidence), evidence,
        )
        publishResult(
            result = result,
            source = source,
            transport = transport,
            guard = guard,
            bound = bound,
            endedAtEpochMs = System.currentTimeMillis(),
            status = "failed",
            radioCollector = radioCollector,
            log = log,
        )
        _telemetry.value = TokenSimulationTelemetry(phase = TokenSimulationPhase.FAILED)
    }

    private suspend fun publishResult(
        result: TokenSimulationResult,
        source: TokenResultEnvelopeSource,
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
        endedAtEpochMs: Long,
        status: String,
        radioCollector: FormalRadioEvidenceCollector,
        log: suspend (String) -> Unit,
    ) {
        val radio = radioCollector.freeze()
        val envelope = TokenResultEnvelopeV1.build(
            TokenResultEnvelopeInput(
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
            resultCommitter.commit(TokenDurableResult(result, envelope, radio))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("TOKEN_V2_DB_WRITE run_id=${result.runId} ok=false error=${e.javaClass.simpleName}")
            throw TokenResultPersistenceException(e)
        }
        log("TOKEN_V2_RADIO run_id=${result.runId} status=${radio.collectionStatus} samples=${radio.samples.size}")
        log("TOKEN_V2_DB_WRITE run_id=${result.runId} ok=true")
    }

    private fun TokenSimulationResult.toEntity(): TokenSimulationResultEntity = TokenSimulationResultEntity(
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
                })
            }
        }),
        conclusionsJson = Json.encodeToString(
            JsonArray.serializer(),
            JsonArray(score.conclusions.map(::JsonPrimitive)),
        ),
        evidenceJson = Json.encodeToString(
            JsonObject.serializer(),
            TokenResultEnvelopeV1.rawEvidenceJson(evidence).withContractVersion(variant),
        ),
    )

    private fun JsonObject.withContractVersion(variant: String): JsonObject = buildJsonObject {
        put("contract_version", "aneb-token-run-evidence-v1")
        put("variant", variant)
        this@withContractVersion.forEach { (key, value) -> put(key, value) }
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
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { sorted ->
        val middle = sorted.size / 2
        if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun medianLong(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { sorted -> sorted[sorted.size / 2] }

    private fun score(evidence: TokenRunEvidence): TokenScoreResult =
        if (evidence.variant == "stress") TokenStressScorer.score(evidence) else TokenSimulationScorer.score(evidence)

    private fun policies(variant: String): Triple<String, String, String> = if (variant == "stress") {
        Triple("token-stress-score-v1", "compliance-anchors-v1", "token-stress-conclusions-v1")
    } else {
        Triple("token-sim-score-v1", "compliance-anchors-v1", "token-sim-conclusions-v1")
    }

    private companion object {
        const val ECHO_SAMPLES = 20
        const val ECHO_GAP_MS = 80L
        const val LOADED_ECHO_GAP_MS = 250L
        const val LOADED_ECHO_IDLE_GAP_MS = 50L
        const val LIVE_WINDOW_NANOS = 1_000_000_000L
    }
}
