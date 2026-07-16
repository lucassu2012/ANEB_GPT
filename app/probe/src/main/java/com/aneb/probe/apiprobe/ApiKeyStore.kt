package com.aneb.probe.apiprobe

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
 *  - API key 只允许进入 [EncryptedSharedPreferences]（AES256-GCM，主密钥入 Android
 *    Keystore）。Keystore 构造失败时 fail-closed：配置仍可保存，但禁止持久化 key；旧版
 *    私有明文 fallback 会被主动清空。降级状态经 [encrypted] 暴露给 UI。
 *  - key **绝不写日志/上报体/导出文件**：本类不提供 toString 泄漏面；出口侧由
 *    [ApiKeyRedactor] 兜底 + JVM 单测锚定（导出/上报不含 key 字符串）。
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    /** true=EncryptedSharedPreferences 可用；false=安全存储不可用，API key 禁止持久化。 */
    var encrypted: Boolean = false
        private set

    private val configPrefs: SharedPreferences =
        appContext.getSharedPreferences("apiprobe_config", Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? = try {
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
    } catch (_: Exception) {
        null
    }

    init {
        // v0.1 曾允许私有明文 fallback；升级后无条件擦除，避免安全策略升级留下旧 key。
        appContext.getSharedPreferences(LEGACY_PLAIN_PREFS, Context.MODE_PRIVATE)
            .edit { clear() }
    }

    fun apiKey(): String? = securePrefs?.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** 返回 false 表示安全存储不可用且非空 key 未被保存。 */
    fun setApiKey(key: String?): Boolean {
        if (key.isNullOrBlank()) {
            securePrefs?.edit { remove(KEY_API_KEY) }
            return true
        }
        val prefs = securePrefs ?: return false
        prefs.edit { putString(KEY_API_KEY, key.trim()) }
        return true
    }

    /** E-03 就绪判定：无 key 时探针入口禁用置灰（缺 key 降级设计，主线不受阻）。 */
    fun hasKey(): Boolean = apiKey() != null

    var provider: LlmProvider
        get() = LlmProvider.fromId(configPrefs.getString(KEY_PROVIDER, null))
        set(value) = configPrefs.edit { putString(KEY_PROVIDER, value.id) }

    /** base URL 覆盖（空=用 provider 默认）；联调时可指向本机 mock（10.0.2.2:port） */
    var baseUrlOverride: String?
        get() = configPrefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = configPrefs.edit {
            if (value.isNullOrBlank()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, value.trim())
        }

    var modelOverride: String?
        get() = configPrefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) = configPrefs.edit {
            if (value.isNullOrBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, value.trim())
        }

    fun effectiveBaseUrl(): String = baseUrlOverride ?: provider.defaultBaseUrl

    fun effectiveModel(): String = modelOverride ?: provider.defaultModel

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val LEGACY_PLAIN_PREFS = "apiprobe_plain_fallback"
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
