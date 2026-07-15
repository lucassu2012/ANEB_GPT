package com.aneb.probe.apiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置 base URL 与 endpointPath 拼接一致性锚定（base-URL 约定：OpenAI 兼容 base 含版本段，
 * 客户端只拼 /chat/completions；Anthropic base 不含版本，拼 /v1/messages）。
 *
 * 目的：防止预置的 base 版本段与 [ApiProbe.endpointPath] 拼接口径漂移，导致 `//chat`、
 * `/v1/v1`、`/chat/completions/chat` 一类脏 URL。此为纯字符串断言，不发网络请求。
 */
class ProviderPresetUrlTest {

    /** OpenAI 兼容家的最终 chat URL = base + endpointPath。 */
    private fun ProviderPreset.finalChatUrl(): String =
        baseUrl + ApiProbe.endpointPath(toLlmProvider())

    @Test
    fun `endpointPath contract fixed for both protocols`() {
        assertEquals("/chat/completions", ApiProbe.endpointPath(LlmProvider.OPENAI_COMPAT))
        assertEquals("/v1/messages", ApiProbe.endpointPath(LlmProvider.ANTHROPIC))
    }

    @Test
    fun `no preset baseUrl has trailing slash`() {
        for (p in ProviderPresets.all) {
            assertFalse(
                "预置 ${p.id} 的 baseUrl 不得以 / 结尾: ${p.baseUrl}",
                p.baseUrl.endsWith("/"),
            )
        }
    }

    @Test
    fun `openai compatible final urls are clean and well formed`() {
        for (p in ProviderPresets.all) {
            if (p.protocol != ProviderPreset.Protocol.OPENAI_COMPATIBLE) continue
            val url = p.finalChatUrl()
            // 版本段/路径分隔既不缺也不重
            assertTrue("${p.id} 最终 URL 应以 /chat/completions 结尾: $url", url.endsWith("/chat/completions"))
            assertFalse("${p.id} 出现 // 拼接错误: $url", url.contains("//chat"))
            assertFalse("${p.id} 出现重复版本段 /v1/v1: $url", url.contains("/v1/v1"))
            assertFalse("${p.id} 出现重复端点段: $url", url.contains("/chat/completions/chat"))
            // 协议头之后不得再出现 //（协议头 https:// 除外）
            assertFalse("${p.id} 路径含双斜杠: $url", url.substringAfter("://").contains("//"))
        }
    }

    @Test
    fun `verified presets resolve to expected chat urls`() {
        val expected = mapOf(
            "doubao_ark" to "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            "kimi_moonshot" to "https://api.moonshot.cn/v1/chat/completions",
            "qwen_dashscope" to "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "deepseek" to "https://api.deepseek.com/v1/chat/completions",
            "glm_bigmodel" to "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "hunyuan" to "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
            "spark_xfyun" to "https://spark-api-open.xf-yun.com/v1/chat/completions",
        )
        // 覆盖锁定：verified 列表恰为这 7 家（新增/删改预置须同步本测）
        assertEquals(expected.keys, ProviderPresets.verified.map { it.id }.toSet())
        for ((id, url) in expected) {
            val preset = ProviderPresets.byId(id) ?: error("缺预置 $id")
            assertEquals("$id 最终 chat URL 不符", url, preset.finalChatUrl())
        }
    }

    @Test
    fun `needsConfirm presets resolve to expected chat urls`() {
        val expected = mapOf(
            "ernie_qianfan" to "https://qianfan.baidubce.com/v2/chat/completions",
            "minimax" to "https://api.minimax.io/v1/chat/completions",
        )
        for ((id, url) in expected) {
            val preset = ProviderPresets.byId(id) ?: error("缺预置 $id")
            assertEquals("$id 最终 chat URL 不符", url, preset.finalChatUrl())
        }
    }

    @Test
    fun `toLlmProvider maps protocol families`() {
        assertEquals(
            LlmProvider.OPENAI_COMPAT,
            ProviderPreset(
                id = "x", displayName = "x", baseUrl = "https://e/v1", defaultModel = "m",
                protocol = ProviderPreset.Protocol.OPENAI_COMPATIBLE,
                keyConsole = "", freeTierNote = "",
            ).toLlmProvider(),
        )
        assertEquals(
            LlmProvider.ANTHROPIC,
            ProviderPreset(
                id = "y", displayName = "y", baseUrl = "https://e", defaultModel = "m",
                protocol = ProviderPreset.Protocol.ANTHROPIC,
                keyConsole = "", freeTierNote = "",
            ).toLlmProvider(),
        )
    }
}
