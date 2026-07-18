package com.aneb.probe.engine

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.room.withTransaction
import com.aneb.probe.BuildConfig
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
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
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
    val confidenceMethodId: String = "network-sample-coverage-v1",
    val coverageRatio: Double? = null,
    val minimumSampleSatisfied: Boolean? = null,
    val notComputableReason: String? = null,
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

private data class LoadedNetworkProfile(
    val profile: ScenarioProfile,
    val profileHash: String,
    val profileAssetUri: String,
)

private class NetworkComprehensiveProfileRepository(private val context: Context) {
    suspend fun load(variant: String): LoadedNetworkProfile = withContext(Dispatchers.IO) {
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
        LoadedNetworkProfile(
            profile = profile,
            profileHash = TokenRuntimeIntegrity.canonicalSha256(text),
            profileAssetUri = "asset:///$path",
        )
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

internal class GatewayControlException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun gatewayFailureForService(
    gatewayRequested: Boolean,
    error: Exception,
): GatewayControlException? = when {
    !gatewayRequested -> null
    error is GatewayControlException -> error
    else -> GatewayControlException("gateway_control_failed", error)
}

internal suspend fun <T> withGatewayControlTimeout(
    timeoutMs: Long,
    operation: String,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (error: TimeoutCancellationException) {
    throw GatewayControlException("gateway_${operation}_timeout", error)
}

/** Cancellation is observational only: it may discover an accepted POST, never issue a new POST. */
internal suspend fun <T> discoverCancelledGatewayStart(
    status: suspend () -> T?,
    isOwned: (T) -> Boolean,
): T? = status()?.takeIf(isOwned)

/** Non-cancel start ambiguity: status, one idempotent POST, then final status discovery. */
internal suspend fun <T> reconcileFailedGatewayStart(
    status: suspend () -> T?,
    retryPost: suspend () -> T?,
    isOwned: (T) -> Boolean,
): T? {
    status()?.takeIf(isOwned)?.let { return it }
    retryPost()?.takeIf(isOwned)?.let { return it }
    return status()?.takeIf(isOwned)
}

/** Explicit HTTP rejection is final; only an unknown POST outcome may be reconciled or retried. */
internal suspend fun <T> reconcileAmbiguousGatewayStart(
    startError: Throwable,
    status: suspend () -> T?,
    retryPost: suspend () -> T?,
    isOwned: (T) -> Boolean,
): T? {
    if (!AnebGatewayClient.isAmbiguousSubmissionFailure(startError)) throw startError
    return reconcileFailedGatewayStart(status, retryPost, isOwned)
}

internal suspend fun <T> pollGatewayStatusUntilFound(
    status: suspend () -> T?,
    accept: (T) -> Boolean,
    pause: suspend () -> Unit,
): T {
    while (true) {
        status()?.takeIf(accept)?.let { return it }
        pause()
    }
}

internal enum class GatewayCleanupNextAction { RETRY_DELETE, POLL_GET }

/** Longer than one 7s control call so an accepted POST can become visible after a lost response. */
internal const val GATEWAY_DISCOVERY_TIMEOUT_MS = 8_000L

/** Decides when an idempotent DELETE retry is justified without creating an unbounded retry loop. */
internal class GatewayCleanupReconciliationPolicy(
    initialDeleteResponseKnown: Boolean,
    private val maxRetryDeletes: Int = 2,
) {
    private var uncertainDeleteNeedsRetry = !initialDeleteResponseKnown
    private var retryDeletes = 0

    fun onDeleteResult(responseKnown: Boolean) {
        uncertainDeleteNeedsRetry = !responseKnown
    }

    fun afterObservation(phase: String?): GatewayCleanupNextAction {
        val shouldRetry = uncertainDeleteNeedsRetry || phase == "cleanup_failed"
        if (shouldRetry && retryDeletes < maxRetryDeletes) {
            uncertainDeleteNeedsRetry = false
            retryDeletes += 1
            return GatewayCleanupNextAction.RETRY_DELETE
        }
        return GatewayCleanupNextAction.POLL_GET
    }
}

internal fun isGatewayControlVariant(variant: String): Boolean =
    variant == "gateway_loss" || variant == "gateway_recovery"

internal suspend fun prepareGatewayFailureEvidence(
    cleanup: suspend () -> Unit,
    freeze: () -> Unit,
) {
    cleanup()
    freeze()
}

private class NetworkResultPersistenceException(cause: Throwable) :
    IllegalStateException("network_result_persistence_failed", cause)

private data class NetworkDurableResult(
    val result: BasicSpeedResult,
    val envelope: com.aneb.probe.data.ResultEnvelopeEntity,
    val radio: FormalRadioEvidence,
)

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
    private val resultCommitter = DurableResultCommitter(
        store = DurableResultStore { durable: NetworkDurableResult ->
            val db = AnebDatabase.get(context)
            db.withTransaction {
                db.networkComprehensiveResultDao().insert(durable.result.toEntity())
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
        val startedAtEpochMs = System.currentTimeMillis()
        val configuredBase = config.serverBase.trim().trimEnd('/')
        val gatewayRequested = isGatewayControlVariant(config.variant)
        _result.value = null
        _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.PREPARING)
        log("NET_V1_START run_id=$runId variant=${config.variant} transport=${config.transport.name.lowercase()} server=$configuredBase")
        val radioCollector = FormalRadioEvidenceCollector(context).also { it.start(this) }

        val guard = NetGuard.guardCheck(context)
        var envelopeSource = NetworkResultEnvelopeSource(profile = null)
        if (!guard.ok) {
            val reason = guard.reasons.joinToString(",")
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(
                failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "guard_rejected:$reason"),
                envelopeSource, config.transport, guard, null, radioCollector,
                System.currentTimeMillis(), "failed", log,
            )
            log("NET_V1_END run_id=$runId status=guard_rejected reasons=$reason")
            return@channelFlow
        }

        var bound: BoundNetwork? = null
        var pathMonitor: PathMonitor? = null
        var gatewayClient: AnebGatewayClient? = null
        var activeGatewayExperimentId: String? = null
        var gatewayOwnedExperiment: AnebGatewayClient.Experiment? = null
        var gatewayActivationAcknowledged = false
        var gatewayEvidence: GatewayRuntimeEvidence? = null
        var gatewayCleanupAcknowledged = false
        var gatewaySpecForCleanup: ProfileGatewayImpairment? = null
        var cleanupEvidenceFrozen = false
        val invalidReason = AtomicReference<String?>(null)

        suspend fun cleanupOwnedGateway(logLabel: String): Boolean {
            val cleanupId = activeGatewayExperimentId ?: return gatewayCleanupAcknowledged
            val cleanupClient = gatewayClient ?: return false
            val cleanupSpec = gatewaySpecForCleanup ?: return false
            return runCatching {
                withContext(NonCancellable) {
                    stopAndAwaitGatewayCleanup(cleanupClient, cleanupId, cleanupSpec, runId)
                }
            }.fold(
                onSuccess = { terminal ->
                    gatewayOwnedExperiment = terminal
                    gatewayCleanupAcknowledged = true
                    activeGatewayExperimentId = null
                    gatewayEvidence = (gatewayEvidence ?: GatewayRuntimeEvidence(
                        experimentId = terminal.experimentId,
                        profileFingerprint = terminal.profileFingerprint,
                        acknowledged = gatewayActivationAcknowledged,
                    )).copy(cleanupAcknowledged = true)
                    withContext(NonCancellable) {
                        runCatching { log("NET_V1_GATEWAY_${logLabel}_CLEARED experiment=$cleanupId") }
                    }
                    true
                },
                onFailure = {
                    withContext(NonCancellable) {
                        runCatching { log("NET_V1_GATEWAY_${logLabel}_FAILED experiment=$cleanupId error=${it.message}") }
                    }
                    false
                },
            )
        }
        try {
            bound = acquireBoundNetwork(config.transport, guard.metadata["active_transports"])
            if (bound != null) {
                pathMonitor = PathMonitor(context, bound, onInvalidate = { invalidReason.compareAndSet(null, it) }).also { it.start() }
            }
        } catch (e: GuardException) {
            _telemetry.value = BasicSpeedTelemetry(phase = BasicSpeedPhase.FAILED)
            publishResult(
                failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "bind_failed:${e.message}"),
                envelopeSource, config.transport, guard, null, radioCollector,
                System.currentTimeMillis(), "failed", log,
            )
            log("NET_V1_END run_id=$runId status=bind_failed")
            return@channelFlow
        }

        try {
            val loadedProfile = NetworkComprehensiveProfileRepository(context).load(config.variant)
            val profile = loadedProfile.profile
            envelopeSource = NetworkResultEnvelopeSource(
                profile = profile,
                profileHash = loadedProfile.profileHash,
                profileUri = loadedProfile.profileAssetUri,
            )
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
            if (gatewaySpec != null && config.variant == "gateway_loss") {
                val started = startGatewayExperiment(
                    gateway = checkNotNull(gatewayClient),
                    runId = runId,
                    spec = gatewaySpec,
                    onOwned = { owned ->
                        gatewayOwnedExperiment = owned
                        activeGatewayExperimentId = owned.experimentId
                    },
                    onActive = { active ->
                        gatewayOwnedExperiment = active
                        gatewayActivationAcknowledged = true
                    },
                )
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
                        onExperimentOwned = { owned ->
                            gatewayOwnedExperiment = owned
                            activeGatewayExperimentId = owned.experimentId
                        },
                        onExperimentActive = { active ->
                            gatewayOwnedExperiment = active
                            gatewayActivationAcknowledged = true
                        },
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
                gatewayOwnedExperiment = terminal
                gatewayEvidence = checkNotNull(gatewayEvidence).copy(cleanupAcknowledged = true)
                GatewayExperimentContract.requireSuccessfulTerminal(
                    terminal,
                    gatewayExpected(runId, gatewaySpec),
                )
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
                confidenceMethodId = score.confidenceMethodId,
                coverageRatio = score.coverageRatio,
                minimumSampleSatisfied = score.minimumSampleSatisfied,
                notComputableReason = score.notComputableReason,
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
            publishResult(
                result, envelopeSource, config.transport, guard, bound, radioCollector, System.currentTimeMillis(),
                if (status == "completed") "completed" else "failed", log,
            )
            _telemetry.value = _telemetry.value.copy(
                phase = if (status == "failed" || status == "invalid") BasicSpeedPhase.FAILED else BasicSpeedPhase.COMPLETE,
                currentMbps = null, phaseAverageMbps = null, progress = 1.0,
            )
            log("NET_V1_RESULT run_id=$runId status=$status score=${score.totalScore ?: "null"} grade=${score.grade ?: "null"} verdict=${score.verdict} confidence=${score.confidence}")
            log("NET_V1_END run_id=$runId status=$status")
        } catch (e: CancellationException) {
            prepareGatewayFailureEvidence(
                cleanup = { cleanupOwnedGateway("CANCEL") },
                freeze = { cleanupEvidenceFrozen = true },
            )
            val cancelled = gatewayFailureResult(
                failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "cancelled"),
                gatewaySpecForCleanup,
                gatewayOwnedExperiment,
                gatewayActivationAcknowledged,
                gatewayCleanupAcknowledged,
                config.gatewayBase,
            )
            withContext(NonCancellable) {
                runCatching {
                    publishResult(
                        cancelled, envelopeSource, config.transport, guard, bound, radioCollector,
                        System.currentTimeMillis(), "cancelled", log,
                    )
                }
            }
            throw e
        } catch (e: NetworkResultPersistenceException) {
            throw e
        } catch (e: Exception) {
            prepareGatewayFailureEvidence(
                cleanup = { cleanupOwnedGateway("ERROR") },
                freeze = { cleanupEvidenceFrozen = true },
            )
            _telemetry.value = _telemetry.value.copy(phase = BasicSpeedPhase.FAILED, currentMbps = null)
            val failed = gatewayFailureResult(
                failureResult(runId, startedAtEpochMs, configuredBase, config.variant, "error:${e.javaClass.simpleName}:${e.message}"),
                gatewaySpecForCleanup,
                gatewayOwnedExperiment,
                gatewayActivationAcknowledged,
                gatewayCleanupAcknowledged,
                config.gatewayBase,
            )
            publishResult(
                failed, envelopeSource, config.transport, guard, bound, radioCollector,
                System.currentTimeMillis(), "failed", log,
            )
            log("NET_V1_FAILED run_id=$runId error=${e.toString().replace(' ', '_')}")
            log("NET_V1_END run_id=$runId status=error")
            gatewayFailureForService(gatewayRequested, e)?.let { throw it }
        } finally {
            if (!cleanupEvidenceFrozen && activeGatewayExperimentId != null && !gatewayCleanupAcknowledged) {
                cleanupOwnedGateway("FAILSAFE")
            }
            pathMonitor?.stop()
            bound?.release()
            radioCollector.close()
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
        onOwned: (AnebGatewayClient.Experiment) -> Unit,
        onActive: (AnebGatewayClient.Experiment) -> Unit,
    ): AnebGatewayClient.Experiment {
        val expected = gatewayExpected(runId, spec)
        val scheduled = try {
            gateway.start(runId, spec.profileRef)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                discoverOwnedGatewayAfterCancellation(gateway, runId, spec)?.let { owned ->
                    GatewayExperimentContract.requireOwned(owned, expected)
                    onOwned(owned)
                    runCatching { stopAndAwaitGatewayCleanup(gateway, owned.experimentId, spec, runId) }
                }
            }
            throw cancelled
        } catch (startError: Exception) {
            val reconciled = withContext(NonCancellable) {
                reconcileOwnedGatewayAfterStartFailure(gateway, runId, spec, startError)
            }
            if (reconciled == null) throw startError
            reconciled
        }
        GatewayExperimentContract.requireOwned(scheduled, expected)
        onOwned(scheduled)
        return try {
            validateGatewayExperiment(scheduled, runId, spec)
            withGatewayControlTimeout(
                timeoutMs = (spec.activationDelayMs + GATEWAY_TRANSITION_TIMEOUT_MS).toLong(),
                operation = "activation",
            ) {
                var current = scheduled
                while (current.phase == "scheduled") {
                    delay(GATEWAY_POLL_MS)
                    current = gateway.get(current.experimentId)
                    validateGatewayExperiment(current, runId, spec)
                }
                GatewayExperimentContract.requireActive(current, expected)
                onActive(current)
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

    private suspend fun discoverOwnedGatewayAfterCancellation(
        gateway: AnebGatewayClient,
        runId: String,
        spec: ProfileGatewayImpairment,
    ): AnebGatewayClient.Experiment? {
        val expected = gatewayExpected(runId, spec)
        return discoverCancelledGatewayStart(
            status = { boundedGatewayStatus(gateway, expected) },
            isOwned = { GatewayExperimentContract.isOwnedBy(it, expected) },
        )
    }

    private suspend fun reconcileOwnedGatewayAfterStartFailure(
        gateway: AnebGatewayClient,
        runId: String,
        spec: ProfileGatewayImpairment,
        startError: Throwable,
    ): AnebGatewayClient.Experiment? {
        val expected = gatewayExpected(runId, spec)
        return reconcileAmbiguousGatewayStart(
            startError = startError,
            status = { boundedGatewayStatus(gateway, expected) },
            retryPost = {
                runCatching {
                    withGatewayControlTimeout(GATEWAY_DISCOVERY_TIMEOUT_MS, "start_retry") {
                        gateway.start(runId, spec.profileRef)
                    }
                }.getOrNull()
            },
            isOwned = { GatewayExperimentContract.isOwnedBy(it, expected) },
        )
    }

    private suspend fun boundedGatewayStatus(
        gateway: AnebGatewayClient,
        expected: GatewayExperimentContract.Expected,
    ): AnebGatewayClient.Experiment? = withTimeoutOrNull(GATEWAY_DISCOVERY_TIMEOUT_MS) {
        pollGatewayStatusUntilFound(
            status = {
                try {
                    withTimeoutOrNull(GATEWAY_DISCOVERY_ATTEMPT_TIMEOUT_MS) { gateway.status() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            },
            accept = { GatewayExperimentContract.isOwnedBy(it, expected) },
            pause = { delay(GATEWAY_DISCOVERY_POLL_MS) },
        )
    }

    private suspend fun ensureGatewayActive(
        gateway: AnebGatewayClient,
        experimentId: String,
        runId: String,
        spec: ProfileGatewayImpairment,
    ) {
        val current = gateway.get(experimentId)
        GatewayExperimentContract.requireActive(current, gatewayExpected(runId, spec))
    }

    private suspend fun stopAndAwaitGatewayCleanup(
        gateway: AnebGatewayClient,
        experimentId: String,
        spec: ProfileGatewayImpairment,
        runId: String,
    ): AnebGatewayClient.Experiment = withGatewayControlTimeout(GATEWAY_CLEANUP_TIMEOUT_MS, "cleanup") {
        val initial = runCatching { gateway.stop(experimentId) }.getOrNull()
        val policy = GatewayCleanupReconciliationPolicy(initialDeleteResponseKnown = initial != null)
        val expected = gatewayExpected(runId, spec)
        var current = initial
        var fetchBeforeDecision = initial == null
        var cleaned: AnebGatewayClient.Experiment? = null
        while (cleaned == null) {
            if (fetchBeforeDecision) {
                current = runCatching { gateway.get(experimentId) }.getOrNull()
                fetchBeforeDecision = false
            }
            current?.let { observed ->
                GatewayExperimentContract.validate(observed, expected)
                if (observed.cleanupVerified) {
                    GatewayExperimentContract.requireCleanTerminal(observed, expected)
                    cleaned = observed
                }
                if (observed.phase == "completed" || observed.phase == "failed") {
                    GatewayExperimentContract.requireCleanTerminal(observed, expected)
                }
            }
            if (cleaned != null) break
            when (policy.afterObservation(current?.phase)) {
                GatewayCleanupNextAction.RETRY_DELETE -> {
                    current = runCatching { gateway.stop(experimentId) }.getOrNull()
                    policy.onDeleteResult(responseKnown = current != null)
                    fetchBeforeDecision = current == null
                }
                GatewayCleanupNextAction.POLL_GET -> {
                    delay(GATEWAY_POLL_MS)
                    current = runCatching { gateway.get(experimentId) }.getOrNull()
                }
            }
        }
        requireNotNull(cleaned)
    }

    private fun validateGatewayExperiment(
        experiment: AnebGatewayClient.Experiment,
        runId: String,
        spec: ProfileGatewayImpairment,
    ) {
        GatewayExperimentContract.validate(experiment, gatewayExpected(runId, spec))
    }

    private fun gatewayExpected(runId: String, spec: ProfileGatewayImpairment) = GatewayExperimentContract.Expected(
        runId = runId,
        profileRef = spec.profileRef,
        profileFingerprint = spec.profileFingerprint,
        impairmentLayer = spec.impairmentLayer,
    )

    private suspend fun runGatewayRecoveryPhase(
        client: AnebClient,
        endpoints: NetworkEndpointContext,
        gateway: AnebGatewayClient,
        runId: String,
        spec: ProfileGatewayImpairment,
        phase: ProfilePhase,
        onExperimentOwned: (AnebGatewayClient.Experiment) -> Unit,
        onExperimentActive: (AnebGatewayClient.Experiment) -> Unit,
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
        val active = startGatewayExperiment(
            gateway = gateway,
            runId = runId,
            spec = spec,
            onOwned = onExperimentOwned,
            onActive = onExperimentActive,
        )
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val tracker = GatewayRecoveryTracker(startedNanos)
        val expected = gatewayExpected(runId, spec)
        var cleanupAcknowledged = false
        var cleanTerminal: AnebGatewayClient.Experiment? = null
        try {
            val attempts = phase.samples.coerceAtLeast(1)
            for (index in 0 until attempts) {
                val before = gateway.get(active.experimentId)
                validateGatewayExperiment(before, runId, spec)
                cleanupAcknowledged = before.cleanupVerified && before.clearedAt != null
                if (cleanupAcknowledged) cleanTerminal = before
                if (before.phase == "failed" || (cleanupAcknowledged && before.error.isNotBlank())) {
                    GatewayExperimentContract.requireSuccessfulTerminal(before, expected)
                }
                _telemetry.value = _telemetry.value.copy(networkLayerOutage = before.phase == "active")
                val echo = client.echo(endpoints.url("/api/v1/echo"), GATEWAY_ECHO_TIMEOUT_MS)
                val echoCompletedNanos = SystemClock.elapsedRealtimeNanos()
                val after = gateway.get(active.experimentId)
                validateGatewayExperiment(after, runId, spec)
                cleanupAcknowledged = after.cleanupVerified && after.clearedAt != null
                if (cleanupAcknowledged) cleanTerminal = after
                if (after.phase == "failed" || (cleanupAcknowledged && after.error.isNotBlank())) {
                    GatewayExperimentContract.requireSuccessfulTerminal(after, expected)
                }
                val elapsedMs = (echoCompletedNanos - startedNanos) / 1e6
                val success = echo.error == null
                tracker.observe(before.phase, after.phase, success, echoCompletedNanos)
                _telemetry.value = _telemetry.value.copy(
                    recoveryElapsedMs = elapsedMs,
                    recoveryFailureCount = tracker.outageFailureCount,
                    networkLayerOutage = after.phase == "active",
                    loadedRttMs = echo.rttUs?.takeIf { success }?.div(1_000.0),
                    progress = 0.91 + 0.06 * (index + 1) / attempts,
                )
                if (tracker.bypassObserved) break
                if (tracker.hasRecoveryCandidate) {
                    if (!cleanupAcknowledged) {
                        cleanTerminal = stopAndAwaitGatewayCleanup(gateway, active.experimentId, spec, runId)
                        cleanupAcknowledged = true
                    }
                    break
                }
                if (index + 1 < attempts) delay(phase.echoIntervalMs.coerceAtLeast(50).toLong())
            }
            if (!cleanupAcknowledged) {
                cleanTerminal = stopAndAwaitGatewayCleanup(gateway, active.experimentId, spec, runId)
                cleanupAcknowledged = true
            }
            GatewayExperimentContract.requireSuccessfulTerminal(checkNotNull(cleanTerminal), expected)
            val runtime = GatewayRuntimeEvidence(
                experimentId = active.experimentId,
                profileFingerprint = active.profileFingerprint,
                acknowledged = true,
                cleanupAcknowledged = cleanupAcknowledged,
                bypassObserved = tracker.bypassObserved,
            )
            val observation = RecoveryRunObservation(
                triggerAcknowledged = true,
                declaredOutageMs = spec.durationMs,
                outageFailureCount = tracker.outageFailureCount,
                recoveryTimeMs = tracker.verifiedRecoveryTimeMs(cleanupAcknowledged),
                gatewayBypassObserved = tracker.bypassObserved,
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

    private suspend fun publishResult(
        result: BasicSpeedResult,
        source: NetworkResultEnvelopeSource,
        transport: TestEngine.TransportMode,
        guard: com.aneb.probe.net.GuardResult,
        bound: BoundNetwork?,
        radioCollector: FormalRadioEvidenceCollector,
        endedAtEpochMs: Long,
        status: String,
        log: suspend (String) -> Unit,
    ) {
        val radio = radioCollector.freeze()
        val envelope = NetworkResultEnvelopeV2.build(
            NetworkResultEnvelopeInput(
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
            resultCommitter.commit(NetworkDurableResult(result, envelope, radio))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("NET_V1_DB_WRITE run_id=${result.runId} ok=false error=${e.javaClass.simpleName}")
            throw NetworkResultPersistenceException(e)
        }
        log("NET_V1_DB_WRITE run_id=${result.runId} ok=true")
        log(
            "NET_V1_RADIO run_id=${result.runId} status=${radio.collectionStatus} " +
                "samples=${radio.samples.size} raw_samples=${radio.rawSamples.size} events=${radio.events.size}",
        )
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

    private fun BasicSpeedResult.toEntity() = NetworkComprehensiveResultEntity(
        runId, startedAtEpochMs, serverBase, claimScope, profileId,
        profileVersion, variant, scorePolicyId, scoreAnchorPolicyId,
        conclusionPolicyId, status, totalScore, grade, verdict.name,
        confidence.name, downloadMbps, uploadMbps, pingMs, loadedRttMs,
        latencyDeltaMs, jitterMs, requestLossRate, throughputRobustCv,
        udpNonReturnRate, postLoadPingMs, downloadBytes, uploadBytes,
        transferErrors.joinToString("\n") { it.replace("\u0000", "") }, metricsJson(metrics),
        JsonObject(groupScores.mapValues { JsonPrimitive(it.value) }).toString(),
        JsonArray(conclusions.map(::JsonPrimitive)).toString(), evidenceJson,
        syntheticImpairment, impairmentProfileId, impairmentProfileVersion,
        impairmentDownlinkMbps, impairmentUplinkMbps, impairmentAddedRttMs,
        impairmentJitterMs, impairmentOutageDurationMs,
        impairmentExcludedFromShaping.joinToString(","), impairmentAcknowledged,
        recoveryTimeMs, recoveryFailureCount, postRecoverySuccessRatio,
        gatewayImpairment, gatewayExperimentId, gatewayProfileFingerprint,
        gatewayManagementBase, gatewayImpairmentLayer, gatewayAcknowledged,
        gatewayCleanupAcknowledged, gatewayBypassObserved, gatewayUplinkDelayMs,
        gatewayDownlinkDelayMs, gatewayUplinkLossPct, gatewayDownlinkLossPct,
    )

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

    private fun gatewayFailureResult(
        result: BasicSpeedResult,
        spec: ProfileGatewayImpairment?,
        owned: AnebGatewayClient.Experiment?,
        activationAcknowledged: Boolean,
        cleanupAcknowledged: Boolean,
        gatewayBase: String?,
    ): BasicSpeedResult {
        if (spec == null) return result
        val reason = result.transferErrors.firstOrNull().orEmpty()
        return result.copy(
            impairmentProfileId = spec.gatewayProfileId,
            impairmentProfileVersion = spec.gatewayProfileVersion,
            impairmentDownlinkMbps = spec.downlink.rateMbps,
            impairmentUplinkMbps = spec.uplink.rateMbps,
            impairmentAddedRttMs = spec.downlink.delayMs + spec.uplink.delayMs,
            impairmentJitterMs = maxOf(spec.downlink.jitterMs, spec.uplink.jitterMs),
            impairmentOutageDurationMs = spec.durationMs.takeIf { spec.kind == "outage" },
            impairmentExcludedFromShaping = spec.excludedFromImpairment,
            impairmentAcknowledged = activationAcknowledged,
            gatewayImpairment = true,
            gatewayExperimentId = owned?.experimentId,
            gatewayProfileFingerprint = owned?.profileFingerprint,
            gatewayManagementBase = gatewayBase?.trim()?.trimEnd('/'),
            gatewayImpairmentLayer = spec.impairmentLayer,
            gatewayAcknowledged = activationAcknowledged,
            gatewayCleanupAcknowledged = cleanupAcknowledged,
            gatewayUplinkDelayMs = spec.uplink.delayMs,
            gatewayDownlinkDelayMs = spec.downlink.delayMs,
            gatewayUplinkLossPct = spec.uplink.lossPct,
            gatewayDownlinkLossPct = spec.downlink.lossPct,
            evidenceJson = buildJsonObject {
                put("invalid_reason", reason)
                put("gateway_experiment_id", owned?.experimentId?.let(::JsonPrimitive) ?: JsonNull)
                put("gateway_profile_fingerprint", owned?.profileFingerprint?.let(::JsonPrimitive) ?: JsonNull)
                put("gateway_ownership_acknowledged", owned != null)
                put("gateway_activation_acknowledged", activationAcknowledged)
                put("gateway_cleanup_acknowledged", cleanupAcknowledged)
            }.toString(),
        )
    }

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
        private const val GATEWAY_CLEANUP_TIMEOUT_MS = 30_000L
        private const val GATEWAY_DISCOVERY_ATTEMPT_TIMEOUT_MS = 500L
        private const val GATEWAY_DISCOVERY_POLL_MS = 100L
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
