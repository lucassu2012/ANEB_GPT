package com.aneb.probe.apiprobe

/**
 * 国内 Top AI 业务的**可测试预置注册表**（真实 API 探针目标）。
 *
 * 入选口径（决定"可直接测试"）：该业务提供**公开的 OpenAI 兼容流式 chat/completions API**
 * （或 Anthropic Messages SSE），用户可自助申请 API key，返回逐 token 的 SSE 增量供测
 * TTFT/ITL。纯消费级 App（无公开流式 API）或非 chat-completions 形态（如飞书 Aily 的
 * Session/Message/Run 异步编排）**不入表**——见文件末尾"明确排除"。
 *
 * 数据核实来源：QuestMobile 2025 AI 应用报告（榜单）+ 各家官方开发者文档（端点/协议），
 * 核对日期 2026-07-13。base URL 精度是常见坑（GLM 必须 /api/paas/v4、豆包 /api/v3、
 * DashScope compatible-mode/v1），已逐项写死。[verified]=false 者以官方文档为准再用。
 *
 * 与 [ApiKeyStore] 的关系：预置只提供 base/model/protocol 的**默认值**；用户选中某预置后
 * 仍可覆盖 model、并必须自填 key（key 走加密存储、绝不入日志/上报/导出，红线见 ApiKeyStore
 * 与 KeyRedactionTest）。探针的测量口径（claim_scope=application_end_to_end_to_llm_api、
 * 不进 AQS）不因预置而改变。
 */
data class ProviderPreset(
    /** 稳定键（存储/日志用，非展示） */
    val id: String,
    /** 展示名（中文，含平台） */
    val displayName: String,
    /** OpenAI 兼容 / Anthropic 端点 base（已含版本路径，客户端不得再自行拼 /v1） */
    val baseUrl: String,
    /** 建议默认模型（用户可覆盖） */
    val defaultModel: String,
    /** 流式协议：决定用哪个适配器 */
    val protocol: Protocol,
    /** key 申请/控制台入口（展示给用户，非请求用） */
    val keyConsole: String,
    /** 免费额度提示（展示） */
    val freeTierNote: String,
    /**
     * 注意事项（展示给用户）；null 表示无特殊注意。
     * verified=false 时此处说明"以官方文档为准"的原因。
     */
    val caveat: String? = null,
    /**
     * 端点/协议是否已由官方开发者文档核实（true=✅ 可直接用；
     * false=⚠️ 检索到但需用户以官方文档最终确认后再用，UI 应显式提示）。
     */
    val verified: Boolean = true,
) {
    enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC }
}

object ProviderPresets {

    /** ✅ 第一批：公开 OpenAI 兼容流式 + 自助 key，字段对齐现有 OpenAiSseAdapter。 */
    val verified: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "doubao_ark",
            displayName = "豆包（火山方舟）",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-seed-1-6",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "火山方舟控制台 → API Key",
            freeTierNote = "新用户每模型赠 token",
            caveat = "历史上需接入点 ID(ep-)，现支持直接填模型名；如报模型不存在，改用控制台的接入点 ID",
        ),
        ProviderPreset(
            id = "kimi_moonshot",
            displayName = "Kimi（Moonshot）",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "platform.moonshot.cn 控制台",
            freeTierNote = "新号送 token，后预付",
            caveat = "本项目已实测通过（TTFT≈2.4s）；k2 系推理模型增量在 delta.reasoning_content（适配器已兼容）",
        ),
        ProviderPreset(
            id = "qwen_dashscope",
            displayName = "通义千问（阿里百炼/DashScope）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "阿里云百炼控制台 → API-KEY",
            freeTierNote = "各模型有免费额度",
            caveat = "经典域名仍可用；阿里正推 workspace 专属 maas 域名，长期可在设置里改 base",
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "platform.deepseek.com",
            freeTierNote = "预付为主（有错峰折扣）",
            caveat = "deepseek-chat/deepseek-reasoner 计划 2026-07-24 后弃用→改 deepseek-v4-flash/pro；reasoner 是 reasoning_content 原生出处，最适合测 TTFT",
        ),
        ProviderPreset(
            id = "glm_bigmodel",
            displayName = "智谱清言（GLM/BigModel）",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "open.bigmodel.cn 控制台",
            freeTierNote = "新号赠额 + glm-4-flash 免费",
            caveat = "base 必须精确到 /api/paas/v4（勿加 /v1，否则 404）；glm-4-flash 免费档适合反复测",
        ),
        ProviderPreset(
            id = "hunyuan",
            displayName = "腾讯混元（元宝）",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            defaultModel = "hunyuan-turbos",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "腾讯云混元控制台",
            freeTierNote = "hunyuan-lite 免费",
            caveat = "另提供 Anthropic 兼容端点，可作 anthropic 适配器的国产侧测试目标",
        ),
        ProviderPreset(
            id = "spark_xfyun",
            displayName = "讯飞星火",
            baseUrl = "https://spark-api-open.xf-yun.com/v1",
            defaultModel = "spark-lite",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "讯飞开放平台（用 APIPassword 作 key）",
            freeTierNote = "约 200 万 token 免费",
            caveat = "多版本对应多 base 后缀（新版 /v2/、旗舰 X2 用 /x2/）；免费用 spark-lite",
        ),
    )

    /** ⚠️ 可测但有前提：端点/协议需用户以官方文档最终确认后再用（UI 显式提示）。 */
    val needsConfirm: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "ernie_qianfan",
            displayName = "文心一言（百度千帆）",
            baseUrl = "https://qianfan.baidubce.com/v2",
            defaultModel = "ernie-4.5-turbo",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "百度智能云千帆 → 建 IAM 应用取 bce-v3 key",
            freeTierNote = "有免费/体验额度",
            caveat = "key 为 bce-v3 IAM 形态、需实名建应用，落地比其他家多一步；模型名以控制台为准",
            verified = false,
        ),
        ProviderPreset(
            id = "minimax",
            displayName = "MiniMax 海螺",
            baseUrl = "https://api.minimax.io/v1",
            defaultModel = "MiniMax-M1",
            protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
            keyConsole = "platform.minimaxi.com",
            freeTierNote = "有体验额度",
            caveat = "国内 OpenAI 兼容 base 路径检索不一致，以官方文档为准；勿误用 /anthropic/v1（那是 Anthropic 版）；部分接口历史上需 GroupId",
            verified = false,
        ),
    )

    /** 全部预置（✅ 在前，⚠️ 在后）。 */
    val all: List<ProviderPreset> = verified + needsConfirm

    fun byId(id: String?): ProviderPreset? = all.firstOrNull { it.id == id }

    /**
     * 明确排除（非疏漏，语义不匹配）——供 UI"为什么没有飞书"文案与文档引用：
     * - 飞书（智能伙伴 Aily）：Session/Message/Run 异步编排 API，非 chat-completions、
     *   不回逐 token SSE、需企业应用授权；内部是编排层（调 Azure OpenAI 等作后端），
     *   与 TTFT/ITL 逐 token 探针语义不匹配。
     * - 蚂蚁阿福：消费级 App，无自助开发者流式 chat-completions API。
     * 覆盖此类"真实 App 体验"应走服务器可达性(REACH) 或 VPN 流量观测模式，而非 API 探针。
     */
    val excludedNote: String =
        "飞书/阿福等无公开流式 chat API，不能作 API 探针目标；其网络体验请用「服务器可达性」或「VPN 流量观测」模式测"
}

/**
 * 预置协议族 → 探针 [LlmProvider]（决定适配器与 endpointPath 拼接口径）。
 * 供 UI/MainActivity 选中预置后复用：`preset.toLlmProvider()` 得到 provider，再以
 * `ApiProbe.endpointPath(provider)` 拼最终 URL（约定见 ApiProbe.endpointPath KDoc）。
 */
fun ProviderPreset.toLlmProvider(): LlmProvider = when (protocol) {
    ProviderPreset.Protocol.OPENAI_COMPATIBLE -> LlmProvider.OPENAI_COMPAT
    ProviderPreset.Protocol.ANTHROPIC -> LlmProvider.ANTHROPIC
}
