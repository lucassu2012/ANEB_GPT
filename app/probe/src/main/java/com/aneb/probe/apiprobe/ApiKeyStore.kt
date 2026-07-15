package com.aneb.probe.apiprobe

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 探针支持的服务商/协议族。 */
enum class LlmProvider(val id: String, val defaultBaseUrl: String, val defaultModel: String) {
    /** Anthropic Messages API（国内通常需代理可达） */
    ANTHROPIC("anthropic", "https://api.anthropic.com", "claude-3-5-haiku-latest"),

    /** OpenAI Chat Completions 兼容端点（Kimi/Moonshot 默认） */
    OPENAI_COMPAT("openai_compat", "https://api.moonshot.cn/v1", "moonshot-v1-8k");

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPAT
    }
}

/**
 * API key 与探针配置存储（E-03：真实 LLM API key，用户自填）。
 *
 * **隐私红线**：
 *  - 首选 [EncryptedSharedPreferences]（androidx.security-crypto，AES256-GCM，主密钥入
 *    Android Keystore）；构造失败（个别 ROM Keystore 损坏 / keyset 解密失败）时**降级为
 *    应用私有明文 SharedPreferences**——取舍：私有目录本就受应用沙箱保护（非 root 不可
 *    读），且 key 是用户自己的低额度实验 key；降级状态经 [encrypted] 暴露给 UI 明示。
 *    降级时清掉损坏 keyset 重建的复杂路径不做（研究自用工具，损坏概率极低）。
 *  - key **绝不写日志/上报体/导出文件**：本类不提供 toString 泄漏面；出口侧由
 *    [ApiKeyRedactor] 兜底 + JVM 单测锚定（导出/上报不含 key 字符串）。
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    /** true=EncryptedSharedPreferences；false=降级私有明文 prefs（UI 须明示） */
    var encrypted: Boolean = false
        private set

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "apiprobe_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { encrypted = true }
    } catch (e: Exception) {
        // 降级路径（见类 KDoc 取舍）；encrypted=false 供 UI 明示
        appContext.getSharedPreferences("apiprobe_plain_fallback", Context.MODE_PRIVATE)
    }

    fun apiKey(): String? = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(key: String?) {
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
        }.apply()
    }

    /** E-03 就绪判定：无 key 时探针入口禁用置灰（缺 key 降级设计，主线不受阻）。 */
    fun hasKey(): Boolean = apiKey() != null

    var provider: LlmProvider
        get() = LlmProvider.fromId(prefs.getString(KEY_PROVIDER, null))
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.id).apply()

    /** base URL 覆盖（空=用 provider 默认）；联调时可指向本机 mock（10.0.2.2:port） */
    var baseUrlOverride: String?
        get() = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, value.trim())
        }.apply()

    var modelOverride: String?
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, value.trim())
        }.apply()

    fun effectiveBaseUrl(): String = baseUrlOverride ?: provider.defaultBaseUrl

    fun effectiveModel(): String = modelOverride ?: provider.defaultModel

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
    }
}

/**
 * key 出口兜底红线：任何将写入日志/Room/导出的自由文本（异常消息、HTTP 错误 body 等）
 * 必须先过 [redact]。纯函数、JVM 单测锚定。
 */
object ApiKeyRedactor {
    const val MASK = "***REDACTED***"

    fun redact(text: String?, key: String?): String? {
        if (text == null) return null
        if (key.isNullOrBlank()) return text
        return text.replace(key, MASK)
    }
}
