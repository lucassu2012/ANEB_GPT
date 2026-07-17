package com.aneb.probe.apiprobe

/** Protocol families supported by the Debug-only real API diagnostic. */
enum class LlmProvider(val id: String, val defaultBaseUrl: String, val defaultModel: String) {
    ANTHROPIC("anthropic", "https://api.anthropic.com", "claude-3-5-haiku-latest"),
    OPENAI_COMPAT("openai_compat", "https://api.moonshot.cn/v1", "moonshot-v1-8k");

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPAT
    }
}
