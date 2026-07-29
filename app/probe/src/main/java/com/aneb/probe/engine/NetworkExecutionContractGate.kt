package com.aneb.probe.engine

import com.aneb.probe.net.AnebAuditRole
import com.aneb.probe.net.AnebAuditScope
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.NetworkUdpProbe
import com.aneb.probe.net.NetworkUdpProbeResult

internal enum class NetworkExecutionAuthorization {
    LEGACY_PROFILE,
    VALIDATED_RECEIPT,
}

internal class NetworkExecutionContractException(
    val reasonCode: String,
    val userMessage: String,
) : IllegalStateException(userMessage)

internal interface NetworkExecutionTransport : ExecutionCapabilityTransport {
    fun evictConnections()

    suspend fun echo(url: String, callTimeoutMs: Long? = null): AnebClient.EchoResult

    suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult

    suspend fun uploadThroughput(
        url: String,
        totalBytes: Long,
        chunkBytes: Int,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult

    suspend fun udpEcho(
        serverBase: String,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
        wireContractId: String,
    ): NetworkUdpProbeResult

    suspend fun triggerSyntheticOutage(url: String): AnebClient.SyntheticOutageTriggerResult
}

internal class AnebNetworkExecutionTransport(
    private val client: AnebClient,
    private val udpProbe: NetworkUdpProbe,
    private val runId: String,
) : NetworkExecutionTransport {
    override fun evictConnections() = client.evictConnections()

    override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult =
        client.fetchServerInfo(
            url = url,
            runId = runId,
            auditRole = AnebAuditRole.CAPABILITY,
            auditScope = AnebAuditScope.NETWORK_RUN,
        )

    override suspend fun echo(url: String, callTimeoutMs: Long?): AnebClient.EchoResult =
        client.echo(
            url = url,
            callTimeoutMs = callTimeoutMs,
            runId = runId,
            auditScope = AnebAuditScope.NETWORK_RUN,
        )

    override suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = client.downloadThroughput(
        url = url,
        runId = runId,
        auditScope = AnebAuditScope.NETWORK_RUN,
        onBytes = onBytes,
    )

    override suspend fun uploadThroughput(
        url: String,
        totalBytes: Long,
        chunkBytes: Int,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = client.uploadThroughput(
        url = url,
        totalBytes = totalBytes,
        chunkBytes = chunkBytes,
        runId = runId,
        auditScope = AnebAuditScope.NETWORK_RUN,
        onBytes = onBytes,
    )

    override suspend fun udpEcho(
        serverBase: String,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
        wireContractId: String,
    ): NetworkUdpProbeResult = when (wireContractId) {
        "aneb-udp-echo-v1" -> udpProbe.runLegacy(
            serverBase = serverBase,
            packets = packets,
            packetBytes = packetBytes,
            ratePerSecond = ratePerSecond,
        )
        "aneb-udp-echo-v2" -> udpProbe.run(
            serverBase = serverBase,
            runId = runId,
            packets = packets,
            packetBytes = packetBytes,
            ratePerSecond = ratePerSecond,
        )
        else -> error("unsupported_network_udp_wire_contract:$wireContractId")
    }

    override suspend fun triggerSyntheticOutage(url: String): AnebClient.SyntheticOutageTriggerResult =
        client.triggerSyntheticOutage(url)
}

internal class AuthorizedNetworkTraffic private constructor(
    val authorization: NetworkExecutionAuthorization,
    private val transport: NetworkExecutionTransport,
) {
    fun evictConnections() = transport.evictConnections()

    suspend fun echo(url: String, callTimeoutMs: Long? = null): AnebClient.EchoResult =
        transport.echo(url, callTimeoutMs)

    suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = transport.downloadThroughput(url, onBytes)

    suspend fun uploadThroughput(
        url: String,
        totalBytes: Long,
        chunkBytes: Int,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = transport.uploadThroughput(url, totalBytes, chunkBytes, onBytes)

    suspend fun udpEcho(
        serverBase: String,
        packets: Int,
        packetBytes: Int,
        ratePerSecond: Int,
    ): NetworkUdpProbeResult = transport.udpEcho(
        serverBase,
        packets,
        packetBytes,
        ratePerSecond,
        if (authorization == NetworkExecutionAuthorization.VALIDATED_RECEIPT) {
            "aneb-udp-echo-v2"
        } else {
            "aneb-udp-echo-v1"
        },
    )

    suspend fun triggerSyntheticOutage(url: String): AnebClient.SyntheticOutageTriggerResult =
        transport.triggerSyntheticOutage(url)

    internal companion object {
        fun create(
            authorization: NetworkExecutionAuthorization,
            transport: NetworkExecutionTransport,
        ): AuthorizedNetworkTraffic = AuthorizedNetworkTraffic(authorization, transport)
    }
}

internal object NetworkExecutionContractGate {
    private val policy = ExecutionContractPolicy(
        clientEngineContractId = "aneb-network-comprehensive-engine",
        clientEngineVersion = "1.0.0",
        supportedPrimitives = mapOf(
            "download" to "aneb-download-v1",
            "echo" to "aneb-echo-v1",
            "udp_echo" to "aneb-udp-echo-v2",
            "upload" to "aneb-upload-v1",
        ),
        acceptedProfiles = listOf(
            networkIdentity("network_comprehensive_quick", "1.2.0", requiresReceipt = true),
            networkIdentity("network_comprehensive_repeatability_qualification", "1.0.0", requiresReceipt = true),
            networkIdentity("network_comprehensive_standard", "1.1.0", requiresReceipt = false),
            networkIdentity(
                "network_comprehensive_weak_capacity_latency",
                "1.1.0",
                requiresReceipt = false,
                claimScope = "application_end_to_end_to_probe_node_with_declared_synthetic_http_impairment",
            ),
            networkIdentity(
                "network_comprehensive_weak_recovery",
                "1.1.0",
                requiresReceipt = false,
                claimScope = "application_end_to_end_to_probe_node_with_declared_synthetic_request_outage",
            ),
            networkIdentity(
                "network_comprehensive_gateway_loss",
                "1.1.0",
                requiresReceipt = false,
                claimScope = "application_end_to_end_to_probe_node_through_dedicated_ip_forwarding_gateway",
            ),
            networkIdentity(
                "network_comprehensive_gateway_recovery",
                "1.2.0",
                requiresReceipt = false,
                claimScope = "application_end_to_end_to_probe_node_through_dedicated_gateway_with_ip_outage",
            ),
        ),
        profileIdentityReasonCode = "network_profile_identity_not_allowed",
        profileIdentityMessage = "网络综合 Profile 不在冻结的 Quick、资格或 Legacy 接受集中，测试已停止。",
    )

    suspend fun authorize(
        serverBase: String,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
        transport: NetworkExecutionTransport,
    ): AuthorizedNetworkTraffic {
        val authorization = try {
            ExecutionContractGate.authorize(
                serverBase = serverBase,
                profile = profile,
                profileCanonicalSha256 = profileCanonicalSha256,
                transport = transport,
                policy = policy,
            )
        } catch (error: ExecutionContractException) {
            throw NetworkExecutionContractException(error.reasonCode, error.userMessage)
        }
        val networkAuthorization = when (authorization) {
            ExecutionAuthorization.LEGACY_PROFILE -> NetworkExecutionAuthorization.LEGACY_PROFILE
            ExecutionAuthorization.VALIDATED_RECEIPT -> NetworkExecutionAuthorization.VALIDATED_RECEIPT
        }
        return AuthorizedNetworkTraffic.create(networkAuthorization, transport)
    }

    private fun networkIdentity(
        profileId: String,
        profileVersion: String,
        requiresReceipt: Boolean,
        claimScope: String = "application_end_to_end_to_probe_node",
    ) = ExecutionProfileIdentity(
        profileId = profileId,
        profileVersion = profileVersion,
        modeId = ScenarioProfile.MODE_NETWORK_COMPREHENSIVE,
        executionTarget = "aneb_probe_simulator",
        claimScope = claimScope,
        requiresExecutionRequirements = requiresReceipt,
    )
}
