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

/** phase 联合体：字段按 [type] 选用（同 Go 侧 Phase）。 */
@Serializable
data class ProfilePhase(
    val type: String,
    // clock_sync
    val samples: Int = 0,
    // upload_burst
    val bytes: Long = 0,
    @SerialName("chunk_kb") val chunkKb: Int = 0,
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
) {
    companion object {
        const val TYPE_CLOCK_SYNC = "clock_sync"
        const val TYPE_UPLOAD_BURST = "upload_burst"
        const val TYPE_THINK_PAUSE = "think_pause"
        const val TYPE_TOKEN_STREAM = "token_stream"
        const val TYPE_TOOL_LOOP = "tool_loop"
    }
}

@Serializable
data class ScenarioProfile(
    @SerialName("profile_id") val profileId: String,
    val version: String,
    @SerialName("kpi_set") val kpiSet: String = "",
    val description: String = "",
    @SerialName("est_duration_s") val estDurationS: Double = 0.0,
    val phases: List<ProfilePhase> = emptyList(),
)

/** GET /api/v1/profiles 响应体。 */
@Serializable
data class ProfilesResponse(
    @SerialName("server_version") val serverVersion: String = "",
    val profiles: List<ScenarioProfile> = emptyList(),
)

object ProfileParser {
    /** 三场景固定集合（阶段 1 范围），顺序即拉丁方的场景下标 0/1/2。 */
    val REQUIRED_IDS = listOf("s1_chat", "s2_coding_agent", "s3_multimodal")

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
