package com.aneb.probe.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 场景 profile 数据模型（与 server/profiles.go 的 Go 结构一一对应，两端共享合同）。
 * 纯 JVM、无 Android 依赖，可直接单测。
 * profile 一旦发布即冻结，修改必须升版本号（设计文档 §3）。
 */

@Serializable
data class ProfileTokenBytes(
    val dist: String = "lognormal",
    val median: Double = 120.0,
    val sigma: Double = 0.6,
)

@Serializable
data class ProfileBurst(
    @SerialName("cluster_tps") val clusterTps: Double = 0.0,
    @SerialName("pause_ms") val pauseMs: List<Int> = emptyList(),
    @SerialName("cluster_geom_p") val clusterGeomP: Double = 0.0,
)

/** Profile 的展示/结论合同；只声明版本化策略 id，不允许服务端下发可执行公式。 */
@Serializable
data class ProfilePresentation(
    @SerialName("live_metric_id") val liveMetricId: String = "",
    @SerialName("live_metric_label") val liveMetricLabel: String = "",
    @SerialName("live_metric_unit") val liveMetricUnit: String = "",
    @SerialName("live_window_ms") val liveWindowMs: Int = 1_000,
    @SerialName("ui_refresh_ms") val uiRefreshMs: Int = 250,
    @SerialName("metric_ids") val metricIds: List<String> = emptyList(),
    @SerialName("conclusion_policy_id") val conclusionPolicyId: String = "",
)

/** Profile Contract v2 的业务声明。品牌原型标签不是第三方实测声明。 */
@Serializable
data class ProfileBusiness(
    @SerialName("category_id") val categoryId: String = "",
    val label: String = "",
    @SerialName("archetype_labels") val archetypeLabels: List<String> = emptyList(),
    @SerialName("behavior_feature_ids") val behaviorFeatureIds: List<String> = emptyList(),
    @SerialName("behavior_model_id") val behaviorModelId: String? = null,
    @SerialName("behavior_model_version") val behaviorModelVersion: String? = null,
    @SerialName("behavior_model_hash") val behaviorModelHash: String? = null,
    @SerialName("calibration_status") val calibrationStatus: String = "",
    @SerialName("model_source_kind") val modelSourceKind: String? = null,
)

@Serializable
data class ProfileQualityTarget(
    val operator: String = "",
    val value: Double? = null,
    val values: Map<String, Double> = emptyMap(),
    @SerialName("policy_id") val policyId: String? = null,
    @SerialName("required_compliance_ratio") val requiredComplianceRatio: Double? = null,
    val provenance: String = "",
)

@Serializable
data class ProfileMeasurement(
    @SerialName("metric_id") val metricId: String,
    val label: String = "",
    val domain: String = "",
    val unit: String = "",
    @SerialName("measurement_level") val measurementLevel: String = "",
    @SerialName("source_event_ids") val sourceEventIds: List<String> = emptyList(),
    @SerialName("formula_id") val formulaId: String = "",
    val aggregation: String = "",
    val direction: String = "",
    @SerialName("required_for_score") val requiredForScore: Boolean = false,
    @SerialName("minimum_sample_count") val minimumSampleCount: Int = 0,
    @SerialName("target_role") val targetRole: String = "",
    @SerialName("quality_target") val qualityTarget: ProfileQualityTarget? = null,
)

@Serializable
data class ProfileLivePresentation(
    @SerialName("primary_metric_id") val primaryMetricId: String = "",
    @SerialName("secondary_metric_ids") val secondaryMetricIds: List<String> = emptyList(),
    @SerialName("window_ms") val windowMs: Int = 1_000,
    @SerialName("ui_refresh_ms") val uiRefreshMs: Int = 250,
    @SerialName("stale_after_ms") val staleAfterMs: Int = 1_500,
    @SerialName("missing_behavior") val missingBehavior: String = "",
)

@Serializable
data class ProfileEvaluation(
    @SerialName("target_set_id") val targetSetId: String = "",
    @SerialName("score_policy_id") val scorePolicyId: String = "",
    @SerialName("score_anchor_policy_id") val scoreAnchorPolicyId: String = "",
    @SerialName("conclusion_policy_id") val conclusionPolicyId: String = "",
    @SerialName("required_metric_ids") val requiredMetricIds: List<String> = emptyList(),
    @SerialName("guardrail_metric_ids") val guardrailMetricIds: List<String> = emptyList(),
    @SerialName("group_weights") val groupWeights: Map<String, Double> = emptyMap(),
    @SerialName("grade_bands") val gradeBands: Map<String, Double> = emptyMap(),
    @SerialName("missing_required_metric") val missingRequiredMetric: String = "",
    @SerialName("invalid_run") val invalidRun: String = "",
)

@Serializable
data class ProfileTrace(
    @SerialName("contract_version") val contractVersion: String = "",
    val seed: Long = 0,
    val prng: String = "",
)

@Serializable
data class ProfileExecutionPlan(
    @SerialName("contract_version") val contractVersion: String = "",
    val artifact: String = "",
    @SerialName("artifact_hash") val artifactHash: String = "",
    val seed: Long = 0,
    val variant: String = "",
)

/** phase 联合体：字段按 [type] 选用（同 Go 侧 Phase）。 */
@Serializable
data class ProfilePhase(
    val type: String,
    // clock_sync
    val samples: Int = 0,
    // upload_burst
    val bytes: Long = 0,
    @SerialName("chunk_kb") val chunkKb: Int = 0,
    /** throughput phase 的并发连接数；其他 phase 保持 0。 */
    val parallel: Int = 0,
    // think_pause
    @SerialName("duration_ms") val durationMs: Int = 0,
    // token_stream
    val tokens: Int = 0,
    @SerialName("rate_tps") val rateTps: Double = 0.0,
    @SerialName("token_bytes") val tokenBytes: ProfileTokenBytes? = null,
    val burst: ProfileBurst? = null,
    val seed: Long = 0,
    // tool_loop
    val rounds: Int = 0,
    @SerialName("up_bytes") val upBytes: Long = 0,
    @SerialName("down_bytes") val downBytes: Long = 0,
    @SerialName("server_proc_ms") val serverProcMs: Int = 0,
    // behavior_trace
    @SerialName("model_id") val modelId: String = "",
    @SerialName("model_version") val modelVersion: String = "",
    @SerialName("model_hash") val modelHash: String = "",
    @SerialName("runtime_artifact") val runtimeArtifact: String = "",
    @SerialName("runtime_artifact_hash") val runtimeArtifactHash: String = "",
) {
    companion object {
        const val TYPE_CLOCK_SYNC = "clock_sync"
        const val TYPE_UPLOAD_BURST = "upload_burst"
        const val TYPE_THINK_PAUSE = "think_pause"
        const val TYPE_TOKEN_STREAM = "token_stream"
        const val TYPE_TOOL_LOOP = "tool_loop"
        const val TYPE_DOWNLOAD_THROUGHPUT = "download_throughput"
        const val TYPE_UPLOAD_THROUGHPUT = "upload_throughput"
        const val TYPE_BEHAVIOR_TRACE = "behavior_trace"
    }
}

@Serializable
data class ScenarioProfile(
    @SerialName("profile_id") val profileId: String,
    val version: String,
    @SerialName("contract_version") val contractVersion: String = "",
    @SerialName("mode_id") val modeId: String = MODE_TOKEN_EXPERIENCE,
    @SerialName("execution_target") val executionTarget: String = "",
    @SerialName("claim_scope") val claimScope: String = "",
    @SerialName("kpi_set") val kpiSet: String = "",
    val description: String = "",
    @SerialName("est_duration_s") val estDurationS: Double = 0.0,
    val business: ProfileBusiness = ProfileBusiness(),
    val measurements: List<ProfileMeasurement> = emptyList(),
    @SerialName("measurement_catalog_id") val measurementCatalogId: String = "",
    @SerialName("live_presentation") val livePresentation: ProfileLivePresentation = ProfileLivePresentation(),
    val evaluation: ProfileEvaluation = ProfileEvaluation(),
    val trace: ProfileTrace? = null,
    @SerialName("evidence_tier") val evidenceTier: String = "",
    @SerialName("execution_plan") val executionPlan: ProfileExecutionPlan? = null,
    /** v1 兼容字段；v2 只能用于显示旧 Profile，不能替代 live_presentation/evaluation。 */
    val presentation: ProfilePresentation = ProfilePresentation(),
    val phases: List<ProfilePhase> = emptyList(),
) {
    companion object {
        const val MODE_TOKEN_EXPERIENCE = "token_experience"
        const val MODE_NETWORK_BASIC = "network_basic"
        const val MODE_TOKEN_SIMULATION = "token_simulation"
        const val MODE_AI_REALTIME_SIMULATION = "ai_realtime_simulation"
        const val MODE_NETWORK_COMPREHENSIVE = "network_comprehensive"
        const val CONTRACT_V2 = "aneb-profile-v2"
    }
}

/** GET /api/v1/profiles 响应体。 */
@Serializable
data class ProfilesResponse(
    @SerialName("server_version") val serverVersion: String = "",
    val profiles: List<ScenarioProfile> = emptyList(),
)

object ProfileParser {
    /** 三场景固定集合（阶段 1 范围），顺序即拉丁方的场景下标 0/1/2。 */
    val REQUIRED_IDS = listOf("s1_chat", "s2_coding_agent", "s3_multimodal")
    /** 随 APK 打包的全部 Profile；REQUIRED_IDS 仍只定义现有 AQS 场景合同。 */
    val BUILTIN_IDS = REQUIRED_IDS + "basic_network"
    /** 只进入合同目录审计，不得被运行引擎自动选择。 */
    val AUDIT_ONLY_ASSET_PATHS = listOf("drafts/network_comprehensive_standard.json")
    /** 已发布的 Profile v2；运行时仍需逐一通过 capability 与哈希校验。 */
    val PUBLISHED_V2_ASSET_PATHS = listOf(
        "published/token_multimodal_quick/profile.json",
        "published/token_multimodal_standard/profile.json",
        "published/ai_realtime_voice_quick/profile.json",
        "published/ai_realtime_voice_standard/profile.json",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 /api/v1/profiles 响应。缺任一必需场景即抛（profile 是两端共享合同，禁静默缺省）。 */
    fun parseServerResponse(body: String): Map<String, ScenarioProfile> {
        val resp = json.decodeFromString(ProfilesResponse.serializer(), body)
        return index(resp.profiles)
    }

    /** 解析单个 profile JSON（打包内置 assets 副本路径）。 */
    fun parseSingle(body: String): ScenarioProfile =
        json.decodeFromString(ScenarioProfile.serializer(), body)

    fun index(profiles: List<ScenarioProfile>): Map<String, ScenarioProfile> {
        val map = profiles.associateBy { it.profileId }
        val missing = REQUIRED_IDS.filter { it !in map }
        require(missing.isEmpty()) { "missing required profiles: $missing" }
        return map
    }

    /** 版本串（结果合同 profile_versions 字段 + 版本一致性告警用）。 */
    fun versionString(profiles: Map<String, ScenarioProfile>): String =
        REQUIRED_IDS.joinToString(";") { id -> "$id@${profiles[id]?.version ?: "missing"}" }
}
