package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.AnebAuditRole
import com.aneb.probe.net.TokenSimArrival
import com.aneb.probe.net.TokenSimPrelude
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class TokenExecutionAuthorization {
    LEGACY_PROFILE,
    VALIDATED_RECEIPT,
}

internal class TokenExecutionContractException(
    val reasonCode: String,
    val userMessage: String,
) : IllegalStateException(userMessage)

internal interface TokenExecutionTransport {
    suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult

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

@Serializable
private data class ServerInfoCapabilityEnvelope(
    @SerialName("execution_capabilities")
    val executionCapabilities: ServerExecutionCapabilities? = null,
)

@Serializable
private data class ServerExecutionCapabilities(
    @SerialName("contract_id") val contractId: String = "",
    @SerialName("contract_version") val contractVersion: String = "",
    val primitives: List<ServerExecutionPrimitive> = emptyList(),
    @SerialName("validated_profiles")
    val validatedProfiles: List<ServerValidatedProfile> = emptyList(),
)

@Serializable
private data class ServerExecutionPrimitive(
    @SerialName("primitive_id") val primitiveId: String = "",
    @SerialName("wire_contract_id") val wireContractId: String = "",
)

@Serializable
private data class ServerValidatedProfile(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_version") val profileVersion: String = "",
    @SerialName("profile_sha256") val profileSha256: String = "",
)

internal object TokenExecutionContractGate {
    private const val REQUIREMENTS_CONTRACT_ID = "aneb-execution-requirements"
    private const val REQUIREMENTS_CONTRACT_VERSION = "1.0.0"
    private const val CLIENT_ENGINE_CONTRACT_ID = "aneb-token-simulation-engine"
    private const val CLIENT_ENGINE_VERSION = "1.0.0"
    private const val RECEIPT_CONTRACT_ID = "aneb-server-capability-receipt"
    private const val QUICK_PROFILE_ID = "token_multimodal_quick"
    private const val QUICK_PROFILE_VERSION = "1.2.1"
    private const val QUICK_EXECUTION_TARGET = "aneb_probe_simulator"
    private const val QUICK_CLAIM_SCOPE = "application_end_to_end_to_probe_node"
    private val legacyProfileVersions = mapOf(
        "token_multimodal_standard" to "1.1.0",
        "token_multimodal_stress" to "1.1.0",
    )


    private val supportedPrimitives = mapOf(
        "echo" to "aneb-echo-v1",
        "token_sim" to "aneb-token-task-v1",
        "download" to "aneb-download-v1",
    )
    private val canonicalShaPattern = Regex("^sha256:[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authorize(
        serverBase: String,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
        transport: TokenExecutionTransport,
    ): AuthorizedTokenTraffic {
        validateTokenProfileAcceptance(profile)
        val requirements = profile.executionRequirements
            ?: return AuthorizedTokenTraffic.create(TokenExecutionAuthorization.LEGACY_PROFILE, transport)

        validateRequirements(requirements, profile)
        if (!canonicalShaPattern.matches(profileCanonicalSha256)) {
            reject("profile_digest_invalid", "Profile 摘要格式无效，测试已停止。")
        }

        val response = try {
            transport.fetchServerInfo("${serverBase.trimEnd('/')}/api/v1/serverinfo")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reject("serverinfo_unavailable", "无法读取节点机器能力回执，测试已停止。")
        }
        if (response.httpCode !in 200..299 || response.error != null || response.body == null) {
            reject("serverinfo_unavailable", "无法读取节点机器能力回执，测试已停止。")
        }
        val receipt = try {
            json.decodeFromString(ServerInfoCapabilityEnvelope.serializer(), response.body).executionCapabilities
        } catch (_: Exception) {
            reject("receipt_malformed", "节点机器能力回执格式无效，测试已停止。")
        } ?: reject("receipt_missing", "节点未返回机器可读能力回执，测试已停止。")

        validateReceipt(receipt, requirements, profile, profileCanonicalSha256)
        return AuthorizedTokenTraffic.create(TokenExecutionAuthorization.VALIDATED_RECEIPT, transport)
    }

    private fun validateTokenProfileAcceptance(profile: ScenarioProfile) {
        val quickIdVersion = profile.profileId == QUICK_PROFILE_ID && profile.version == QUICK_PROFILE_VERSION
        val sharedIdentityMatches = profile.modeId == ScenarioProfile.MODE_TOKEN_SIMULATION &&
            profile.executionTarget == QUICK_EXECUTION_TARGET &&
            profile.claimScope == QUICK_CLAIM_SCOPE &&
            profile.contractVersion == ScenarioProfile.CONTRACT_V2

        if (profile.executionRequirements != null) {
            if (!quickIdVersion) {
                reject(
                    "execution_requirements_profile_not_migrated",
                    "execution_requirements \u4ec5\u5141\u8bb8\u51bb\u7ed3\u7684 Quick Profile \u4f7f\u7528\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002",
                )
            }
            if (!sharedIdentityMatches) {
                reject(
                    "quick_identity_mismatch",
                    "Quick Profile \u8eab\u4efd\u5b57\u6bb5\u4e0d\u5339\u914d\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002",
                )
            }
            return
        }

        if (quickIdVersion) {
            if (!sharedIdentityMatches) {
                reject(
                    "quick_identity_mismatch",
                    "Quick Profile \u8eab\u4efd\u5b57\u6bb5\u4e0d\u5339\u914d\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002",
                )
            }
            reject(
                "execution_requirements_missing",
                "Quick Profile \u7f3a\u5c11\u6267\u884c\u8981\u6c42\u5408\u540c\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002",
            )
        }

        val legacyVersion = legacyProfileVersions[profile.profileId]
        if (legacyVersion == profile.version && sharedIdentityMatches) return
        reject(
            "token_profile_identity_not_allowed",
            "Token Profile \u4e0d\u5728\u51bb\u7ed3\u7684 Quick \u6216 Legacy \u63a5\u53d7\u96c6\u4e2d\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002",
        )
    }

    private fun validateRequirements(
        requirements: ProfileExecutionRequirements,
        profile: ScenarioProfile,
    ) {
        if (requirements.contractId != REQUIREMENTS_CONTRACT_ID ||
            requirements.contractVersion != REQUIREMENTS_CONTRACT_VERSION
        ) {
            reject("requirements_contract_unsupported", "Profile 执行要求合同不受支持，测试已停止。")
        }
        requireVersion(profile.version, "profile_version_invalid", "Profile 版本格式无效，测试已停止。")
        val engine = requirements.clientEngine
        if (engine.contractId != CLIENT_ENGINE_CONTRACT_ID ||
            !versionInRange(CLIENT_ENGINE_VERSION, engine.minVersion, engine.maxVersionExclusive)
        ) {
            reject("client_engine_incompatible", "客户端执行引擎版本与 Profile 不兼容，测试已停止。")
        }
        val receipt = requirements.serverCapabilityReceipt
        if (receipt.contractId != RECEIPT_CONTRACT_ID ||
            parseVersion(receipt.minVersion) == null ||
            parseVersion(receipt.maxVersionExclusive) == null ||
            parseVersion(receipt.minVersion)!! >= parseVersion(receipt.maxVersionExclusive)!!
        ) {
            reject("receipt_range_invalid", "Profile 节点能力回执版本范围无效，测试已停止。")
        }
        if (requirements.requiredPrimitives.isEmpty()) {
            reject("required_primitives_empty", "Profile 未声明必需的执行原语，测试已停止。")
        }
        val ids = mutableSetOf<String>()
        requirements.requiredPrimitives.forEach { primitive ->
            if (primitive.primitiveId.isBlank() || !ids.add(primitive.primitiveId)) {
                reject("required_primitive_duplicate", "Profile 的必需执行原语重复或无效，测试已停止。")
            }
            val supportedWire = supportedPrimitives[primitive.primitiveId]
                ?: reject("required_primitive_unknown", "Profile 要求了客户端未知的执行原语，测试已停止。")
            if (primitive.wireContractId != supportedWire) {
                reject("required_wire_unsupported", "Profile 的执行原语线路合同不受支持，测试已停止。")
            }
        }
        if (ids != supportedPrimitives.keys) {
            reject("required_primitive_set_incomplete", "Profile \u5fc5\u987b\u5b8c\u6574\u58f0\u660e\u4e09\u9879\u6267\u884c\u539f\u8bed\uff0c\u6d4b\u8bd5\u5df2\u505c\u6b62\u3002")
        }
    }

    private fun validateReceipt(
        receipt: ServerExecutionCapabilities,
        requirements: ProfileExecutionRequirements,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
    ) {
        if (receipt.contractId != RECEIPT_CONTRACT_ID ||
            !versionInRange(
                receipt.contractVersion,
                requirements.serverCapabilityReceipt.minVersion,
                requirements.serverCapabilityReceipt.maxVersionExclusive,
            )
        ) {
            reject("receipt_version_incompatible", "节点能力回执版本与 Profile 不兼容，测试已停止。")
        }

        val receiptPrimitives = linkedMapOf<String, String>()
        receipt.primitives.forEach { primitive ->
            if (primitive.primitiveId.isBlank() || primitive.wireContractId.isBlank() ||
                receiptPrimitives.put(primitive.primitiveId, primitive.wireContractId) != null
            ) {
                reject("receipt_primitive_duplicate", "节点能力回执包含重复或无效的执行原语，测试已停止。")
            }
        }
        requirements.requiredPrimitives.forEach { required ->
            when (val actualWire = receiptPrimitives[required.primitiveId]) {
                null -> reject(
                    "receipt_primitive_missing",
                    "节点缺少必需执行原语 ${required.primitiveId}，测试已停止。",
                )
                required.wireContractId -> Unit
                else -> reject(
                    "receipt_wire_mismatch",
                    "节点执行原语 ${required.primitiveId} 的线路合同不兼容，测试已停止。",
                )
            }
        }

        val validatedProfiles = linkedMapOf<String, ServerValidatedProfile>()
        receipt.validatedProfiles.forEach { validated ->
            if (validated.profileId.isBlank() || validated.profileVersion.isBlank() ||
                !canonicalShaPattern.matches(validated.profileSha256) ||
                validatedProfiles.put(validated.profileId, validated) != null
            ) {
                reject("receipt_profile_duplicate", "节点能力回执包含重复或无效的 Profile 记录，测试已停止。")
            }
        }
        val validated = validatedProfiles[profile.profileId]
            ?: reject("receipt_profile_missing", "节点尚未验证当前 Profile，测试已停止。")
        if (validated.profileVersion != profile.version) {
            reject("receipt_profile_version_mismatch", "节点验证的 Profile 版本与当前版本不一致，测试已停止。")
        }
        if (validated.profileSha256 != profileCanonicalSha256) {
            reject("receipt_profile_digest_mismatch", "Profile 摘要与节点验证回执不一致，测试已停止。")
        }
    }

    private fun versionInRange(version: String, minimum: String, maximumExclusive: String): Boolean {
        val parsed = parseVersion(version) ?: return false
        val min = parseVersion(minimum) ?: return false
        val max = parseVersion(maximumExclusive) ?: return false
        return min < max && parsed >= min && parsed < max
    }

    private fun requireVersion(version: String, reasonCode: String, message: String) {
        if (parseVersion(version) == null) reject(reasonCode, message)
    }

    private fun parseVersion(raw: String): SemanticVersion? {
        val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(raw)
            ?: return null
        val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        return SemanticVersion(parts[0], parts[1], parts[2])
    }

    private fun reject(reasonCode: String, detail: String): Nothing =
        throw TokenExecutionContractException(reasonCode, "节点能力合同校验失败：$detail")

    private data class SemanticVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int =
            compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
    }
}
