package com.aneb.probe.engine

import com.aneb.probe.net.AnebAuditRole
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimArrival
import com.aneb.probe.net.TokenSimPrelude
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult

internal enum class TokenExecutionAuthorization {
    LEGACY_PROFILE,
    VALIDATED_RECEIPT,
}

internal class TokenExecutionContractException(
    val reasonCode: String,
    val userMessage: String,
) : IllegalStateException(userMessage)

internal interface TokenExecutionTransport : ExecutionCapabilityTransport {
    suspend fun echo(url: String): AnebClient.EchoResult

    suspend fun tokenSim(
        url: String,
        plan: TokenSimTaskPlan,
        uploadChunkBytes: Int,
        uploadChunkCadenceMs: Double,
        onUploadBytes: (Long, Long) -> Unit,
        onPrelude: (TokenSimPrelude, Long) -> Unit,
        onToken: (TokenSimArrival) -> Unit,
    ): TokenSimTaskResult

    suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult
}

internal class AnebTokenExecutionTransport(
    private val client: AnebClient,
    private val runId: String,
) : TokenExecutionTransport {
    override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult =
        client.fetchServerInfo(url, runId, AnebAuditRole.CAPABILITY)

    override suspend fun echo(url: String): AnebClient.EchoResult = client.echo(url, runId = runId)

    override suspend fun tokenSim(
        url: String,
        plan: TokenSimTaskPlan,
        uploadChunkBytes: Int,
        uploadChunkCadenceMs: Double,
        onUploadBytes: (Long, Long) -> Unit,
        onPrelude: (TokenSimPrelude, Long) -> Unit,
        onToken: (TokenSimArrival) -> Unit,
    ): TokenSimTaskResult = client.tokenSim(
        url = url,
        runId = runId,
        plan = plan,
        uploadChunkBytes = uploadChunkBytes,
        uploadChunkCadenceMs = uploadChunkCadenceMs,
        onUploadBytes = onUploadBytes,
        onPrelude = onPrelude,
        onToken = onToken,
    )

    override suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = client.downloadThroughput(url, runId, onBytes)
}

/**
 * The only Token Quick surface allowed to issue business-plane requests.
 * Construction is restricted to [TokenExecutionContractGate].
 */
internal class AuthorizedTokenTraffic private constructor(
    val authorization: TokenExecutionAuthorization,
    private val transport: TokenExecutionTransport,
) {
    suspend fun echo(url: String): AnebClient.EchoResult = transport.echo(url)

    suspend fun tokenSim(
        url: String,
        plan: TokenSimTaskPlan,
        uploadChunkBytes: Int,
        uploadChunkCadenceMs: Double,
        onUploadBytes: (Long, Long) -> Unit,
        onPrelude: (TokenSimPrelude, Long) -> Unit,
        onToken: (TokenSimArrival) -> Unit,
    ): TokenSimTaskResult = transport.tokenSim(
        url,
        plan,
        uploadChunkBytes,
        uploadChunkCadenceMs,
        onUploadBytes,
        onPrelude,
        onToken,
    )

    suspend fun downloadThroughput(
        url: String,
        onBytes: (Int, Long) -> Unit,
    ): AnebClient.TransferResult = transport.downloadThroughput(url, onBytes)

    internal companion object {
        fun create(
            authorization: TokenExecutionAuthorization,
            transport: TokenExecutionTransport,
        ): AuthorizedTokenTraffic = AuthorizedTokenTraffic(authorization, transport)
    }
}

internal object TokenExecutionContractGate {
    private val policy = ExecutionContractPolicy(
        clientEngineContractId = "aneb-token-simulation-engine",
        clientEngineVersion = "1.0.0",
        supportedPrimitives = mapOf(
            "echo" to "aneb-echo-v1",
            "token_sim" to "aneb-token-task-v1",
            "download" to "aneb-download-v1",
        ),
        acceptedProfiles = listOf(
            tokenIdentity("token_multimodal_quick", "1.2.1", requiresReceipt = true),
            tokenIdentity("token_multimodal_standard", "1.1.0", requiresReceipt = false),
            tokenIdentity("token_multimodal_stress", "1.1.0", requiresReceipt = false),
        ),
        profileIdentityReasonCode = "token_profile_identity_not_allowed",
        profileIdentityMessage = "Token Profile 不在冻结的 Quick 或 Legacy 接受集中，测试已停止。",
    )

    suspend fun authorize(
        serverBase: String,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
        transport: TokenExecutionTransport,
    ): AuthorizedTokenTraffic {
        val authorization = try {
            ExecutionContractGate.authorize(
                serverBase = serverBase,
                profile = profile,
                profileCanonicalSha256 = profileCanonicalSha256,
                transport = transport,
                policy = policy,
            )
        } catch (error: ExecutionContractException) {
            throw TokenExecutionContractException(error.reasonCode, error.userMessage)
        }
        val tokenAuthorization = when (authorization) {
            ExecutionAuthorization.LEGACY_PROFILE -> TokenExecutionAuthorization.LEGACY_PROFILE
            ExecutionAuthorization.VALIDATED_RECEIPT -> TokenExecutionAuthorization.VALIDATED_RECEIPT
        }
        return AuthorizedTokenTraffic.create(tokenAuthorization, transport)
    }

    private fun tokenIdentity(
        profileId: String,
        profileVersion: String,
        requiresReceipt: Boolean,
    ) = ExecutionProfileIdentity(
        profileId = profileId,
        profileVersion = profileVersion,
        modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        requiresExecutionRequirements = requiresReceipt,
    )
}
