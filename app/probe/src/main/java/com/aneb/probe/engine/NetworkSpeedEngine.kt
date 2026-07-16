package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.NetworkUdpProbe
import com.aneb.probe.net.PathMonitor
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.net.TimingRecord
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AnebTestMode(val label: String) {
    /** 内部枚举名为兼容旧设置保留；正式产品语义已升级为网络综合性能。 */
    NETWORK_BASIC("网络综合"),
    TOKEN_SIMULATION("Token 仿真"),
    AI_REALTIME_SIMULATION("AI 实时"),
    TOKEN_EXPERIENCE("Agent 取证"),
}

enum class BasicSpeedPhase { IDLE, PREPARING, HANDSHAKE, LATENCY, DOWNLOAD, UPLOAD, DATAGRAM, FINALIZING, COMPLETE, FAILED }

data class BasicSpeedTelemetry(
    val phase: BasicSpeedPhase = BasicSpeedPhase.IDLE,
    val currentMbps: Double? = null,
    val phaseAverageMbps: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val loadedRttMs: Double? = null,
    val latencyDeltaMs: Double? = null,
    /** 应用层 echo 请求失败率，不是 IP 层丢包。 */
    val requestLossRate: Double? = null,
    val lowSpeedWindowRatio: Double? = null,
    val udpReturnRatio: Double? = null,
    val progress: Double = 0.0,
    val historyMbps: List<Double> = emptyList(),
    val historyLoadedRttMs: List<Double> = emptyList(),
)

data class BasicSpeedResult(
    val runId: String,
    val startedAtEpochMs: Long,
    val serverBase: String,
    val claimScope: String = "application_end_to_end_to_probe_node",
    val profileId: String = "basic_network",
    val profileVersion: String,
    val variant: String = "legacy",
    val scorePolicyId: String = "network-basic-score-none",
    val scoreAnchorPolicyId: String = "none",
    val conclusionPolicyId: String = "network-basic-conclusions-v1",
    val status: String,
    val totalScore: Double? = null,
    val grade: String? = null,
    val verdict: TokenVerdict = TokenVerdict.INCONCLUSIVE,
    val confidence: TokenConfidence = TokenConfidence.LOW,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val pingMs: Double?,
    val loadedRttMs: Double? = null,
    val latencyDeltaMs: Double? = null,
    val jitterMs: Double?,
    val requestLossRate: Double?,
    val throughputRobustCv: Double? = null,
    val udpNonReturnRate: Double? = null,
    val postLoadPingMs: Double?,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val transferErrors: List<String>,
    val metrics: Map<String, NetworkMetricEvidence> = emptyMap(),
    val groupScores: Map<String, Double> = emptyMap(),
    val conclusions: List<String> = emptyList(),
    val evidenceJson: String = "{}",
)

private class NetworkComprehensiveProfileRepository(private val context: Context) {
    suspend fun load(variant: String): ScenarioProfile = withContext(Dispatchers.IO) {
        require(variant in setOf("quick", "standard")) { "unsupported_network_variant:$variant" }
        val path = "published/network_comprehensive_$variant/profile.json"
        val text = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        val profile = ProfileParser.parseSingle(text)
        val assessment = ProfileCapability.assess(profile)
        require(assessment.executable) {
            "network_profile_not_executable:${(assessment.contractIssues + assessment.unsupportedPhaseTypes).joinToString("|")}"
        }
        require(profile.evidenceTier == variant) { "network_profile_variant_mismatch" }
        profile
    }
}

/** 网络综合性能引擎：容量测试期间持续并发 echo，主动态指标始终是 loaded RTT。 */
class NetworkSpeedEngine(private val context: Context) {
    data class Config(
        val serverBase: String,
        val variant: String = "quick",
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
    )

    private val _telemetry = MutableStateFlow(BasicSpeedTelemetry())
    val telemetry: StateFlow<BasicSpeedTelemetry> = _telemetry.asStateFlow()
    private val _result = MutableStateFlow<BasicSpeedResult?>(null)
    val result: StateFlow<BasicSpeedResult?> = _result.asStateFlow()

    fun run(config: Config): Flow<String> = channelFlow {
        val log: suspend (String) -> Unit = { send(it) }
        val runId = TestEngine.newRunId()
        val startedAtEpochMs = System.currentTimeMillis()
        val configuredBase = config.serverBase.trim().trimEnd('/')
        _result.value = null
        _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.PREPARING)
        log("NET_V1_START run_id=$runId variant=${config.variant} transport=${config.transport.name.lowercase()} server=$configuredBase")

        val guard = NetGuard.guardCheck(context)
        if (!guard.ok) {
            val reason = guard.reasons.joinToString(",")
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "guard_rejected:$reason"), log)
            log("NET_V1_END run_id=$runId status=guard_rejected reasons=$reason")
            return@channelFlow
        }

        var bound: BoundNetwork? = null
        var pathMonitor: PathMonitor? = null
        val invalidReason = AtomicReference<String?>(null)
        try {
            bound = acquireBoundNetwork(config.transport, guard.metadata["active_transports"])
            if (bound != null) {
                pathMonitor = PathMonitor(context, bound, onInvalidate = { invalidReason.compareAndSet(null, it) }).also { it.start() }
            }
        } catch (e: GuardException) {
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "bind_failed:${e.message}"), log)
            log("NET_V1_END run_id=$runId status=bind_failed")
            return@channelFlow
        }

        try {
            val profile = NetworkComprehensiveProfileRepository(context).load(config.variant)
            val client = AnebClient(bound)
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sniBase, ipBase) ->
                reach = runCatching { ReachabilityProbe(bound).probeDual(sniBase, ipBase) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            if (measureBase != configuredBase) log("NET_V1_REACH_SWITCH from=sni_host to=bare_ip base=$measureBase")
            log("NET_V1_PROFILE id=${profile.profileId} version=${profile.version} source=bundled score=${profile.evaluation.scorePolicyId}")

            fun phase(type: String) = profile.phases.first { it.type == type }
            val handshakePhase = phase(ProfilePhase.TYPE_PATH_SETUP)
            val idlePhase = phase(ProfilePhase.TYPE_IDLE_LATENCY)
            val downloadPhase = phase(ProfilePhase.TYPE_DOWNLOAD_LOADED)
            val uploadPhase = phase(ProfilePhase.TYPE_UPLOAD_LOADED)
            val udpPhase = phase(ProfilePhase.TYPE_UDP_SEQUENCE)
            val postPhase = phase(ProfilePhase.TYPE_POST_LOAD_LATENCY)

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.HANDSHAKE, progress = 0.02)
            val handshakes = measureHandshakes(client, measureBase, handshakePhase.attempts) { index ->
                _telemetry.value = _telemetry.value.copy(progress = 0.02 + 0.06 * index / handshakePhase.attempts.coerceAtLeast(1))
            }
            log("NET_V1_PHASE run_id=$runId phase=handshake attempts=${handshakes.size}")

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.LATENCY, progress = 0.09)
            val idle = measureEcho(client, measureBase, idlePhase.samples) { index, samples ->
                val summary = BasicSpeedMath.summarizeEcho(samples)
                _telemetry.value = _telemetry.value.copy(
                    pingMs = summary.rttP50Ms,
                    jitterMs = summary.jitterMs,
                    requestLossRate = summary.requestLossRate,
                    progress = 0.09 + 0.11 * index / idlePhase.samples.coerceAtLeast(1),
                )
            }
            val idleP50 = BasicSpeedMath.percentile(idle.filterNotNull(), 0.50)
            log("NET_V1_PHASE run_id=$runId phase=idle_latency samples=${idle.size}")

            log("NET_V1_PHASE run_id=$runId phase=download_loaded duration_ms=${downloadPhase.durationMs}")
            val download = runTransferPhase(client, measureBase, runId, downloadPhase, TransferDirection.DOWNLOAD, idleP50, 0.21, 0.48)
            _telemetry.value = _telemetry.value.copy(downloadMbps = download.averageMbps)
            log("NET_V1_PHASE run_id=$runId phase=upload_loaded duration_ms=${uploadPhase.durationMs}")
            val upload = runTransferPhase(client, measureBase, runId, uploadPhase, TransferDirection.UPLOAD, idleP50, 0.49, 0.76)
            _telemetry.value = _telemetry.value.copy(uploadMbps = upload.averageMbps)

            _telemetry.value = _telemetry.value.copy(
                phase = BasicSpeedPhase.DATAGRAM, currentMbps = null, phaseAverageMbps = null, progress = 0.78,
            )
            val udp = NetworkUdpProbe(bound).run(measureBase, udpPhase.packets, udpPhase.packetBytes, udpPhase.ratePerSecond)
            _telemetry.value = _telemetry.value.copy(
                udpReturnRatio = udp.packetsSent.takeIf { it > 0 }?.let { udp.receivedSeqs.distinct().size.toDouble() / it },
                progress = 0.91,
            )
            log("NET_V1_PHASE run_id=$runId phase=udp sent=${udp.packetsSent} received=${udp.receivedSeqs.distinct().size} error=${udp.error ?: "none"}")

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.FINALIZING, progress = 0.92)
            val post = measureEcho(client, measureBase, postPhase.samples) { index, _ ->
                _telemetry.value = _telemetry.value.copy(progress = 0.92 + 0.07 * index / postPhase.samples.coerceAtLeast(1))
            }

            val loaded = download.loadedRttMs + upload.loadedRttMs
            val allEcho = handshakes.size + idle.size + loaded.size + post.size
            val echoSuccess = handshakes.count { it.success } + idle.count { it != null } + loaded.count { it != null } + post.count { it != null }
            val evidence = NetworkComprehensiveEvidence(
                variant = config.variant,
                idleRttMs = idle,
                loadedRttMs = loaded,
                downloadWindowsMbps = download.windowsMbps,
                uploadWindowsMbps = upload.windowsMbps,
                appRequestAttempts = allEcho,
                appRequestSuccesses = echoSuccess,
                udpPacketsSent = udp.packetsSent,
                udpReceivedSeqs = udp.receivedSeqs,
                udpUnavailableReason = udp.error,
                handshakes = handshakes,
                invalidReason = invalidReason.get(),
            )
            val score = NetworkComprehensiveScorer.score(evidence)
            val metrics = score.metrics
            val errors = download.errors + upload.errors + listOfNotNull(udp.error)
            val status = when {
                score.verdict == TokenVerdict.INVALID -> "invalid"
                download.totalBytes == 0L && upload.totalBytes == 0L -> "failed"
                score.totalScore == null -> "partial"
                else -> "completed"
            }
            val result = BasicSpeedResult(
                runId, startedAtEpochMs, measureBase, profile.claimScope, profile.profileId, profile.version, config.variant,
                profile.evaluation.scorePolicyId, profile.evaluation.scoreAnchorPolicyId, profile.evaluation.conclusionPolicyId,
                status, score.totalScore, score.grade, score.verdict, score.confidence,
                metrics["NET-B01"]?.value, metrics["NET-B02"]?.value, metrics["NET-B03"]?.value,
                metrics["NET-B04"]?.value, metrics["NET-B05"]?.value, metrics["NET-B06"]?.value,
                metrics["NET-B09"]?.value, metrics["NET-B07"]?.value, metrics["NET-B10"]?.value,
                BasicSpeedMath.percentile(post.filterNotNull(), 0.50), download.totalBytes, upload.totalBytes, errors,
                metrics, score.groupScores, score.conclusions, evidenceJson(evidence),
            )
            publishResult(result, log)
            _telemetry.value = _telemetry.value.copy(
                phase = if (status == "failed" || status == "invalid") BasicSpeedPhase.FAILED else BasicSpeedPhase.COMPLETE,
                currentMbps = null, phaseAverageMbps = null, progress = 1.0,
            )
            log("NET_V1_RESULT run_id=$runId status=$status score=${score.totalScore ?: "null"} grade=${score.grade ?: "null"} verdict=${score.verdict} confidence=${score.confidence}")
            log("NET_V1_END run_id=$runId status=$status")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.FAILED, currentMbps = null)
            publishResult(failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "error:${e.javaClass.simpleName}:${e.message}"), log)
            log("NET_V1_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("NET_V1_END run_id=$runId status=error")
        } finally {
            pathMonitor?.stop()
            bound?.release()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun acquireBoundNetwork(mode: TestEngine.TransportMode, active: String?): BoundNetwork? {
        val transport = when (mode) {
            TestEngine.TransportMode.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            TestEngine.TransportMode.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            TestEngine.TransportMode.AUTO -> when {
                active?.contains("wifi") == true -> NetworkCapabilities.TRANSPORT_WIFI
                active?.contains("cellular") == true -> NetworkCapabilities.TRANSPORT_CELLULAR
                else -> null
            }
        }
        return transport?.let { NetGuard.acquireNetwork(context, it) }
    }

    private suspend fun measureHandshakes(
        client: AnebClient,
        base: String,
        attempts: Int,
        onProgress: (Int) -> Unit,
    ): List<NetworkHandshakeEvidence> {
        val values = ArrayList<NetworkHandshakeEvidence>()
        repeat(attempts.coerceAtLeast(1)) { index ->
            client.evictConnections()
            val echo = client.echo("$base/api/v1/echo")
            values += handshakeEvidence(echo.timing, echo.error == null)
            onProgress(index + 1)
            if (index + 1 < attempts) delay(ECHO_GAP_MS)
        }
        return values
    }

    private fun handshakeEvidence(timing: TimingRecord?, success: Boolean): NetworkHandshakeEvidence {
        fun delta(end: Long?, start: Long?) = if (end != null && start != null && end >= start) (end - start) / 1e6 else null
        val tcpEnd = timing?.secureConnectStartNs ?: timing?.connectEndNs
        return NetworkHandshakeEvidence(
            dnsMs = delta(timing?.dnsEndNs, timing?.dnsStartNs),
            tcpMs = delta(tcpEnd, timing?.connectStartNs),
            tlsMs = delta(timing?.secureConnectEndNs, timing?.secureConnectStartNs),
            success = success,
        )
    }

    private suspend fun measureEcho(
        client: AnebClient,
        base: String,
        samples: Int,
        onProgress: (Int, List<Double?>) -> Unit,
    ): List<Double?> {
        val values = ArrayList<Double?>(samples)
        repeat(samples.coerceAtLeast(1)) { index ->
            val echo = client.echo("$base/api/v1/echo")
            values += echo.rttUs?.takeIf { echo.error == null }?.div(1_000.0)
            onProgress(index + 1, values)
            if (index + 1 < samples) delay(ECHO_GAP_MS)
        }
        return values
    }

    private enum class TransferDirection { DOWNLOAD, UPLOAD }
    private data class TransferSummary(
        val averageMbps: Double?,
        val totalBytes: Long,
        val windowsMbps: List<Double>,
        val loadedRttMs: List<Double?>,
        val errors: List<String>,
    )

    private suspend fun runTransferPhase(
        client: AnebClient,
        base: String,
        runId: String,
        phase: ProfilePhase,
        direction: TransferDirection,
        idleP50Ms: Double?,
        progressStart: Double,
        progressEnd: Double,
    ): TransferSummary = coroutineScope {
        val durationMs = phase.durationMs.coerceAtLeast(1_000)
        val parallel = phase.parallel.coerceIn(1, MAX_PARALLEL)
        val transferBytes = phase.bytes.coerceAtLeast(1L)
        val chunkBytes = (phase.chunkKb.coerceAtLeast(16) * 1024).coerceAtMost(1 shl 20)
        val echoIntervalMs = phase.echoIntervalMs.coerceAtLeast(100)
        val totalBytes = LongAdder()
        val errors = ConcurrentLinkedQueue<String>()
        val loadedRtt = Collections.synchronizedList(mutableListOf<Double?>())
        val windows = ConcurrentLinkedQueue<Double>()
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val deadlineNanos = startNanos + durationMs * 1_000_000L
        val rateWindow = ByteRateWindow()
        val history = ArrayDeque<Double>()
        val rttHistory = ArrayDeque<Double>()
        val uiPhase = if (direction == TransferDirection.DOWNLOAD) BasicSpeedPhase.DOWNLOAD else BasicSpeedPhase.UPLOAD
        val speedTarget = if (direction == TransferDirection.DOWNLOAD) 25.0 else 10.0

        val sampler: Job = launch(Dispatchers.Default) {
            var nextScoreWindow = startNanos + 1_000_000_000L
            while (isActive) {
                val now = SystemClock.elapsedRealtimeNanos()
                val bytes = totalBytes.sum()
                val live = rateWindow.add(now, bytes)
                if (live != null) {
                    history.addLast(live)
                    while (history.size > HISTORY_POINTS) history.removeFirst()
                    if (now >= nextScoreWindow) {
                        windows.add(live)
                        nextScoreWindow += 1_000_000_000L
                    }
                }
                val lowRatio = windows.toList().takeIf { it.isNotEmpty() }?.let { list -> list.count { it < speedTarget }.toDouble() / list.size }
                val phaseProgress = ((now - startNanos).toDouble() / (durationMs * 1_000_000.0)).coerceIn(0.0, 1.0)
                _telemetry.value = _telemetry.value.copy(
                    phase = uiPhase, currentMbps = live, phaseAverageMbps = BasicSpeedMath.mbps(bytes, now - startNanos),
                    lowSpeedWindowRatio = lowRatio, progress = progressStart + (progressEnd - progressStart) * phaseProgress,
                    historyMbps = history.toList(),
                )
                delay(TELEMETRY_MS)
            }
        }
        val echoJob = launch(Dispatchers.IO) {
            while (isActive && SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                val echo = client.echo("$base/api/v1/echo")
                val rtt = echo.rttUs?.takeIf { echo.error == null }?.div(1_000.0)
                loadedRtt.add(rtt)
                if (rtt != null) {
                    rttHistory.addLast(rtt)
                    while (rttHistory.size > HISTORY_POINTS) rttHistory.removeFirst()
                }
                val valid = loadedRtt.count { it != null }
                _telemetry.value = _telemetry.value.copy(
                    loadedRttMs = rtt,
                    latencyDeltaMs = if (rtt != null && idleP50Ms != null) rtt - idleP50Ms else null,
                    requestLossRate = (loadedRtt.size - valid).toDouble() / loadedRtt.size.coerceAtLeast(1),
                    historyLoadedRttMs = rttHistory.toList(),
                )
                delay(echoIntervalMs.toLong())
            }
        }
        val workers = List(parallel) { workerIndex ->
            launch(Dispatchers.IO) {
                while (isActive && SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                    val transfer = when (direction) {
                        TransferDirection.DOWNLOAD -> client.downloadThroughput(
                            "$base/api/v1/download?bytes=$transferBytes&chunk_kb=${phase.chunkKb}",
                        ) { count, _ -> totalBytes.add(count.toLong()) }
                        TransferDirection.UPLOAD -> client.uploadThroughput(
                            "$base/api/v1/upload?run=$runId-net-$workerIndex", transferBytes, chunkBytes,
                        ) { count, _ -> totalBytes.add(count.toLong()) }
                    }
                    if (transfer.error != null) { errors.add(transfer.error); break }
                }
            }
        }
        delay(durationMs.toLong())
        workers.forEach { it.cancel() }
        workers.forEach { it.cancelAndJoin() }
        echoJob.cancelAndJoin()
        sampler.cancelAndJoin()
        val endNanos = SystemClock.elapsedRealtimeNanos()
        val bytes = totalBytes.sum()
        val loadedSnapshot = synchronized(loadedRtt) { loadedRtt.toList() }
        TransferSummary(BasicSpeedMath.mbps(bytes, endNanos - startNanos), bytes, windows.toList(), loadedSnapshot, errors.toList())
    }

    private suspend fun publishResult(result: BasicSpeedResult, log: suspend (String) -> Unit) {
        _result.value = result
        val write = runCatching {
            AnebDatabase.get(context).networkComprehensiveResultDao().insert(
                NetworkComprehensiveResultEntity(
                    result.runId, result.startedAtEpochMs, result.serverBase, result.claimScope, result.profileId,
                    result.profileVersion, result.variant, result.scorePolicyId, result.scoreAnchorPolicyId,
                    result.conclusionPolicyId, result.status, result.totalScore, result.grade, result.verdict.name,
                    result.confidence.name, result.downloadMbps, result.uploadMbps, result.pingMs, result.loadedRttMs,
                    result.latencyDeltaMs, result.jitterMs, result.requestLossRate, result.throughputRobustCv,
                    result.udpNonReturnRate, result.postLoadPingMs, result.downloadBytes, result.uploadBytes,
                    result.transferErrors.joinToString("\n") { it.replace("\u0000", "") }, metricsJson(result.metrics),
                    JsonObject(result.groupScores.mapValues { JsonPrimitive(it.value) }).toString(),
                    JsonArray(result.conclusions.map(::JsonPrimitive)).toString(), result.evidenceJson,
                ),
            )
        }
        log(if (write.isSuccess) "NET_V1_DB_WRITE run_id=${result.runId} ok=true" else "NET_V1_DB_WRITE run_id=${result.runId} ok=false error=${write.exceptionOrNull()?.javaClass?.simpleName}")
    }

    private fun failureResult(runId: String, started: Long, base: String, variant: String, reason: String) = BasicSpeedResult(
        runId = runId,
        startedAtEpochMs = started,
        serverBase = base,
        claimScope = CLAIM_SCOPE,
        profileId = "network_comprehensive_$variant",
        profileVersion = "unknown",
        variant = variant,
        scorePolicyId = SCORE_POLICY,
        scoreAnchorPolicyId = SCORE_ANCHOR_POLICY,
        conclusionPolicyId = CONCLUSION_POLICY,
        status = "invalid",
        totalScore = null,
        grade = null,
        verdict = TokenVerdict.INVALID,
        confidence = TokenConfidence.INVALID,
        downloadMbps = null,
        uploadMbps = null,
        pingMs = null,
        loadedRttMs = null,
        latencyDeltaMs = null,
        jitterMs = null,
        requestLossRate = null,
        throughputRobustCv = null,
        udpNonReturnRate = null,
        postLoadPingMs = null,
        downloadBytes = 0L,
        uploadBytes = 0L,
        transferErrors = listOf(reason),
        metrics = emptyMap(),
        groupScores = emptyMap(),
        conclusions = listOf("测试未完成：$reason"),
        evidenceJson = buildJsonObject { put("invalid_reason", reason) }.toString(),
    )

    private fun metricsJson(metrics: Map<String, NetworkMetricEvidence>) = JsonObject(metrics.mapValues { (_, m) ->
        buildJsonObject {
            put("value", m.value?.let(::JsonPrimitive) ?: JsonNull)
            put("compliance_ratio", m.complianceRatio?.let(::JsonPrimitive) ?: JsonNull)
            put("sample_count", m.sampleCount)
            put("minimum_sample_count", m.minimumSampleCount)
            put("score", m.score?.let(::JsonPrimitive) ?: JsonNull)
        }
    }).toString()

    private fun evidenceJson(e: NetworkComprehensiveEvidence) = buildJsonObject {
        put("contract_version", "aneb-network-evidence-v1")
        put("variant", e.variant)
        put("idle_rtt_ms", nullableArray(e.idleRttMs))
        put("loaded_rtt_ms", nullableArray(e.loadedRttMs))
        put("download_windows_mbps", buildJsonArray { e.downloadWindowsMbps.forEach { add(JsonPrimitive(it)) } })
        put("upload_windows_mbps", buildJsonArray { e.uploadWindowsMbps.forEach { add(JsonPrimitive(it)) } })
        put("app_request_attempts", e.appRequestAttempts)
        put("app_request_successes", e.appRequestSuccesses)
        put("udp_packets_sent", e.udpPacketsSent)
        put("udp_received_seqs", buildJsonArray { e.udpReceivedSeqs.forEach { add(JsonPrimitive(it)) } })
        put("udp_unavailable_reason", e.udpUnavailableReason?.let(::JsonPrimitive) ?: JsonNull)
        put("handshakes", buildJsonArray {
            e.handshakes.forEach { h -> add(buildJsonObject {
                put("dns_ms", h.dnsMs?.let(::JsonPrimitive) ?: JsonNull)
                put("tcp_ms", h.tcpMs?.let(::JsonPrimitive) ?: JsonNull)
                put("tls_ms", h.tlsMs?.let(::JsonPrimitive) ?: JsonNull)
                put("success", h.success)
            }) }
        })
        put("invalid_reason", e.invalidReason?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    private fun nullableArray(values: List<Double?>) = buildJsonArray { values.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } }

    companion object {
        const val CLAIM_SCOPE = "application_end_to_end_to_probe_node"
        const val SCORE_POLICY = "network-comprehensive-score-v1"
        const val SCORE_ANCHOR_POLICY = "compliance-anchors-v1"
        const val CONCLUSION_POLICY = "network-comprehensive-conclusions-v1"
        private const val ECHO_GAP_MS = 75L
        private const val TELEMETRY_MS = 100L
        private const val HISTORY_POINTS = 40
        private const val MAX_PARALLEL = 8
    }
}

object BasicSpeedMath {
    data class EchoSummary(val rttP50Ms: Double?, val jitterMs: Double?, val requestLossRate: Double?)

    fun summarizeEcho(samples: List<Double?>): EchoSummary {
        if (samples.isEmpty()) return EchoSummary(null, null, null)
        val valid = samples.filterNotNull()
        val jitter = if (valid.size >= 2) percentile(valid.zipWithNext { a, b -> kotlin.math.abs(b - a) }, 0.50) else null
        return EchoSummary(percentile(valid, 0.50), jitter, (samples.size - valid.size).toDouble() / samples.size)
    }

    fun mbps(bytes: Long, elapsedNanos: Long): Double? =
        if (bytes <= 0L || elapsedNanos <= 0L) null else bytes * 8.0 / (elapsedNanos / 1e9) / 1e6

    fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        return if (lower == upper) sorted[lower] else sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }
}

class ByteRateWindow(private val windowNanos: Long = 1_000_000_000L) {
    private data class Sample(val atNanos: Long, val totalBytes: Long)
    private val samples = ArrayDeque<Sample>()
    fun add(nowNanos: Long, totalBytes: Long): Double? {
        samples.addLast(Sample(nowNanos, totalBytes))
        while (samples.isNotEmpty() && samples.first().atNanos < nowNanos - windowNanos) samples.removeFirst()
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val elapsed = last.atNanos - first.atNanos
        if (elapsed < LiveStreamWindow.MIN_SAMPLE_NANOS) return null
        return BasicSpeedMath.mbps(last.totalBytes - first.totalBytes, elapsed)
    }
}
