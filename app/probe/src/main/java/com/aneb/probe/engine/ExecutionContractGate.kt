package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.AnebAuditRole
import com.aneb.probe.net.AnebAuditScope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class ExecutionAuthorization {
    LEGACY_PROFILE,
    VALIDATED_RECEIPT,
}

internal class ExecutionContractException(
    val reasonCode: String,
    val userMessage: String,
) : IllegalStateException(userMessage)

internal interface ExecutionCapabilityTransport {
    suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult
}

internal class AnebRealtimeExecutionTransport(
    private val client: AnebClient,
    private val runId: String,
) : ExecutionCapabilityTransport {
    override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult =
        client.fetchServerInfo(
            url = url,
            runId = runId,
            auditRole = AnebAuditRole.CAPABILITY,
            auditScope = AnebAuditScope.REALTIME_RUN,
        )
}

internal data class ExecutionProfileIdentity(
    val profileId: String,
    val profileVersion: String,
    val modeId: String,
    val executionTarget: String,
    val claimScope: String,
    val requiresExecutionRequirements: Boolean,
)

internal data class ExecutionContractPolicy(
    val clientEngineContractId: String,
    val clientEngineVersion: String,
    val supportedPrimitives: Map<String, String>,
    val acceptedProfiles: List<ExecutionProfileIdentity>,
    val profileIdentityReasonCode: String,
    val profileIdentityMessage: String,
)

@Serializable
private data class CapabilityEnvelope(
    @SerialName("execution_capabilities")
    val executionCapabilities: CapabilityReceipt? = null,
)

@Serializable
private data class CapabilityReceipt(
    @SerialName("contract_id") val contractId: String = "",
    @SerialName("contract_version") val contractVersion: String = "",
    val primitives: List<CapabilityPrimitive> = emptyList(),
    @SerialName("validated_profiles")
    val validatedProfiles: List<CapabilityProfile> = emptyList(),
)

@Serializable
private data class CapabilityPrimitive(
    @SerialName("primitive_id") val primitiveId: String = "",
    @SerialName("wire_contract_id") val wireContractId: String = "",
)

@Serializable
private data class CapabilityProfile(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_version") val profileVersion: String = "",
    @SerialName("profile_sha256") val profileSha256: String = "",
)

internal object ExecutionContractGate {
    private const val REQUIREMENTS_CONTRACT_ID = "aneb-execution-requirements"
    private const val REQUIREMENTS_CONTRACT_VERSION = "1.0.0"
    private const val RECEIPT_CONTRACT_ID = "aneb-server-capability-receipt"
    private val canonicalShaPattern = Regex("^sha256:[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authorize(
        serverBase: String,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
        transport: ExecutionCapabilityTransport,
        policy: ExecutionContractPolicy,
    ): ExecutionAuthorization {
        val identity = validateProfileAcceptance(profile, policy)
        val requirements = profile.executionRequirements
            ?: return ExecutionAuthorization.LEGACY_PROFILE

        validateRequirements(requirements, profile, policy)
        if (!canonicalShaPattern.matches(profileCanonicalSha256)) {
            reject("profile_digest_invalid", "Profile 摘要格式无效，测试已停止。")
        }

        val response = try {
            transport.fetchServerInfo("${serverBase.trimEnd('/')}/api/v1/serverinfo")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            reject("serverinfo_unavailable", "无法读取节点机器能力回执，测试已停止。")
        }
        if (response.httpCode !in 200..299 || response.error != null || response.body == null) {
            reject("serverinfo_unavailable", "无法读取节点机器能力回执，测试已停止。")
        }
        val receipt = try {
            json.decodeFromString(CapabilityEnvelope.serializer(), response.body).executionCapabilities
        } catch (_: Exception) {
            reject("receipt_malformed", "节点机器能力回执格式无效，测试已停止。")
        } ?: reject("receipt_missing", "节点未返回机器可读能力回执，测试已停止。")

        validateReceipt(receipt, requirements, identity, profileCanonicalSha256)
        return ExecutionAuthorization.VALIDATED_RECEIPT
    }

    private fun validateProfileAcceptance(
        profile: ScenarioProfile,
        policy: ExecutionContractPolicy,
    ): ExecutionProfileIdentity {
        val idVersion = policy.acceptedProfiles.filter {
            it.profileId == profile.profileId && it.profileVersion == profile.version
        }
        val identity = idVersion.singleOrNull()
            ?: if (profile.executionRequirements != null) {
                reject(
                    "execution_requirements_profile_not_migrated",
                    "execution_requirements 仅允许已冻结且已迁移的 Profile 使用，测试已停止。",
                )
            } else {
                reject(policy.profileIdentityReasonCode, policy.profileIdentityMessage)
            }

        val exactIdentity = profile.contractVersion == ScenarioProfile.CONTRACT_V2 &&
            profile.modeId == identity.modeId &&
            profile.executionTarget == identity.executionTarget &&
            profile.claimScope == identity.claimScope
        if (!exactIdentity) {
            if (identity.requiresExecutionRequirements) {
                reject("quick_identity_mismatch", "Profile 身份字段不匹配，测试已停止。")
            }
            reject(policy.profileIdentityReasonCode, policy.profileIdentityMessage)
        }
        if (identity.requiresExecutionRequirements && profile.executionRequirements == null) {
            reject(
                "execution_requirements_missing",
                "当前 Profile 缺少执行要求合同，测试已停止。",
            )
        }
        if (!identity.requiresExecutionRequirements && profile.executionRequirements != null) {
            reject(
                "execution_requirements_profile_not_migrated",
                "execution_requirements 仅允许已冻结且已迁移的 Profile 使用，测试已停止。",
            )
        }
        return identity
    }

    private fun validateRequirements(
        requirements: ProfileExecutionRequirements,
        profile: ScenarioProfile,
        policy: ExecutionContractPolicy,
    ) {
        if (requirements.contractId != REQUIREMENTS_CONTRACT_ID ||
            requirements.contractVersion != REQUIREMENTS_CONTRACT_VERSION
        ) {
            reject("requirements_contract_unsupported", "Profile 执行要求合同不受支持，测试已停止。")
        }
        if (parseVersion(profile.version) == null) {
            reject("profile_version_invalid", "Profile 版本格式无效，测试已停止。")
        }
        val engine = requirements.clientEngine
        if (engine.contractId != policy.clientEngineContractId ||
            !versionInRange(
                policy.clientEngineVersion,
                engine.minVersion,
                engine.maxVersionExclusive,
            )
        ) {
            reject("client_engine_incompatible", "客户端执行引擎版本与 Profile 不兼容，测试已停止。")
        }
        val receipt = requirements.serverCapabilityReceipt
        if (receipt.contractId != RECEIPT_CONTRACT_ID ||
            !validVersionRange(receipt.minVersion, receipt.maxVersionExclusive)
        ) {
            reject("receipt_range_invalid", "Profile 节点能力回执版本范围无效，测试已停止。")
        }
        if (requirements.requiredPrimitives.isEmpty()) {
            reject("required_primitives_empty", "Profile 未声明必需的执行原语，测试已停止。")
        }
        val required = linkedMapOf<String, String>()
        requirements.requiredPrimitives.forEach { primitive ->
            if (primitive.primitiveId.isBlank() ||
                required.put(primitive.primitiveId, primitive.wireContractId) != null
            ) {
                reject("required_primitive_duplicate", "Profile 的必需执行原语重复或无效，测试已停止。")
            }
            val supportedWire = policy.supportedPrimitives[primitive.primitiveId]
                ?: reject("required_primitive_unknown", "Profile 要求了客户端未知的执行原语，测试已停止。")
            if (primitive.wireContractId != supportedWire) {
                reject("required_wire_unsupported", "Profile 的执行原语线路合同不受支持，测试已停止。")
            }
        }
        if (required != policy.supportedPrimitives) {
            reject("required_primitive_set_incomplete", "Profile 必须完整声明冻结的执行原语集合，测试已停止。")
        }
    }

    private fun validateReceipt(
        receipt: CapabilityReceipt,
        requirements: ProfileExecutionRequirements,
        identity: ExecutionProfileIdentity,
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

        val primitives = linkedMapOf<String, String>()
        receipt.primitives.forEach { primitive ->
            if (primitive.primitiveId.isBlank() || primitive.wireContractId.isBlank() ||
                primitives.put(primitive.primitiveId, primitive.wireContractId) != null
            ) {
                reject("receipt_primitive_duplicate", "节点能力回执包含重复或无效的执行原语，测试已停止。")
            }
        }
        requirements.requiredPrimitives.forEach { required ->
            when (val actualWire = primitives[required.primitiveId]) {
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

        val profiles = linkedMapOf<String, CapabilityProfile>()
        receipt.validatedProfiles.forEach { validated ->
            if (validated.profileId.isBlank() || validated.profileVersion.isBlank() ||
                !canonicalShaPattern.matches(validated.profileSha256) ||
                profiles.put(validated.profileId, validated) != null
            ) {
                reject("receipt_profile_duplicate", "节点能力回执包含重复或无效的 Profile 记录，测试已停止。")
            }
        }
        val validated = profiles[identity.profileId]
            ?: reject("receipt_profile_missing", "节点尚未验证当前 Profile，测试已停止。")
        if (validated.profileVersion != identity.profileVersion) {
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

    private fun validVersionRange(minimum: String, maximumExclusive: String): Boolean {
        val min = parseVersion(minimum) ?: return false
        val max = parseVersion(maximumExclusive) ?: return false
        return min < max
    }

    private fun parseVersion(raw: String): SemanticVersion? {
        val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
            .matchEntire(raw)
            ?: return null
        val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        return SemanticVersion(parts[0], parts[1], parts[2])
    }

    private fun reject(reasonCode: String, detail: String): Nothing =
        throw ExecutionContractException(reasonCode, "节点能力合同校验失败：$detail")

    private data class SemanticVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int =
            compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
    }
}

internal object RealtimeExecutionContractGate {
    private val policy = ExecutionContractPolicy(
        clientEngineContractId = "aneb-realtime-simulation-engine",
        clientEngineVersion = "1.0.0",
        supportedPrimitives = mapOf(
            "realtime_sim" to "aneb-realtime-session-v1",
        ),
        acceptedProfiles = listOf(
            ExecutionProfileIdentity(
                profileId = "ai_realtime_voice_quick",
                profileVersion = "1.1.1",
                modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
                executionTarget = "aneb_probe_simulator",
                claimScope = "application_end_to_end_to_probe_node",
                requiresExecutionRequirements = true,
            ),
            ExecutionProfileIdentity(
                profileId = "ai_realtime_voice_repeatability_qualification",
                profileVersion = "1.0.0",
                modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
                executionTarget = "aneb_probe_simulator",
                claimScope = "application_end_to_end_to_probe_node",
                requiresExecutionRequirements = true,
            ),
            ExecutionProfileIdentity(
                profileId = "ai_realtime_voice_standard",
                profileVersion = "1.1.0",
                modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
                executionTarget = "aneb_probe_simulator",
                claimScope = "application_end_to_end_to_probe_node",
                requiresExecutionRequirements = false,
            ),
            ExecutionProfileIdentity(
                profileId = "ai_realtime_voice_recovery",
                profileVersion = "1.3.0",
                modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
                executionTarget = "aneb_probe_simulator",
                claimScope = "controlled_server_disconnect_recovery_to_probe_node",
                requiresExecutionRequirements = false,
            ),
        ),
        profileIdentityReasonCode = "realtime_profile_identity_not_allowed",
        profileIdentityMessage = "AI 实时 Profile 不在冻结的 Quick、资格或 Legacy 接受集中，测试已停止。",
    )

    suspend fun authorize(
        serverBase: String,
        profile: ScenarioProfile,
        profileCanonicalSha256: String,
        transport: ExecutionCapabilityTransport,
    ): ExecutionAuthorization = ExecutionContractGate.authorize(
        serverBase = serverBase,
        profile = profile,
        profileCanonicalSha256 = profileCanonicalSha256,
        transport = transport,
        policy = policy,
    )
}
