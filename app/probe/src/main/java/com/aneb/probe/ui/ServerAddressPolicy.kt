package com.aneb.probe.ui

import java.net.URI

internal data class ServerAddressValidation(
    val normalized: String?,
    val message: String?,
) {
    val isValid: Boolean get() = normalized != null
}

/**
 * 开测前对节点基址做同一套校验。所有引擎都会在基址后追加固定 API 路径，因此自定义
 * 地址只允许站点根，不接受账号信息、查询参数或片段。正式包只允许 HTTPS；调试包的
 * HTTP 例外仍受 network-security-config 白名单约束。
 */
internal object ServerAddressPolicy {
    fun validate(value: String, allowCleartext: Boolean): ServerAddressValidation {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return invalid("请输入测试节点地址。")
        if (trimmed.length > MAX_LENGTH) return invalid("测试节点地址过长，请检查后重试。")

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return invalid("节点地址格式无效，请使用完整的 HTTPS 地址。")
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("https", "http")) {
            return invalid("节点地址必须以 https:// 开头。")
        }
        if (scheme == "http" && !allowCleartext) {
            return invalid("正式版本只允许 HTTPS 测试节点。")
        }
        if (uri.host.isNullOrBlank()) {
            return invalid("节点地址缺少有效主机名或 IP。")
        }
        if (uri.rawUserInfo != null) {
            return invalid("节点地址不能包含账号或密码。")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return invalid("节点地址不能包含查询参数或页面片段。")
        }
        if (!uri.path.isNullOrEmpty() && uri.path != "/") {
            return invalid("请输入节点根地址，不要附加 API 路径。")
        }
        if (uri.port == 0 || uri.port > 65_535) {
            return invalid("节点端口必须在 1–65535 之间。")
        }

        return ServerAddressValidation(
            normalized = trimmed.trimEnd('/'),
            message = null,
        )
    }

    private fun invalid(message: String) = ServerAddressValidation(
        normalized = null,
        message = message,
    )

    private const val MAX_LENGTH = 2_048
}

/** 纯函数入口，确保 UI 和后续自动化对“能否开始”使用同一判定。 */
internal object ManualRunReadiness {
    fun blocker(
        hasActiveNetwork: Boolean,
        serverUrl: String,
        allowCleartext: Boolean,
    ): String? {
        if (!hasActiveNetwork) {
            return "当前没有可用网络。连接 WiFi 或蜂窝网络后再试。"
        }
        return ServerAddressPolicy.validate(serverUrl, allowCleartext).message?.let {
            "$it 请在“设置 > 高级”中修正。"
        }
    }
}
