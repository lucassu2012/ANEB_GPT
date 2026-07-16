package com.aneb.probe.engine

import android.content.Context
import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 场景 profile 获取（P1 范围 1）：启动时 GET /api/v1/profiles 拉取三场景；
 * 拉不到用打包内置 assets 副本（build.gradle sourceSets 指向仓库 profiles/ 目录，
 * 单一事实来源）并告警"版本一致性未经服务端核验"。
 *
 * 服务端拉取成功时仍与内置副本比对版本：不一致以服务端为准并告警（设计文档 §2：
 * 两端共享合同，不一致以服务端为准）。
 */
class ProfileRepository(private val context: Context) {

    data class Loaded(
        val profiles: Map<String, ScenarioProfile>,
        /** server / assets_fallback */
        val source: String,
        val warnings: List<String>,
    )

    suspend fun load(client: AnebClient, serverBase: String): Loaded {
        val warnings = mutableListOf<String>()
        val assets = loadAssets()

        val resp = client.fetchProfiles("${serverBase.trimEnd('/')}/api/v1/profiles")
        if (resp.error == null && resp.body != null) {
            val server = try {
                ProfileParser.parseServerResponse(resp.body)
            } catch (e: Exception) {
                warnings += "server_profiles_parse_failed:${e.javaClass.simpleName}"
                null
            }
            if (server != null) {
                val sv = ProfileParser.versionString(server)
                val av = ProfileParser.versionString(assets)
                if (sv != av) {
                    warnings += "version_mismatch server=$sv assets=$av (以服务端为准)"
                }
                val auditOnly = assets.values.filter { candidate ->
                    candidate.contractVersion == ScenarioProfile.CONTRACT_V2 && candidate.profileId !in server
                }
                if (auditOnly.isNotEmpty()) {
                    warnings += "bundled_audit_only_profiles=" + auditOnly.joinToString(",") { it.profileId }
                }
                return Loaded(server + auditOnly.associateBy { it.profileId }, "server", warnings)
            }
        } else {
            warnings += "profiles_fetch_failed http=${resp.httpCode ?: "null"} error=${resp.error ?: "none"}"
        }

        // 兜底：打包内置副本。与服务端的版本一致性无法核验——显式告警（证据缺失 ≠ 隐式健康）
        warnings += "using_assets_fallback version_consistency_with_server=UNVERIFIED " +
            "versions=${ProfileParser.versionString(assets)}"
        return Loaded(assets, "assets_fallback", warnings)
    }

    /**
     * 仅读取随 APK 发布的版本化 Profile，用于目录展示和离线审计。
     * 不参与测量选路，也不会把本地副本伪装成已与节点核验的配置。
     */
    suspend fun loadBundled(): List<ScenarioProfile> =
        loadAssets().values.sortedWith(compareBy({ it.modeId }, { it.profileId }))

    private suspend fun loadAssets(): Map<String, ScenarioProfile> = withContext(Dispatchers.IO) {
        // 同步磁盘 IO 显式落 IO 池（R-16；与 TestEngine.run 的 flowOn(IO) 双重兜底）
        val paths = ProfileParser.BUILTIN_IDS.map { "$it.json" } +
            ProfileParser.PUBLISHED_V2_ASSET_PATHS +
            ProfileParser.AUDIT_ONLY_ASSET_PATHS
        val profiles = paths.map { path ->
            context.assets.open(path).use { input ->
                ProfileParser.parseSingle(input.readBytes().toString(Charsets.UTF_8))
            }
        }
        ProfileParser.index(profiles)
    }
}
