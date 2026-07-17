package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.AnebGatewayClient
import com.aneb.probe.net.BoundNetwork
import com.aneb.probe.net.GuardException
import com.aneb.probe.net.NetGuard
import com.aneb.probe.net.NetworkUdpProbe
import com.aneb.probe.net.PathMonitor
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.net.TimingRecord
import java.net.URLEncoder
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withTimeout
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

enum class BasicSpeedPhase { IDLE, PREPARING, HANDSHAKE, LATENCY, DOWNLOAD, UPLOAD, DATAGRAM, RECOVERY, FINALIZING, COMPLETE, FAILED }

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
    val recoveryElapsedMs: Double? = null,
    val recoveryFailureCount: Int = 0,
    val syntheticOutageActive: Boolean = false,
    /** Non-null only for an explicitly declared synthetic run. */
    val syntheticImpairmentLabel: String? = null,
    /** Non-null only for a dedicated IP-forwarding gateway lab run. */
    val gatewayImpairmentLabel: String? = null,
    val networkLayerOutage: Boolean = false,
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
    val syntheticImpairment: Boolean = false,
    val impairmentProfileId: String? = null,
    val impairmentProfileVersion: String? = null,
    val impairmentDownlinkMbps: Double? = null,
    val impairmentUplinkMbps: Double? = null,
    val impairmentAddedRttMs: Int? = null,
    val impairmentJitterMs: Int? = null,
    val impairmentOutageDurationMs: Int? = null,
    val impairmentExcludedFromShaping: List<String> = emptyList(),
    val impairmentAcknowledged: Boolean = false,
    val recoveryTimeMs: Double? = null,
    val recoveryFailureCount: Int = 0,
    val postRecoverySuccessRatio: Double? = null,
    val gatewayImpairment: Boolean = false,
    val gatewayExperimentId: String? = null,
    val gatewayProfileFingerprint: String? = null,
    val gatewayManagementBase: String? = null,
    val gatewayImpairmentLayer: String? = null,
    val gatewayAcknowledged: Boolean = false,
    val gatewayCleanupAcknowledged: Boolean = false,
    val gatewayBypassObserved: Boolean = false,
    val gatewayUplinkDelayMs: Int? = null,
    val gatewayDownlinkDelayMs: Int? = null,
    val gatewayUplinkLossPct: Double? = null,
    val gatewayDownlinkLossPct: Double? = null,
)

private class NetworkComprehensiveProfileRepository(private val context: Context) {
    suspend fun load(variant: String): ScenarioProfile = withContext(Dispatchers.IO) {
        require(variant in setOf("quick", "standard", "weak_capacity_latency", "weak_recovery", "gateway_loss", "gateway_recovery")) {
            "unsupported_network_variant:$variant"
        }
        val path = "published/network_comprehensive_$variant/profile.json"
        val text = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        val profile = ProfileParser.parseSingle(text)
        val assessment = ProfileCapability.assess(profile)
        require(assessment.executable) {
            "network_profile_not_executable:${(assessment.contractIssues + assessment.unsupportedPhaseTypes).joinToString("|")}"
        }
        val expectedTier = when (variant) {
            "weak_capacity_latency" -> "standard"
            "weak_recovery" -> "recovery"
            "gateway_loss", "gateway_recovery" -> "gateway_lab"
            else -> variant
        }
        require(profile.evidenceTier == expectedTier) { "network_profile_variant_mismatch" }
        require((profile.syntheticImpairment != null) == (variant in setOf("weak_capacity_latency", "weak_recovery"))) {
            "network_profile_impairment_mismatch"
        }
        require((profile.gatewayImpairment != null) == (variant in setOf("gateway_loss", "gateway_recovery"))) {
            "network_profile_gateway_mismatch"
        }
        profile
    }
}

private class NetworkEndpointContext(
    private val base: String,
    private val runId: String,
    profileId: String,
    profileVersion: String,
    val impairment: ProfileSyntheticImpairment?,
    gatewayImpairment: Boolean = false,
    private val sequence: AtomicInteger = AtomicInteger(0),
) {
    val expectedAcknowledgement: String? = impairment?.let { "$profileId@$profileVersion" }
    val requiresServerAcknowledgedUpload: Boolean = impairment != null || gatewayImpairment

    fun url(pathAndQuery: String): String {
        val spec = impairment ?: return base + pathAndQuery
        val separator = if ('?' in pathAndQuery) '&' else '?'
        return buildString {
            append(base)
            append("/synthetic/")
            append(spec.routeId)
            append(pathAndQuery)
            append(separator)
            append("impair_run=")
            append(URLEncoder.encode(runId, Charsets.UTF_8.name()))
            append("&impair_seed=")
            append(spec.seed)
            append("&impair_seq=")
            append(sequence.getAndIncrement())
        }
    }
}

private class SyntheticAcknowledgementTracker(private val expected: String?) {
    private val observations = AtomicInteger(0)
    private val mismatches = AtomicInteger(0)

    fun observe(actual: String?) {
        if (expected == null) return
        observations.incrementAndGet()
        if (actual != expected) mismatches.incrementAndGet()
    }

    val acknowledged: Boolean
        get() = expected == null || (observations.get() > 0 && mismatches.get() == 0)
}

/** 网络综合性能引擎：容量测试期间持续并发 echo，主动态指标始终是 loaded RTT。 */
class NetworkSpeedEngine(private val context: Context) {
    data class Config(
        val serverBase: String,
        val variant: String = "quick",
        val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
        val gatewayBase: String? = null,
        val gatewayToken: String? = null,
    )

    private data class RecoveryRunObservation(
        val triggerAcknowledged: Boolean,
        val declaredOutageMs: Int,
        val outageFailureCount: Int,
        val recoveryTimeMs: Double?,
        val gatewayBypassObserved: Boolean = false,
    )

    private data class GatewayRuntimeEvidence(
        val experimentId: String,
        val profileFingerprint: String,
        val acknowledged: Boolean,
        val cleanupAcknowledged: Boolean = false,
        val bypassObserved: Boolean = false,
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
        var gatewayClient: AnebGatewayClient? = null
        var activeGatewayExperimentId: String? = null
        var gatewayCleanupAcknowledged = false
        var gatewaySpecForCleanup: ProfileGatewayImpairment? = null
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
            val gatewaySpec = profile.gatewayImpairment
            gatewaySpecForCleanup = gatewaySpec
            if (gatewaySpec != null) {
                val gatewayBase = config.gatewayBase?.trim()?.trimEnd('/').orEmpty()
                val gatewayToken = config.gatewayToken.orEmpty()
                require(gatewayBase.isNotBlank() && gatewayToken.isNotBlank()) { "gateway_credentials_required" }
                gatewayClient = AnebGatewayClient(gatewayBase, gatewayToken, bound)
                _telemetry.value = _telemetry.value.copy(gatewayImpairmentLabel = gatewayLabel(gatewaySpec))
                log(
                    "NET_V1_GATEWAY requested=${gatewaySpec.profileRef} fingerprint=${gatewaySpec.profileFingerprint} " +
                        "layer=${gatewaySpec.impairmentLayer} excluded=${gatewaySpec.excludedFromImpairment.joinToString(",")}",
                )
            }
            var reach: ReachabilityProbe.DualReach? = null
            ReachabilityProbe.deriveE01Pair(configuredBase)?.let { (sniBase, ipBase) ->
                reach = runCatching { ReachabilityProbe(bound).probeDual(sniBase, ipBase) }.getOrNull()
            }
            val measureBase = ReachabilityProbe.preferredMeasureBase(configuredBase, reach)
            if (measureBase != configuredBase) log("NET_V1_REACH_SWITCH from=sni_host to=bare_ip base=$measureBase")
            log("NET_V1_PROFILE id=${profile.profileId} version=${profile.version} source=bundled score=${profile.evaluation.scorePolicyId}")
            val endpoints = NetworkEndpointContext(
                base = measureBase,
                runId = runId,
                profileId = profile.profileId,
                profileVersion = profile.version,
                impairment = profile.syntheticImpairment,
                gatewayImpairment = gatewaySpec != null,
            )
            val acknowledgement = SyntheticAcknowledgementTracker(endpoints.expectedAcknowledgement)
            var gatewayEvidence: GatewayRuntimeEvidence? = null
            if (gatewaySpec != null && config.variant == "gateway_loss") {
                val started = startGatewayExperiment(checkNotNull(gatewayClient), runId, gatewaySpec)
                activeGatewayExperimentId = started.experimentId
                gatewayEvidence = GatewayRuntimeEvidence(
                    experimentId = started.experimentId,
                    profileFingerprint = started.profileFingerprint,
                    acknowledged = true,
                )
                log("NET_V1_GATEWAY_ACTIVE experiment=${started.experimentId} layer=${started.impairmentLayer}")
            }
            profile.syntheticImpairment?.let { impairment ->
                val outage = impairment.outageDurationMs.takeIf { it > 0 }?.let { " · 中断 ${it}ms" }.orEmpty()
                _telemetry.value = _telemetry.value.copy(
                    syntheticImpairmentLabel = "合成弱网 · ↓${impairment.downlinkMbps.toInt()} ↑${impairment.uplinkMbps.toInt()} Mbps · +${impairment.addedRttMs}±${impairment.jitterMs} ms$outage",
                )
                log(
                    "NET_V1_IMPAIRMENT requested=${endpoints.expectedAcknowledgement} " +
                        "dl_mbps=${impairment.downlinkMbps} ul_mbps=${impairment.uplinkMbps} " +
                        "added_rtt_ms=${impairment.addedRttMs} jitter_ms=${impairment.jitterMs} " +
                        "outage_ms=${impairment.outageDurationMs} " +
                        "excluded=${impairment.excludedFromShaping.joinToString(",")}",
                )
            }

            fun phase(type: String) = profile.phases.first { it.type == type }
            val handshakePhase = phase(ProfilePhase.TYPE_PATH_SETUP)
            val idlePhase = phase(ProfilePhase.TYPE_IDLE_LATENCY)
            val downloadPhase = phase(ProfilePhase.TYPE_DOWNLOAD_LOADED)
            val uploadPhase = phase(ProfilePhase.TYPE_UPLOAD_LOADED)
            val udpPhase = phase(ProfilePhase.TYPE_UDP_SEQUENCE)
            val recoveryPhase = profile.phases.firstOrNull { it.type == ProfilePhase.TYPE_CONTROLLED_OUTAGE_RECOVERY }
            val postPhase = phase(ProfilePhase.TYPE_POST_LOAD_LATENCY)

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.HANDSHAKE, progress = 0.02)
            gatewayEvidence?.let { ensureGatewayActive(checkNotNull(gatewayClient), it.experimentId, runId, checkNotNull(gatewaySpec)) }
            val handshakes = measureHandshakes(client, endpoints, acknowledgement, handshakePhase.attempts) { index ->
                _telemetry.value = _telemetry.value.copy(progress = 0.02 + 0.06 * index / handshakePhase.attempts.coerceAtLeast(1))
            }
            log("NET_V1_PHASE run_id=$runId phase=handshake attempts=${handshakes.size}")

            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.LATENCY, progress = 0.09)
            val idle = measureEcho(client, endpoints, acknowledgement, idlePhase.samples) { index, samples ->
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
            gatewayEvidence?.let { ensureGatewayActive(checkNotNull(gatewayClient), it.experimentId, runId, checkNotNull(gatewaySpec)) }
            val download = runTransferPhase(client, endpoints, acknowledgement, runId, downloadPhase, TransferDirection.DOWNLOAD, idleP50, 0.21, 0.48)
            _telemetry.value = _telemetry.value.copy(downloadMbps = download.averageMbps)
            log("NET_V1_PHASE run_id=$runId phase=upload_loaded duration_ms=${uploadPhase.durationMs}")
            gatewayEvidence?.let { ensureGatewayActive(checkNotNull(gatewayClient), it.experimentId, runId, checkNotNull(gatewaySpec)) }
            val upload = runTransferPhase(client, endpoints, acknowledgement, runId, uploadPhase, TransferDirection.UPLOAD, idleP50, 0.49, 0.76)
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

            val recovery = recoveryPhase?.let { phase ->
                val observation = if (gatewaySpec != null) {
                    runGatewayRecoveryPhase(
                        client = client,
                        endpoints = endpoints,
                        gateway = checkNotNull(gatewayClient),
                        runId = runId,
                        spec = gatewaySpec,
                        phase = phase,
                        onExperimentStarted = { id -> activeGatewayExperimentId = id },
                    ).also { (runtime, _) ->
                        gatewayEvidence = runtime
                        gatewayCleanupAcknowledged = runtime.cleanupAcknowledged
                        activeGatewayExperimentId = null
                    }.second
                } else {
                    runRecoveryPhase(
                        client,
                        endpoints,
                        acknowledgement,
                        phase,
                        profile.syntheticImpairment?.outageDurationMs ?: 0,
                    )
                }
                observation.also {
                    log(
                        "NET_V1_PHASE run_id=$runId phase=controlled_outage_recovery " +
                            "trigger_ack=${it.triggerAcknowledged} failures=${it.outageFailureCount} " +
                            "recovery_ms=${it.recoveryTimeMs ?: "null"} bypass=${it.gatewayBypassObserved}",
                    )
                }
            }

            val postStart = if (recovery != null) 0.97 else 0.92
            _telemetry.value = _telemetry.value.copy(
                phase = BasicSpeedPhase.FINALIZING,
                syntheticOutageActive = false,
                networkLayerOutage = false,
                progress = postStart,
            )
            val post = measureEcho(client, endpoints, acknowledgement, postPhase.samples) { index, _ ->
                _telemetry.value = _telemetry.value.copy(progress = postStart + (0.99 - postStart) * index / postPhase.samples.coerceAtLeast(1))
            }

            if (gatewaySpec != null && config.variant == "gateway_loss") {
                ensureGatewayActive(checkNotNull(gatewayClient), checkNotNull(activeGatewayExperimentId), runId, gatewaySpec)
                val terminal = stopAndAwaitGatewayCleanup(
                    checkNotNull(gatewayClient),
                    checkNotNull(activeGatewayExperimentId),
                    gatewaySpec,
                    runId,
                )
                gatewayCleanupAcknowledged = true
                activeGatewayExperimentId = null
                gatewayEvidence = checkNotNull(gatewayEvidence).copy(cleanupAcknowledged = true)
                log("NET_V1_GATEWAY_CLEARED experiment=${terminal.experimentId} reason=${terminal.stopReason}")
            }

            if (profile.syntheticImpairment != null && !acknowledgement.acknowledged) {
                invalidReason.compareAndSet(null, "synthetic_impairment_not_acknowledged")
            }
            if (profile.syntheticImpairment != null) {
                log(
                    "NET_V1_IMPAIRMENT acknowledged=${acknowledgement.acknowledged} " +
                        "server_profile=${endpoints.expectedAcknowledgement}",
                )
            }
            if (gatewaySpec != null && gatewayEvidence?.acknowledged != true) {
                invalidReason.compareAndSet(null, "gateway_experiment_not_acknowledged")
            }
            if (gatewaySpec != null && !gatewayCleanupAcknowledged) {
                invalidReason.compareAndSet(null, "gateway_cleanup_not_acknowledged")
            }
            if (gatewayEvidence?.bypassObserved == true) {
                invalidReason.compareAndSet(null, "gateway_bypass_observed")
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
                syntheticImpairment = profile.syntheticImpairment?.let { impairment ->
                    SyntheticNetworkEvidence(
                        profileId = profile.profileId,
                        profileVersion = profile.version,
                        downlinkMbps = impairment.downlinkMbps,
                        uplinkMbps = impairment.uplinkMbps,
                        addedRttMs = impairment.addedRttMs,
                        jitterMs = impairment.jitterMs,
                        outageDurationMs = impairment.outageDurationMs,
                        appliesTo = impairment.appliesTo,
                        excludedFromShaping = impairment.excludedFromShaping,
                        serverAcknowledged = acknowledgement.acknowledged,
                    )
                },
                gatewayImpairment = gatewaySpec?.let { gateway ->
                    gatewayEvidence?.let { runtime ->
                        GatewayNetworkEvidence(
                            experimentId = runtime.experimentId,
                            profileRef = gateway.profileRef,
                            profileFingerprint = runtime.profileFingerprint,
                            impairmentLayer = gateway.impairmentLayer,
                            downlink = gateway.downlink,
                            uplink = gateway.uplink,
                            excludedFromImpairment = gateway.excludedFromImpairment,
                            gatewayAcknowledged = runtime.acknowledged,
                            cleanupAcknowledged = runtime.cleanupAcknowledged,
                            bypassObserved = runtime.bypassObserved,
                        )
                    }
                },
                invalidReason = invalidReason.get(),
            )
            val capacityScore = NetworkComprehensiveScorer.score(evidence)
            val recoveryEvidence = recovery?.let {
                NetworkRecoveryEvidence(
                    serverAcknowledged = acknowledgement.acknowledged,
                    triggerAcknowledged = it.triggerAcknowledged,
                    declaredOutageMs = it.declaredOutageMs,
                    outageFailureCount = it.outageFailureCount,
                    recoveryTimeMs = it.recoveryTimeMs,
                    postRecoveryRttMs = post,
                    invalidReason = invalidReason.get(),
                    impairmentLayer = gatewaySpec?.impairmentLayer ?: "application_http",
                    bypassObserved = it.gatewayBypassObserved,
                )
            }
            val score = recoveryEvidence?.let(NetworkRecoveryScorer::score) ?: capacityScore
            val metrics = if (recoveryEvidence != null) capacityScore.metrics + score.metrics else score.metrics
            val capacityMetrics = capacityScore.metrics
            val errors = download.errors + upload.errors + listOfNotNull(udp.error)
            val status = when {
                score.verdict == TokenVerdict.INVALID -> "invalid"
                recovery != null && recovery.recoveryTimeMs == null -> "failed"
                download.totalBytes == 0L && upload.totalBytes == 0L -> "failed"
                score.totalScore == null -> "partial"
                else -> "completed"
            }
            val result = BasicSpeedResult(
                runId, startedAtEpochMs, measureBase, profile.claimScope, profile.profileId, profile.version, config.variant,
                profile.evaluation.scorePolicyId, profile.evaluation.scoreAnchorPolicyId, profile.evaluation.conclusionPolicyId,
                status, score.totalScore, score.grade, score.verdict, score.confidence,
                capacityMetrics["NET-B01"]?.value, capacityMetrics["NET-B02"]?.value, capacityMetrics["NET-B03"]?.value,
                capacityMetrics["NET-B04"]?.value, capacityMetrics["NET-B05"]?.value, capacityMetrics["NET-B06"]?.value,
                capacityMetrics["NET-B09"]?.value, capacityMetrics["NET-B07"]?.value, capacityMetrics["NET-B10"]?.value,
                BasicSpeedMath.percentile(post.filterNotNull(), 0.50), download.totalBytes, upload.totalBytes, errors,
                metrics, score.groupScores, score.conclusions, evidenceJson(evidence, recoveryEvidence),
                syntheticImpairment = profile.syntheticImpairment != null,
                impairmentProfileId = when {
                    profile.syntheticImpairment != null -> profile.profileId
                    gatewaySpec != null -> gatewaySpec.gatewayProfileId
                    else -> null
                },
                impairmentProfileVersion = when {
                    profile.syntheticImpairment != null -> profile.version
                    gatewaySpec != null -> gatewaySpec.gatewayProfileVersion
                    else -> null
                },
                impairmentDownlinkMbps = profile.syntheticImpairment?.downlinkMbps ?: gatewaySpec?.downlink?.rateMbps,
                impairmentUplinkMbps = profile.syntheticImpairment?.uplinkMbps ?: gatewaySpec?.uplink?.rateMbps,
                impairmentAddedRttMs = profile.syntheticImpairment?.addedRttMs
                    ?: gatewaySpec?.let { it.downlink.delayMs + it.uplink.delayMs },
                impairmentJitterMs = profile.syntheticImpairment?.jitterMs
                    ?: gatewaySpec?.let { maxOf(it.downlink.jitterMs, it.uplink.jitterMs) },
                impairmentOutageDurationMs = profile.syntheticImpairment?.outageDurationMs?.takeIf { it > 0 }
                    ?: gatewaySpec?.durationMs?.takeIf { gatewaySpec.kind == "outage" },
                impairmentExcludedFromShaping = profile.syntheticImpairment?.excludedFromShaping
                    ?: gatewaySpec?.excludedFromImpairment.orEmpty(),
                impairmentAcknowledged = (profile.syntheticImpairment != null && acknowledgement.acknowledged) ||
                    gatewayEvidence?.acknowledged == true,
                recoveryTimeMs = recovery?.recoveryTimeMs,
                recoveryFailureCount = recovery?.outageFailureCount ?: 0,
                postRecoverySuccessRatio = post.takeIf { recovery != null && it.isNotEmpty() }?.let { values -> values.count { it != null }.toDouble() / values.size },
                gatewayImpairment = gatewaySpec != null,
                gatewayExperimentId = gatewayEvidence?.experimentId,
                gatewayProfileFingerprint = gatewayEvidence?.profileFingerprint,
                gatewayManagementBase = config.gatewayBase?.trim()?.trimEnd('/'),
                gatewayImpairmentLayer = gatewaySpec?.impairmentLayer,
                gatewayAcknowledged = gatewayEvidence?.acknowledged == true,
                gatewayCleanupAcknowledged = gatewayEvidence?.cleanupAcknowledged == true,
                gatewayBypassObserved = gatewayEvidence?.bypassObserved == true,
                gatewayUplinkDelayMs = gatewaySpec?.uplink?.delayMs,
                gatewayDownlinkDelayMs = gatewaySpec?.downlink?.delayMs,
                gatewayUplinkLossPct = gatewaySpec?.uplink?.lossPct,
                gatewayDownlinkLossPct = gatewaySpec?.downlink?.lossPct,
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
            val cleanupId = activeGatewayExperimentId
            val cleanupClient = gatewayClient
            val cleanupSpec = gatewaySpecForCleanup
            if (cleanupId != null && cleanupClient != null && cleanupSpec != null && !gatewayCleanupAcknowledged) {
                runCatching {
                    withContext(NonCancellable) {
                        stopAndAwaitGatewayCleanup(cleanupClient, cleanupId, cleanupSpec, runId)
                    }
                }
                    .onSuccess { log("NET_V1_GATEWAY_FAILSAFE_CLEARED experiment=$cleanupId") }
                    .onFailure { log("NET_V1_GATEWAY_FAILSAFE_FAILED experiment=$cleanupId error=${it.message}") }
            }
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
        endpoints: NetworkEndpointContext,
        acknowledgement: SyntheticAcknowledgementTracker,
        attempts: Int,
        onProgress: (Int) -> Unit,
    ): List<NetworkHandshakeEvidence> {
        val values = ArrayList<NetworkHandshakeEvidence>()
        repeat(attempts.coerceAtLeast(1)) { index ->
            client.evictConnections()
            val echo = client.echo(endpoints.url("/api/v1/echo"))
            acknowledgement.observe(echo.syntheticImpairment)
            values += handshakeEvidence(echo.timing, echo.error == null, echo.syntheticImpairment)
            onProgress(index + 1)
            if (index + 1 < attempts) delay(ECHO_GAP_MS)
        }
        return values
    }

    private fun handshakeEvidence(
        timing: TimingRecord?,
        success: Boolean,
        syntheticImpairment: String?,
    ): NetworkHandshakeEvidence {
        fun delta(end: Long?, start: Long?) = if (end != null && start != null && end >= start) (end - start) / 1e6 else null
        val tcpEnd = timing?.secureConnectStartNs ?: timing?.connectEndNs
        return NetworkHandshakeEvidence(
            dnsMs = delta(timing?.dnsEndNs, timing?.dnsStartNs),
            tcpMs = delta(tcpEnd, timing?.connectStartNs),
            tlsMs = delta(timing?.secureConnectEndNs, timing?.secureConnectStartNs),
            success = success,
            syntheticImpairment = syntheticImpairment,
        )
    }

    private suspend fun measureEcho(
        client: AnebClient,
        endpoints: NetworkEndpointContext,
        acknowledgement: SyntheticAcknowledgementTracker,
        samples: Int,
        onProgress: (Int, List<Double?>) -> Unit,
    ): List<Double?> {
        val values = ArrayList<Double?>(samples)
        repeat(samples.coerceAtLeast(1)) { index ->
            val echo = client.echo(endpoints.url("/api/v1/echo"))
            acknowledgement.observe(echo.syntheticImpairment)
            values += echo.rttUs?.takeIf { echo.error == null }?.div(1_000.0)
            onProgress(index + 1, values)
            if (index + 1 < samples) delay(ECHO_GAP_MS)
        }
        return values
    }

    private suspend fun startGatewayExperiment(
        gateway: AnebGatewayClient,
        runId: String,
        spec: ProfileGatewayImpairment,
    ): AnebGatewayClient.Experiment {
        val scheduled = gateway.start(runId, spec.profileRef)
        validateGatewayExperiment(scheduled, runId, spec)
        return try {
            withTimeout((spec.activationDelayMs + GATEWAY_TRANSITION_TIMEOUT_MS).toLong()) {
                var current = scheduled
                while (current.phase == "scheduled") {
                    delay(GATEWAY_POLL_MS)
                    current = gateway.get(current.experimentId)
                    validateGatewayExperiment(current, runId, spec)
                }
                require(current.phase == "active") { "gateway_activation_failed:${current.phase}" }
                require(current.error.isBlank()) { "gateway_activation_error" }
                current
            }
        } catch (error: Throwable) {
            runCatching {
                withContext(NonCancellable) {
                    stopAndAwaitGatewayCleanup(gateway, scheduled.experimentId, spec, runId)
                }
            }
            throw error
        }
    }

    private suspend fun ensureGatewayActive(
        gateway: AnebGatewayClient,
        experimentId: String,
        runId: String,
        spec: ProfileGatewayImpairment,
    ) {
        val current = gateway.get(experimentId)
        validateGatewayExperiment(current, runId, spec)
        require(current.phase == "active" && current.error.isBlank()) { "gateway_not_active:${current.phase}" }
    }

    private suspend fun stopAndAwaitGatewayCleanup(
        gateway: AnebGatewayClient,
        experimentId: String,
        spec: ProfileGatewayImpairment,
        runId: String,
    ): AnebGatewayClient.Experiment {
        val stopping = gateway.stop(experimentId)
        validateGatewayExperiment(stopping, runId, spec)
        return withTimeout(GATEWAY_CLEANUP_TIMEOUT_MS) {
            var current = stopping
            while (current.phase in setOf("scheduled", "active", "clearing")) {
                delay(GATEWAY_POLL_MS)
                current = gateway.get(experimentId)
                validateGatewayExperiment(current, runId, spec)
            }
            require(current.phase == "completed") { "gateway_cleanup_failed:${current.phase}" }
            require(current.clearedAt != null && current.error.isBlank()) { "gateway_cleanup_not_confirmed" }
            current
        }
    }

    private fun validateGatewayExperiment(
        experiment: AnebGatewayClient.Experiment,
        runId: String,
        spec: ProfileGatewayImpairment,
    ) {
        require(experiment.runId == runId) { "gateway_run_id_mismatch" }
        require(experiment.profileRef == spec.profileRef) { "gateway_profile_ref_mismatch" }
        require(experiment.profileFingerprint == spec.profileFingerprint) { "gateway_profile_fingerprint_mismatch" }
        require(experiment.impairmentLayer == spec.impairmentLayer) { "gateway_impairment_layer_mismatch" }
        require(experiment.claimScope == "dedicated_gateway_ip_forwarding") { "gateway_claim_scope_mismatch" }
    }

    private suspend fun runGatewayRecoveryPhase(
        client: AnebClient,
        endpoints: NetworkEndpointContext,
        gateway: AnebGatewayClient,
        runId: String,
        spec: ProfileGatewayImpairment,
        phase: ProfilePhase,
        onExperimentStarted: (String) -> Unit,
    ): Pair<GatewayRuntimeEvidence, RecoveryRunObservation> {
        _telemetry.value = _telemetry.value.copy(
            phase = BasicSpeedPhase.RECOVERY,
            currentMbps = null,
            phaseAverageMbps = null,
            recoveryElapsedMs = 0.0,
            recoveryFailureCount = 0,
            syntheticOutageActive = false,
            networkLayerOutage = false,
            progress = 0.91,
        )
        val active = startGatewayExperiment(gateway, runId, spec)
        onExperimentStarted(active.experimentId)
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        var failures = 0
        var recoveryTimeMs: Double? = null
        var bypassObserved = false
        var cleanupAcknowledged = false
        try {
            val attempts = phase.samples.coerceAtLeast(1)
            for (index in 0 until attempts) {
                val before = gateway.get(active.experimentId)
                validateGatewayExperiment(before, runId, spec)
                if (before.phase == "failed") error("gateway_experiment_failed")
                _telemetry.value = _telemetry.value.copy(networkLayerOutage = before.phase == "active")
                val echo = client.echo(endpoints.url("/api/v1/echo"), GATEWAY_ECHO_TIMEOUT_MS)
                val after = gateway.get(active.experimentId)
                validateGatewayExperiment(after, runId, spec)
                if (after.phase == "failed") error("gateway_experiment_failed")
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1e6
                val success = echo.error == null
                if (success && after.phase == "active") {
                    bypassObserved = true
                    break
                }
                if (!success && before.phase == "active") failures += 1
                if (after.phase == "completed") {
                    cleanupAcknowledged = after.clearedAt != null && after.error.isBlank()
                    if (success) {
                        recoveryTimeMs = elapsedMs
                        break
                    }
                }
                _telemetry.value = _telemetry.value.copy(
                    recoveryElapsedMs = elapsedMs,
                    recoveryFailureCount = failures,
                    networkLayerOutage = after.phase == "active",
                    loadedRttMs = echo.rttUs?.takeIf { success }?.div(1_000.0),
                    progress = 0.91 + 0.06 * (index + 1) / attempts,
                )
                if (index + 1 < attempts) delay(phase.echoIntervalMs.coerceAtLeast(50).toLong())
            }
            if (!cleanupAcknowledged) {
                stopAndAwaitGatewayCleanup(gateway, active.experimentId, spec, runId)
                cleanupAcknowledged = true
            }
            val runtime = GatewayRuntimeEvidence(
                experimentId = active.experimentId,
                profileFingerprint = active.profileFingerprint,
                acknowledged = true,
                cleanupAcknowledged = cleanupAcknowledged,
                bypassObserved = bypassObserved,
            )
            val observation = RecoveryRunObservation(
                triggerAcknowledged = true,
                declaredOutageMs = spec.durationMs,
                outageFailureCount = failures,
                recoveryTimeMs = recoveryTimeMs,
                gatewayBypassObserved = bypassObserved,
            )
            return runtime to observation
        } finally {
            if (!cleanupAcknowledged) runCatching {
                withContext(NonCancellable) {
                    stopAndAwaitGatewayCleanup(gateway, active.experimentId, spec, runId)
                }
            }
        }
    }

    private fun gatewayLabel(spec: ProfileGatewayImpairment): String = if (spec.kind == "outage") {
        "网络层网关恢复 · 双向中断 ${spec.durationMs}ms · 不改变 RSRP/SINR"
    } else {
        "网络层网关实验 · ↓${spec.downlink.rateMbps.toInt()} ↑${spec.uplink.rateMbps.toInt()} Mbps · " +
            "双向丢包 ${spec.downlink.lossPct.toInt()}% · 不改变 RSRP/SINR"
    }

    private suspend fun runRecoveryPhase(
        client: AnebClient,
        endpoints: NetworkEndpointContext,
        acknowledgement: SyntheticAcknowledgementTracker,
        phase: ProfilePhase,
        declaredOutageMs: Int,
    ): RecoveryRunObservation {
        _telemetry.value = _telemetry.value.copy(
            phase = BasicSpeedPhase.RECOVERY,
            currentMbps = null,
            phaseAverageMbps = null,
            recoveryElapsedMs = 0.0,
            recoveryFailureCount = 0,
            syntheticOutageActive = false,
            progress = 0.91,
        )
        val trigger = client.triggerSyntheticOutage(endpoints.url("/api/v1/recovery"))
        acknowledgement.observe(trigger.syntheticImpairment)
        val triggerAcknowledged = trigger.accepted &&
            trigger.outageDurationMs == declaredOutageMs && declaredOutageMs > 0
        if (!triggerAcknowledged) {
            return RecoveryRunObservation(false, declaredOutageMs, 0, null)
        }

        val startedNanos = SystemClock.elapsedRealtimeNanos()
        var failures = 0
        var recoveryTimeMs: Double? = null
        val attempts = phase.samples.coerceAtLeast(1)
        for (index in 0 until attempts) {
            val echo = client.echo(endpoints.url("/api/v1/echo"))
            acknowledgement.observe(echo.syntheticImpairment)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1e6
            if (echo.error == null) {
                recoveryTimeMs = elapsedMs
            } else if (echo.syntheticOutageActive) {
                failures += 1
            }
            _telemetry.value = _telemetry.value.copy(
                recoveryElapsedMs = elapsedMs,
                recoveryFailureCount = failures,
                syntheticOutageActive = echo.syntheticOutageActive,
                loadedRttMs = echo.rttUs?.takeIf { echo.error == null }?.div(1_000.0),
                progress = 0.91 + 0.06 * (index + 1) / attempts,
            )
            if (recoveryTimeMs != null) break
            if (index + 1 < attempts) delay(phase.echoIntervalMs.coerceAtLeast(50).toLong())
        }
        return RecoveryRunObservation(triggerAcknowledged, declaredOutageMs, failures, recoveryTimeMs)
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
        endpoints: NetworkEndpointContext,
        acknowledgement: SyntheticAcknowledgementTracker,
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
                val echo = client.echo(endpoints.url("/api/v1/echo"))
                acknowledgement.observe(echo.syntheticImpairment)
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
                            endpoints.url("/api/v1/download?bytes=$transferBytes&chunk_kb=${phase.chunkKb}"),
                        ) { count, _ -> totalBytes.add(count.toLong()) }
                        TransferDirection.UPLOAD -> client.uploadThroughput(
                            endpoints.url("/api/v1/upload?run=$runId-net-$workerIndex"), transferBytes, chunkBytes,
                        ) { count, _ ->
                            // Impaired runs count only bytes acknowledged by the server. Local socket writes
                            // can run ahead of a constrained uplink and would otherwise overstate goodput.
                            if (!endpoints.requiresServerAcknowledgedUpload) totalBytes.add(count.toLong())
                        }
                    }
                    acknowledgement.observe(transfer.syntheticImpairment)
                    if (
                        direction == TransferDirection.UPLOAD &&
                        endpoints.requiresServerAcknowledgedUpload &&
                        transfer.error == null
                    ) {
                        totalBytes.add(transfer.totalBytes)
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
                    result.syntheticImpairment, result.impairmentProfileId, result.impairmentProfileVersion,
                    result.impairmentDownlinkMbps, result.impairmentUplinkMbps, result.impairmentAddedRttMs,
                    result.impairmentJitterMs, result.impairmentOutageDurationMs,
                    result.impairmentExcludedFromShaping.joinToString(","), result.impairmentAcknowledged,
                    result.recoveryTimeMs, result.recoveryFailureCount, result.postRecoverySuccessRatio,
                    result.gatewayImpairment, result.gatewayExperimentId, result.gatewayProfileFingerprint,
                    result.gatewayManagementBase, result.gatewayImpairmentLayer, result.gatewayAcknowledged,
                    result.gatewayCleanupAcknowledged, result.gatewayBypassObserved, result.gatewayUplinkDelayMs,
                    result.gatewayDownlinkDelayMs, result.gatewayUplinkLossPct, result.gatewayDownlinkLossPct,
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

    private fun evidenceJson(e: NetworkComprehensiveEvidence, recovery: NetworkRecoveryEvidence? = null) = buildJsonObject {
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
                put("synthetic_impairment", h.syntheticImpairment?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        })
        put("synthetic_impairment", e.syntheticImpairment?.let { synthetic ->
            buildJsonObject {
                put("contract_version", "aneb-synthetic-evidence-v1")
                put("profile_id", synthetic.profileId)
                put("profile_version", synthetic.profileVersion)
                put("downlink_mbps", synthetic.downlinkMbps)
                put("uplink_mbps", synthetic.uplinkMbps)
                put("added_rtt_ms", synthetic.addedRttMs)
                put("jitter_ms", synthetic.jitterMs)
                put("outage_duration_ms", synthetic.outageDurationMs)
                put("applies_to", buildJsonArray { synthetic.appliesTo.forEach { add(JsonPrimitive(it)) } })
                put("excluded_from_shaping", buildJsonArray { synthetic.excludedFromShaping.forEach { add(JsonPrimitive(it)) } })
                put("server_acknowledged", synthetic.serverAcknowledged)
            }
        } ?: JsonNull)
        put("gateway_impairment", e.gatewayImpairment?.let { gateway ->
            buildJsonObject {
                put("contract_version", "aneb-gateway-evidence-v1")
                put("experiment_id", gateway.experimentId)
                put("profile_ref", gateway.profileRef)
                put("profile_fingerprint", gateway.profileFingerprint)
                put("impairment_layer", gateway.impairmentLayer)
                put("gateway_acknowledged", gateway.gatewayAcknowledged)
                put("cleanup_acknowledged", gateway.cleanupAcknowledged)
                put("bypass_observed", gateway.bypassObserved)
                put("uplink", buildJsonObject {
                    put("rate_mbps", gateway.uplink.rateMbps)
                    put("delay_ms", gateway.uplink.delayMs)
                    put("jitter_ms", gateway.uplink.jitterMs)
                    put("loss_pct", gateway.uplink.lossPct)
                })
                put("downlink", buildJsonObject {
                    put("rate_mbps", gateway.downlink.rateMbps)
                    put("delay_ms", gateway.downlink.delayMs)
                    put("jitter_ms", gateway.downlink.jitterMs)
                    put("loss_pct", gateway.downlink.lossPct)
                })
                put("excluded_from_impairment", buildJsonArray {
                    gateway.excludedFromImpairment.forEach { add(JsonPrimitive(it)) }
                })
            }
        } ?: JsonNull)
        put("recovery", recovery?.let { r ->
            buildJsonObject {
                put("contract_version", "aneb-network-recovery-evidence-v1")
                put("server_acknowledged", r.serverAcknowledged)
                put("trigger_acknowledged", r.triggerAcknowledged)
                put("declared_outage_ms", r.declaredOutageMs)
                put("outage_failure_count", r.outageFailureCount)
                put("recovery_time_ms", r.recoveryTimeMs?.let(::JsonPrimitive) ?: JsonNull)
                put("post_recovery_rtt_ms", nullableArray(r.postRecoveryRttMs))
                put("impairment_layer", r.impairmentLayer)
                put("bypass_observed", r.bypassObserved)
            }
        } ?: JsonNull)
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
        private const val GATEWAY_POLL_MS = 100L
        private const val GATEWAY_TRANSITION_TIMEOUT_MS = 10_000
        private const val GATEWAY_CLEANUP_TIMEOUT_MS = 12_000L
        private const val GATEWAY_ECHO_TIMEOUT_MS = 350L
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
